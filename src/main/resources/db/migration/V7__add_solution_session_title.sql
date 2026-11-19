-- SOL-07 목록 제목: 민감정보 검사를 통과한 첫 피해 설명의 앞 40자(공백 정리). 설명 없이 시작하면 NULL.
ALTER TABLE solution_sessions ADD COLUMN title VARCHAR(60);
