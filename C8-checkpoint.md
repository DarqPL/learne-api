# C8 API activation checkpoint — 2026-10-19

Status: **C8 spec NOT APPROVED; sole 55P03 review finding fixed and verified, pending re-review (2026-10-20 00:21).** Prior DONE claims are superseded by the user's review rejection. Full-backend phase-exit accounting remains deferred to C10. No C9/C10 implementation or R work. No Git mutations, staging, commits, pushes, branches, worktrees, pointer updates or subagents.

## Implemented in this checkpoint

- Existing POST submit delegates directly to acceptance and returns 202, persisted SubmissionResponse and exact course/submission Location. No enrichment call in the controller.
- SubmissionQueryService and CourseSubmissionController expose latest/exact status, checking current course ownership and course/submission identity under repeatable read; missing routes use the same 404 message.
- CourseResponse adds `submission` and `editingLocked`; both factories updated. Teacher list uses one batch `distinct on (course_id)` summary query; detail loads persisted summary. `hasEditableRevision` remains independent.
- NotificationService/Controller implement list, global unread-count and idempotent owner read. DTOs are nested records with the approved fields. Signed cursor binds user/unreadOnly, stores timestamp/UUID without numeric conversion, uses PostgreSQL tuple ordering. Size outside 1..100 is 400. List/count explicitly use read-only REPEATABLE_READ; patch uses REPEATABLE_READ and returns count in the update transaction.
- Resource existence/ownership uses page-query joins for course, revision and exact submission, with no per-notification query. Unavailable history retains message and returns false/null; no resource retargeting.
- CORS exposes Location. Production handler feature property now defaults true. Native fixtures retain worker-enabled=false so no dispatcher consumes their jobs.

## Files changed by this turn

New:
- src/main/java/com/learne/backend/notification/NotificationService.java
- src/main/java/com/learne/backend/notification/NotificationController.java
- src/main/java/com/learne/backend/teacher/content/submission/SubmissionQueryService.java
- src/main/java/com/learne/backend/teacher/content/controller/CourseSubmissionController.java
- src/test/java/com/learne/backend/notification/NotificationApiIT.java
- src/test/java/com/learne/backend/teacher/content/submission/SubmissionApiIT.java
- C8-checkpoint.md

Updated:
- src/main/java/com/learne/backend/config/SecurityConfig.java
- src/main/java/com/learne/backend/teacher/content/controller/TeacherContentController.java
- src/main/java/com/learne/backend/teacher/content/dto/CourseResponse.java
- src/main/java/com/learne/backend/teacher/content/service/TeacherContentService.java
- src/main/java/com/learne/backend/teacher/content/submission/SubmissionRepository.java
- src/main/resources/application.properties
- src/test/java/com/learne/backend/teacher/content/audio/CourseWriteProtocolTests.java (constructor dependency)

## Verification

All Maven commands ran in backend with `$env:JAVA_HOME='F:\jdk_21'`, Java 21.0.7; each command timeout <=180 seconds. Disposable Testcontainers PostgreSQL 17.11, no real AI/SMTP/cloud calls. Existing jobs-it package includes already select both exact API IT classes, so no pom modification was needed this turn.

1. First test compile failed because domain User is not UserDetails; corrected fixtures to use authentication with domain principal.
2. Behavioral red: `./mvnw.cmd -q -Pjobs-it '-Dtest=CourseSubmissionHandlerTests' '-Dit.test=SubmissionApiIT,NotificationApiIT' verify` — expected notification 404 instead of 200 and duplicate submit 409 instead of 202, two failures.
3. Same command after API implementation — exit 0, two IT cases plus three handler units passed.
4. Added summary assertion, then `./mvnw.cmd -q -Pjobs-it '-Dtest=CourseSubmissionHandlerTests' '-Dit.test=SubmissionApiIT' verify` — expected missing `$.submission.id` assertion failure.
5. Final shard: `./mvnw.cmd -q -Pjobs-it '-Dtest=CourseSubmissionHandlerTests,CourseResponseTests,CourseWriteProtocolTests' '-Dit.test=SubmissionApiIT,NotificationApiIT,SubmissionAcceptanceIT' verify` — exit 0, **34 cases**, zero failures/errors/skips:
   - NotificationApiIT 2 / 17.02s
   - SubmissionApiIT 1 / 6.420s
   - SubmissionAcceptanceIT 11 / 8.259s (includes real concurrent duplicate acceptance and blocked fake AI acceptance)
   - CourseWriteProtocolTests 15 / 19.54s
   - CourseResponseTests 2 / 0.200s
   - CourseSubmissionHandlerTests 3 / 0.269s
6. `git diff --check` exit 0; existing LF/CRLF warnings only. This does not inspect untracked files.

API cases verify duplicate 202/Location, latest/exact polling, wrong course 404, persisted detail summary/edit lock, global unread count beyond page one, foreign read 404, repeat read count, unavailable history, invalid filter/cursor, native UUID ties across signed UUID boundary, cursor user/filter binding. Not every individual assertion had a separate behavioral red run.

