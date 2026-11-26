-- 시뮬레이션 시작 전 'AI 처리(해외 전송) 안내'에 동의한 기록. 어떤 문구 버전에 언제 동의했는지 남겨요.
-- 문구가 바뀌면 프론트가 새 버전으로 다시 동의를 받아요. 동의 전이면 NULL.
ALTER TABLE users ADD COLUMN ai_consent_version VARCHAR(20);
ALTER TABLE users ADD COLUMN ai_consent_at TIMESTAMPTZ;
