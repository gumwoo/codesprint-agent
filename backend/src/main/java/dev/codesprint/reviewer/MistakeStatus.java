package dev.codesprint.reviewer;

/**
 * Reviewer 가 낸 Mistake 후보를 시스템이 어디까지 인정했는가. 정본: Addendum §19.
 *
 * <p><b>LLM 이 이 값을 스스로 선언할 수 없다.</b> Reviewer 요청 스키마에는 이 필드가
 * 아예 없다(ADR-0001). 시스템이 confidence 구간과 Judge 근거를 보고 붙인다.
 */
public enum MistakeStatus {

    /** confidence &lt; 0.60. 참고 로그만 남기고 mastery 에 반영하지 않는다. */
    LOGGED_ONLY,

    /** 0.60 ~ 0.80. 자동 드릴 금지. 같은 패턴이 재발하면 확정될 수 있다. */
    POSSIBLE,

    /** 0.80 ~ 0.90. 약한 penalty 까지. */
    PROBABLE,

    /** 확정. Judge 근거와 맞았거나(§21-A) 재발이 확인됐다(§21-B). */
    CONFIRMED;

    /** 이 상태의 Mistake 를 Decision Engine 에 넘길 수 있는가. */
    public boolean isConfirmed() {
        return this == CONFIRMED;
    }
}
