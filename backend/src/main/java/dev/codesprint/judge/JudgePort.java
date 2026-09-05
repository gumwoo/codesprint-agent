package dev.codesprint.judge;

import dev.codesprint.learning.domain.JudgeStatus;

/**
 * 제출을 채점한다. <b>구현은 Java 밖에 있다.</b>
 *
 * <p>샌드박스와 하네스는 Python 이 맡는다(ADR-0011). 여기서 인터페이스만 두는 이유는
 * 두 가지다.
 *
 * <ol>
 *   <li>서비스 계층이 채점 <b>방식</b>에 묶이지 않는다. 지금은 프로세스를 직접 부르지만
 *       다음 단계는 큐를 통한 Judge Worker 다 - 그때 이 인터페이스만 다시 구현한다.
 *   <li>테스트가 Docker 없이 돈다. 오케스트레이션이 검증해야 하는 것은 "샌드박스가
 *       안전한가" 가 아니라 "판정을 받아 Evidence 로 옮기고 다음 행동을 정하는가" 다.
 *       샌드박스 격리는 {@code judge/tests/test_judge.py} 가 따로 검증한다.
 * </ol>
 */
public interface JudgePort {

    /**
     * 채점 결과. 계약: contracts/judge-result.schema.json.
     *
     * <p>Test Case 를 실행하지 못한 경우({@code COMPILE_ERROR} 등) executionMs /
     * memoryKb / failedCaseId 는 null 이다. 0 이 아니다 - "0ms 에 끝났다" 와
     * "재지 못했다" 는 다르다.
     *
     * @param stderr 호스트 경로를 지운 결과만(Addendum §63).
     */
    record Result(
            JudgeStatus status,
            int passed,
            int total,
            Integer executionMs,
            Integer memoryKb,
            Integer failedCaseId,
            String stderr) {
    }

    /**
     * @param problemCode 채점할 문제
     * @param sourceCode 사용자가 낸 코드. <b>신뢰할 수 없는 입력이다</b> - 읽거나
     *     실행하지 않고 그대로 샌드박스에 넘긴다.
     */
    Result judge(String problemCode, String sourceCode);
}