## Remaining C8 work — required before completion/review

1. **Legacy synchronous bypass is still present:** TeacherContentService.submitCourse, CourseSubmitPreparationService, CourseSubmitFinalizationService.finalizeSubmit and whole-graph enrichment snapshot/MAX200 path. Migrate all callers/tests to acceptance + a real test driver executing worker turns before admin operations. Then remove obsolete dependencies/preparation/finalization, preserving transitionToPending currently called by C7 and all validation. Narrow enrichment to enrichBatch after call inventory.
2. Existing H2 suites calling POST still expect synchronous 200/pending state and will fail until migrated. H2 excludes V26; new summary reads also require mock facades or a compatible fast-test schema. Do not interpret this shard as full-suite passing.
3. Add real concurrent notification page/count orchestration proving one snapshot during insert/read/delete, including resource availability. Current isolation is implemented but **no notification concurrency proof was run**.
4. Expand owner exact/latest 404 matrix: real foreign owner, missing course, missing summary, deleted course; blocked fake-AI HTTP 202 case (service case passed); persistent summary list query-count/no-N+1 proof; terminal/restart states and all write-response summaries as appropriate.
5. Expand notification cases: available course/revision/summary, deletion of each and ownership changes, full unreadOnly page traversal, stable readAt equality, missing UUID read, unauthenticated APIs. Current historical fixture has no resource.
6. Update former disabled-registration expectations (SubmissionWorkerIT) for activation or explicitly isolate that configuration test. Verify enabled real dispatcher/finalizer regression shard again; final shard verified enabled context startup with worker disabled.
7. Audit scheduler isolation in AudioPostgresTestSupport and H2 fixtures; native jobs support already disables workers. Fix inaccurate old activation comments if needed. Run remaining tests in bounded shards, including jobs finalization/worker, admin/teacher, monetization/student and audio; no unbounded full-suite command.

Recommended next step: finish the real submission test driver and legacy caller migration before broad H2/API regression runs. Keep C8 partial until the above are closed.

## Continuation checkpoint — 2026-10-19, 23:32 local

**PARTIAL. Production synchronous bypass removed; broad HTTP fixture migration remains unfinished.** Initial inspection after the reported harness error found only the new public-surface test, no implementation to assume complete.

### Exact files changed in this continuation

- Deleted `src/main/java/com/learne/backend/teacher/content/service/CourseSubmitPreparationService.java`.
- Deleted `src/main/java/com/learne/backend/teacher/content/service/CourseSubmitFinalizationService.java`.
- Added `src/main/java/com/learne/backend/teacher/content/service/PendingReviewTransition.java`: narrow static domain transition only, preserving supersession and published-course behavior.
- Updated `src/main/java/com/learne/backend/teacher/content/submission/SubmissionFinalizationService.java` to call that helper.
- Updated `src/main/java/com/learne/backend/teacher/content/service/TeacherContentService.java`: removed submitCourse and obsolete constructor dependencies.
- Replaced `src/main/java/com/learne/backend/teacher/content/enrichment/CourseReviewEnrichmentService.java` with bounded enrichBatch-only service, retaining normalization, exact response-ID validation and difficulty/XP policy. No graph capture/apply, hidden synchronous loop or MAX200 remains.
- Added `src/test/java/com/learne/backend/testsupport/SubmissionTestDriver.java`: acceptance, real claim, real handler turns and real finalization; fake provider transport only. Bounded to 100 turns. Currently a test-classpath component; scope it explicitly by import if disabled-handler context tests expose wiring issues.
- Added `src/test/java/com/learne/backend/teacher/content/submission/SubmissionPublicSurfaceTests.java`.
- Updated `src/test/java/com/learne/backend/teacher/content/submission/SubmissionWriterProtocolIT.java`: removed obsolete sync-final/sync-preflight matrix entries for deleted APIs. Remaining writer cases preserved; this IT was compiled but not executed this turn.
- Updated `src/test/java/com/learne/backend/teacher/content/audio/AudioPostgresTestSupport.java`: driver injection and explicit worker-disabled fixture isolation.
- Updated `src/test/java/com/learne/backend/teacher/content/audio/LessonAudioWriterProtocolIT.java`: all eight direct synchronous submit calls use real acceptance/worker driver; paused-enrichment test now proves accepted logical lock blocks upload; duplicate HTTP submit expects 202. Admin fixtures reach real pending review before decisions.
- Updated `src/test/java/com/learne/backend/teacher/content/audio/CourseWriteProtocolTests.java`: constructor cleanup and removed two obsolete snapshot identity cases (durable finalizer identity/fencing cases remain in C7 IT).
- Replaced `src/test/java/com/learne/backend/teacher/content/enrichment/CourseReviewEnrichmentServiceTests.java` with batch validation/normalization/contract/boundary cases. Projection order/options remain covered by native projection/worker suites, not the removed graph tests.
- Updated `src/test/java/com/learne/backend/notification/NotificationApiIT.java`: three real concurrent mutation cases pause at cursor encoding after page query and before count, commit insert/read/delete independently, assert page count remains 3 while subsequent request sees 4/2/2. Assert actual PostgreSQL `repeatable read` and read-only transaction. Existing UUID tie/current-user/unreadOnly binding cases pass.
- Updated this checkpoint.

