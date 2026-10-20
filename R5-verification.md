# R5 final regression checkpoint — 2026-10-20

Fresh backend, AI and frontend verification completed with no test failures or errors. Java: **2,113 distinct passed / 15 skipped** (2,128 reported). Backend Python: **31 passed**. AI: **221 passed**. Frontend: **322 passed in 46 files**, lint exit 0 with two warnings, production build exit 0.

This is bounded, sharded regression evidence. **134 fast source classes and 42 native IT source classes ran freshly.** The full inventory is 134 fast / 43 IT, compared with C10's 130 / 37. `BackgroundJobsAcceptanceIT` (three tests) remains accepted R4 evidence, deliberately not rerun or added to fresh totals because resource remeasurement was excluded by the R5 instruction. No other named shard remains pending.

Only this workspace report was added by R5; no application/test source correction was needed. Existing R1–R4 and unrelated user changes were preserved. No Git mutations, root reports, README edits, docs/config/agent edits, Compose checks, subagents or deployment actions. Compose belongs to the separate requested workstream. No actual env-file/secret inspection or real AI/SMTP/Cloudinary action was performed. Native fixtures use disposable local PostgreSQL/Redis and fake provider ports; no actual asset deletion.

## Environment, bounds and evidence

Backend working directory: `C:\Users\Admin\Desktop\learne-platform\backend`. Java 21.0.7 at `F:\jdk_21`; workspace Python `..\.venv\Scripts\python.exe`. Maven ran offline throughout. Existing compiled classes were checked through the package lifecycle and again in J6's verify lifecycle; Maven reported them up to date. Packaging and Spring Boot repackaging succeeded.

Each Java shard was bounded to 170 seconds by a temporary Python subprocess runner, with Surefire/Failsafe fork limits of 150 seconds and `-Xmx896m`. At most three shards ran concurrently, without subagents. The longest finished in 156.396 seconds. No shard timed out. The initial runner-launch tool call reported a 10-second timeout after printing PID 13196 because its child inherited the tool handles; this did not terminate the bounded runner or any shard. All 19 shard result files subsequently recorded exit 0. Final process inspection found only the two pre-existing VS Code language-server JVMs from October 19; no Maven/test JVM remained and no process was killed.

Temporary evidence directory: `C:\Users\Admin\AppData\Local\Temp\opencode`. Runner: `r5_run.py`; full console logs `r5_<shard>.log`; exact expanded command, PID, timestamps, exit and elapsed seconds in `r5_<shard>.json`; discovery/selection data in `r5_inventory.json`. These are outside the repository. Console aggregate summaries establish each shard's counts. Parallel Failsafe invocations share `target/failsafe-reports/failsafe-summary.xml`, so that mutable summary alone is not used as inventory evidence; every shard's own console aggregate has zero failures/errors, with distinct per-class reports corroborating execution.

## Exact Java commands and class inventory

PowerShell environment for all backend commands:

```powershell
$env:JAVA_HOME='F:\jdk_21'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
```

Initial compilation/package/repackage check, exit 0, Maven **2.574s**:

```powershell
.\mvnw.cmd -o '-DskipTests' package
```

This first command intentionally executes no tests; test evidence comes from the following shards. The runner invokes the absolute wrapper path with the arguments shown below. Define `COMMON` as these exact arguments for command reconstruction:

```text
-o -DargLine=-Xmx896m -Dsurefire.forkedProcessTimeoutInSeconds=150 -Dfailsafe.forkedProcessTimeoutInSeconds=150
```

Fast command for each table row: `.\mvnw.cmd COMMON -Dtest=<exact selector> surefire:test`. All rows exited 0 and printed BUILD SUCCESS. Count includes skipped tests; elapsed is runner wall seconds, including wrapper startup.

