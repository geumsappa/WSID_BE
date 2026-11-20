-- SOL-01: 비회원 피해 대응 상담 소유자 지원.
-- 번호 V3는 LSH 담당 확인 대상이에요(합의 5). 머지 전 대상 브랜치에 같은 번호가 없는지 다시 확인해요.
-- 기존 회원 상담은 user_id를 유지하고 anonymous_owner_id = NULL이라 새 제약을 그대로 만족해요.

ALTER TABLE solution_sessions
    ALTER COLUMN user_id DROP NOT NULL;

ALTER TABLE solution_sessions
    ADD COLUMN anonymous_owner_id UUID NULL;

ALTER TABLE solution_sessions
    ADD CONSTRAINT ck_solution_sessions_single_owner
        CHECK ((user_id IS NULL) <> (anonymous_owner_id IS NULL));

CREATE INDEX ix_solution_sessions_anonymous_status
    ON solution_sessions (anonymous_owner_id, status)
    WHERE anonymous_owner_id IS NOT NULL;
