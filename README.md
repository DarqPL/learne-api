# LearnE Backend

## Tài liệu API

## C2 enrichment HTTP client

The `app.course-enrichment` namespace owns `timeout-ms` (default `120000`, total
headers/body deadline), `connect-timeout-ms` (default/max `5000`), and `max-in-flight`
(default `2`, range `1..8`). Total deadlines accept short test budgets and are capped
at 120 seconds, independent of interactive `app.ai-service.timeout-ms`. Base URL,
enabled flag, and internal token continue to come from `app.ai-service`.

`CourseKnowledgePointClient.enrichKnowledgePoints(request, contract)` requires an
explicit `EnrichmentContract`; queued submissions pin their persisted contract.
The JSON remains course/questions only. Successful responses must echo both pinned
execution headers. Typed job failures have finite safe codes and no provider causes;
the legacy synchronous bridge is not a submission rollback path.

`EnrichmentHttpTransport.exchange(HttpRequest, Duration)` returns a defensive
`Response(status, headers, body)` with a 1 MiB body cap. `requestTimeout()` exposes
the configured budget. `capabilities(URI, String internalToken)` performs a bounded
GET (at most five seconds) for the cached admission gate. Neither construction
nor bean startup performs HTTP. Owned exchanges are cancelled on deadline/close;
the four-thread daemon executor has a finite queue, and shutdown waits at most 200 ms.
Redirects and system proxies are disabled. Requests and responses are capped at
1 MiB; JSON nesting is capped at 32. Retry-After accepts one seconds/date value up
to one day; invalid, negative, duplicate, oversized or unrepresentable values are ignored.

### Runtime configuration and compatibility gate

| Environment variable | `app.course-enrichment` property | Default / accepted bounds |
| --- | --- | --- |
| `COURSE_ENRICHMENT_TIMEOUT_MS` | `timeout-ms` | `120000`; positive, at most `120000` |
| `COURSE_ENRICHMENT_CONNECT_TIMEOUT_MS` | `connect-timeout-ms` | `5000`; positive, at most `5000` |
| `COURSE_ENRICHMENT_MAX_IN_FLIGHT` | `max-in-flight` | `2`; `1..8` |

`EnrichmentClientProperties` binds this dedicated namespace. `AI_SERVICE_TIMEOUT_MS`
still controls interactive/other AI modules, not this Java HTTP transport. Production
layers must stay consistent: the backend's **120-second** total HTTP deadline exceeds
Python's **110-second** monotonic enrichment budget, while the AI job deadline remains
**120 seconds** and its lease **180 seconds**. The production Gunicorn worker watchdog
is **140 seconds**; it does not itself guarantee a per-request deadline. Short test
budgets are supported; independently valid overrides do not prove production-layer
consistency. `LLM_TIMEOUT_MS` is a per-attempt ceiling clamped to remaining total budget,
not a fresh budget multiplied by the number of fallback models.

`EnrichmentCapabilityGate` starts scheduled refresh only after `ApplicationReadyEvent`
when workers and AI are enabled. Claims read cached state only; refresh uses a bounded
five-second GET, repeats after 15 seconds when healthy, and backs off 5/10/30/60 seconds
on failure. Missing compatibility pauses **AI only**, not mail. Capabilities prove exact
contract compatibility, not LLM-provider health or usable credentials. Required contracts
are `V1` union active persisted contracts supplied lazily through `EnrichmentContractSource`;
`SubmissionRepository` reads these from `QUEUED`/`PROCESSING` attempts.

Prompt/response wire versions use `[1-9][0-9]{0,8}` in Java and Python; the policy
identifier is backend-local and is not sent as an AI contract header. Unsupported runtime
tuples produce typed failures, never automatic version fallback. Submission finalization
owns terminal state and unlock handling. Metadata parsing is pure and bounded; it does not execute
provider output. Failures use safe finite codes (including
`ENRICHMENT_CONFIGURATION_UNAVAILABLE`); do not log raw diagnostics, tokens or payloads.