### Commands and exact attributable results

Every Maven command used `$env:JAVA_HOME='F:\jdk_21'`, Java 21.0.7, command timeout 180 seconds. Native tests use disposable PostgreSQL 17.11, fake provider, no external application services. No Git mutations/frontend/R/subagents.

1. `./mvnw.cmd -q '-Dtest=SubmissionPublicSurfaceTests' test`: behavioral RED, 1 case / 1 failure / 0 errors, suite 0.283s; old public submit method found.
2. `./mvnw.cmd -q '-Dtest=SubmissionPublicSurfaceTests,CourseReviewEnrichmentServiceTests' test`: exit 0, 4 passed / 0 failures/errors/skips after removal. New batch tests were rewritten during migration; not every assertion had an independent red run.
3. `./mvnw.cmd -q -Pjobs-it '-Dtest=SubmissionPublicSurfaceTests,CourseReviewEnrichmentServiceTests' '-Dit.test=NotificationApiIT,SubmissionFinalizationIT' verify`: 4 units passed; 50 IT cases, 49 passed / **1 failed** / 0 errors/skips. NotificationApiIT 5/5 passed, 36.90s. SubmissionFinalizationIT 44/45 passed, 28.75s. Failure: `finalizerLockTimeoutRollsBackAndDispatcherSafelyRetriesOrRecovers(false)` expected RetryableJobException but PostgreSQL socket read timed out during course lock, rollback produced JpaSystemException. This remains unresolved; do not report C7 regression green. No production retry classification was widened to hide unknown rollback outcome.
4. `./mvnw.cmd -q -Paudio-it '-Dtest=SubmissionPublicSurfaceTests,CourseReviewEnrichmentServiceTests,CourseWriteProtocolTests' '-Dit.test=LessonAudioWriterProtocolIT' verify`: 17 units passed; native audio 33 cases, 32 passed / 1 failed / 0 errors/skips, 45.76s. Failure was old duplicate POST expectation 409 vs correct 202; corrected test.
5. `./mvnw.cmd -q -Paudio-it '-Dtest=SubmissionPublicSurfaceTests,CourseReviewEnrichmentServiceTests,CourseWriteProtocolTests' '-Dit.test=LessonAudioWriterProtocolIT#httpMutationsCannotBypassGuardAndOptionalCodeKeepsLegacyEnvelope' verify`: exit 0; 18 passed / 0 failures/errors/skips. Suite timings: public surface 1/0.042s, enrichment 3/0.177s, CourseWriteProtocolTests **13/20.46s (H2)**, corrected native audio case 1/29.01s. Other 32 native audio cases passed before the assertion-only change. Full 33-case audio suite not rerun afterward.
6. Source grep for `submitCourse(`, `buildSnapshot(`, `enrichSnapshot(`, `finalizeSubmit(` and `MAX_QUESTIONS_PER_COURSE`: only HTTP controller submitCourse method remains; no old service calls. All test sources compile.
7. `git diff --check`: exit 0, existing line-ending warnings. Attempted `rg -c` inventory could not run because rg is unavailable; do not infer numeric HTTP-call counts.

99 distinct cases have passing evidence across these continuation shards (17 units, 5 notifications, 44 finalization, 33 audio), plus **one unresolved finalization case**. This is not a full-suite result. Suite timings above include context startup and should not be interpreted as individual method durations.

### Unfinished migration / proof

- **No unfinished direct legacy Java submit calls remain.** However old HTTP POST expectations/fixtures still remain in TeacherContentApiTests, AdminContentReviewApiTests, monetization API suites (Checkout, VipSelection, SepayWebhook, SepaySimulation and other submit fixtures) and student learning suites. They still need explicit acceptance 202 followed by real test-driver completion before admin behavior. Do not substitute mocked success or blanket response interception.
- H2 V24/V25/V26 compatibility migrations were already present (earlier checkpoint statement that V26 was absent was inaccurate). This continuation verified H2 CourseWriteProtocolTests startup/migration and 13 cases, but did **not** adapt/run the broad H2 HTTP submit fixtures. PostgreSQL-specific acceptance/worker SQL requires choosing native fixtures for those workflows or a carefully scoped compatible test adapter; no H2 concurrency claims.
- Notification page/count snapshot proof is now covered for real concurrent notification insert/read/delete; resource-deletion/ownership snapshot scenarios, exact DTO field-set serialization, available/missing course/revision/submission matrix and no-retarget authorization still need expanded assertions.
- Course summary list is still one batch query by inspection; explicit query-count/no-N+1 native assertion remains open.
- Resolve the finalizer socket/statement/transaction timeout boundary failure with a focused bounded test run and diagnosis; do not weaken unknown rollback handling.
- Rerun worker registration expectations, remaining writer matrix and bounded teacher/admin/student/monetization shards after HTTP migration. Keep C8 PARTIAL and do not advance to frontend C9 until closed.

