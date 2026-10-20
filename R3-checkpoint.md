# R3 complete

Final offline Java 21 verification passed: 85 tests, zero failures/errors/skips. Retention, maintenance, lease, metrics, retry, context and production registration suites were verified together. Repackaging passed and the packaged artifact was exercised through the actual PowerShell wrapper against isolated PostgreSQL. No R4 work.

Implemented operator retry:

- `jobs/maintenance/JobMaintenanceApplication`: explicit bean source, no component scan or auto-configuration; nonweb, JDBC datasource/transaction and readers only. Uses `job-maintenance` configuration name to avoid the normal application's development datasource defaults.
- `jobs/maintenance/JobRetryCommand`: exact v1 allowlist for three MAIL_NOTICE types and MEDIA_CLEANUP. Validates canonical UUID, required incident reason code and operator identifier. Rejects active jobs, unsupported types/versions, secrets and expiring envelopes. Reuses authoritative mail eligibility inside the caller transaction. Media shares existing provenance/ambiguity functions, locks course first, reads global live/revision references, locks job last and revalidates the envelope. Resets the same row and dedupe key, attempts to zero. Audit log contains job/type/operator/reason only, after commit.
- `scripts/retry-background-job.ps1`: explicit Java and packaged artifact paths; inherited deployment datasource settings; PropertiesLauncher selects maintenance main; propagates exit status. Reason is an uppercase incident code, not free-form prose.
- `pom.xml`: normal packaging main remains explicitly BackendApplication.
- Existing media decision methods were exposed as static pure checks without changing their predicates. Existing mail reader gained a notice-only caller-transaction entry point.

Verification with F:\jdk_21 and offline Maven (all commands <=180s):

- JobRetryCommandIT: 11 passed on isolated PostgreSQL. Covers valid notice/same envelope/lease, changed email, unsupported auth/invitation/course/unknown type/version, active rejection, unknown/malformed IDs, concurrent retry one winner, actual minimal context, media provenance/reference/ambiguity/missing-course negatives, and native course-lock wait with writer committing a new reference before retry continues. Also covers pending review's active-admin/latest-pending rule and historical decision equality after course deletion; rejection preserves the complete job row.
- JobRetryCommandTests: 1 passed; actual context excludes web port, handlers, scheduler and provider clients.
- MailJobConfigurationTests: 3 passed; retry allowlist checked against real production notice registrations, excludes auth registrations.
- MediaCleanupConfigurationTests: 3 passed; retry media type/version checked against production handler.
- LessonAudioCleanupServiceTests: 32 passed, including existing log assertions (run without root ERROR suppression).
- JobRetentionIT: 6 passed; staging 29/31 days, summary and notification 89/91 days, active submissions, immutable decisions, missing-resource messages without actions, bounded batches and locked-row skipping.
- JobMaintenanceIT: existing 5 passed (inventory below); strengthened recent terminal fixture to 29 days opposite the 31-day deletion fixture.
- JobLeaseIT: 15 passed, including terminalization secret erasure in all three terminal states and secret preservation across retry/yield.
- JobMetricsIT: 2 passed; JobMetricsTests: 5 passed; JobPropertiesTests: 2 passed.
- Actual PowerShell wrapper and packaged jar: success exit 0 resets the same row; repeat-active, malformed-payload and unknown-ID requests exit 2, preserve rows, and do not leak payload/email/password sentinels. Child environment is cleared and populated with explicit OS bootstrap settings and isolated PostgreSQL fixture values only. Child process is required to terminate within 12 seconds. Normal jar Start-Class was asserted to be BackendApplication.
- Actual-context proof: precisely five non-framework bean definitions: jobMaintenanceApplication, maintenanceDataSource, maintenanceJdbc, maintenanceTransactions, retryCommand. Context is not a WebApplicationContext and has no local server port. Existing dedicated context test also inspects every bean for forbidden app/security/worker/provider types.
- Missing, malformed, duplicate and unknown maintenance options, positional arguments, malformed payloads and unsafe audit identifiers fail with nonzero status. New unknown-option rejection was proven red before its production fix. Audit capture asserts one exact successful job/type/operator/reason line, no throwable diagnostics and no payload, email or database-password content.

Existing JobMaintenanceIT five-test inventory:

- expiresOnlyKnownAuthMailAndNeverFutureOrNonmailOrUnknown: expires supported auth/invitation work; purges secrets; preserves future/nonmail/unknown rows.
- runningExpiryOnlyPurgesSecretsThenRecoveryAllowsTerminalizationWithoutSecret: purges expired running secrets without changing lease, attempts, timestamp or running state.
- boundedExpiryAndTerminalRetentionNeverDeleteOldActiveJobs: 500+1 expiry/deletion batches; READY, RUNNING and RETRY_WAIT survive regardless of creation age; terminal 29/31-day boundary.
- runningSecretPurgeIsAlsoBoundedAtFiveHundred: 500+1 purge batches retain all running leases.
- skipsLockedCandidate: expiry, running-secret and terminal-retention lanes skip locked candidates.

Final command (JAVA_HOME=F:\jdk_21; Maven offline; timeout 180 seconds):

```text
./mvnw.cmd -o -Pjobs-it -Dit.test=JobRetryCommandIT,JobRetentionIT,JobMaintenanceIT,JobLeaseIT,JobMetricsIT -Dtest=JobRetryCommandTests,MailJobConfigurationTests,MediaCleanupConfigurationTests,LessonAudioCleanupServiceTests,JobMetricsTests,JobPropertiesTests verify -q
```

No remaining R3 implementation or verification gaps identified for the requested scope.

No Git mutations, root/infra/report edits, deployment requests or external service calls were made. No R4 work.
