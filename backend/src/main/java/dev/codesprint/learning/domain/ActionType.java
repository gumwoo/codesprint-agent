package dev.codesprint.learning.domain;

/**
 * 다음 학습 행동. 정본: PRD §75.
 *
 * <p><b>LLM 에게 묻지 않는다</b>(ADR-0002). Rule Engine 이 유일한 결정 주체이며,
 * Reviewer 출력 스키마에는 이 값을 담을 자리가 아예 없다.
 */
public enum ActionType {
    /** 같은 흐름을 이어간다. 난이도도 Skill 도 바꾸지 않는다. */
    CONTINUE,
    HARDER,
    EASIER,
    /** 특정 실수를 교정하는 2~5분짜리 문제(Addendum §72). targetSkill 이 반드시 있다. */
    MICRO_DRILL,
    REVIEW_CONCEPT,
    /** 같은 Skill 의 다른 문제. 구현 연습이 더 필요할 때. */
    RETRY_VARIANT,
    /** 지금 Skill 을 두고 다른 Skill 로. 선수 조건을 채우러 갈 때 쓴다. */
    CHANGE_SKILL,
    UNLOCK_NEXT,
    SCHEDULE_REVIEW,
    MOCK_TEST,
    END_SESSION
}
