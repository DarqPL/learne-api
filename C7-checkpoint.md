# C7 atomic finalization checkpoint — 2026-10-19

Status: **C7 DONE** after the follow-up verification below closed the listed acceptance gaps. Public acceptance API remains synchronous (C8 seam). No C8–C10 or R work performed.

## Files changed in this turn

- `src/main/java/com/learne/backend/teacher/content/submission/SubmissionFinalizationService.java` (new real completion port)
- `src/main/java/com/learne/backend/teacher/content/submission/CourseSubmissionHandler.java`
- `src/main/java/com/learne/backend/teacher/content/service/CourseSubmitFinalizationService.java`
- `src/main/java/com/learne/backend/admin/content/service/ReviewDecisionService.java`
- `src/main/resources/application.properties`
- `src/test/java/com/learne/backend/teacher/content/submission/SubmissionFinalizationIT.java` (new)
- `src/test/java/com/learne/backend/mail/ReviewMailTransactionsIT.java`
- `C7-checkpoint.md`

The existing jobs-it include `**/teacher/content/submission/**/*IT.java` already includes SubmissionFinalizationIT exactly through its package; no pom change was necessary.

## Implementation

Finalization locks course → matching revision → active submission → exact live leased job. Missing resources still lock the historical submission and job and terminalize with SUBMISSION_RESOURCE_UNAVAILABLE. Teacher eligibility/ownership, latest revision/number, original editable state, pinned V1 tuple, global fingerprint, per-question identity/ordinal/fingerprint, progress, exact actual manifest count and complete staged metadata are checked before application. JDBC applies batches of 100 in one transaction. Existing synchronous pending-review transition was extracted and reused, including superseding older pending revisions and preserving an already published course. The public synchronous route retains SubmissionGuard.

Submission/job terminal states, completion timestamps, lease removal, teacher notification and per-active-admin mail intents share the transaction. Active locked admins are included; zero admins is supported. Notifications use submission-success/failure dedupe keys; mail uses course-pending:submission:admin. Deleted teachers cannot own a notification because user_notifications has a user FK; existing teachers retain historical notification even after content deletion.

The handler translates known transient DB failures and JobEnqueueException outside completion's transaction proxy to BUSY; failure callback errors remain recoverable through lease recovery. Cache evictions run separately caught after commit. Review decisions look up only SUCCEEDED submission for the exact course/revision; legacy remains null and decision mail dedupe stays unchanged.

Registration retains the explicit feature property, now documented with default false in application.properties until C8. Enabled native context verifies real port registration. Lazy resolution of the real MailJobProducer breaks the enabled-context metrics → registry → handler → finalizer → producer → store startup cycle; no no-op implementation exists.

## Verification evidence

All Maven commands ran from backend with `$env:JAVA_HOME='F:\jdk_21'`, Java 21.0.7, disposable Testcontainers PostgreSQL 17.11, no real AI/SMTP/cloud. Tool timeouts were at most 180 seconds.

1. Initial `./mvnw.cmd -Pjobs-it '-Dtest=CourseSubmissionHandlerTests' '-Dit.test=SubmissionFinalizationIT' verify`: expected missing completion bean, five injection errors, 52.923 seconds. This is wiring-red evidence, not behavioral-red.
2. Initial implementation shard caught the missing background_jobs completed_at constraint and rolled back (four integration errors); fixed the terminal SQL. Review/payment suites passed in that run.
3. `./mvnw.cmd -q -Pjobs-it '-Dtest=CourseSubmissionHandlerTests,JobConfigurationTests' '-Dit.test=SubmissionFinalizationIT,ReviewMailTransactionsIT,PaymentMailTransactionsIT' verify`: exit 0. At this point 11 finalization + 18 review + 17 payment + 5 unit tests passed.
4. Expanded enabled-registration/cache shard exposed the real startup dependency cycle. SubmissionWorkerIT independently passed all 12 tests in 20.90 seconds. Fixed with lazy producer resolution.
5. Final `./mvnw.cmd -q -Pjobs-it '-Dtest=CourseSubmissionHandlerTests,JobConfigurationTests' '-Dit.test=SubmissionFinalizationIT' verify`: exit 0, **14 finalization tests in 25.08 seconds**, 3 configuration tests in 3.157 seconds, 2 handler tests in 0.087 seconds; zero failures/errors/skips. Command ran approximately 45 seconds including compilation and JVM startup.
6. ReviewMailTransactionsIT retained passing report: 18 tests / 8.957 seconds. PaymentMailTransactionsIT: 17 / 23.19 seconds. These preceded only the finalizer lazy dependency change, comments/default configuration and additional finalization tests.
7. `git diff --check`: exit 0, existing LF/CRLF warnings only.

