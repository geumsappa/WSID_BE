-- 상담 메시지에서 LLM 문맥(assist history)으로만 쓰는 내용. SOL-02 조회 응답에는 내보내지 않아요.
-- 지금은 SOL-06 사진 메시지의 "[사진 · 종류] 요약 + 가린 인식 글자"를 저장해요.
ALTER TABLE solution_messages ADD COLUMN assist_context TEXT NULL;
