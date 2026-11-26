-- SOL-03: 선택으로 답한 사실의 키. 기존 메시지는 NULL을 유지해요.
ALTER TABLE solution_messages ADD COLUMN fact_key VARCHAR(50) NULL;

ALTER TABLE solution_messages ADD CONSTRAINT ck_solution_messages_fact_key
    CHECK (fact_key IS NULL OR fact_key IN (
        'transferredMoney', 'sharedInformation', 'installedApp', 'contactContinues'
    ));
