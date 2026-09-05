package dev.codesprint.learning.domain;

/**
 * Evidence 가 무엇을 보고 만들어졌는지. 계약: skill-evidence.schema.json 의 context.
 *
 * <p>모든 필드가 nullable 이고 required 다. 해당 없는 항목은 생략이 아니라 null 로
 * 명시한다 - 생략은 "기록하지 않았다", null 은 "해당 없다" 로 서로 다른 뜻이다.
 *
 * @param independentAttempt "최근 독립 풀이" 로 셀 것인가(Addendum §22).
 *     힌트를 4단계 이상 썼거나 정답을 본 제출은 성공이든 실패든 여기 들어오지 않는다.
 *     이 값이 틀리면 Decision Engine 이 잘못된 다음 문제를 추천한다.
 */
public record EvidenceContext(
        String problemCode,
        String judgeStatus,
        Integer hintLevel,
        Boolean solutionViewed,
        Boolean independentAttempt,
        Boolean reviewSucceeded,
        Integer daysSinceLast,
        String verdict) {

    public boolean isIndependentAttempt() {
        return Boolean.TRUE.equals(independentAttempt);
    }

    public boolean isReviewSuccess() {
        return Boolean.TRUE.equals(reviewSucceeded);
    }

    public boolean isAccepted() {
        return "ACCEPTED".equals(judgeStatus);
    }
}
