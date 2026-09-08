-- 간격 복습 일정. 근거: PRD §79, ADR-0021.
--
-- **이 행 하나가 "복습인가" 를 정한다.** 문제 종류가 아니다 - kind: REVIEW 를
-- 목록에서 찾아 연달아 풀어도 복습이 아니고, 만기된 일정이 있으면 평범한 문제도
-- 복습이다. 그러지 않으면 사용자가 자기 MASTERED 를 만들 수 있다.
--
-- (사용자, Skill) 당 하나다. 이력을 남기지 않는 이유는 "복습을 몇 번 했는가" 가
-- 이미 Evidence 에 남기 때문이다 - 여기 또 쌓으면 정본이 둘이 된다.

CREATE TABLE review_schedules (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT      NOT NULL REFERENCES users (id),
    skill_code     VARCHAR(100) NOT NULL,

    -- 이 시각 이후의 제출이 복습으로 센다.
    due_at         TIMESTAMPTZ NOT NULL,

    -- 직전 관측 시각. daysSinceLast 를 여기서 잰다 - 클라이언트가 보내지 않는다.
    last_observed_at TIMESTAMPTZ NOT NULL,

    -- 지금 간격(일). PRD §79 의 1 → 3 → 7 → 14 → 30.
    interval_days  INTEGER     NOT NULL,

    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT review_schedules_one_per_skill UNIQUE (user_id, skill_code),

    -- **0 일 간격을 허용하지 않는다.** 허용하면 같은 자리에서 두 번 제출하는 것이
    -- 간격 복습이 되고, retention 이 "시간이 지나도 되는가" 를 재지 못한다.
    -- 산식(SubmissionEvidenceFactory)은 음수만 막는다 - 며칠이어야 복습인가는
    -- 일정의 결정이지 산식의 결정이 아니다.
    CONSTRAINT review_schedules_interval_positive CHECK (interval_days >= 1),

    -- 만기는 직전 관측보다 뒤다. 시계가 거꾸로 가거나 계산이 틀리면 여기서 멈춘다.
    CONSTRAINT review_schedules_due_after_observed CHECK (due_at > last_observed_at)
);

-- "지금 만기인 것" 을 매 제출 반영마다 찾는다.
CREATE INDEX review_schedules_due ON review_schedules (user_id, due_at);

-- 만기된 복습으로 보내는 행동. SCHEDULE_REVIEW("복습을 예약한다")와 다른 것이라
-- 값을 따로 둔다 - 섞으면 나중에 "이 제출이 예약된 복습이었는가" 를 이력에서
-- 되읽을 수 없다. DIAGNOSTIC_PROBE 를 따로 둔 것과 같은 이유다(V6).

ALTER TABLE submissions
    DROP CONSTRAINT submissions_next_action_known;

ALTER TABLE submissions
    ADD CONSTRAINT submissions_next_action_known CHECK (
        next_action_type IS NULL OR next_action_type IN (
            'CONTINUE', 'HARDER', 'EASIER', 'MICRO_DRILL', 'REVIEW_CONCEPT',
            'RETRY_VARIANT', 'CHANGE_SKILL', 'DIAGNOSTIC_PROBE', 'REVIEW_DUE',
            'UNLOCK_NEXT', 'SCHEDULE_REVIEW', 'MOCK_TEST', 'END_SESSION'
        )
    );
