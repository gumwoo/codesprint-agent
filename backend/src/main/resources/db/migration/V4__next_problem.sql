-- 다음에 풀 문제를 제출 행에 고정한다. 정본 근거: ADR-0002.
--
-- nextAction 을 저장한 것과 같은 이유다(V2). 조회할 때마다 다시 고르면 그 사이
-- 다른 제출이 바꿔 놓은 상태를 보게 되어, **같은 제출을 두 번 조회했을 때 다른
-- 문제가 나온다.**
--
-- 그리고 한 응답 안에서 기준 시점이 둘이 되는 것이 더 나쁘다 - nextAction 은 과거
-- 시점에 고정돼 있는데 그 행동이 가리키는 문제만 현재 상태로 고르면, 응답이
-- "언제의 판단인가" 를 스스로 설명하지 못한다.
ALTER TABLE submissions
    ADD COLUMN next_problem_code   VARCHAR(100),
    ADD COLUMN next_problem_reason TEXT;

-- 이미 처리된 제출을 채운다.
--
-- 컬럼만 추가하고 두면 V4 이전에 반영된 제출은 next_problem_reason 이 NULL 로 남는다.
-- 그런데 조회는 next_action_type 이 있으면 "완료" 로 보고 200 을 돌려주므로,
-- reason 이 required 인 계약(next-problem.schema.json)을 그대로 어긴다.
--
-- 새 데이터만 보는 테스트는 통과한다. 마이그레이션 이전 데이터가 계약을 깨는 것은
-- 그 테스트가 볼 수 없는 자리다.
UPDATE submissions
   SET next_problem_reason = '문제 제공 기능이 생기기 전에 처리된 제출이라 다음 문제가 고정되지 않았다'
 WHERE next_action_type IS NOT NULL
   AND next_problem_reason IS NULL;

-- 앞으로 같은 구멍이 다시 생기지 않게 막는다.
--
-- "결정이 있으면 그 결정을 문제로 옮긴 결과도 있다" 가 불변식이다. 문제를 고르지
-- 못한 경우에도 **왜 없는지**는 반드시 남는다 - 빈 응답으로 두면 "아직 안 끝났다" 와
-- "줄 문제가 없다" 를 구분할 수 없다.
ALTER TABLE submissions
    ADD CONSTRAINT submissions_decided_has_next_problem_reason CHECK (
        next_action_type IS NULL OR next_problem_reason IS NOT NULL
    );
