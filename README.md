# LearnE Backend

## Tài liệu API

## C2 enrichment HTTP client

The `app.course-enrichment` namespace owns `timeout-ms` (default `120000`, total
headers/body deadline), `connect-timeout-ms` (default/max `5000`), and `max-in-flight`
(default `2`, range `1..8`). Total deadlines accept short test budgets and are capped
at 120 seconds, independent of interactive `app.ai-service.timeout-ms`. Base URL,
enabled flag, and internal token continue to come from `app.ai-service`.

`CourseKnowledgePointClient.enrichKnowledgePoints(request, contract)` requires an
explicit `EnrichmentContract`; the temporary synchronous orchestrator pins `V1`.
The JSON remains course/questions only. Successful responses must echo both pinned
execution headers. Typed job failures have finite safe codes and no provider causes;
the old synchronous orchestrator bridges disabled to 503 and other failures to 502.

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
only that interface exists now, with the database source pending C3.

Prompt/response wire versions use `[1-9][0-9]{0,8}` in Java and Python; the policy
identifier is backend-local and is not sent as an AI contract header. Unsupported runtime
tuples produce typed failures, never automatic version fallback. C6 will own future
submission unlock handling. Metadata parsing is pure and bounded; it does not execute
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
database queue. Once future C-phase submissions are active, do not roll back to an old
writer that cannot preserve their protocol. Course background submission/import and UI
integration remain **future C3–C10 work**; C2 does not establish a 1,000-user capacity fix.

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

Actuator và chính sách bảo mật hiện có được giữ nguyên. Đây là nền tảng, chưa có business handler
mail/course và chưa có kết luận về năng lực production.

## M8 mail queue configuration and operational semantics

The F6 paragraph above records the foundation-stage scope. Mail queues are now integrated
with registration OTP, password reset, teacher invitations, payment confirmations, and
course review-result notices. Pending-review delivery support (handler/template) is registered,
but production business-producer activation is deferred to Plan 3/C7.
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

Course asynchronous submission/import/enrichment is **future C-phase work**, not completed
by mail notices or queue foundations. This configuration does not establish support for
1,000 concurrent users, CPU/RAM sizing or native Linux capacity. It does not close the
full M8 phase exit; controller verification and mail integration-test shards are separate.
