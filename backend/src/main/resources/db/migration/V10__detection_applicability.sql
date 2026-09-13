-- 그 문제에서 일어날 수 있다고 선언된 실수였는가. 근거: ADR-0029.
--
-- **확정의 근거가 되지 않는 탐지를 재발 집계에서도 빼기 위해 필요하다.** 확정을
-- 그 자리에서 막는 것만으로는 부족하다 - 그 행이 남아 있으면 나중에 다른 문제에서
-- 같은 실수가 나올 때 §21-B 의 재발 횟수를 채워 준다. 일어날 수 없다고 선언된
-- 탐지가 남의 확정을 앞당기는 셈이다.
--
-- 기존 행은 true 로 둔다. 그때는 이 구분이 없었고, 소급해서 "선언되지 않았다" 고
-- 말할 근거가 없다 - 없는 값을 지어내지 않는다.
ALTER TABLE mistake_detections
    ADD COLUMN declared_for_problem BOOLEAN NOT NULL DEFAULT true;

COMMENT ON COLUMN mistake_detections.declared_for_problem IS
    'problems/<CODE>/problem.yaml 의 commonMistakes 에 있던 실수인가(ADR-0029). false 면 확정에도 재발 집계에도 쓰지 않는다.';
