package dev.codesprint.judge;

import dev.codesprint.learning.domain.JudgeStatus;

/**
 * 채점 결과. 계약: contracts/judge-result.schema.json.
 *
 * <p>Test Case 를 실행하지 못한 경우({@code COMPILE_ERROR} 등) executionMs / memoryKb /
 * failedCaseId 는 null 이다. 0 이 아니다 - "0ms 에 끝났다" 와 "재지 못했다" 는 다르다.
 *
 * @param stderr 호스트 경로를 지운 결과만(Addendum §63).
 * @param passedCaseIds 통과한 case.
 * @param failedCaseIds 통과하지 못한 case. <b>실행되지 않은 case 는 여기 없다</b> -
 *     비싼 실패에서는 거기서 멈추므로(ADR-0015) 둘의 합이 total 보다 작을 수 있다.
 *     "실패했다" 와 "거기까지 가지 못했다" 를 섞으면 앞에서 멈춘 제출이 뒤쪽 case 의
 *     성격 태그까지 만족한 것으로 읽힌다.
 */
public record JudgeResult(
        JudgeStatus status,
        int passed,
        int total,
        Integer executionMs,
        Integer memoryKb,
        Integer failedCaseId,
        String stderr,
        java.util.Set<Integer> passedCaseIds,
        java.util.Set<Integer> failedCaseIds) {

    public JudgeResult {
        passedCaseIds = java.util.Set.copyOf(passedCaseIds);
        failedCaseIds = java.util.Set.copyOf(failedCaseIds);
    }

    /**
     * 채점 자체가 불가능했을 때. <b>우리 잘못이다.</b>
     *
     * <p>SYSTEM_ERROR 는 Evidence 를 만들지 않고 학습 경로도 바꾸지 않는다
     * (Addendum §12, ADR-0013).
     */
    public static JudgeResult systemError() {
        return new JudgeResult(JudgeStatus.SYSTEM_ERROR, 0, 1, null, null, null, null,
                java.util.Set.of(), java.util.Set.of());
    }
}
