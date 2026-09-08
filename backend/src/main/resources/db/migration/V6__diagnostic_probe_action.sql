-- 초기 진단이 내는 행동을 정본 목록에 넣는다. 근거: ADR-0019.
--
-- 진단이 아직 확인하지 못한 Skill 로 보내는 행동이다. CHANGE_SKILL 로 적으면
-- "선수 조건을 채우러 간다" 와 "아직 재 보지 않았다" 가 한 값에 섞이고, 나중에
-- "이 사용자가 진단 중이었는가" 를 제출 이력에서 되읽을 수 없게 된다.
--
-- **이 제약이 실제로 일했다.** ActionType 에 값을 더하고 계약 두 개를 고친 뒤에도
-- 여기를 빠뜨렸는데, 반영이 조용히 넘어가지 않고 이 CHECK 에서 멈췄다.
-- 행동 이름이 사는 곳은 셋이다 - enum, contracts/*.schema.json, 그리고 여기.

ALTER TABLE submissions
    DROP CONSTRAINT submissions_next_action_known;

ALTER TABLE submissions
    ADD CONSTRAINT submissions_next_action_known CHECK (
        next_action_type IS NULL OR next_action_type IN (
            'CONTINUE', 'HARDER', 'EASIER', 'MICRO_DRILL', 'REVIEW_CONCEPT',
            'RETRY_VARIANT', 'CHANGE_SKILL', 'DIAGNOSTIC_PROBE', 'UNLOCK_NEXT',
            'SCHEDULE_REVIEW', 'MOCK_TEST', 'END_SESSION'
        )
    );