| Shard | Exact selector | Reported / skipped | Seconds |
| --- | --- | ---: | ---: |
| F1 | JobConfigurationTests,CourseSubmissionHandlerTests,SubmissionPublicSurfaceTests,SubmissionFingerprintTests,EnrichmentContractTests,EnrichmentCapabilitiesTests,HttpCourseKnowledgePointClientTests,CourseReviewEnrichmentServiceTests,QuestionDifficultyCalculatorTests,CourseResponseTests | 81 / 0 | 9.341 |
| F2 | JobRuntimeTests,JobMetricsTests,JobPayloadCipherTests,JobPropertiesTests,JobStoreTests | 45 / 0 | 13.870 |
| F3 | TeacherContentApiTests,AdminContentReviewApiTests,CourseWriteProtocolTests,CourseImportTransactionBoundaryTests,LessonAudioCleanupServiceTests,LessonAudioFileServiceTests,LessonAudioMultipartHttpTests,LessonAudioPersistenceTests,LessonAudioPostCommitServiceTests,LessonAudioServiceTests,LessonAudioTargetResolverTests,RevisionAudioRebuildGuardTests,TeacherLessonAudioApiTests | 345 / 14 | 156.396 |
| F4 | CourseImportExportServiceTests,CourseImportSecurityTests,CourseRevisionMigrationTests,CourseExcelTemplateExporterTests,CourseExcelTemplateParserTests,CourseExportPackageBuilderTests,CourseImportArchiveReaderTests | 106 / 0 | 33.593 |
| F5 | TrustedOriginFilterTests,RedisLoginAttemptStoreTests,PasswordResetServiceTests,PasswordResetMailServiceTests,LoginAttemptServiceTests,AuthSessionServiceTests,StudentRegistrationApiTests,TeacherInvitationMailServiceTests,AdminUserApiTests,AdminDashboardApiTests,RegistrationRateLimitFilterTests,AuthSessionApiTests,PasswordResetApiTests,PasswordResetRateLimitFilterTests,LoginRateLimitFilterTests,StudentRegistrationServiceTests,RegistrationMailServiceTests,AuthTokenCleanupJobTests,ProductionSafetyValidatorTests,CorsConfigTests,AdminSeederTests,RedisTokenBucketStoreTests,RateLimitServiceTests,RateLimitPropertiesTests,RateLimitPolicyTests,RateLimitKeyFactoryTests,RateLimitHttpContractTests,LimiterRedisConnectionTests,IpLiteralTests,ClientIpResolverTests,CapacityProcessJoinTests,CapacityMeasurementsTests,CapacityLifecycleTests | 466 / 0 | 89.820 |
| F6 | CacheKeySupportTests,CacheInvalidationServiceTests,CacheConfigTests,SmtpMailTransportTests,SmtpLoggingGuardTests,SmtpCallerDeadlineTests,PaymentConfirmationMailServiceTests,MailTemplateRendererTests,MailPayloadTemplateTests,MailJobProducerTests,MailJobConfigurationTests,MailEligibilityTests | 77 / 0 | 30.272 |
| F7 | StudentCatalogQueryOptimizationTests,StudentLearningApiTests,StudentLearningPathApiTests,StudentProfileServiceTests,StudentProfileApiTests,CursorQueryCompatibilityTests,StudentCourseRecommendationServiceTests,StudentCourseRecommendationApiTests,LearningPathMappingTests,StudentDashboardApiTests,StudentLearningPathServiceTests,StudentPlacementServiceTests,StudentLearningServiceTests,StudentLearningReadCacheServiceTests,StudentPlacementApiTests,HttpAiLearningPathClientTests,AiServicePropertiesTests,TeacherProfileServiceTests,TeacherProfileApiTests,TeacherDashboardApiTests | 137 / 0 | 99.403 |
| F8 | VipSelectionApiTests,StudentOrderApiTests,SepayWebhookApiTests,SepayWebhookApiKeyModeTests,SepaySimulationApiTests,SepayPaymentServiceTests,SepayMigrationTests,PlanSubscriptionApiTests,OrderTests,MonetizationMigrationTests,CourseEntitlementRepositoryTests,CouponPricingServiceTests,CouponApiTests,CheckoutApiTests,AiUsageGuardTests,AdminRevenueReportApiTests | 124 / 0 | 97.274 |
| F9 | BackendApplicationTests,HealthApiTests,OtpRepositoryLockingTests,DashboardAggregateRepositoryTests,GrammarCheckServiceTests,GrammarCheckExceptionHandlerTests,GrammarCheckApiTests,AiTutorServiceTests,AiTutorCleanupJobTests,AiTutorApiTests,HttpAiTutorClientTests,HttpAiGrammarCheckClientTests,CursorTokenServiceTests,CloudinaryStorageServiceTests | 127 / 0 | 39.846 |
| F10 | JobRetryCommandTests,MediaCleanupConfigurationTests,MediaCleanupTests,MediaDeleteAdapterTests,TestDatabaseCleaner | 14 / 0 | 9.994 |

