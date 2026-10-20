# R4 integration acceptance verification — 2026-10-20

The exact acceptance class passes: **3 tests, 0 failures, 0 errors, 0 skips**.
The continuation's reconstruction, disposable Redis failure, JDBC execution
counting, and repeated bounded-stall gates are covered. Measurements below are
local bounded-execution evidence, not Linux/VPS or combined-container capacity proof.

Branch: `update/postgre-queue`; backend HEAD initially
`21ca5a91329cd16ed823630cbd339b53ef17a5a7`. Only this report and
`src/test/java/com/learne/backend/jobs/BackgroundJobsAcceptanceIT.java` were changed
by R4. Existing R1–R3 changes were preserved. No Git mutation, production edit,
migration, root report edit, external provider, deployment, or R5 work occurred.

## Actual command and result

Working directory: `C:\Users\Admin\Desktop\learne-platform\backend`.
Java 21.0.7 from `F:\jdk_21`. Every command timeout was at most 180 seconds.

```powershell
$env:JAVA_HOME='F:\jdk_21'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\mvnw.cmd -Pjobs-it '-Dit.test=BackgroundJobsAcceptanceIT' '-Dtest=NoR4UnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx896m' '-Dlogging.level.root=ERROR' verify -q
```

Final run exit code: **0**; acceptance class elapsed **21.75 seconds**. See
`target/failsafe-reports/com.learne.backend.jobs.BackgroundJobsAcceptanceIT.txt`.
The exact class matches the existing `**/jobs/**/*IT.java` include; R4 needed no
POM edit. The unit-test selector avoids unrelated unit tests at this checkpoint;
the complete `jobs-it` suite was not run. No acceptance tests are disabled or
skipped, and no external dependency failures are converted into skips.

The initial continuation run exposed an incompatible Lettuce ACL overload in the
test fixture. The fixture now configures the disposable Redis ACL using its own
container's `redis-cli`; subsequent runs pass. Expected `SimulatedCrash` error
traces on the worker threads are intentional crash injection, not hidden failures:
each injection is observed through a deadline-bound latch and checked against SQL.

## Exact assertions

### 1,000-question runtime acceptance

- Actual submission acceptance persists 1,000 manifest questions and a durable
  job. The real registry/dispatcher delegates to actual submission, mail, and
  R1 MEDIA handlers. Provider ports alone are fake; per-lane wrappers measure
  concurrent handler entries.
- AI blocks on a latch outside a transaction. Actual MailJobProducer enqueues
  OTP, password reset, invitation, and payment messages from SQL domain fixtures.
  Actual MediaCleanupProducer enqueues an unreferenced, managed Cloudinary VIDEO
  candidate. All five jobs succeed independently while AI remains blocked.
- The real MEDIA handler and provenance/reference decision service authorize
  precisely `learne/course-audio/r4`; the fake deletion port records that ID.
- No admin pending-review intent exists during the stall. At admin transport
  entry, SQL must already show PENDING_REVIEW and submission SUCCEEDED.
- Polling reconstructs a SQL query service after discarding the previous client;
  persisted progress/success survive that simulated disconnect.
- Initially 2,000 saturated AI admissions are refused. Automatic polling then
  runs during three independent windows, each ending only after **10 observed
  committed lease extensions**. Each window refuses another 2,000 admissions.
  Total: **8,000 refused admissions, 30 observed renewals**, with one provider call
  throughout the stall. AI heartbeat is configured to 100 ms and polling to 50 ms
  for this test; production defaults are not changed.
- After release, exactly 40 provider calls process 25 questions each. All 1,000
  unique staged questions finalize into actual question revisions. The finalizer
  persists PENDING_REVIEW, one teacher notification, and one admin mail intent.
  All jobs in the scenario finish SUCCEEDED; maximum handler concurrency is one
  in every lane.

### Reconstruction at durable crash boundaries

Five graph generations use the **same disposable PostgreSQL database**. Every
generation constructs a new JobStore, JdbcTemplate, submission repository,
projection reader, transactional checkpoint service, transactional finalizer,
enrichment service, handler, registry, and dispatcher. Transaction proxies use
the real transaction manager and production annotation metadata. Tests explicitly
assert different identities for store/repository/checkpoint/finalizer/handler/runtime.
The connection pool, transaction manager, EntityManagerFactory and leaf JPA
repository infrastructure are shared; this is component reconstruction, not a
whole Spring application-context or OS-process restart.

1. **Claim before handler:** the real dispatcher claims a job, then the entry
   hook throws `SimulatedCrash` before the domain handler. The runtime is closed.
   The next graph recovers its expired durable claim.
2. **Model response before checkpoint:** the reconstructed runtime calls the
   provider, then the checkpoint hook crashes before mutation. SQL progress stays
   zero. Another graph recovers the lease and repeats the remote batch.
3. **Committed stage before return:** the next runtime commits the actual
   transactional checkpoint/yield, then crashes before returning to the dispatcher.
   SQL progress is 25. Another graph resumes the second batch without replaying
   that committed stage. Three provider calls cover 50 questions: one response
   lost before checkpoint, its retry, and the second distinct batch.
4. **Finalization before invalidation:** the reconstructed actual finalizer commits
   success. Its after-commit cache boundary independently opens a SQL connection
   and verifies SUCCEEDED before attempting Redis deletion. Disposable Redis ACL
   rejects DEL with a real command error. Exactly one failure is recorded; the
   cache still contains `cached-draft`. A further reconstructed graph reads
   SUCCEEDED, cannot dispatch another AI turn, and sends the committed admin intent.
   There remain exactly 50 unique stages, 50 enriched business questions, and one
   teacher notification.
