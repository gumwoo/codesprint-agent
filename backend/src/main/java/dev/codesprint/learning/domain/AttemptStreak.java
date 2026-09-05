package dev.codesprint.learning.domain;

import java.util.List;

/**
 * "이 문제에서 지금 연속으로 몇 번 막혀 있는가" 를 센다.
 *
 * <p>Decision Engine 의 {@code sameProblemAttempts} 가 쓰는 값이다. Addendum §43 의
 * "같은 문제 3회 실패면 개념부터" 는 <b>지금 막혀 있는 상태</b>를 말한다.
 *
 * <p>처음에는 제출 행을 그냥 세었다. 그러면 세 가지가 함께 섞인다.
 *
 * <ul>
 *   <li><b>우리 장애가 나중 제출의 경로를 바꾼다.</b> SYSTEM_ERROR 두 번 뒤의 첫 WA 가
 *       3회째로 세어져 REVIEW_CONCEPT 로 간다. 그 순간에는 CONTINUE 를 냈으므로
 *       "즉시 바꾸지 않는다" 는 지켰지만, 시간을 두고 바꾼 셈이다.
 *   <li><b>문법 오류가 개념 실패로 세어진다.</b> Evidence 를 만들지 않기로 한 판정이
 *       Decision 에는 그대로 영향을 준다(Addendum §12 와 어긋난다).
 *   <li><b>몇 달 전 기록이 계속 남는다.</b> 예전에 두 번 낸 문제를 오늘 다시 풀다
 *       한 번 틀리면 곧바로 3회째다.
 * </ul>
 *
 * <p>그래서 <b>마지막 정답 이후</b>의, <b>Evidence 를 만드는 실패</b>만 센다.
 */
public final class AttemptStreak {

    private AttemptStreak() {
    }

    /**
     * @param newestFirst 이 문제의 판정들. <b>최근 것이 앞</b>이며 이번 제출을 포함한다.
     * @return 마지막 정답 이후의 연속 실패 횟수. 정답이면 0 이다.
     */
    public static int consecutiveFailures(List<JudgeStatus> newestFirst) {
        int failures = 0;
        for (JudgeStatus status : newestFirst) {
            // 한 번이라도 풀었으면 거기서 끊는다. 그 앞의 실패는 이미 해소된 것이다.
            if (status == JudgeStatus.ACCEPTED) {
                return failures;
            }
            // 채점 실패와 문법 오류, 그리고 아직 끝나지 않은 판정은 세지도 끊지도
            // 않는다. "이 사람이 이 문제에서 막혀 있는가" 에 대해 아무것도 말해주지
            // 않기 때문이다 - 끊어버리면 그 사이의 진짜 실패까지 지워진다.
            if (!status.producesEvidence()) {
                continue;
            }
            failures++;
        }
        return failures;
    }
}
