-- 개념 자료가 **실제로 건네진** 시각. 근거: ADR-0030 개정.
--
-- next_action_type = 'REVIEW_CONCEPT' 은 "보여주기로 정했다" 이지 "보여줬다" 가
-- 아니다. 사용자가 "다음 단계 보기" 를 누르지 않으면 자료는 화면에 뜨지 않는다.
--
-- 그 둘을 같게 보면, **한 번도 열어 보지 않은 사용자에게 다시는 자료를 주지 않는다.**
-- 실제로 그랬다 - 3회 실패로 REVIEW_CONCEPT 가 정해진 뒤 조회하지 않고 또 틀리면,
-- 결정 기록만 보고 "이미 봤다" 로 판단했다.
--
-- 힌트에서 그은 선과 같다(ADR-0027): 결정은 결정이고, 관측은 서버가 내주면서 남긴다.
ALTER TABLE submissions
    ADD COLUMN concept_delivered_at TIMESTAMPTZ;

COMMENT ON COLUMN submissions.concept_delivered_at IS
    '이 제출의 REVIEW_CONCEPT 자료가 실제로 응답에 실려 나간 시각. 처음 한 번만 찍는다.';
