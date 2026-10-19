package com.learne.backend.jobs.config;

import com.learne.backend.jobs.model.QueueName;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.EnumMap;
import java.util.Map;
import java.util.List;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.jobs")
public class JobProperties {
    private boolean workerEnabled = true;
    private double jitter = 0.1;
    @NotNull
    private Map<QueueName, @Valid @NotNull Lane> lanes = new EnumMap<>(QueueName.class);

    public JobProperties() {
        for (QueueName queue : QueueName.values()) {
            var lane = new Lane(queue == QueueName.AI ? 3 : 5);
            lane.poll = Duration.ofSeconds(queue == QueueName.MAIL_AUTH ? 1 : queue == QueueName.MEDIA ? 5 : 2);
            lane.lease = Duration.ofSeconds(queue == QueueName.AI ? 180 : queue == QueueName.MEDIA ? 90 : 60);
            lane.heartbeat = Duration.ofSeconds(queue == QueueName.AI ? 30 : queue == QueueName.MEDIA ? 15 : 10);
            lane.deadline = Duration.ofSeconds(queue == QueueName.AI ? 120 : queue == QueueName.MEDIA ? 60 : 45);
            lane.backoff = (queue == QueueName.AI ? List.of(30, 120)
                    : queue == QueueName.MAIL_AUTH ? List.of(5, 15, 45, 120) : List.of(30, 120, 600, 1800))
                    .stream().map(seconds -> Duration.ofSeconds(seconds)).toList();
            lanes.put(queue, lane);
        }
    }

    public Map<QueueName, Lane> getLanes() {
        return lanes;
    }

    public void setLanes(Map<QueueName, Lane> lanes) {
        if (lanes != null) {
            var defaults = new JobProperties().lanes;
            lanes.forEach((queue, lane) -> {
                if (lane != null && defaults.containsKey(queue)) lane.applyDefaults(defaults.get(queue));
            });
        }
        this.lanes = lanes;
    }

    public boolean isWorkerEnabled() { return workerEnabled; }
    public void setWorkerEnabled(boolean value) { workerEnabled = value; }
    public double getJitter() { return jitter; }
    public void setJitter(double value) { jitter = value; }

    public Snapshot snapshot() {
        if (!isAllLanesConfigured() || !Double.isFinite(jitter) || jitter < 0 || jitter > 0.1)
            throw new IllegalArgumentException("Invalid job runtime policy");
        var copy = new EnumMap<QueueName, LanePolicy>(QueueName.class);
        for (var queue : QueueName.values()) {
            var lane = lanes.get(queue);
            if (lane == null || lane.maxAttempts < 1 || lane.slots < 1 || !positive(lane.poll)
                    || !positive(lane.lease) || !positive(lane.heartbeat) || !positive(lane.deadline)
                    || lane.heartbeat.compareTo(lane.lease) >= 0 || lane.deadline.compareTo(lane.lease) >= 0
                    || lane.backoff == null || lane.backoff.size() != lane.maxAttempts - 1
                    || lane.backoff.stream().anyMatch(d -> !positive(d)))
                throw new IllegalArgumentException("Invalid job lane policy");
            copy.put(queue, new LanePolicy(lane.slots, lane.poll, lane.lease, lane.heartbeat,
                    lane.deadline, lane.maxAttempts, List.copyOf(lane.backoff)));
        }
        return new Snapshot(workerEnabled, jitter, Map.copyOf(copy));
    }

    private static boolean positive(Duration value) {
        return value != null && !value.isNegative() && !value.isZero();
    }

    public record Snapshot(boolean workerEnabled, double jitter, Map<QueueName, LanePolicy> lanes) {
        public Snapshot { lanes = Map.copyOf(lanes); }
    }
    public record LanePolicy(int slots, Duration poll, Duration lease, Duration heartbeat,
                             Duration deadline, int maxAttempts, List<Duration> backoff) {
        public LanePolicy { backoff = List.copyOf(backoff); }
    }

    @AssertTrue(message = "All job lanes must be configured")
    public boolean isAllLanesConfigured() {
        return lanes != null && lanes.keySet().containsAll(java.util.Set.of(QueueName.values()));
    }

    public static class Lane {
        private int slots = 1;
        private Duration poll;
        private Duration lease;
        private Duration heartbeat;
        private Duration deadline;
        private List<Duration> backoff;
        @Min(1)
        private int maxAttempts;
        private boolean maxAttemptsConfigured;

        public Lane() {
        }

        public Lane(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            this.maxAttemptsConfigured = true;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            this.maxAttemptsConfigured = true;
        }
        private void applyDefaults(Lane defaults) {
            if (!maxAttemptsConfigured) maxAttempts = defaults.maxAttempts;
            if (poll == null) poll = defaults.poll;
            if (lease == null) lease = defaults.lease;
            if (heartbeat == null) heartbeat = defaults.heartbeat;
            if (deadline == null) deadline = defaults.deadline;
            if (backoff == null) backoff = defaults.backoff;
        }
        public int getSlots() { return slots; }
        public void setSlots(int value) { slots = value; }
        public Duration getPoll() { return poll; }
        public void setPoll(Duration value) { poll = value; }
        public Duration getLease() { return lease; }
        public void setLease(Duration value) { lease = value; }
        public Duration getHeartbeat() { return heartbeat; }
        public void setHeartbeat(Duration value) { heartbeat = value; }
        public Duration getDeadline() { return deadline; }
        public void setDeadline(Duration value) { deadline = value; }
        public List<Duration> getBackoff() { return backoff; }
        public void setBackoff(List<Duration> value) { backoff = value; }
    }
}
