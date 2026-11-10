# Premium course seed standard

## Minimum content standard

Every A2-C2 grammar pilot must have at least:

- eight ordered topics with a reviewed progression;
- four complete lessons per topic;
- ten questions per lesson (40 questions per topic);
- an objective, explanation/content, contextual examples, common errors and
  corrections, guided practice, and summary for every lesson.

Higher CEFR levels may need more content. Each level still requires its own
curriculum review before authoring or seeding.

## One topic per reviewed batch

Each topic is authored and applied as a separate JSON/SQL batch. The teacher or
course owner reviews and approves one topic before the next topic is authored.
`grammar-a2-batch-1.json` contains only Topic 1, Present Simple and Present
Continuous. Topics 4-6 are available as separately reviewable payloads:
`grammar-a2-batch-4.json` (Future Plans and Predictions),
`grammar-a2-batch-5.json` (Countable and Uncountable Nouns), and
`grammar-a2-batch-6.json` (Comparatives and Superlatives). Topics 7-8 are
also available as separately reviewable payloads:
`grammar-a2-batch-7.json` (Modals for Obligation, Advice, and Permission) and
`grammar-a2-batch-8.json` (Present Perfect for Experience and Unfinished Time).

The pilot uses `MULTIPLE_CHOICE` only. The current content request validation
requires exactly one correct option for choice questions, while typed-answer
types do not provide an authored accepted-answer list or a separate
normalization contract in the seed format. Multiple-choice data is supported
through live persistence, revision snapshots, submission projection/fingerprint
events, async enrichment payloads, and deterministic quiz grading. Do not add a
different type until those paths have an explicit test-backed contract.

## No price or publish while incomplete

The pilot remains `DRAFT`, `FREE`, and unpriced through all eight topic batches
and content review. Seed SQL must not set `PAID`, submit a revision, bypass
enrichment, or publish a course. After all content is reviewed, use the normal
teacher submission workflow, wait for async enrichment to finish, and then use
the admin/teacher commercial workflow to approve pricing and publish.

## Local/dev-only generation and application

Run these commands from the repository root or use the paths shown:

```powershell
Set-Location backend
python scripts/build_premium_a2_seed.py `
  --input database/seed_batches/grammar-a2-batch-1.json `
  --output database/seed_batches/grammar-a2-batch-1.sql
```

Generate the reviewed Topic 4-6 SQL files separately, without applying them:

```powershell
python scripts/build_premium_a2_seed.py --batch-4 `
  --input database/seed_batches/grammar-a2-batch-4.json `
  --output database/seed_batches/grammar-a2-batch-4.sql
python scripts/build_premium_a2_seed.py --batch-5 `
  --input database/seed_batches/grammar-a2-batch-5.json `
  --output database/seed_batches/grammar-a2-batch-5.sql
python scripts/build_premium_a2_seed.py --batch-6 `
  --input database/seed_batches/grammar-a2-batch-6.json `
  --output database/seed_batches/grammar-a2-batch-6.sql
python scripts/build_premium_a2_seed.py --batch-7 `
  --input database/seed_batches/grammar-a2-batch-7.json `
  --output database/seed_batches/grammar-a2-batch-7.sql
python scripts/build_premium_a2_seed.py --batch-8 `
  --input database/seed_batches/grammar-a2-batch-8.json `
  --output database/seed_batches/grammar-a2-batch-8.sql
```

Application of Topics 4-8 is intentionally deferred until the user has
reviewed the complete content package. After approval, apply the files
separately in syllabus order (4, then 5, then 6, then 7, then 8) with
`psql -v ON_ERROR_STOP=1`. These commands do not reset data, set pricing, or
publish the course. Topics 7 and 8 require all preceding topic revisions to
be present and complete. No database application occurred while preparing
these Topic 7-8 payloads; the local/dev database remains unchanged until
approval and an explicit application request.

## Topics 4-6 review package

Each batch has four lessons and 40 `MULTIPLE_CHOICE` questions (160 live
options), and the authoring tests found no normalized prompt or complete
question-signature duplicates against A1 batches or A2 Topics 1-3 and no
duplicates among Topics 4-6.

### Topic 4 — Future Plans and Predictions

1. Going to for Plans and Intentions — use `be going to` for prior plans.
2. Going to for Evidence-Based Predictions — predict from visible evidence.
3. Will for Decisions, Offers, and Promises — use `will` for decisions now,
   offers, and promises.
