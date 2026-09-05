package dev.codesprint.learning.domain;

/**
 * Skill 의 학습 상태. 정본: Addendum §5.
 *
 * <p>LOCKED 와 READY 는 선수 관계에서 나오므로 {@link MasteryCalculator} 가 정하지
 * 않는다. Evidence 만 봐서는 "앞 Skill 을 충분히 했는가" 를 알 수 없다.
 */
public enum SkillStatus {
    UNASSESSED,
    READY,
    LEARNING,
    PRACTICING,
    MASTERED,
    REVIEW_DUE,
    WEAKENED,
    LOCKED
}