## Continuation checkpoint — 2026-10-19, 23:45 local

**PARTIAL.** Stopped at bounded checkpoint; no frontend/R/Git mutations/external application calls. Java 21.0.7 from F:\jdk_21, command tool timeouts <=180s.

### Files changed this turn

- `src/test/java/com/learne/backend/jobs/JobsPostgresTestSupport.java`
- Added `src/test/java/com/learne/backend/testsupport/SubmissionHttpPostgresSupport.java`
- `src/test/java/com/learne/backend/monetization/CheckoutApiTests.java`
- `src/test/java/com/learne/backend/monetization/VipSelectionApiTests.java`
- `src/test/java/com/learne/backend/monetization/SepayWebhookApiTests.java`
- `src/test/java/com/learne/backend/monetization/SepaySimulationApiTests.java`
- `src/test/java/com/learne/backend/student/learning/StudentLearningApiTests.java`
- `src/test/java/com/learne/backend/student/learning/StudentLearningPathApiTests.java`
- `src/test/java/com/learne/backend/student/learning/StudentCatalogQueryOptimizationTests.java`
- This checkpoint.

HTTP support uses disposable native PostgreSQL shared within the test JVM, production migrations, workers explicitly disabled, HTTP 202/QUEUED assertion and real SubmissionTestDriver completion before the existing admin HTTP call. No success-route mock. Nested checkout/simulation contexts with OVERRIDE also extend native support explicitly. Fixed test JDBC Instant arguments to Timestamp for PostgreSQL. Existing unrelated ReviewDecisionService mocks were retained; finalization and worker are real. Student helper changes compile but were not executed this turn.

### Systematic timeout diagnosis and resolution

Inspection found `application.properties` socketTimeout=5 and original JobsPostgresTestSupport statement_timeout=5s / lock_timeout=5s, combined with finalizer @Transactional(timeout=5). All deadlines raced. Earlier SocketTimeoutException closed the connection, then JPA rollback failed, producing JpaSystemException. This was not evidence that all JPA failures are retryable.

First hypothesis experiment set server lock_timeout=2s. It eliminated socket/rollback failure but exposed a separate path: Spring translated PostgreSQL 55P03 to UncategorizedSQLException, which the handler intentionally did not classify as transient. C7 originally tests statement cancellation, so final fixture uses statement_timeout=2s / lock_timeout=5s. Server statement cancellation now precedes both the socket and transaction deadline, rolls back cleanly and exercises existing QueryTimeoutException -> sanitized BUSY behavior. **No production exception classification or retry code changed.** Unknown rollback remains unclassified. Native 55P03 classification is outside this fix; do not claim that separate path is handled.

### Commands and results

1. `./mvnw.cmd -q -Pjobs-it '-Dtest=CheckoutApiTests,VipSelectionApiTests,SepayWebhookApiTests,SepaySimulationApiTests' '-Dit.test=SubmissionFinalizationIT' verify`
   - Initial native HTTP migration: Surefire **40 cases, 27 passed / 13 errors / 0 assertion failures/skips**. Failsafe did not run. Causes: test Instant JDBC binding and two nested OVERRIDE contexts still on H2 (enqueue failure). Corrected fixtures as described above.
2. Repeated exact command after JDBC/nested fixes:
   - Surefire completed successfully for the same four classes/40 selected cases; no unit failures/errors. Failsafe then ran 45 finalizer cases: **43 passed / 2 assertion failures / 0 errors/skips**, suite 44.49s. Both failures were expected RetryableJobException vs actual UncategorizedSQLException/55P03 from the lock-timeout hypothesis experiment.
   - Surefire reporting caveat: JUnit nested parent txt/XML reports overwrite Checkout/Simulation counters with zero, so the final parent reports alone do not substantiate aggregate 40. Initial command printed 40 total and identical selectors were rerun successfully. Direct retained reports: SepayWebhookApiTests 8/8, 9.935s; VipSelectionApiTests 6/6, 8.608s; Checkout parent elapsed 53.11s and Simulation parent 12.80s. Do not sum zero parent counters as the executed count. Full command output is at `C:\Users\Admin\.local\share\opencode\tool-output\tool_155093ce2001hKv5W13iuqqA0j`.
3. After statement-timeout ordering fix: `./mvnw.cmd -q -Pjobs-it '-Dtest=SubmissionPublicSurfaceTests' '-Dit.test=SubmissionFinalizationIT' verify`
   - **exit 0**, 1 public-surface unit + **45/45 finalizer IT**, zero failures/errors/skips. Finalizer suite **42.01s** including native context startup. Retained authoritative artifact: `target/failsafe-reports/com.learne.backend.teacher.content.submission.SubmissionFinalizationIT.txt`.
   - Both original real dispatcher lock-wait retry/control-only recovery cases pass again, along with entire pending transition/fencing suite.