`TestDatabaseCleaner` was a discovery false positive from `Test*.java`, included in F10's selector but contains no tests and is **excluded** from the 134-class count. The four real new fast classes are JobRetryCommandTests, MediaCleanupConfigurationTests, MediaCleanupTests and MediaDeleteAdapterTests. Remaining fast classes are the C10 inventory, fully enumerated above. Naming discovery inspected all Java files for `*Tests`, `*Test`, `Test*` and `*TestCase`; no additional runnable class was found. `rg` was unavailable, so dedicated glob discovery and temporary Python pathlib discovery were used.

Native command for each row except J6: `.\mvnw.cmd COMMON -P<profile> -Dit.test=<exact selector> failsafe:integration-test failsafe:verify`. These use current compiled classes and the fresh packaged artifact. Native profiles remain in use; no blanket/wildcard IT override. All rows exited 0 and printed BUILD SUCCESS.

| Shard | Profile | Exact selector | Reported / skipped | Seconds |
| --- | --- | --- | ---: | ---: |
| J1 | jobs-it | EnrichmentDeadlineIT,SubmissionMigrationIT,SubmissionFingerprintIT,SubmissionAcceptanceIT,SubmissionDraftCapacityIT | 42 / 0 | 64.039 |
| J2 | jobs-it | SubmissionWriterProtocolIT,SubmissionWorkerIT,SubmissionFinalizationIT,SubmissionApiIT,NotificationApiIT | 141 / 0 | 85.856 |
| J3 | jobs-it | JobStoreIT,JobSchemaSnapshotIT,JobMetricsIT,JobMaintenanceIT,JobLeaseIT,JobEnqueueIT,JobRetentionIT | 46 / 0 | 66.123 |
| J4 | jobs-it | AuthMailTransactionsIT,TeacherInvitationIT,PaymentMailTransactionsIT,ReviewMailTransactionsIT | 66 / 0 | 55.964 |
| J5 | jobs-it | MailProducerIT,MailMigrationIT,MailEligibilityIT,MailDeliveryIT,SmtpDeadlineIT | 64 / 0 | 56.301 |
| J6 | jobs-it | JobRetryCommandIT,MediaCleanupIT,MediaCleanupTransactionsIT | 26 / 0 | 57.219 |
| A1 | audio-it | LessonAudioConcurrencyIT,LessonAudioImportConcurrencyIT,LessonAudioWriterProtocolIT,MediaWriterTransactionsIT | 61 / 0 | 67.934 |
| D1 | redis-it | RedisLoginAttemptStoreIT,RedisTokenBucketIT,LimiterTransportIT,LimiterStartupIT,LimiterRecoveryIT,LimiterCapacityIT | 86 / 1 | 60.081 |
| D2 | redis-it | RegistrationProtectionIT,PasswordResetProtectionIT,LoginProtectionIT | 74 / 0 | 50.688 |

J6 uses the real lifecycle so the packaged-wrapper test receives the current executable jar:

```powershell
.\mvnw.cmd -o '-DargLine=-Xmx896m' '-Dsurefire.forkedProcessTimeoutInSeconds=150' '-Dfailsafe.forkedProcessTimeoutInSeconds=150' -Pjobs-it '-Dtest=JobRetryCommandTests' '-Dit.test=JobRetryCommandIT,MediaCleanupIT,MediaCleanupTransactionsIT' verify
```

J6 log proves compile/test-compile checks, one fast test, jar/repackage, then Failsafe. JobRetryCommandIT: **11 passed / 29.207s**; its real PowerShell wrapper + packaged-jar method: **9.256s**, no skip. It asserts normal jar Start-Class, isolated child environment, exit codes, durable same-row retry and rejected-request preservation. MEDIA provider deletion remains fake. J3 freshly verifies retention and maintenance alongside lease/metrics/store. New native source classes relative to C10: BackgroundJobsAcceptanceIT (R4 carry-forward), JobRetryCommandIT, JobRetentionIT, MediaCleanupIT, MediaCleanupTransactionsIT and MediaWriterTransactionsIT. Audio and jobs overlapping profile coverage is counted once via the explicit selectors.

### Count reconciliation and skips

