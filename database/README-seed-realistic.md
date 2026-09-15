# Realistic Seed Data

This local development seed resets business data, preserves admin users, and creates a production-like LearnE dataset:

- 24 approved courses across A1, A2, and B1 levels.
- 631 lessons.
- 6,510 questions.
- 96 Listening lessons with Cloudinary-hosted TTS audio through `audioUrl` and `audioPublicId`.
- 240 placement questions.
- 5 teachers and 30 students with realistic local identities.
- Enrollments, lesson progress, quiz attempts, XP, streaks, grammar check records, and AI tutor conversations.
- No speech-scoring course content is included.

## Prerequisites

Run commands from `backend/` unless a command says otherwise.

Install the seed script dependencies:

```powershell
python -m pip install -r scripts/requirements-realistic-seed.txt
```

For listening audio uploads, the safest path is to pass `--env-file` explicitly to a file that contains real Cloudinary credentials.

Recommended for this repo: put non-empty values in `../infra/.env` and use `--env-file ../infra/.env` in upload commands:

```env
CLOUDINARY_CLOUD_NAME=
CLOUDINARY_API_KEY=
CLOUDINARY_API_SECRET=
CLOUDINARY_LISTENING_FOLDER=learne/listening-audio
```

`generate_listening_audio.py` merges environment values in this order: current process environment first, then values from the env file. Env-file values override process values, including blank values. The script also has a default env-file path of `.env`; if `backend/.env` exists, it can override current PowerShell environment values even when you do not pass `--env-file`.

If you intentionally want to use only the current PowerShell process environment, set the values and pass `--env-file` to a path that does not exist:

```powershell
$env:CLOUDINARY_CLOUD_NAME="your-cloud-name"
$env:CLOUDINARY_API_KEY="your-api-key"
$env:CLOUDINARY_API_SECRET="your-api-secret"
$env:CLOUDINARY_LISTENING_FOLDER="learne/listening-audio"
$processOnlyEnvFile=".cloudinary-process-env-only-do-not-create"
```

If Cloudinary credentials are absent, `generate_listening_audio.py` can validate the manifest with `--dry-run`, but real upload cannot populate `audioUrl` or `audioPublicId` until credentials are added.

## Listening Audio

Validate the listening manifest without generating or uploading audio. This does not require Cloudinary credentials:

```powershell
python scripts/generate_listening_audio.py --dry-run
```

Generate TTS audio, upload it to Cloudinary, and update `database/realistic-content/listening-manifest.json` with `audioUrl` and `audioPublicId` using credentials from `../infra/.env`:

```powershell
python scripts/generate_listening_audio.py --env-file ../infra/.env
```

Or use credentials from the current PowerShell process while preventing an existing default `backend/.env` file from overriding them:

```powershell
python scripts/generate_listening_audio.py --env-file .cloudinary-process-env-only-do-not-create
```

Cloudinary folder selection is resolved by the script in this order: explicit `--folder`, then `CLOUDINARY_LISTENING_FOLDER` from the merged environment, then `cloudinaryFolder` from the manifest, then `learne/listening-audio`. For predictable local seeding, use `--folder learne/listening-audio` or rely on the manifest value.

## Generate SQL

Regenerate the full realistic seed SQL from the listening manifest:

```powershell
python scripts/build_realistic_seed.py --manifest database/realistic-content/listening-manifest.json --output database/seed-realistic-full.sql
```

The generated file is `database/seed-realistic-full.sql`. It is not a Flyway migration.

## Apply To Docker Postgres

From the repository root, with the dev Postgres container running:

```powershell
Get-Content -Raw "backend/database/seed-realistic-full.sql" | docker compose -f infra/docker-compose.dev.yml exec -T postgres psql -U learne -d learne
```

This command runs `psql` inside the `postgres` container, so it uses the local container connection. If `POSTGRES_USER` or `POSTGRES_DB` are different in `infra/.env`, adjust the `psql` user and database accordingly. No password flag is needed for this Docker exec path.

## Verification

Run these checks after applying the seed:

```sql
SELECT COUNT(*) AS courses
FROM courses;
-- expected: 24

SELECT COUNT(*) AS lessons
FROM lessons;
-- expected: 631

SELECT COUNT(*) AS questions
FROM questions;
-- expected: 6510

SELECT COUNT(*) AS excluded_speech_courses
FROM courses
WHERE lower(type) LIKE '%pronun%' OR lower(name) LIKE '%pronun%';
-- expected: 0

SELECT COUNT(*) AS excluded_speech_lessons
FROM lessons l
WHERE lower(l.skill_type) LIKE '%pronun%' OR lower(l.title) LIKE '%pronun%';
-- expected: 0

SELECT COUNT(*) AS excluded_speech_questions
FROM questions q
WHERE lower(q.type) LIKE '%pronun%' OR lower(q.question_text) LIKE '%pronun%';
-- expected: 0

SELECT COUNT(*) AS listening_lessons_with_audio
FROM lessons l
WHERE l.skill_type = 'LISTENING'
  AND COALESCE(l.audio_url, '') <> ''
  AND COALESCE(l.audio_public_id, '') <> '';
-- expected: 96

SELECT COUNT(*) AS placement_lessons
FROM lessons l
WHERE l.is_placement_test = TRUE;
-- expected: 4

SELECT COUNT(*) AS enrollments
FROM course_enrollments;
-- expected: 120

SELECT COUNT(*) AS lesson_progress
FROM lesson_progress;
-- expected: 360

SELECT COUNT(*) AS quiz_attempts
FROM quiz_attempts;
-- expected: 360

SELECT COUNT(*) AS user_stats
FROM user_stats us
WHERE us.total_xp > 0
  AND us.longest_streak_days > 0;
-- expected: 30

SELECT COUNT(*) AS active_course_recommendation_paths
FROM course_recommendation_paths
WHERE status = 'ACTIVE';
-- expected: 30

SELECT COUNT(*) AS course_recommendation_items
FROM course_recommendation_items;
-- expected: 120

SELECT COUNT(*) AS grammar_checks
FROM grammar_checks;
-- expected: 30

SELECT COUNT(*) AS ai_tutor_conversations
FROM ai_conversations;
-- expected: 30
```
