package dev.codesprint.judge;

import dev.codesprint.learning.domain.JudgeStatus;

/**
 * 채점 결과. 계약: contracts/judge-result.schema.json.
 *
 * <p>Test Case 를 실행하지 못한 경우({@code COMPILE_ERROR} 등) executionMs / memoryKb /
 * failedCaseId 는 null 이다. 0 이 아니다 - "0ms 에 끝났다" 와 "재지 못했다" 는 다르다.
 *
 * @param stderr 호스트 경로를 지운 결과만(Addendum §63).
 */
public record JudgeResult(
        JudgeStatus status,
        int passed,
        int total,
        Integer executionMs,
        Integer memoryKb,
        Integer failedCaseId,
        String stderr) {

    /**
     * 채점 자체가 불가능했을 때. <b>우리 잘못이다.</b>
     *
     * <p>SYSTEM_ERROR 는 Evidence 를 만들지 않고 학습 경로도 바꾸지 않는다
     * (Addendum §12, ADR-0013).
     */
    public static JudgeResult systemError() {
        return new JudgeResult(JudgeStatus.SYSTEM_ERROR, 0, 1, null, null, null, null);
    }
}