### Remaining exact scope

- TeacherContentApiTests and AdminContentReviewApiTests: their many old POST submit expectations still require migration. Neither class was changed/run this turn.
- StudentLearningApiTests, StudentLearningPathApiTests, StudentCatalogQueryOptimizationTests: migrated helper + PostgreSQL base, compiled, **not run yet**. Inspect any test JDBC Instant or H2-only SQL if native run fails.
- Resource authorization/DTO exact-field and course-list batch/no-N+1 proof tests **not added this turn**; all previous open matrix items remain.
- Required full suite still not run. Continue bounded shards (student three classes, teacher/admin after migration, jobs/worker/writer/notification/audio, remaining H2 units). Four monetization classes now exercise real worker completion before admin; repeat with non-quiet output or reliable nested reporting when final full-suite accounting is assembled.
- No old callable synchronous production path has been reintroduced. No C9/C10 work started; C8 remains partial.

## Continuation checkpoint — 2026-10-19, 23:58 local

**PARTIAL overall: requested HTTP migration and native resource/DTO/batch-query proofs now verified; full-suite shards and earlier residual coverage remain.** No migrated class from this or the preceding turn is left unrun among the five explicitly requested teacher/admin/student classes.

### Exact files changed this turn

- `src/test/java/com/learne/backend/teacher/content/TeacherContentApiTests.java`: extends native HTTP support; all successful submit calls use 202 + real worker driver before admin/next mutations; minimum-content validation still tests POST 400. Replaced H2-only referential-integrity fixture commands in two ID-collision tests with transaction-local PostgreSQL session_replication_role, confined to disposable test DB and reset automatically on transaction completion. No production integrity configuration changed.
- `src/test/java/com/learne/backend/admin/content/AdminContentReviewApiTests.java`: extends native support; every submit call (including existing disabled historical-import cases) migrated to acceptance/worker helper. Existing disabled annotations unchanged.
- `src/test/java/com/learne/backend/notification/NotificationApiIT.java`: four resource matrix cases for deleted course/revision/submission and ownership change; initially available action points to original course; then false/null while historical IDs/message remain. Real HTTP serialization checks exact page and item property sets. Owner read remains possible; another user's list is empty.
- `src/test/java/com/learne/backend/teacher/content/submission/SubmissionApiIT.java`: native four-course HTTP list proves one executed summary SQL query with four IN parameters and DISTINCT ON, not one summary query per course; original detail summary/hasEditableRevision assertions preserved. Exact/latest polling both become 404 after course ownership changes. JdbcTemplate spy counts only the PreparedStatementSetter execution overload, excluding its public convenience-method delegation.
- This checkpoint.

### Exact commands/results from non-quiet console

All use Java 21.0.7 with `$env:JAVA_HOME='F:\jdk_21'`, disposable PostgreSQL 17.11, fake providers, workers disabled except explicit driver turns; each command timeout 180s.

1. `./mvnw.cmd '-Dtest=StudentLearningApiTests,StudentLearningPathApiTests,StudentCatalogQueryOptimizationTests' test` — **BUILD SUCCESS**, total **1:27**. **55 passed, 0 failures/errors/skips**:
   - StudentCatalogQueryOptimizationTests 2 / 28.65s
   - StudentLearningApiTests 46 / 41.86s
   - StudentLearningPathApiTests 7 / 12.03s
   Console artifact: `C:\Users\Admin\.local\share\opencode\tool-output\tool_1551157ac001X8sNgiUOKPQybD`.
2. `./mvnw.cmd -Pjobs-it '-Dtest=TeacherContentApiTests,AdminContentReviewApiTests' '-Dit.test=NotificationApiIT,SubmissionApiIT' verify` — first run failed in Surefire, total **1:14**: **53 passed, 2 errors, 14 existing skips**, no assertion failures. Both errors were native-incompatible H2 SET REFERENTIAL_INTEGRITY in teacher ID-collision fixtures. Failsafe not reached. Corrected fixture transaction handling.
3. Repeated exact command — total **1:57**:
   - AdminContentReviewApiTests: 19 reported, **13 passed / 6 existing skips**, 0 failures/errors, 38.57s.
   - TeacherContentApiTests: 50 reported, **42 passed / 8 existing skips**, 0 failures/errors, 30.71s.
   - NotificationApiIT: **9 passed**, 0 failures/errors/skips, 28.77s.
   - SubmissionApiIT: 1 assertion failure, 10.08s. Spy counted JdbcTemplate convenience call and delegated execution as two; captured SQL showed one four-course batch. Corrected counter to execution overload. No production query change.
   Console artifact: `C:\Users\Admin\.local\share\opencode\tool-output\tool_155167467001WxRXKioSnaHEdn` (contains exact counts; no nested parent-zero ambiguity).
