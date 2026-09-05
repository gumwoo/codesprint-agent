package dev.codesprint.reviewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Addendum §19 의 구간과 §21 의 확정 조건.
 *
 * <p>여기가 <b>LLM 의 주장이 학습 경로로 넘어가는 유일한 관문</b>이다. 값 하나가
 * 틀리면 오분류가 그대로 드릴이 되고, 그 드릴 결과가 Evidence 로 쌓여 mastery 를
 * 바꾼다.
 */
class MistakeConfirmationTest {

    @Nested
    @DisplayName("confidence 구간 (Addendum 19)")
    class Bands {

        @ParameterizedTest(name = "confidence={0} -> {1}")
        @CsvSource({
            "0.00, LOGGED_ONLY",
            "0.59, LOGGED_ONLY",
            "0.60, POSSIBLE",
            "0.79, POSSIBLE",
            "0.80, PROBABLE",
            "0.89, PROBABLE",
        })
        @DisplayName("근거가 없으면 구간이 그대로 상한이다")
        void bandsWithoutEvidence(double confidence, MistakeStatus expected) {
            // 근거 인용도 없고 재발도 없다 - 확정 조건 A/B 를 둘 다 못 채운다.
            assertThat(MistakeConfirmation.decide(confidence, false, 1).status())
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("경계값은 구간에 포함된다")
        void boundariesAreInclusive() {
            // 0.60 미만이 LOGGED_ONLY 다. 0.60 자체는 POSSIBLE 이어야 한다 -
            // 부등호를 뒤집으면 문턱에 걸친 분석이 통째로 한 칸씩 내려간다.
            assertThat(MistakeConfirmation.decide(0.60, false, 1).status())
                    .isEqualTo(MistakeStatus.POSSIBLE);
            assertThat(MistakeConfirmation.decide(0.80, false, 1).status())
                    .isEqualTo(MistakeStatus.PROBABLE);
            assertThat(MistakeConfirmation.decide(0.90, true, 1).status())
                    .isEqualTo(MistakeStatus.CONFIRMED);
        }
    }

    @Nested
    @DisplayName("확정 조건 (Addendum 21)")
    class Confirmation {

        @Test
        @DisplayName("A: confidence 0.90 이상이고 Judge 가 실패시킨 case 를 인용했다")
        void conditionA() {
            var verdict = MistakeConfirmation.decide(0.95, true, 1);
            assertThat(verdict.status()).isEqualTo(MistakeStatus.CONFIRMED);
            assertThat(verdict.reason()).contains("Judge");
        }

        @Test
        @DisplayName("확신이 높아도 근거가 어긋나면 확정하지 않는다")
        void highConfidenceWithoutEvidenceIsNotConfirmed() {
            // Judge Evidence 가 LLM 보다 우선한다(Addendum 20). 확신에 찬 오분류를
            // 막을 수 있는 유일한 지점이다 - confidence 는 LLM 이 스스로 매긴다.
            var verdict = MistakeConfirmation.decide(0.99, false, 1);
            assertThat(verdict.status()).isEqualTo(MistakeStatus.PROBABLE);
            assertThat(verdict.isConfirmed()).isFalse();
        }

        @Test
        @DisplayName("B: confidence 0.80 이상이고 최근 3문제에서 2회 이상 탐지됐다")
        void conditionB() {
            var verdict = MistakeConfirmation.decide(0.82, false, 2);
            assertThat(verdict.status()).isEqualTo(MistakeStatus.CONFIRMED);
            assertThat(verdict.reason()).contains("2회");
        }

        @Test
        @DisplayName("B 는 case 인용을 요구하지 않는다")
        void conditionBNeedsNoCitation() {
            // 재발 자체가 다른 종류의 근거다. 한 번의 분석이 아니라 여러 제출에
            // 걸친 관측이다.
            assertThat(MistakeConfirmation.decide(0.80, false, 3).isConfirmed()).isTrue();
        }

        @Test
        @DisplayName("재발이 있어도 confidence 가 0.80 미만이면 확정하지 않는다")
        void recurrenceAloneIsNotEnough() {
            // 0.60~0.80 은 "자동 드릴 금지" 구간이다(Addendum 19). 재발했다는
            // 사실만으로 그 구간을 건너뛰면 낮은 확신의 오분류가 반복될 때
            // 오히려 확정된다.
            var verdict = MistakeConfirmation.decide(0.79, false, 3);
            assertThat(verdict.status()).isEqualTo(MistakeStatus.POSSIBLE);
        }

        @Test
        @DisplayName("한 번만 탐지된 것은 재발이 아니다")
        void singleDetectionIsNotRecurrence() {
            assertThat(MistakeConfirmation.decide(0.85, false, 1).isConfirmed()).isFalse();
        }
    }

    @Test
    @DisplayName("범위를 벗어난 confidence 는 거부한다")
    void rejectsOutOfRangeConfidence() {
        // 스키마 밖에서 들어온 값이 그대로 확정 구간에 걸리면 안 된다.
        for (double bad : new double[] {-0.1, 1.5}) {
            assertThatThrownBy(() -> MistakeConfirmation.decide(bad, true, 1))
                    .as("confidence=%s", bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("왜 그 판단인지 항상 남는다")
    void alwaysExplainsItself() {
        // 사용자가 왜 이 드릴을 받았는지 나중에 추적해야 한다.
        for (double confidence : new double[] {0.1, 0.7, 0.85, 0.95}) {
            assertThat(MistakeConfirmation.decide(confidence, true, 2).reason())
                    .as("confidence=%s", confidence)
                    .isNotBlank();
        }
    }
}
