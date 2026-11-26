-- 팀 ERD(GSB.sql)를 PostgreSQL에서 실행되도록 옮긴 초기 스키마예요.
-- 테이블과 컬럼은 ERD 그대로이고, 실행을 위해 고친 것은 두 가지뿐이에요.
--   1. 백틱(`)은 MySQL 문법이라 제거
--   2. solution_sessions.card_codes_json 기본값 '' 은 올바른 JSON이 아니라 '[]' 로 변경
--
-- 이 파일은 한 번 적용되면 수정하지 않아요. 바꿀 때는 V3__... 파일을 새로 만들어요.

CREATE TABLE users (
    id                BIGSERIAL    PRIMARY KEY,
    email             VARCHAR(320) NOT NULL,
    password_hash     VARCHAR(255) NOT NULL,
    gender            VARCHAR(10)  NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    email_verified_at TIMESTAMPTZ  NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE training_sessions (
    id                      BIGSERIAL   PRIMARY KEY,
    user_id                 BIGINT      NOT NULL,
    phase                   VARCHAR(25) NOT NULL DEFAULT 'DISCOVERY',
    status                  VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    fraud_type_code         VARCHAR(50) NULL,
    context_facts           JSONB       NULL,
    scenario_json           JSONB       NULL,
    mock_page_template_code VARCHAR(50) NULL,
    flow_state_json         JSONB       NOT NULL DEFAULT '{}',
    discovery_turn_count    INTEGER     NOT NULL DEFAULT 0,
    simulation_turn_count   INTEGER     NOT NULL DEFAULT 0,
    state_version           INTEGER     NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_activity_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at              TIMESTAMPTZ NOT NULL,
    completed_at            TIMESTAMPTZ NULL
);

CREATE TABLE training_messages (
    id                   BIGSERIAL    PRIMARY KEY,
    training_id          BIGINT       NOT NULL,
    phase                VARCHAR(20)  NOT NULL,
    sender               VARCHAR(10)  NOT NULL,
    input_kind           VARCHAR(15)  NOT NULL DEFAULT 'TEXT',
    content              TEXT         NULL,
    action_code          VARCHAR(100) NULL,
    action_meta_json     JSONB        NULL,
    options_json         JSONB        NULL,
    turn_evaluation_json JSONB        NULL,
    sequence_no          INTEGER      NOT NULL,
    client_request_id    UUID         NULL,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE training_reports (
    id          BIGSERIAL    PRIMARY KEY,
    training_id BIGINT       NOT NULL,
    title       VARCHAR(200) NOT NULL,
    report_json JSONB        NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    saved_at    TIMESTAMPTZ  NULL
);

CREATE TABLE solution_sessions (
    id                 BIGSERIAL   PRIMARY KEY,
    user_id            BIGINT      NOT NULL,
    status             VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    fraud_type_code    VARCHAR(50) NULL,
    facts_json         JSONB       NOT NULL DEFAULT '{}',
    card_codes_json    JSONB       NOT NULL DEFAULT '[]',
    next_question_json JSONB       NULL,
    state_version      INTEGER     NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at       TIMESTAMPTZ NULL
);

CREATE TABLE solution_messages (
    id                   BIGSERIAL    PRIMARY KEY,
    solution_id          BIGINT       NOT NULL,
    sender               VARCHAR(10)  NOT NULL,
    input_kind           VARCHAR(15)  NOT NULL DEFAULT 'TEXT',
    safe_content         TEXT         NULL,
    selected_code        VARCHAR(100) NULL,
    reference_codes_json JSONB        NULL,
    sequence_no          INTEGER      NOT NULL,
    client_request_id    UUID         NULL,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);