4. `./mvnw.cmd -Pjobs-it '-Dtest=SubmissionPublicSurfaceTests' '-Dit.test=SubmissionApiIT' verify` — **BUILD SUCCESS**, total **31.989s**; **2 passed / 0 failures/errors/skips**. Public-surface test 1 / 0.135s; native SubmissionApiIT 1 / 25.18s. Batch summary query assertion and both ownership-change 404 assertions pass.

**121 distinct passing cases this turn, 14 existing skips, no unresolved failures in the classes executed above.** The failures in steps 2/3 were corrected and their affected tests rerun. These new tests characterize existing production behavior; no claim of per-assertion behavioral TDD red for the resource matrix.

Source scan for teacher POST submit now finds only: shared real HTTP driver, empty-course validation, explicit duplicate-202 audio test, and SubmissionApiIT duplicate-202 test. No remaining old synchronous-success HTTP fixture calls found. Existing production synchronous API removal is unchanged.

### Remaining named issues / next checkpoint

- **TeacherContentApiTests:** no active failing/unrun case; eight existing disabled import scenarios remain skipped.
- **AdminContentReviewApiTests:** no active failing/unrun case; six existing disabled import scenarios remain skipped.
- **StudentLearningApiTests / StudentLearningPathApiTests / StudentCatalogQueryOptimizationTests:** all executed, green, no remaining migration issue known.
- **NotificationApiIT / SubmissionApiIT:** new resource authorization, exact notification DTO fields, and native batch-summary query-count checks pass. Earlier remaining boundary matrix (unauthenticated APIs, exact submission DTO field set, missing-summary/missing-course variants, HTTP blocked-AI case, stronger concurrent resource-availability mutation test) has not all been expanded. Do not equate notification-row insert/read/delete snapshot proof with concurrent resource-mutation proof.
- **SubmissionWorkerIT / SubmissionWriterProtocolIT / JobConfigurationTests:** still require the post-activation/removal regression shard, particularly old disabled-registration expectation in worker IT and test-driver component dependency in explicitly disabled handler contexts.
- **LessonAudioWriterProtocolIT:** preceding checkpoint had 32 passing then corrected-case passing separately; full suite after final assertion change still needs fresh aggregate run. Other audio suites and remaining jobs/mail/H2 units need bounded full-suite accounting.
- **CheckoutApiTests / SepaySimulationApiTests:** preceding run completed successfully but nested report accounting limitation remains; capture non-quiet console in final full-suite verification. VipSelectionApiTests / SepayWebhookApiTests previously green.
- Required full-suite verification remains incomplete. C8 stays PARTIAL until remaining bounded shards and explicit requirement inventory are closed. No frontend C9/R work started.

## C8 focused closure — 2026-10-20, 00:09 local

**C8 DONE; ready for review.** User explicitly authorized deferring full-backend accounting to C10 phase exit in bounded shards. No known C8 implementation or affected-class blocker remains. Prior partial statuses and failure reports above are historical evidence, superseded by closure results here.

### Exact files changed this turn

- `src/test/java/com/learne/backend/teacher/content/submission/SubmissionApiIT.java`
- `src/test/java/com/learne/backend/notification/NotificationApiIT.java`
- `src/test/java/com/learne/backend/teacher/content/submission/SubmissionWorkerIT.java`
- `src/test/java/com/learne/backend/testsupport/SubmissionTestDriver.java`
- `src/test/java/com/learne/backend/testsupport/SubmissionHttpPostgresSupport.java`
- `src/test/java/com/learne/backend/teacher/content/audio/AudioPostgresTestSupport.java`
- This checkpoint.

The driver is no longer a globally scanned component. Only HTTP/audio fixture bases import it explicitly. This removes its dependency on an enabled submission handler from unrelated/disabled contexts. Worker registration test now asserts C8 production activation rather than the obsolete C7-disabled seam.

### Closed explicit boundary inventory

- All three submission routes reject anonymous requests with 401; all three notification routes reject anonymous requests with 401.
- Submission 202 JSON has exactly the approved ten fields, and Location remains exact course/submission UUID route. Existing exact/latest owner polling, foreign ownership 404, course without summary 404, missing course 404, existing-course unknown exact UUID 404, exact submission belonging to another course 404, and deleted-course exact polling 404 are exercised.
- A real claimed worker is blocked inside fake AI with latches. A parallel duplicate HTTP POST completes within two seconds with 202 and the same submission UUID while the worker is still blocked. Provider release is in finally; futures are bounded. No real AI transport.
- For each course deletion/revision deletion/submission deletion/ownership change, notification list pauses after the joined page query and before global count; independent connection commits the resource change. In-flight response retains original availability/action and count from its snapshot; subsequent request sees false/null. Historical identity is retained and owner read still works. Separate concurrent notification insert/read/delete tests remain passing.
- Exact notification page and item property sets remain asserted over real HTTP serialization.
- Signed cursor user/unreadOnly binding, UUID tie order across 0x80/0xff boundary, unreadOnly traversal after marking the newest read, global count independent of page, terminal cursor null/hasNext false, invalid size/filter/cursor, missing read UUID, and stable idempotent readAt are exercised.
- Native four-course list proof executes one batch summary SQL query with four course parameters, preserving independent hasEditableRevision and editingLocked. This passed in SubmissionApiIT again with the new HTTP/blocking matrix.
- No synchronous wholegraph/MAX200 API reintroduced; real worker-driver migrations remain intact. All five teacher/admin/student migrated classes were run green in the preceding checkpoint; this turn additionally reruns representative HTTP imports and both nested contexts after explicit driver-import change.

