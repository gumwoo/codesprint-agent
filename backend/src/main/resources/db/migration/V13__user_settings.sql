-- 학습 계획의 입력인 사용자 설정. 정본: PRD §80(입력: 시험일까지 남은 날짜, 하루 공부 가능 시간),
-- §152(사용자 설정), ADR-0038.
--
-- 둘 다 NULL 을 허용한다. NULL 은 "정하지 않았다" 다 - 계획은 그때 시간을 어림하지 않고
-- 순서만 준다. 기본값(예: 60 분)을 넣으면 정하지 않은 사용자와 60 분을 고른 사용자가 구별되지
-- 않는다(ADR-0035 의 목표에 기본값을 두지 않은 것과 같은 이유).
ALTER TABLE users ADD COLUMN daily_minutes INTEGER;
ALTER TABLE users ADD COLUMN exam_date DATE;
ALTER TABLE users ADD CONSTRAINT users_daily_minutes_range
    CHECK (daily_minutes IS NULL OR daily_minutes BETWEEN 10 AND 720);
