# Local seed batches

These scripts are for a positively identified local/development PostgreSQL
database only. No production connection or command is provided.

## Batch 1 workflow

1. Inspect the active Docker/Postgres container, database name, and connection
   target. Stop if the target is not clearly local/development.
2. Capture counts for `users`, `grammar_checks`, `orders`, and `order_items`.
3. Run [`reset-local-learning-seed.sql`](./reset-local-learning-seed.sql) once.
   It is transactional, preserves accounts and unrelated history, and aborts
   when preserved commerce rows still reference catalog courses.
4. Apply [`seed_batches/grammar-a1-batch-1.sql`](./seed_batches/grammar-a1-batch-1.sql).
   Rerunning the batch is supported; do not rerun the reset for later batches.

Batch courses and lessons intentionally start as `DRAFT`. After content review,
approve and publish them through the normal teacher/admin workflow; applying
the seed does not make unreviewed content visible to learners.

The legacy `seed-realistic-full.sql` is not a batch seed and must not be used
for this workflow.

## Batch 2 workflow

Batch 2 adds the A1 Grammar course for Pham Linh Chi. Generate it from the
backend directory with explicit input and output paths:

```powershell
Push-Location backend
python scripts/build_grammar_a1_seed.py --input database/seed_batches/grammar-a1-batch-2.json --output database/seed_batches/grammar-a1-batch-2.sql
Pop-Location
```

After confirming that the target is the local/dev database, apply the
generated SQL from the repository root:

```powershell
Get-Content -Raw "backend/database/seed_batches/grammar-a1-batch-2.sql" | docker compose -f infra/docker-compose.dev.yml exec -T postgres psql -U learne -d learne -v ON_ERROR_STOP=1
```

Do not rerun the reset script or Batch 1 SQL to add Batch 2. The generated
Batch 2 SQL is additive and supports reruns without duplicating its course,
content, or revision snapshot.

Expected Batch 2 deltas are +1 course, +2 topics, +2 lessons, +10 questions,
+40 question options, +1 course revision, +2 topic revisions, +2 lesson
revisions, +10 question revisions, and +40 question option revisions. The
courses with IDs 232 and 233, their revisions and submission states, all users,
and all orders and order items must remain unchanged.

## Batch 3 workflow

Batch 3 adds separate A1 Grammar courses for Le Hoang Nam and Doan Thu Trang.
Generate it from the backend directory with explicit input and output paths:

```powershell
Push-Location backend
python scripts/build_grammar_a1_seed.py --input database/seed_batches/grammar-a1-batch-3.json --output database/seed_batches/grammar-a1-batch-3.sql
Pop-Location
```

After confirming that the target is the local/dev database, apply only the
generated SQL from the repository root:

```powershell
Get-Content -Raw "backend/database/seed_batches/grammar-a1-batch-3.sql" | docker compose -f infra/docker-compose.dev.yml exec -T postgres psql -U learne -d learne -v ON_ERROR_STOP=1
```

Do not run the local reset script or Batch 1/2 SQL to add Batch 3. The SQL is
additive and supports reruns without duplicating courses, content, or revision
snapshots. Both courses remain `DRAFT` until the normal teacher/admin review
workflow approves and publishes them.

Expected Batch 3 additions are +2 courses, +4 topics, +4 lessons, +20
questions, +80 question options, +2 course revisions, +4 topic revisions, +4
lesson revisions, +20 question revisions, and +80 question option revisions.
Courses 232, 233, and 234, their revisions, submissions, and review states,
plus all users, grammar checks, orders, and order items must remain unchanged.

## Verification

Expected Batch 1 totals are two courses, four lessons, 20 questions, and 80
options:

```sql
SELECT c.name, u.email AS teacher_email,
       COUNT(DISTINCT l.id) AS lessons,
       COUNT(DISTINCT q.id) AS questions,
       COUNT(DISTINCT qo.id) AS options
FROM courses c
JOIN users u ON u.id = c.teacher_id
LEFT JOIN topics t ON t.course_id = c.id
LEFT JOIN lessons l ON l.topic_id = t.id
LEFT JOIN questions q ON q.lesson_id = l.id
LEFT JOIN question_options qo ON qo.question_id = q.id
WHERE c.name IN (
  'A1 Grammar: Clear Rules with Nguyen Minh Ha',
  'A1 Grammar: English in Everyday Dialogues with Tran Quoc Anh'
)
GROUP BY c.id, c.name, u.email
ORDER BY c.name;
```

Check duplicate prompts and invalid answer cardinality:

```sql
SELECT lower(regexp_replace(trim(q.question_text), '\s+', ' ', 'g')) AS prompt,
       COUNT(*)
FROM questions q
GROUP BY 1
HAVING COUNT(*) > 1;

SELECT q.id, COUNT(*) FILTER (WHERE qo.is_correct) AS correct_options
FROM questions q
JOIN question_options qo ON qo.question_id = q.id
GROUP BY q.id
HAVING COUNT(*) FILTER (WHERE qo.is_correct) <> 1;
```

Compare the post-reset counts for `users`, `grammar_checks`, `orders`, and
`order_items` with the captured values. They must be unchanged. Never execute
the reset or batch until the target identity is confirmed.