4. Choosing Will or Going to — distinguish plans, evidence, and spontaneous
   decisions.

### Topic 5 — Countable and Uncountable Nouns

1. Common Countable and Uncountable Nouns — classify singular, plural, and
   mass nouns.
2. Some and Any — choose quantifiers in positive, negative, and question
   contexts.
3. Much, Many, and a Lot of — match quantity expressions to noun types.
4. Food and Shopping Quantities — integrate noun type and quantifier choices.

### Topic 6 — Comparatives and Superlatives

1. Comparatives with Short Adjectives — form `-er` comparatives and spelling
   changes.
2. Comparatives with More and Less — compare longer adjectives with
   `more/less ... than`.
3. Superlatives and the — form `-est`/`most` with the comparison set.
4. Irregular Forms and Comparison Modifiers — use irregular forms and
   `a bit`, `much`, and `far`.

Representative question coverage includes prior-plan versus spontaneous
future choices, evidence-based predictions, food quantity questions, and
clearly named comparison sets. The expected distribution is 40 multiple-choice
questions per batch, with four options and one correct answer per question.

## Topics 7-8 review package

Each batch has four lessons and 40 `MULTIPLE_CHOICE` questions (160 live
options). The authoring tests found 80 unique normalized question signatures
across Topics 7-8, with no duplicates against A1 batches 1-3 or A2 Topics
1-6. The generated SQL for each batch is deterministic and contains one topic,
four lessons, 40 questions, 160 options, and their revision snapshots. It is
append-only, requires the existing course and revision to remain `DRAFT`/`FREE`
and unpriced, and performs no course or revision updates.

### Topic 7 — Modals for Obligation, Advice, and Permission

1. Must and have to for obligation and rules — express strong personal
   obligations and external requirements.
2. Mustn't and don't have to — distinguish prohibition from no obligation.
3. Should and shouldn't for advice — give positive and negative advice.
4. Can and could for permission and polite requests — ask permission and make
   polite requests.

Representative items distinguish a school rule requiring an ID badge,
prohibited phone use during an exam, advice to rest with a headache, and a
polite `Could you carry this box?` request. Explanations identify whether the
meaning is obligation, prohibition, optional action, advice, permission, or a
polite request.

### Topic 8 — Present Perfect for Experience and Unfinished Time

1. Have or has plus the past participle — form affirmative present perfect
   statements with regular and irregular participles.
2. Ever and never for life experience — ask about and report experiences.
3. Just, already, yet, and unfinished time — describe recent actions and
   results during today or this week.
4. Present perfect and past simple time contrasts — select the tense using
   unfinished or explicitly finished time references.

Representative items cover `ridden`, `flown`, and `written`, `Have you ever...`
questions, `just/already/yet`, and contrasts such as `yesterday` versus
`this week`. Explanations state why the time reference selects present
perfect or past simple.

The Topic 7-8 review package is content-only. Do not apply either SQL file
before the user reviews both JSON and SQL batches and explicitly approves
database application.

Before application, review the JSON and generated SQL together. The renderers
verify the existing A2 course and revision remain `DRAFT`/`FREE` and append
only the selected topic when its prerequisites are complete.

Before applying SQL, positively confirm that the target is the local/dev
PostgreSQL service `learne_postgres_dev`, database `learne`, and not a
production or shared database. Record pre-apply counts for existing A1 course
IDs/statuses/revision IDs, submissions, users, grammar checks, orders, and
order items. Record them again after applying and confirm they are unchanged.

```powershell
Get-Content -Raw "backend/database/seed_batches/grammar-a2-batch-1.sql" |
  docker compose -f infra/docker-compose.dev.yml exec -T postgres `
  psql -U learne -d learne -v ON_ERROR_STOP=1
```

Verify one course, one topic, four lessons, 40 questions, their options, and
all revision snapshots. Confirm the course and revision are still `DRAFT` and
`FREE`. Apply the same SQL a second time and confirm counts, existing IDs, and
revision counts remain stable without duplicate lessons, questions, options,
or snapshots. Do not run reset SQL, A1 seed SQL, or any production command.

## Completion gate

After Topic 8, verify eight topics, 32 lessons, 320 questions, unique
questions across every batch, complete revision snapshots, and unchanged
existing courses, users, submissions, grammar checks, orders, and order items.
Present the full syllabus, lesson inventory, question-type distribution, and
quality results for final user approval before normal submission and pricing.