66 distinct tests passed across these targeted shards: 14 finalization, 12 C6 worker, 18 review transactions, 17 payment transactions, 5 configuration/handler units.

New finalization scenarios: 1000-row application; real handler 40 staging calls then completion with no early question writes; zero admins; active admin fanout including locked and excluding inactive/non-admin; changed fingerprint; locked/removed-role teacher; exhausted callback unlock; stale lease; missing revision/course; incomplete staged set; SQL failure at question 150 rolls back prior JDBC batch; mail enqueue rollback; three individually throwing postcommit cache evictions leave success committed. Review linkage test includes a newer successful submission for another revision and verifies the exact matching ID in decision and mail.

## Initial checkpoint gaps (closed by follow-up below)

- Dedicated native assertions for preserving live published course and superseding older pending revisions (shared implementation preserves existing semantics, but new native fixture does not cover those states).
- Native finalization-specific DB lock-timeout retry and dispatcher control-only exhausted recovery. Existing C6 timeout/recovery tests and direct C7 exhausted callback pass; the combined dispatcher/finalizer path needs its own assertion.
- Stronger native lease identity/version/payload mismatch matrix and missing-course recreation/concurrent deletion scenarios.
- Additional new behavior tests were added after the implementation existed; strict per-scenario behavioral red/green was not established for every added case.

No stage, commit, push, branch, worktree or submodule-pointer operation was performed. Existing unrelated dirty work was retained.

## C7 completion follow-up — 2026-10-19

This follow-up changed only:

- `src/main/java/com/learne/backend/teacher/content/submission/CourseSubmissionHandler.java`
- `src/main/java/com/learne/backend/teacher/content/submission/SubmissionFinalizationService.java`
- `src/test/java/com/learne/backend/teacher/content/submission/CourseSubmissionHandlerTests.java`
- `src/test/java/com/learne/backend/teacher/content/submission/SubmissionFinalizationIT.java`
- `C7-checkpoint.md`

Boundary inspection confirmed success completion already used databaseTurn outside its transactional proxy. Permanent callbacks did not. They now use the same narrowly classified, sanitized BUSY exception boundary and reject ambient transactions. Neither path retries locally, resets attempts, rewrites leases or suppresses finalizer exceptions. Generic dispatcher success retries retain the existing finite attempt budget. A callback that cannot commit propagates to the existing dispatcher recovery boundary, leaves its RUNNING lease intact and is retried through lease expiry/reclaim; it never commits a job-only DEAD. Control-only recovery intentionally continues while persistence is unavailable, because terminalizing the job without committing the domain unlock would strand the submission. There is no recursive callback retry.

Native red evidence also exposed that direct completion/failure port calls could pair the current job/token with a different incoming submission payload even though the stored job payload matched. The real port now independently parses and validates the exact one-field incoming payload before taking locks. Stored payload/version/type and live token fencing remain checked under the existing ordered locks.

Closed coverage:

