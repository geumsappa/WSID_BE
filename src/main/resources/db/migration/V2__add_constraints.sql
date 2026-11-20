-- ERD에는 없지만 명세서 요구사항을 DB에서 지키기 위해 제안하는 제약이에요.
-- 팀에서 동의하지 않는 항목은 이 파일에서 빼고, ERD에도 같이 반영해 주세요.

-- AUTH-001: 이미 가입한 이메일은 다시 가입할 수 없어요.
ALTER TABLE users
    ADD CONSTRAINT uq_users_email UNIQUE (email);

-- 세션·리포트는 반드시 존재하는 사용자·훈련에 연결돼요.
ALTER TABLE training_sessions
    ADD CONSTRAINT fk_training_sessions_user FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE training_messages
    ADD CONSTRAINT fk_training_messages_training FOREIGN KEY (training_id) REFERENCES training_sessions (id);
ALTER TABLE training_reports
    ADD CONSTRAINT fk_training_reports_training FOREIGN KEY (training_id) REFERENCES training_sessions (id);
ALTER TABLE solution_sessions
    ADD CONSTRAINT fk_solution_sessions_user FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE solution_messages
    ADD CONSTRAINT fk_solution_messages_solution FOREIGN KEY (solution_id) REFERENCES solution_sessions (id);

-- 중복 처리: 같은 요청 ID의 메시지는 한 번만 저장돼요. (client_request_id가 NULL인 AI 메시지는 제한 없음)
ALTER TABLE training_messages
    ADD CONSTRAINT uq_training_messages_request UNIQUE (training_id, client_request_id);
ALTER TABLE solution_messages
    ADD CONSTRAINT uq_solution_messages_request UNIQUE (solution_id, client_request_id);

-- 대화 순서가 겹치지 않아요. 동시에 두 요청이 들어와도 하나만 저장돼요.
ALTER TABLE training_messages
    ADD CONSTRAINT uq_training_messages_sequence UNIQUE (training_id, sequence_no);
ALTER TABLE solution_messages
    ADD CONSTRAINT uq_solution_messages_sequence UNIQUE (solution_id, sequence_no);

-- SIM-010, RPT-001: 훈련 하나에 리포트는 하나예요.
ALTER TABLE training_reports
    ADD CONSTRAINT uq_training_reports_training UNIQUE (training_id);

-- 홈의 '이어서 하기': 사용자의 활성 세션을 빠르게 찾아요.
CREATE INDEX ix_training_sessions_user_status ON training_sessions (user_id, status, last_activity_at DESC);
CREATE INDEX ix_solution_sessions_user_status ON solution_sessions (user_id, status);
