# C6 batch worker checkpoint — 2026-10-19

Status: C6 implementation and targeted verification complete; independent review pending. C7 domain finalization is intentionally not implemented. Public submit remains synchronous until C8.

## Changed files in this turn

- `src/main/java/com/learne/backend/teacher/content/submission/CourseSubmissionHandler.java` (new)
- `src/main/java/com/learne/backend/teacher/content/submission/SubmissionCheckpointService.java` (new)
- `src/main/java/com/learne/backend/teacher/content/submission/SubmissionCompletionPort.java` (new)
- `src/main/java/com/learne/backend/teacher/content/submission/SubmissionRepository.java`
- `src/main/java/com/learne/backend/teacher/content/enrichment/CourseReviewEnrichmentService.java`
- `src/main/java/com/learne/backend/jobs/config/JobConfiguration.java`
- `src/test/java/com/learne/backend/teacher/content/submission/SubmissionWorkerIT.java` (new)
- `C6-checkpoint.md` (this report)

The existing JobRegistry consumes the conditional handler through its existing List<JobHandler> protocol; its indexing and dispatcher lifecycle do not require changes. No generic dispatcher success transition was added.

## Behavior

Each turn reads the immutable submission identity/contract and next at most 25 unstaged manifest rows in a five-second repeatable-read transaction. The reviewed projection rebuilds per-question input fingerprints and bounded provider payloads. Only V1 is implemented locally; unavailable pinned versions are permanent and never default to another tuple. Provider work runs after the read transaction ends; fake providers assert no active transaction.

Whole response validation uses the existing exact-ID-set validator and knowledge-point normalization. Original projection text feeds the existing difficulty calculator and XP policy. The synchronous snapshot API remains available unchanged for C8 migration.

Checkpoint locks course, revision, submission and job in that order. It checks active submission identity/contract and current token, database-clock expiry, payload version/type and exact submission-only JSON. All staged results, unique-row progress recount and fenced READY yield commit together. Fencing failure throws and rolls back. Attempts reset only in this whole-batch checkpoint; availability uses the database clock and created_at is preserved. Revision metadata is never written by staging.

## Exact C7 registration seam

`SubmissionCompletionPort.complete(lease, submissionId)` owns atomic staged-set validation/application, revision transition, submission/job success and notifications/fanout. `fail(lease, submissionId, code)` owns atomic submission failure, job death, unlock and historical notification, including exhausted/control-only dispatcher callbacks. Both must implement the approved course → revision → submission → job locking/fencing protocol and resource-unavailable path. Neither has a production implementation in C6.

`JobConfiguration.courseSubmissionHandler` is conditional on `app.jobs.course-submission-enabled=true` (absent/default disabled) and requires a SubmissionCompletionPort bean. Enabling it without C7 fails dependency resolution; no success no-op fallback exists. C7 must supply the real implementation before setting this property. The existing registry consequently does not advertise COURSE_ENRICHMENT in the current production context. Tests construct a handler with a fake completion port that durably terminalizes only test jobs; those assertions prove delegation, not C7 domain unlock/notification correctness.

## Verification

All commands ran in backend with `$env:JAVA_HOME='F:\jdk_21'`, Java 21.0.7 and disposable native PostgreSQL 17.11 through existing Testcontainers support. Each command timeout was at most 180 seconds. No real enrichment provider was called.

1. Red `./mvnw.cmd -Pjobs-it '-Dtest=SubmissionWorkerIT' '-Dit.test=SubmissionWorkerIT' test-compile`: expected missing worker/checkpoint classes; BUILD FAILURE, 7.792 seconds. This is compilation-red evidence, not a behavioral assertion-red claim.
2. Initial `./mvnw.cmd -Pjobs-it '-Dtest=CourseReviewEnrichmentServiceTests' '-Dit.test=SubmissionWorkerIT,JobLeaseIT' '-Dsurefire.timeout=120' verify`: seven units + two worker + fifteen lease tests passed, 69 seconds.
3. Expanded non-clean verification encountered unresolved IDE-generated test bytecode and an incorrect RetryableJobException constructor. Corrected constructor to `(code, null)` and used clean verification.
4. `./mvnw.cmd -Pjobs-it '-Dtest=CourseReviewEnrichmentServiceTests' '-Dit.test=SubmissionWorkerIT,JobLeaseIT' '-Dsurefire.timeout=120' clean verify`: seven units + nine worker + fifteen lease tests passed, zero failures/errors/skips, 69 seconds.
5. Final after two added worker scenarios: `./mvnw.cmd -Pjobs-it '-Dtest=CourseReviewEnrichmentServiceTests' '-Dit.test=SubmissionWorkerIT' '-Dsurefire.timeout=120' '-Dmaven.compiler.useIncrementalCompilation=false' verify`: seven units + eleven worker tests passed, zero failures/errors/skips, 30.710 seconds. Worker suite 23.16 seconds. Production code was unchanged since the preceding clean lease verification.
6. `git diff --check` passed (existing Git LF/CRLF conversion warnings only).

