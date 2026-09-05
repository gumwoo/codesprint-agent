package dev.codesprint.learning.domain;

/**
 * Evidence 의 종류와 그에 따른 두 계수. 정본: Addendum §9(alpha), §18(가중치).
 *
 * <p>alpha 는 EMA 가 새 관측을 얼마나 반영하는가다. 진단과 모의고사가 큰 이유는
 * 유형을 알려주지 않은 상태의 관측이라 정보량이 크기 때문이고, 드릴이 작은 이유는
 * 좁은 범위만 보기 때문이다.
 *
 * <p>confidenceWeight 는 "이 판단을 얼마나 믿을 수 있는가" 에 쌓이는 값이다.
 * 복습과 모의고사가 큰 이유는 "시간이 지난 뒤에도 되는가" 와 "유형을 모르는
 * 상태에서도 되는가" 를 보기 때문이다.
 */
public enum EvidenceType {
    DIAGNOSTIC_RESULT(0.25, 1.0),
    PROBLEM_SUBMISSION(0.18, 1.0),
    MICRO_DRILL_RESULT(0.12, 0.5),
    REVIEW_RESULT(0.22, 1.3),
    CONCEPT_CHECK(0.18, 0.5),
    EXPLAIN_BACK(0.18, 0.5),
    MOCK_TEST_RESULT(0.28, 1.5);

    private final double alpha;
    private final double confidenceWeight;

    EvidenceType(double alpha, double confidenceWeight) {
        this.alpha = alpha;
        this.confidenceWeight = confidenceWeight;
    }

    public double alpha() {
        return alpha;
    }

    public double confidenceWeight() {
        return confidenceWeight;
    }
}
