-- Structured state only: no original questions or generated answer prose.
ALTER TABLE solution_sessions ADD COLUMN pending_assist_json JSONB;
ALTER TABLE solution_messages ADD COLUMN purpose VARCHAR(32);
ALTER TABLE solution_messages ADD COLUMN assist_question_id UUID;
