-- 힌트 발급 기록. 근거: PRD §73·§109, ADR-0026.
--
-- **이 표가 "몇 단계를 봤는가" 의 정본이다.** 클라이언트가 신고하지 않는다 -
-- 서버가 힌트를 내주면서 여기에 남기고, 제출은 이 기록에서 읽는다. 예전에는
-- 제출 요청이 hintLevel 을 실어 보냈고, 아무도 확인할 수 없는 그 값으로
-- mastery 가 깎였다(PR #19 에서 입력을 막았다).
--
-- 한 번 본 힌트는 되돌릴 수 없으므로 (사용자, 문제, 단계) 당 한 행이다.
-- 같은 단계를 다시 열어 봐도 새 기록이 되지 않는다 - 그러면 "여러 번 본 사람"
-- 이 더 많이 본 것으로 계산된다.

CREATE TABLE hint_usage (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT       NOT NULL REFERENCES users (id),
    problem_id   BIGINT       NOT NULL REFERENCES problems (id),

    -- 1..5 는 hints.yaml 의 사다리, 6 은 전체 풀이(reference.py)다.
    -- Evidence 는 6 을 힌트 최고 단계보다 위로 친다(learning/evidence.py AC_BY_HINT).
    hint_level   INTEGER      NOT NULL,

    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT hint_usage_once_per_level UNIQUE (user_id, problem_id, hint_level),

    -- 사다리 밖의 값이 들어오면 AC_BY_HINT 조회가 터지거나, 더 나쁘게는
    -- 조용히 잘린 값으로 계산된다.
    CONSTRAINT hint_usage_level_range CHECK (hint_level BETWEEN 1 AND 6)
);

CREATE INDEX hint_usage_by_user_problem ON hint_usage (user_id, problem_id);

-- 제출은 더 이상 신고받은 값을 저장하지 않는다. 기존 행은 전부 0 / false 이므로
-- (PR #19 가 그 외를 400 으로 막았다) 옮길 값이 없다. 컬럼은 그대로 둔다 -
-- 제출 시점에 읽은 값을 얼려 두는 자리이고, 이제 그 값이 이 표에서 온다.
COMMENT ON COLUMN submissions.hint_level IS
    '제출 시점에 hint_usage 에서 읽은 최고 단계. 클라이언트가 보내지 않는다(ADR-0026).';
