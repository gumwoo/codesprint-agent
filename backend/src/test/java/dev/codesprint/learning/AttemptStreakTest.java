package dev.codesprint.learning;

import static org.assertj.core.api.Assertions.assertThat;

import dev.codesprint.learning.domain.AttemptStreak;
import dev.codesprint.learning.domain.JudgeStatus;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * "지금 이 문제에서 연속으로 몇 번 막혀 있는가".
 *
 * <p>제출 행을 그냥 세면 세 가지가 섞인다 - 우리 장애, 문법 오류, 그리고 몇 달 전
 * 기록. 그 값이 {@code sameProblemAttempts} 로 들어가면 사용자가 왜 갑자기
 * REVIEW_CONCEPT 로 갔는지 추적할 수 없게 된다.
 */
class AttemptStreakTest {

    @Test
    @DisplayName("제출이 없으면 0")
    void noSubmissions() {
        assertThat(AttemptStreak.consecutiveFailures(List.of())).isZero();
    }

    @Test
    @DisplayName("연속 실패는 그대로 센다")
    void countsConsecutiveFailures() {
        assertThat(AttemptStreak.consecutiveFailures(List.of(
                JudgeStatus.WRONG_ANSWER, JudgeStatus.RUNTIME_ERROR, JudgeStatus.TIME_LIMIT)))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("마지막 정답에서 끊는다")
    void acceptedResetsTheStreak() {
        // 앞의 실패는 이미 해소된 것이다. 오늘 다시 풀다 한 번 틀렸다고 해서
        // 예전 실패까지 합쳐 3회로 보면 안 된다.
        assertThat(AttemptStreak.consecutiveFailures(List.of(
                JudgeStatus.WRONG_ANSWER,      // 이번
                JudgeStatus.ACCEPTED,          // 여기서 끊긴다
                JudgeStatus.WRONG_ANSWER,
                JudgeStatus.WRONG_ANSWER)))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("채점 실패는 세지 않는다")
    void systemErrorIsNotCounted() {
        // 우리 잘못이다. 즉시 경로를 바꾸지 않기로 해놓고 나중 제출에서 세면
        // 시간을 두고 바꾸는 것과 같다.
        assertThat(AttemptStreak.consecutiveFailures(List.of(
                JudgeStatus.WRONG_ANSWER, JudgeStatus.SYSTEM_ERROR, JudgeStatus.SYSTEM_ERROR)))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("문법 오류도 세지 않는다")
    void compileErrorIsNotCounted() {
        // Evidence 를 만들지 않기로 한 판정이(Addendum 12) Decision 에는 영향을 주면
        // 그 결정이 어긋난다.
        assertThat(AttemptStreak.consecutiveFailures(List.of(
                JudgeStatus.WRONG_ANSWER, JudgeStatus.COMPILE_ERROR, JudgeStatus.WRONG_ANSWER)))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("세지 않는 판정이 연속을 끊지도 않는다")
    void neutralStatusesDoNotResetEither() {
        // 끊어버리면 그 사이의 진짜 실패까지 지워진다. WA 두 번 사이에 채점이
        // 한 번 실패했다고 해서 "처음 막혔다" 가 되면 안 된다.
        assertThat(AttemptStreak.consecutiveFailures(List.of(
                JudgeStatus.WRONG_ANSWER, JudgeStatus.SYSTEM_ERROR, JudgeStatus.WRONG_ANSWER,
                JudgeStatus.WRONG_ANSWER)))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("아직 끝나지 않은 판정은 세지 않는다")
    void pendingStatusesAreIgnored() {
        assertThat(AttemptStreak.consecutiveFailures(List.of(
                JudgeStatus.QUEUED, JudgeStatus.RUNNING, JudgeStatus.WRONG_ANSWER)))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("정답이 가장 최근이면 0")
    void latestAcceptedMeansZero() {
        assertThat(AttemptStreak.consecutiveFailures(List.of(
                JudgeStatus.ACCEPTED, JudgeStatus.WRONG_ANSWER)))
                .isZero();
    }
}