33 distinct passing targeted tests across final worker/enrichment and clean lease evidence. Worker scenarios cover 1,000 → 40 remote calls of at most 25; batch-15 transient retry with handler restart and no repeated committed IDs; two-job interleaving and original created_at; duplicate/unknown/missing/null/malformed responses without partial checkpoints; blocked old worker expiry/reclaim; response-before-checkpoint repeat and checkpoint-before-return resume; unavailable local/remote versions and input fingerprint changes; production registration disabled; and a test trigger expiring the lease after staging/progress writes to prove final fenced-yield failure rolls the entire transaction back.

No stage, commit, push, branch, worktree or submodule pointer operation was performed. No C7–C10 implementation or R work was started in this turn.

## C6 quality follow-up — transient database failures

Important finding addressed: raw database/transaction errors from the read/checkpoint proxies previously reached the dispatcher as generic errors and invoked permanent failure while a valid lease still existed. A native course-row lock reproduced a five-second PostgreSQL statement timeout, translated to Spring QueryTimeoutException, after proxy rollback.

CourseSubmissionHandler now translates recognized failures only around `checkpoints.load` and `checkpoints.checkpoint`, outside their transactional proxies:

- TransientDataAccessException subclasses (including query timeout, lock acquisition, serialization/concurrency and transient resource errors), RecoverableDataAccessException, CannotGetJdbcConnectionException and TransactionTimedOutException become sanitized `RetryableJobException("BUSY", null)`.
- CannotCreateTransactionException becomes BUSY only if a bounded scan of at most eight causes finds one of those database resource classifications, SQLTransientException, SQLRecoverableException, or a SQLException with SQLState class 08 (connection exception). Unrecognized transaction-creation failures propagate unchanged.
- Permanent validation/version errors, stale-fence IllegalStateException, data-integrity/SQL-grammar errors, unknown rollback failures and other runtime exceptions propagate unchanged. Provider and C7 completion/failure calls are outside this translation boundary.
- There is no local retry loop or attempt reset. The dispatcher retains finite max attempts, configured backoff/jitter, current-lease checks and exhausted-attempt domain-failure delegation. Converted exceptions discard SQL messages/causes and stack traces through the existing RetryableJobException contract.

Files changed for this follow-up: CourseSubmissionHandler.java; new CourseSubmissionHandlerTests.java; SubmissionWorkerIT.java; JobConfigurationTests.java; this report. No dispatcher/configuration production change was needed.

Red command (Java 21.0.7): `./mvnw.cmd -Pjobs-it '-Dtest=JobConfigurationTests' '-Dit.test=SubmissionWorkerIT#checkpointCourseLockTimeoutRetriesAfterRollbackWithoutFailingSubmission' clean verify` — 64 seconds, three configuration tests passed; the new native test failed its expected RetryableJobException assertion because the actual error was QueryTimeoutException. This is behavioral-red evidence. The startup missing-port assertion already passed against the required bean dependency.

Green command: `./mvnw.cmd -Pjobs-it '-Dtest=CourseSubmissionHandlerTests,JobConfigurationTests' '-Dit.test=SubmissionWorkerIT' clean verify` — BUILD SUCCESS, 61 seconds, **17 tests passed** (two classification unit tests, three configuration tests, twelve native worker tests), zero failures/errors/skips, PostgreSQL 17.11. Classification units exercise nine retryable and six unchanged exception cases. The native timeout scenario uses the real transactional checkpoint proxy and dispatcher, holds a course lock on a separate connection, verifies no ambient transaction when the handler error escapes, then asserts RETRY_WAIT, attempts=1, zero permanent-port calls, one fake-provider call, QUEUED submission, zero processed count and zero staged rows. Existing stale/reclaim and post-staging rollback scenarios passed again. Latches and polling are bounded; both commands had 180-second tool timeouts.

The startup regression explicitly sets course-submission-enabled=true with all preceding handler dependencies supplied and verifies NoSuchBeanDefinitionException for SubmissionCompletionPort. C7 registration remains gated exactly as described above. No Git mutations, real provider calls or R work were performed.