### Non-quiet commands and exact results

All commands ran from backend with `$env:JAVA_HOME='F:\jdk_21'`, Java 21.0.7, disposable PostgreSQL 17.11, fake AI/SMTP/storage as existing fixtures specify. Each tool timeout 180s. All three commands returned BUILD SUCCESS with **zero failures/errors/skips**.

1. `./mvnw.cmd -Pjobs-it '-Dtest=JobConfigurationTests,SubmissionPublicSurfaceTests' '-Dit.test=SubmissionWorkerIT,SubmissionWriterProtocolIT,SubmissionApiIT,NotificationApiIT' verify`
   - Total **1:29**; **97 passed** (4 units + 93 IT).
   - JobConfigurationTests 3 / 4.032s
   - SubmissionPublicSurfaceTests 1 / 0.029s
   - NotificationApiIT 10 / 29.06s
   - SubmissionApiIT 2 / 10.05s
   - SubmissionWorkerIT 12 / 19.09s
   - SubmissionWriterProtocolIT 69 / 19.06s
   - Console `C:\Users\Admin\.local\share\opencode\tool-output\tool_1551e04ea001PsCu0dMX1QQyRa`.
2. `./mvnw.cmd -Paudio-it '-Dtest=JobConfigurationTests,CourseWriteProtocolTests,CourseReviewEnrichmentServiceTests,CourseResponseTests' '-Dit.test=LessonAudioWriterProtocolIT,LessonAudioImportConcurrencyIT' verify`
   - Total **1:26**; **63 passed** (21 units + 42 native IT).
   - JobConfigurationTests 3 / 4.403s
   - CourseWriteProtocolTests 13 / 18.59s (H2 compatibility/unit protocol evidence only)
   - CourseResponseTests 2 / 0.010s
   - CourseReviewEnrichmentServiceTests 3 / 0.030s
   - LessonAudioImportConcurrencyIT 9 / 31.37s
   - LessonAudioWriterProtocolIT **33 / 23.16s**, fresh full aggregate after all submit migrations.
   - Console `C:\Users\Admin\.local\share\opencode\tool-output\tool_1551fc086001i4O3UeYKxv0lFZ`.
3. `./mvnw.cmd -Pjobs-it '-Dtest=CheckoutApiTests,SepaySimulationApiTests,StudentCatalogQueryOptimizationTests' '-Dit.test=NotificationApiIT' verify`
   - Total **1:37**; **38 passed** (28 HTTP tests + 10 native notification cases).
   - Console explicitly reports nested attribution: CheckoutApiTests$SepayEnabledCheckoutTests **20 total**, enclosing parent 0; SepaySimulationApiTests$DisabledSimulationTests **6 total**, enclosing parent 0; StudentCatalogQueryOptimizationTests 2 / 4.876s; Surefire aggregate **28**. This resolves prior count uncertainty; do not add zero enclosing parent counts again.
   - NotificationApiIT 10 / 25.60s, including final unreadOnly traversal/invalid-size/missing-ID/stable-readAt additions.
   - Checkout enclosing elapsed 48.10s, Simulation enclosing 11.16s; nested attributed durations 4.168s/3.564s are not the whole class runtime.
   - Console `C:\Users\Admin\.local\share\opencode\tool-output\tool_15521e25c001ZmcnAbDUdPuRam`.

**198 passing executions, 185 distinct cases this turn**, subtracting three repeated configuration tests and ten repeated notification cases. No failed or skipped execution this turn. Native concurrency claims rely on PostgreSQL; H2 has no concurrency claims. Existing earlier teacher/admin 14 disabled historical-import tests remain unchanged, recorded separately.

### Explicitly deferred / tracked

- **C10 phase exit:** full-backend test inventory/accounting and remaining baseline shards, including remaining jobs/mail/audio/H2 suites, using bounded commands. This is user-approved phase-exit work, not an outstanding C8 blocker. No assertion that the entire backend suite has passed.
- **55P03 tracking:** earlier native experiment exposed Spring UncategorizedSQLException for PostgreSQL lock_timeout (55P03). It remains unclassified by production worker boundaries. C7's intended statement-timeout retry path is verified after fixture deadline ordering correction; unknown JPA rollback outcomes remain unclassified. No broad JpaException retry and no production change for 55P03 was made. Preserve this item for review/C10 assessment; do not silently treat it as fixed.
- No unrun migrated teacher/admin/student class or known active failure remains in the C8 affected scope. Review is still a separate step; this checkpoint does not claim external review approval.
- No frontend/C9, R, Git mutations, subagents, environment-secret reads, or real external application calls occurred.