- Fast aggregate: 81+45+345+106+466+77+137+124+127+14 = **1,522 reported; 1,508 passed / 14 skipped**. F5 owns RateLimitHttpContractTests; unlike C10 it is not repeated in F6.
- Native aggregate: 42+141+46+66+64+26+61+86+74 = **606 reported; 605 passed / 1 skipped**.
- J6's repeated one-test JobRetryCommandTests already belongs to F10 and is excluded from the distinct total. J6 therefore executed 27 tests but its native table cell reports 26.
- Nested JUnit classes execute through parents. Console aggregate totals are used once per shard; parent and nested XML totals are not summed together. F8's full aggregate is 124. Historical C10/R3/R4 executions are not added.
- The 14 existing disabled import tests remain TeacherContentApiTests **8** and AdminContentReviewApiTests **6**. D1 retains the existing opt-in LimiterCapacityIT benchmark skip (`redis.capacity.enabled=false`). No skip was introduced or dependency failure converted to a skip.

## AI, backend Python and frontend

AI working directory `C:\Users\Admin\Desktop\learne-platform\ai-service`, command:

```powershell
$env:PYTHONDONTWRITEBYTECODE='1'
..\.venv\Scripts\python.exe C:\Users\Admin\AppData\Local\Temp\opencode\c10_ai_verify.py
```

Exit 0: **221 passed in 7.46s**, no skips. Existing launcher invokes `pytest -q -p no:cacheprovider tests/test_app.py tests/test_enrichment_contracts.py tests/test_enrichment_deadline.py`, the complete current three-file pytest inventory. It patches DNS resolution and socket connect to permit loopback only; external DNS/connections fail. No application change or launcher change was needed.

Backend Python utility command from backend:

```powershell
$env:PYTHONDONTWRITEBYTECODE='1'
..\.venv\Scripts\python.exe -m pytest -q -p no:cacheprovider scripts/test_build_realistic_seed.py scripts/test_generate_listening_audio.py
```

Exit 0: **31 passed in 1.79s**, no skips. Test fixtures/mock ports supply configuration and storage/audio behavior.

Frontend working directory `C:\Users\Admin\Desktop\learne-platform\frontend`, exact successful invocation:

```powershell
npm.cmd test; if ($LASTEXITCODE -eq 0) { npm.cmd run lint; if ($LASTEXITCODE -eq 0) { npm.cmd run build } }; exit $LASTEXITCODE
```

All three stages exited 0. Vitest: **46 files / 322 tests passed, 59.97s**. ESLint: zero errors, two `react-hooks/exhaustive-deps` warnings for missing `page` dependency at `src/pages/admin/AdminStudentListPage.tsx:76` and `src/pages/admin/coupons/AdminCouponPage.tsx:489`. Build ran `tsc -b && vite build`, transformed 2,710 modules, Vite build **9.12s**; separate TypeScript/lint durations were not instrumented. Initial `npm` attempt was blocked by PowerShell's npm.ps1 execution policy before tests ran; using installed `npm.cmd` resolved the launcher issue without changing policy or source.

## Remaining limits

No unresolved regression failure or unfinished named fresh shard. The only native class without fresh R5 execution is explicitly retained **BackgroundJobsAcceptanceIT**, three accepted R4 tests; its resource numbers were not remeasured. R4's local Windows/Docker, sampled heap/pool/executor, fake-AI, bounded-stall and component-reconstruction limitations remain exactly as documented in `R4-verification.md`. This checkpoint adds no Linux/VPS throughput, combined backend/AI memory qualification, soak, process-kill or real deployment claim. The opt-in capacity benchmark remains skipped. The two frontend lint warnings remain visible.

Backend and frontend `git diff --check` exited 0 (backend emitted existing LF/CRLF warnings). That check does not cover this untracked report or other untracked files. No source fixes or weakening of tests, selectors' assertions, timeouts or skip conditions was required.

## Final workspace hygiene follow-up

Final status review found five Windows cache files under a literal backend/%SystemDrive% directory. Their creation times matched the first isolated packaged-wrapper run. The fixture cleared child environment variables but omitted SystemDrive; ProgramData and ALLUSERSPROFILE were also absent. JobRetryCommandIT now explicitly allows those OS bootstrap paths alongside SystemRoot, while retaining environment().clear() and isolated datasource values. It asserts the literal directory is absent after each wrapper invocation.

Only the five verified generated cache files and their empty parent directories were removed. The actual packaged wrapper (four child runs) and minimal maintenance context were rerun under Java 21: two tests passed, zero failures/errors/skips; the artifact did not return. These two overlapping cases do not increase the distinct totals above. Independent focused review approved the test-only correction without rerunning it. No production code or application credentials were involved.
