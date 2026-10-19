package com.learne.backend.jobs.service;

import com.learne.backend.jobs.config.JobProperties;
import com.learne.backend.jobs.model.*;
import io.micrometer.core.instrument.*;
import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.*;

/** Finite labels and cached gauges only; instrumentation never escapes into business execution. */
public final class JobMetrics implements AutoCloseable {
    private record Key(String queue, String type, String state) {}
    private final MeterRegistry meters;
    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final String sql;
    private final Map<Key, AtomicLong> backlog;
    private final Map<Key, AtomicReference<Double>> ages;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private ScheduledExecutorService scheduler;

    public JobMetrics(MeterRegistry meters, JdbcTemplate jdbc, JobRegistry registry, JobProperties.Snapshot policy) {
        this.meters = meters; this.jdbc = jdbc; enabled = policy.workerEnabled();
        var counts = new HashMap<Key, AtomicLong>();
        var oldest = new HashMap<Key, AtomicReference<Double>>();
        var queues = new ArrayList<>(Arrays.stream(QueueName.values()).map(Enum::name).toList());
        var types = new ArrayList<>(Arrays.stream(JobType.values()).map(Enum::name).toList());
        queues.add("UNSUPPORTED"); types.add("UNSUPPORTED");
        for (String q : queues) for (String t : types) {
            var age = new AtomicReference<>(0.0);
            oldest.put(new Key(q,t,""), age);
            safe(() -> Gauge.builder("learne.jobs.oldest.ready.seconds", age, AtomicReference::get)
                    .tags("queue",q,"type",t).register(meters));
            for (String state : List.of("PENDING","RETRY","RUNNING","DEAD")) {
                var value = new AtomicLong(); counts.put(new Key(q,t,state), value);
                safe(() -> Gauge.builder("learne.jobs.backlog", value, AtomicLong::doubleValue)
                        .tags("queue",q,"type",t,"state",state).register(meters));
            }
        }
        backlog = Map.copyOf(counts); ages = Map.copyOf(oldest);
        var supported = new StringJoiner(" OR ");
        for (var queue : QueueName.values()) registry.supported(queue).forEach((type, versions) -> {
            for (int version : versions) supported.add("(queue='" + queue.name() + "' AND type='"
                    + type.name() + "' AND payload_version=" + version + ")");
        });
        // SQL fragments contain only local enum names and validated integer capabilities, never DB values.
        String capability = supported.length() == 0 ? "FALSE" : supported.toString();
        sql = """
                WITH snapshot AS (
                  SELECT CASE WHEN queue IN (%s) THEN queue ELSE 'UNSUPPORTED' END AS q,
                    CASE WHEN type IN (%s) THEN type ELSE 'UNSUPPORTED' END AS t,
                    CASE state WHEN 'READY' THEN 'PENDING' WHEN 'RETRY_WAIT' THEN 'RETRY' ELSE state END AS s,
                    available_at, (%s) AS supported, clock_timestamp() AS db_now
                  FROM background_jobs WHERE state IN ('READY','RETRY_WAIT','RUNNING','DEAD')
                ) SELECT q,t,s,supported,count(*) AS n,
                  coalesce(max(CASE WHEN s IN ('PENDING','RETRY') AND available_at<=db_now
                    THEN extract(epoch FROM db_now-available_at) ELSE 0 END),0) AS age
                  FROM snapshot GROUP BY q,t,s,supported
                """.formatted(quoted(QueueName.values()), quoted(JobType.values()), capability);
    }

    private static String quoted(Enum<?>[] values) {
        return Arrays.stream(values).map(e -> "'" + e.name() + "'").collect(Collectors.joining(","));
    }

    @PostConstruct
    public synchronized void start() {
        if (!enabled || scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r,"jobs-metrics"); thread.setDaemon(true); return thread;
        });
        scheduler.scheduleWithFixedDelay(this::refresh, 60, 60, TimeUnit.SECONDS);
    }

    /** One bounded-result query, five-second statement timeout; failure retains the last complete snapshot. */
    public void refresh() {
        if (!refreshing.compareAndSet(false,true)) return;
        try {
            var next = new HashMap<Key, Long>();
            var nextAges = new HashMap<Key, Double>();
            var unsupported = new HashMap<Key, Long>();
            jdbc.query(sql, ps -> ps.setQueryTimeout(5), (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> {
                while (rs.next()) {
                    var key = new Key(rs.getString("q"),rs.getString("t"),rs.getString("s"));
                    if (!backlog.containsKey(key)) continue;
                    next.merge(key,rs.getLong("n"),Long::sum);
                    var ageKey = new Key(key.queue(),key.type(),"");
                    nextAges.merge(ageKey,Math.max(0,rs.getDouble("age")),Math::max);
                    if (!rs.getBoolean("supported") && (key.state().equals("PENDING") || key.state().equals("RETRY")))
                        unsupported.merge(ageKey,rs.getLong("n"),Long::sum);
                }
                return null;
            });
            backlog.forEach((k,v) -> v.set(next.getOrDefault(k,0L)));
            ages.forEach((k,v) -> v.set(nextAges.getOrDefault(k,0.0)));
            unsupported.forEach((k,n) -> safe(() -> meters.counter("learne.jobs.unsupported.version",
                    "queue",k.queue(),"type",k.type(),"outcome","UNSUPPORTED").increment(n)));
        } catch (RuntimeException ignored) {
            safe(() -> meters.counter("learne.jobs.unsupported.version", "queue","UNSUPPORTED",
                    "type","UNSUPPORTED","outcome","REFRESH_FAILED").increment());
        } finally { refreshing.set(false); }
    }

    public void completed(JobLease lease, JobState state) {
        afterCommit(() -> counter("learne.jobs.completed",lease,state.name()));
    }
    public void retried(JobLease lease) { afterCommit(() -> counter("learne.jobs.retries",lease,"RETRY")); }
    public void stale(JobLease lease) { safe(() -> counter("learne.jobs.lease.stale",lease,"STALE")); }
    /** Wall time of each actual worker turn, including routing/control, not queue delay or committed job latency. */
    public void duration(JobLease lease, long started) {
        safe(() -> meters.timer("learne.jobs.duration", "queue",JobRegistry.queueFor(lease.type()).name(),
                "type",lease.type().name()).record(Math.max(0,System.nanoTime()-started),TimeUnit.NANOSECONDS));
    }
    private void counter(String name, JobLease lease, String outcome) {
        meters.counter(name,"queue",JobRegistry.queueFor(lease.type()).name(),"type",lease.type().name(),
                "outcome",outcome).increment();
    }
    private static void afterCommit(Runnable action) {
        safe(() -> {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                if (TransactionSynchronizationManager.isSynchronizationActive())
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override public void afterCommit() { safe(action); }
                    });
            } else safe(action);
        });
    }
    private static void safe(Runnable action) { try { action.run(); } catch (RuntimeException ignored) { } }
    @Override public synchronized void close() { if (scheduler != null) scheduler.shutdownNow(); }
}