## Sole review finding remediation — 2026-10-20 00:21 local

User rejected C8 spec approval for confirmed PostgreSQL lock timeout 55P03 escaping bounded transient retry. The prior decision to defer it is superseded. **Fix verified; C8 spec remains NOT APPROVED pending re-review.**

Files changed in this remediation only:

- `src/main/java/com/learne/backend/teacher/content/submission/CourseSubmissionHandler.java`: shared `databaseTurn` catches direct Spring `UncategorizedSQLException`, extracts only `getSQLException().getSQLState()`, and maps exactly `55P03` to sanitized `RetryableJobException("BUSY", null)`. Catch remains outside transaction proxy after rollback; dispatcher retains finite attempts/backoff. No cause-chain search for JPA/unknown rollback errors, no local retry loop, no broader SQLSTATE classification.
- `src/test/java/com/learne/backend/teacher/content/submission/CourseSubmissionHandlerTests.java`: direct 55P03 positive load/failure-callback coverage; negative uncategorized SQL states 23505, 23503, 42601, 42P01, XX000, 57014; original exception identity retained for unknown rollback/JpaSystemException/TransactionSystemException and integrity/grammar wrappers even with nested 55P03.
- `src/test/java/com/learne/backend/teacher/content/submission/SubmissionFinalizationIT.java`: two real dispatcher regressions, checkpoint and finalization. Spies call real transactional methods, set transaction-local server lock_timeout=250ms, and capture real JdbcTemplate translation without replacing SQL. Independent native connection holds course row; statement_timeout stays 2s and existing socketTimeout stays 5s. Fake provider transport is supplied only for checkpoint variant. Both capture actual UncategorizedSQLException SQLSTATE 55P03, observe no active transaction at handler exception boundary, assert RETRY_WAIT, attempts=1, no permanent callback, no partial graph/notification/checkpoint writes, release contention, then dispatch through checkpoint/finalization to SUCCEEDED/PENDING_REVIEW. Existing finite-attempt exhaustion/failure-unlock regression remains passing in the complete 47-case finalization class.
- This checkpoint.

### Exact red/green evidence

All commands ran in backend using `$env:JAVA_HOME='F:\jdk_21'`, Java 21.0.7; every command timeout 180000ms. Native database PostgreSQL 17.11. No Git, frontend/R, subagents, or real external application calls.

1. RED unit: `./mvnw.cmd '-Dtest=CourseSubmissionHandlerTests' test`
   - 5 tests, **1 failure**, 0 errors/skips; 5.829s total, class 1.909s. New direct-55P03 test expected RetryableJobException but got UncategorizedSQLException SQLSTATE 55P03. Negative states/wrappers passed.
2. Initial native fixture diagnostic: `./mvnw.cmd -Pjobs-it '-Dtest=CourseSubmissionHandlerTests#unrelatedSqlStatesAndUnknownRollbackWrappersRemainUnchanged' '-Dit.test=SubmissionFinalizationIT#postgres55P03RetriesAfterRollbackThenCompletes' verify`
   - 1 unit passed; 2 IT failures, 0 errors/skips; 46.220s total. Finalization reproduced real 55P03; checkpoint variant stopped before staging because fake provider response was missing. Corrected fixture before any production edit. Artifact `C:\Users\Admin\.local\share\opencode\tool-output\tool_1552a862e001q6srcsYPzBYP9U`.
3. Correct RED native: same command with `-q` added.
   - 1 negative unit passed; **both native variants failed exactly expecting RetryableJobException but observing real UncategorizedSQLException 55P03 from locked course SELECT FOR UPDATE**, 0 errors/skips. IT class 27.99s. Artifact `C:\Users\Admin\.local\share\opencode\tool-output\tool_1552c4d3c0014YQbCIN18Dw6f9`. Quiet command does not print Maven total duration; no inferred total reported.
4. GREEN after narrow production catch: `./mvnw.cmd -Pjobs-it '-Dtest=CourseSubmissionHandlerTests,JobConfigurationTests' '-Dit.test=SubmissionFinalizationIT,SubmissionWorkerIT' verify`
   - **BUILD SUCCESS, 67 passed, 0 failures/errors/skips; total 1:14.**
   - CourseSubmissionHandlerTests 5 / 0.147s; JobConfigurationTests 3 / 4.299s.
   - SubmissionFinalizationIT **47 / 43.47s**, including both native 55P03 cases, existing statement-timeout retry/control recovery, finite-attempt exhaustion and atomic finalization cases.
   - SubmissionWorkerIT **12 / 16.75s**.
   - Artifact `C:\Users\Admin\.local\share\opencode\tool-output\tool_1552df765001LxWAMab7RVUG4l`.

No named remediation blocker remains. Review approval is not asserted. Full-backend accounting remains the explicitly deferred C10 phase-exit task.