5. **SMTP accept / lost acknowledgment:** actual mail handler and dispatcher run
   with a freshly constructed store/eligibility/codec/renderer/handler/runtime.
   Fake transport records acceptance, then throws a retryable lost-ack failure.
   SQL reaches RETRY_WAIT. A second newly constructed graph retries the same job.
   Exactly two equal messages are accepted; one paid order and one durable payment
   mail identity remain. Duplicate SMTP delivery is explicitly possible.

Old-token completion is fenced after reclaim and tokens differ. The separate
boundary test also verifies that a stale checkpoint fails. Expiry fixtures set
`lease_until=clock_timestamp()-interval '1 second'`; retry availability likewise
uses current SQL clock. There are no huge waits or distant date assumptions.

## Actual JDBC and resource measurements

A test-only DataSource BeanPostProcessor wraps the real Hikari DataSource and
JDBC connections/statements. It counts actual `execute*` invocations, including
JPA and JdbcTemplate statements. It does not count SQL text logging or infer
queries from service calls. Batch invocation counts represent JDBC batch calls,
not individual rows/server round trips. Counts include fixture creation,
acceptance, workers, polling/verification queries, and finalization; automatic
poll scheduling makes exact totals vary across runs.

Final run's 1,000-question scenario:

Acceptance elapsed is measured separately with `System.nanoTime()` immediately
around the injected transactional proxy's `acceptance.accept(teacher, course)`
call, after all 1,000 fixture questions have been inserted. The timer stops only
after the proxy returns, including transaction commit. Assertions confirm the
service is proxied, no ambient transaction exists before or after the call, and
elapsed nanoseconds are positive. There is no SLA threshold. Fixture preparation,
worker dispatch, provider stalls, enrichment, finalization, and verification are
outside this acceptance timing window. The earlier 5.55-second measurement was
whole-scenario elapsed, not acceptance elapsed.

| Measurement | Observed |
| --- | ---: |
| Transactional acceptance, including proxy commit/return | 88,247,200 ns (88.247 ms) |
| JDBC executions | 2,291 |
| executeQuery calls | 1,136 |
| executeUpdate calls | 1,135 |
| executeBatch calls | 20 |
| Used JVM heap before fixture | 273,522,368 bytes |
| Used heap at pre-window stall snapshot | 284,008,128 bytes |
| Used heap at windows 1 / 2 / 3 | 289,251,008 / 289,775,296 / 292,396,736 bytes |
| JVM maximum heap, asserted <=896 MiB | 939,524,096 bytes |
| Hikari maximum configured/asserted | 4 connections |
| Hikari active / idle at each window endpoint | 0 / 4 |
| Jobs threads at each window endpoint | 15 / 15 / 15 |
| Maximum active handler AI / AUTH / NOTICE / MEDIA | 1 / 1 / 1 / 1 |
| Per-lane executor maximum / queued at stall checks | 1 / 0 |
| poll I/O active / pending / limit, window endpoints | 0 / 0 / 5 |
| renewal I/O active / pending / limit, window endpoints | 0 / 0 / 4 |
| poll scheduler active / pending, window endpoints | 0 / 4 |
| control scheduler active / pending, window endpoints | 0 / 2 |
| Main scenario elapsed, fixture through completion | 5,651 ms |
| Reconstruction generations / Redis failures / provider calls | 5 / 1 / 3 |

The test checks executors and pool bounds after each of the 30 observed renewals
and at each window endpoint. Worker executors have one slot each; poll I/O has
five threads and no queue; renewal I/O has four threads and no queue. Scheduled
executors have one core thread; their JDK-reported maximumPoolSize is
Integer.MAX_VALUE, which is not their effective scheduler concurrency. Pending
poll/control task counts are asserted <=5 and <=8 respectively. Jobs thread count
is asserted <=15 at each window endpoint. Used heap values are GC-sensitive
samples, not process RSS or continuously captured peaks. Pool/queue observations
are repeated samples, not continuous maxima.

## Scope and capacity limitations

- Environment is Windows Java 21 and Docker Desktop 29.8.2, Linux containers,
  PostgreSQL 17.11 with all 26 real Flyway migrations. Docker reports 15,887 MB
  available memory. Redis is disposable with a 64 MiB container limit and 32 MB
  maxmemory. PostgreSQL is disposable; Hikari is capped at four connections.
- Backend is run with and asserts an 896 MiB JVM heap ceiling. This does not cap
  total JVM/native/container memory. AI is a bounded in-process fake: one active
  request and at most 25 questions per batch. A separate 384 MiB AI process is not
  launched or measured. A real combined 896/384 MiB deployment remains a capacity
  limitation, not a blocker for these isolated bounded-execution assertions.
- The stall spans 30 actual lease renewals (roughly three seconds of blocked
  provider time), not a long-duration soak or deadline-expiry qualification.
  It uses state-change/latch oracles; 10 ms condition polling is only cadence,
  never a sleep-based success assertion.
- Redis invalidation failure is injected at the finalizer's cache boundary using
  a real ACL-rejected Redis operation. This does not exercise the application's
  entire RedisCacheManager serialization/configuration path; inherited normal
  test application caching stays disabled. Persisted SQL success and durable
  intents are proven despite failure before invalidation.
- Providers remain fake, SMTP is not a network server, and client disconnect is
  a query-service simulation. Higher-level registration/payment business flows
  are represented by SQL fixtures plus actual durable producers.
- No Linux/VPS throughput, separate AI memory capacity, full JVM process-kill,
  or full-suite regression claim is made. These are explicit scope limits, not
  unknown/skipped acceptance outcomes.
