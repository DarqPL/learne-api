ALTER TABLE question_options ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT TRUE;

CREATE INDEX idx_question_options_question_active ON question_options(question_id, is_active);
