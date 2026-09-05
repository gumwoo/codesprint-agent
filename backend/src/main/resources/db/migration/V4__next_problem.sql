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