This backend currently implements only response validator version `1` (`items`,
`questionId`, `knowledgePoint`). Local policy support remains `1`; prompt versions are
AI-owned, so a remotely supported prompt `2` with response `1` remains compatible.
An unimplemented local response version pauses AI admission as `UNSUPPORTED_RESPONSE`
and direct client calls fail permanently with `ENRICHMENT_CONTRACT_UNAVAILABLE` before HTTP.

Before any deployment or AI-only update, verify the **target AI image and every replica**
support every active prompt/response pair, and verify the target backend implements every
active response validator and local policy. Probing one current load-balanced URL is not
proof. Rollback must retain required version implementations, the decryption keyring and
database queue. Active submissions require writers that preserve their locking protocol.
Course background submission/import and UI integration are implemented; deployment and
capacity acceptance remain separate. See the [cutover runbook](../infra/README.md#r5-queue-cutover-and-rollback).

- [Audio bài học cho giảng viên: discovery, upload/thay và xóa](docs/teacher-lesson-audio-api.md)

## Kiểm chứng audio trên PostgreSQL

Yêu cầu Java 21 và Docker Engine đang chạy. Chạy tại thư mục `backend` bằng PowerShell; profile này chạy cả Surefire regression và ba suite Failsafe PostgreSQL native:

```powershell
./mvnw.cmd -Paudio-it "-Dit.test=LessonAudioConcurrencyIT,LessonAudioWriterProtocolIT,LessonAudioImportConcurrencyIT" verify
```

Profile `audio-it` dùng Testcontainers với `postgres:17-alpine` và Flyway hiện có; Docker không khả dụng là lỗi kiểm chứng, không fallback sang H2. Cloudinary, ClamAV và AI được mock trong các suite này.

## F6 — số liệu nền tảng background jobs

Micrometer đăng ký các metric `learne.jobs.completed`, `learne.jobs.retries`,
`learne.jobs.lease.stale`, `learne.jobs.duration`, `learne.jobs.oldest.ready.seconds`,
`learne.jobs.unsupported.version` và `learne.jobs.backlog`. Nhãn chỉ thuộc các tập hữu hạn
`queue`, `type`, `state`, `outcome`; không chứa ID, email, payload hoặc thông báo nhà cung cấp.
Kiểu/queue không nhận diện được gộp vào `UNSUPPORTED` ngay trong SQL.

- Completion/retry của `JobStore` chỉ tăng sau commit; rollback không được tính.
  Stale đếm từng lần kiểm tra ownership/heartbeat/completion bị từ chối, không phải số job duy nhất.
- Duration đo thời gian thực của **mỗi lượt worker**, gồm execute/control và định tuyến lỗi;
  không biểu thị thời gian chờ hàng đợi hay độ trễ hoàn thành đã commit.
- Backlog gồm `PENDING` (READY), `RETRY` (RETRY_WAIT), `RUNNING`, `DEAD`, bao gồm cả job trì hoãn.
  Tuổi oldest-ready chỉ xét READY/RETRY_WAIT đã đến hạn, dùng `clock_timestamp()` của PostgreSQL;
  hàng đợi rỗng hoặc chỉ có job tương lai có tuổi bằng 0.
- Một truy vấn aggregate chung, kết quả có số nhóm hữu hạn và timeout statement 5 giây,
  làm mới snapshot mỗi **60 giây** trên scheduler riêng khi `app.jobs.worker-enabled=true`.
  Không truy vấn trong constructor, control timer hoặc lúc HTTP scrape; có `refresh()` thủ công cho test.
  Lỗi đọc giữ snapshot trước và tăng outcome `REFRESH_FAILED`, không ghi stack/SQL parameters.
- Unsupported là **số quan sát job chưa hỗ trợ trên mỗi snapshot thành công**, có thể đếm lại cùng job
  qua nhiều lần refresh; outcome `UNSUPPORTED`, không claim hoặc sửa job. Bao gồm handler không có
  tại deployment này, version không hỗ trợ và kiểu dữ liệu không nhận diện.

Đoạn F6 ghi nhận phạm vi nền tảng tại thời điểm đó; các handler mail/course hiện đã được
tích hợp như mô tả bên dưới. Chưa có kết luận về năng lực production.

## M8 mail queue configuration and operational semantics

The F6 paragraph above records the foundation-stage scope. Mail queues are now integrated
with registration OTP, password reset, teacher invitations, payment confirmations, and
course review-result notices. Pending-review production also uses the durable notice queue
when submission finalization enters pending review.
Business changes and queue insertion commit
together; an accepted request means durable queue acceptance, **not delivered email**.
The historical auth/rate-limit verification reports describe their original phases;
their synchronous SMTP rollback descriptions are not the current queue delivery contract.

### Runtime configuration

| Environment variable | Application property / meaning | Default |
| --- | --- | --- |
| `APP_JOB_CRYPTO_ACTIVE_KEY_ID` | `app.job-crypto.active-key-id`, key used for new encrypted jobs | Empty; configure explicitly |
| `APP_JOB_CRYPTO_KEYS` | `app.job-crypto.keys-json`, JSON object mapping key IDs to Base64 keys | Empty; configure explicitly |
| `APP_JOBS_WORKER_ENABLED` | `app.jobs.worker-enabled`, delivery and job maintenance scheduling | `true` |
| `APP_FRONTEND_BASE_URL` | `app.frontend-base-url`; `app.mail.frontend-base-url` aliases this for the renderer | `http://localhost:5173` |
| `MAIL_SMTP_CONNECTION_TIMEOUT_MS` | `spring.mail.properties.mail.smtp.connectiontimeout` | `5000` |
| `MAIL_SMTP_READ_TIMEOUT_MS` | `spring.mail.properties.mail.smtp.timeout` | `15000` |
| `MAIL_SMTP_WRITE_TIMEOUT_MS` | `spring.mail.properties.mail.smtp.writetimeout` | `15000` |
| `APP_MAIL_DELIVERY_DEADLINE_MS` | `app.mail.delivery-deadline-ms`, total SMTP caller deadline | `45000` |

`SmtpTransportConfiguration` reads the existing `spring.mail.host/port/username/password`
from `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, plus the existing
`MAIL_SMTP_AUTH` and `MAIL_SMTP_STARTTLS` mappings. There is one SMTP credential source.
`MAIL_FROM` maps to `app.mail.from` and must be a valid single From address; the MIME
transport rejects a missing/invalid From. STARTTLS enabled requires successful STARTTLS.

Configure **one trusted HTTP(S) frontend URI**, not a comma-separated CORS list: host
required, no user-info, query, fragment or path other than `/`. Production uses
`https://learne.connection-app.online`; links are rendered from configuration.

### Sensitive keyring and rotation

Every key must decode from Base64 to **exactly 32 bytes**. The active ID must exist in
the JSON map. OTPs, reset codes and invitation temporary passwords use encrypted job
secrets. Never reuse the JWT secret or rate-limit HMAC key. The `local-v1` key in the
development example is public test material only. The `prod-v1` placeholder is
intentionally invalid, not a usable production key.

Generate a new independent production key in a secure operator environment, for example
`openssl rand -base64 32`, and place it under a new ID in the protected JSON keyring.
Distribute the enlarged keyring to all participating instances before switching the
active ID for new jobs. **Retain old decryption keys through all active jobs referencing
them**, including pending, running and retrying jobs. Do not expose key values, encrypted
payloads or plaintext credentials in logs or rendered Compose output.

### Delivery, expiry and retries

- Registration OTPs live **5 minutes** and reset codes **15 minutes from generation**.
  Queue delay/retries do not extend validity or reset the **5 verification-attempt**
  limit. Stale generations, exhausted attempts and codes with **less than 30 seconds**
  remaining are skipped after fresh eligibility checks; pending delivery is not a
  new TTL window.
- Teacher invitation jobs have a **24-hour delivery deadline**. Explicit eligible
  resend creates a new temporary password and generation and revokes visible committed
  refresh sessions atomically with enqueue. It does not resend an old password or
  promise cancellation of an SMTP send already in flight. This delivery deadline is
  not a claim that the credential itself automatically expires after 24 hours.
- Invitation delivery statuses include `QUEUED`, `SENDING`, `RETRYING`, `SENT`, `FAILED`
  and `SKIPPED` (also `NOT_REQUESTED` / `HISTORY_UNAVAILABLE` where applicable).
  `SENT` means SMTP DATA was accepted and queue success recorded, **not inbox delivery**.
- Notice delivery is skipped if the recipient email changed; it is not redirected to
  a new address. Review/payment eligibility is rechecked before sending.
- Mail retries are finite (default **5 total attempts**), with backoff and expiry checks.
  SMTP acceptance followed by a lost acknowledgement or failed completion commit can
  cause duplicate sends. This is neither exactly-once delivery nor an inbox guarantee.

### Worker pause and bounded SMTP

`APP_JOBS_WORKER_ENABLED=false` pauses delivery and scheduled queue maintenance; it does
not create an in-memory mail fallback or stop ingress from accepting durable jobs.
Use controlled ingress and drain in-flight requests/jobs before a maintenance cutover.
Watch backlog, oldest-ready age and expiring auth generations before resuming workers;
an expired code is not renewed automatically. Worker-off does not bypass required
encryption/SMTP configuration validation.

SMTP has **two actual I/O slots**, with zero waiting queue, shared by both mail lanes.
The default **45-second caller deadline includes DNS**. Uninterruptible OS DNS may
retain an I/O slot after the caller returns; saturation returns retryable `BUSY` rather
than creating more threads. Aborted DNS work cannot later connect/send. Socket connect,
read and write limits complement the total deadline; they do not guarantee timely OS
DNS termination. Do not raise slots/deadlines to mask a stuck resolver.

The Angus provider logging guard suppresses sensitive provider diagnostics separately
from disabled session debug. Review that guard and provider logger coverage on every
Angus/provider upgrade; do not enable SMTP protocol/body/credential logging to diagnose
delivery failures.

Course submission now accepts durable work with HTTP **202**, processes pinned batches,
and finalizes status, locks and notifications. Import keeps its upload/scan/write boundary;
subsequent submission enrichment is queued. The frontend must understand acceptance and
poll persisted status rather than treating 202 as completed review.

## R5 media, maintenance and retention operations

`APP_JOBS_MEDIA_CLEANUP_ENABLED=true` maps through Spring's environment binding to
`app.jobs.media-cleanup-enabled`. Missing/false leaves the MEDIA handler unregistered;
both Compose examples explicitly default to **false**, including production. Producers
can still persist cleanup intent. `APP_JOBS_WORKER_ENABLED=false` also stops claims and
scheduled maintenance, so it is not an ingress switch. Enable media only after the
[infrastructure preflight](../infra/README.md#media-enable-preflight).

Media v1 deletes only Cloudinary **VIDEO** resources at the fixed HTTPS endpoint
`https://api.cloudinary.com/v1_1/<configured-cloud>/video/destroy`, with no redirects.
The bounded adapter has two slots, a 60-second total deadline, five-second connect limit,
eight-second read limit and 16 KiB response cap. `ok` and `not found` are successful.
The decision transaction locks the course before checking global live/revision references,
then ends before provider I/O. Managed provenance requires an exact configured-cloud
HTTPS delivery URL and `learne/course-audio/` public ID. Missing courses, unproven
provenance, references or ambiguous URLs retain the asset; failed DB checks retry.
Every writer must preserve the course-lock/no-reintroduction protocol, including rollback images.

Committed mutations enqueue cleanup in their transaction. Upload compensation creates
durable intent only for a known rollback (or proven pre-persistence failure); an unknown
commit outcome retains assets. Compensation enqueue failure retains assets and emits a
safe diagnostic: an upload followed by process loss or failed compensation enqueue can
leave an orphan without durable intent. This is not a complete orphan collector; never
infer safe deletion from a failed response or sweep historical uploads into new jobs.

### Explicit operator retry

Use the retained envelope through `scripts/retry-background-job.ps1`, from `backend/`,
with **explicit Java 21 executable and packaged backend artifact** paths. The operator
environment must supply `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and
`SPRING_DATASOURCE_PASSWORD` for the intended database; media retry additionally needs
`APP_CLOUDINARY_CLOUD_NAME` (the public cloud name, distinct from normal runtime
`CLOUDINARY_CLOUD_NAME`). Load credentials through the protected operator environment;
do not echo them or pass them as CLI arguments. These are mutating operator actions,
not read-only diagnostics; they are not run by documentation verification.

```powershell
./scripts/retry-background-job.ps1 `
  -JobId '11111111-1111-4111-8111-111111111111' `
  -Reason 'INCIDENT_123' -Operator 'operator.one' `
  -ArtifactPath 'C:\operator\artifacts\backend.jar' `
  -JavaPath 'C:\operator\jdk-21\bin\java.exe'
```

The wrapper uses `-Dloader.main=com.learne.backend.jobs.maintenance.JobMaintenanceApplication`,
`-cp <artifact>` and `org.springframework.boot.loader.launch.PropertiesLauncher`, with
exactly `--app.jobs.maintenance.retry-job`, `--app.jobs.maintenance.reason` and
`--app.jobs.maintenance.operator`. It starts an isolated nonweb maintenance context,
not the normal server, scheduler or provider clients. Exit 0 means the transaction
reset the same envelope/dedupe key to READY with attempts zero; rejection exits 2.
The worker performs delivery later, subject to its normal checks and flags.

Job IDs must be canonical lowercase UUIDs. Reason matches `[A-Z][A-Z0-9_-]{2,63}`;
operator matches `[A-Za-z0-9][A-Za-z0-9_.-]{0,63}`. Use incident/operator identifiers,
never free text, recipient data or secrets. Audit contains only job/type/operator/reason.
The exact allowlist is payload v1 of `PAYMENT_CONFIRMATION_MAIL`,
`COURSE_PENDING_REVIEW_MAIL`, `COURSE_REVIEW_RESULT_MAIL` and `MEDIA_CLEANUP`, only in
`SUCCEEDED`, `SKIPPED` or `DEAD`, without secrets or expiry. Authoritative notice
eligibility and media safety are checked again under domain locks before the job lock.
Active jobs, other versions/types, auth, invitations and course enrichment are rejected.
Use eligible auth/invitation resend to generate a fresh credential, and fresh course
submission acceptance after terminal failure. Never repair jobs by SQL writes, relabel
versions, replay historical events or bypass generation checks. Retrying a successful
notice deliberately risks another SMTP send; record that decision.

### Retention and replay boundary

Maintenance runs every 60 seconds when workers are enabled, in bounded batches of 500
with locked candidates skipped. Terminal jobs (`SUCCEEDED`/`SKIPPED`/`DEAD`) and terminal
submission question staging are deleted after **30 days from completion**. Terminal
submission summaries expire after **90 days from completion**; user notifications after
**90 days from creation**, regardless of read status. Active attempts/jobs are retained
regardless of age. Historical immutable review decisions are not purged by these lanes.
Expired auth/invitation secrets are erased, including on RUNNING envelopes without
changing their lease; all terminalization also clears secrets. Retention is fixed in
code, not an environment-configurable retention policy.

Dedupe is bounded by retained records. After purge, do not replay historical PAID,
review, OTP, import or media events to rebuild jobs. A retained terminal notice/media
envelope is the only supported operator retry boundary. SMTP can duplicate after a lost
acknowledgement or unknown completion commit; retention does not establish exactly-once
effects or resolve an unknown business commit outcome.

Historical [auth evidence](../docs/superpowers/reports/2026-10-16-p0-phase-3b-password-reset-verification.md)
and [audio API documentation](docs/teacher-lesson-audio-api.md) retain their original
scope. Current queued delivery, media intent and retry/retention semantics above supersede
only their synchronous SMTP/cleanup descriptions; their unrelated findings remain evidence.
R1–R4 approval is prior review evidence, not a new test result from this documentation task.
Windows/Docker Desktop verification does not establish native Linux capacity, 1,000-user
support or adequacy of the AI container's 384 MiB limit.
