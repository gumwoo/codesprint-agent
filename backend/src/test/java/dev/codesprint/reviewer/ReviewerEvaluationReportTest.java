package dev.codesprint.reviewer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 평가 보고가 사람을 오해시키지 않는가.
 *
 * <p>모델을 부르지 않는다 - 집계만 본다. {@link ReviewerEvaluation} 본체는 실제
 * 모델이 필요해 CI 에서 돌지 않으므로(ADR-0016), <b>모델 없이 확인할 수 있는 부분은
 * 여기서 확인한다.</b>
 *
 * <p>보고가 틀리면 측정 자체가 틀린 것이 된다. 숫자를 읽고 판단하는 것이 이 하네스의
 * 유일한 산출물이다.
 */
class ReviewerEvaluationReportTest {

    private static ReviewerEvaluation.Outcome usable(String label, String predicted) {
        return new ReviewerEvaluation.Outcome(
                "P02_GRID_TRAVERSAL", label, predicted, 0.9, MistakeStatus.PROBABLE, null);
    }

    private static ReviewerEvaluation.Outcome rejected(String label, String predicted) {
        // 검증에서 떨어진 분석. status 가 없다 - 학습 경로에 닿지 않는다.
        return new ReviewerEvaluation.Outcome(
                "P02_GRID_TRAVERSAL", label, predicted, 0.9, null, "검증 탈락");
    }

    private static String render(List<ReviewerEvaluation.Outcome> outcomes) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            ReviewerEvaluation.report(outcomes, "test-v1");
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("버려진 분석이 맞혔다고 정확도가 올라가 보이지 않는다")
    void rejectedAnalysesAreCountedSeparately() {
        // 검증에서 떨어진 분석도 primary 문자열은 남아 있다. 하나로 합쳐 세면
        // "쓸 수 있는 분석 1/3, primary 일치 3/3" 같은 줄이 나와, 실제로는 하나만
        // 학습 경로에 닿는데 다 맞힌 것처럼 읽힌다.
        String output = render(List.of(
                usable("BOUNDARY_CHECK", "BOUNDARY_CHECK"),
                rejected("NO_VISITED", "NO_VISITED"),
                rejected("INDEX_ERROR", "INDEX_ERROR")));

        assertThat(output)
                .as("쓸 수 있는 분석 중의 일치와, 버려진 것까지 센 일치를 나눠 적는다")
                .contains("쓸 수 있는 분석 중 1/1")
                .contains("버려진 것까지 3/3");
    }

    @Test
    @DisplayName("확정은 쓸 수 있는 분석만 센다")
    void confirmationCountsOnlyUsable() {
        String output = render(List.of(
                new ReviewerEvaluation.Outcome("P02_GRID_TRAVERSAL", "BOUNDARY_CHECK",
                        "BOUNDARY_CHECK", 0.95, MistakeStatus.CONFIRMED, null),
                rejected("NO_VISITED", "NO_VISITED")));

        assertThat(output).contains("확정(§21-A) 1건");
    }

    @Test
    @DisplayName("라벨과 다른 것이 확정되면 오확정이다")
    void falseConfirmation() {
        var wrong = new ReviewerEvaluation.Outcome("P01_QUEUE_BASIC", "OUTPUT_FORMAT",
                "BOUNDARY_CHECK", 0.95, MistakeStatus.CONFIRMED, null);
        var right = new ReviewerEvaluation.Outcome("P02_GRID_TRAVERSAL", "BOUNDARY_CHECK",
                "BOUNDARY_CHECK", 0.95, MistakeStatus.CONFIRMED, null);

        // 이 하네스가 단언하는 유일한 것이다(ADR-0016).
        assertThat(wrong.isFalseConfirmation()).isTrue();
        assertThat(right.isFalseConfirmation()).isFalse();
        // 확정되지 않으면 틀렸어도 오확정이 아니다 - 학습 경로를 바꾸지 않는다.
        assertThat(usable("OUTPUT_FORMAT", "BOUNDARY_CHECK").isFalseConfirmation()).isFalse();
    }
}