- Entire published course row stays equal across completion, current revision becomes pending, older pending becomes superseded, approved history remains untouched by the shared transition.
- Real dispatcher, real registered handler and real finalizer recover an abandoned exhausted lease through a control-only callback, atomically fail/unlock, notify once and reject the abandoned lease.
- Native five-second course lock timeout reaches the real finalizer after the read turn. Success execution emits RetryableJobException after rollback, reaches RETRY_WAIT without calling permanent failure, then succeeds after release. Control-only failure timeout leaves RUNNING and all domain state unchanged, then succeeds through expire/recover/reclaim.
- Three repeated native serialization failures consume exactly the configured attempts without reset, then real permanent failure commits FAILED/DEAD and unlocks with EXHAUSTED_ATTEMPTS.
- Failure notification insert serialization error rolls back failure/job terminalization; normal permanent callback propagates sanitized BUSY after rollback. Subsequent control-only recovery commits the unlock.
- Real dispatcher mail-enqueue infrastructure failure becomes retryable after full metadata/notification/job rollback; resume commits success with exactly one admin mail intent.
- Both complete/fail paths reject wrong incoming submission ID; wrong token/job ID/incoming version/incoming type; stored version/type/submission ID; incoming/stored extra payload fields; expired lease; terminal submission. This is 24 native mismatch cases, each proving unchanged job/submission rows and zero question metadata/notifications.

### Commands and attributable results

Every command used Java 21.0.7 from `F:\jdk_21`, disposable PostgreSQL 17.11 and a tool timeout of 180 seconds or less. No real AI/SMTP/cloud calls occurred.

1. Red unit: `./mvnw.cmd -q '-Dtest=CourseSubmissionHandlerTests#failureCallbackClassifiesOnlyKnownInfrastructureAfterProxyReturns' test` — one expected assertion failure, raw QueryTimeoutException instead of RetryableJobException; suite 1.584 seconds.
2. Red native: `./mvnw.cmd -q -Pjobs-it '-Dtest=JobConfigurationTests' '-Dit.test=SubmissionFinalizationIT#incomingPayloadMustMatchSubmissionEvenWithCurrentToken' verify` — both complete/fail cases failed expected rejection assertions; native suite 21.93 seconds. Three configuration tests passed.
3. Green native/lease: `./mvnw.cmd -Pjobs-it '-Dtest=CourseSubmissionHandlerTests,JobConfigurationTests' '-Dit.test=SubmissionFinalizationIT,JobLeaseIT' verify` — BUILD SUCCESS, Maven total 1:01; 36 finalization cases / 26.34 seconds, 15 lease cases / 25.34 seconds, six units. This preceded only additional test cases; production implementation was unchanged thereafter.
4. Final regression shard: `./mvnw.cmd -Pjobs-it '-Dtest=CourseSubmissionHandlerTests,JobConfigurationTests' '-Dit.test=SubmissionFinalizationIT,ReviewMailTransactionsIT,PaymentMailTransactionsIT,SubmissionWorkerIT' verify` — BUILD SUCCESS, Maven total 1:16; 92 integration cases plus six units, zero failures/errors/skips.

Final attributable suite reports:

| Suite | Cases | Seconds |
|---|---:|---:|
| SubmissionFinalizationIT | 45 | 21.40 |
| SubmissionWorkerIT | 12 | 13.05 |
| ReviewMailTransactionsIT | 18 | 6.974 |
| PaymentMailTransactionsIT | 17 | 24.41 |
| CourseSubmissionHandlerTests | 3 | 0.114 |
| JobConfigurationTests | 3 | 3.465 |
| JobLeaseIT (preceding shard, same production implementation) | 15 | 25.34 |

**113 distinct passing cases this follow-up**, including 31 added native finalization cases and one added handler unit case. Repeated cases across shards are counted once. Full suite was not run.

No remaining implementation blocker is known for the requested C7 scope. C8 still owns public API activation. The earlier strict per-scenario TDD limitation is historical evidence, not a claim that all original scenarios had behavioral red runs. No commit/stage/push/branch/worktree/pointer changes, external service calls, subagents or R work were performed.
