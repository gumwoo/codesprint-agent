package dev.codesprint.support;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * fixture 검증이 실제로 일하는가. 통과하는 것만 보면 아무것도 검사하지 않는 문도 통과한다.
 */
class JudgeResultFixtureTest {

    @Test
    @DisplayName("계약을 지키는 채점 결과는 통과한다")
    void aValidResultPasses() {
        assertThatCode(() -> JudgeResultFixture.requireValid("""
                {"status": "WRONG_ANSWER", "passed": 5, "total": 6, "executionMs": 90,
                 "memoryKb": 20480, "failedCaseId": 4, "stderr": null,
                 "cases": [{"id": 4, "status": "WRONG_ANSWER", "executionMs": 10}]}
                """)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("PR #40 에서 실제로 틀렸던 모양을 막는다")
    void theShapeThatActuallyBrokeIsRejected() {
        // cases 의 필드를 caseId 로 적고 executionMs 를 빠뜨렸다. 반영 단계가 파싱에
        // 실패해 SYSTEM_ERROR 가 됐고, 테스트는 판정 대신 CONTINUE 를 받았다.
        assertThatThrownBy(() -> JudgeResultFixture.requireValid("""
                {"status": "WRONG_ANSWER", "passed": 5, "total": 6, "executionMs": 90,
                 "memoryKb": 20480, "failedCaseId": 4, "stderr": null,
                 "cases": [{"caseId": 4, "status": "WRONG_ANSWER"}]}
                """))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("judge-result.schema.json");
    }

    private static final String RUN_RESULT = """
            {"status": "WRONG_ANSWER", "passed": 0, "total": 1, "executionMs": 40,
             "memoryKb": 10240, "failedCaseId": 1, "stderr": null,
             "cases": [{"id": 1, "status": "WRONG_ANSWER", "executionMs": 40,
                        "input": "1 1\\n1\\n", "expectedOutput": "1\\n",
                        "stdout": "0\\n", "stderr": null}]}
            """;

    @Test
    @DisplayName("실행 결과는 출력이 실린 채로 run 계약을 통과한다")
    void aRunResultWithOutputPassesTheRunContract() {
        // 실제 Worker 가 kind RUN 에 쓰는 모양이다(run_submission.py 의 with_output).
        assertThatCode(() -> JudgeResultFixture.requireValidRun(RUN_RESULT))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("출력이 실린 결과는 제출 채점 계약을 통과하지 못한다")
    void outputNeverPassesAsASubmissionResult() {
        // 대조군이자 기밀성 경계다. 제출 채점 결과에 출력이 실리면 hidden case 의
        // 입력 · 출력이 새는 조합이 생긴다. 두 계약을 나눈 이유가 이것이다.
        assertThatThrownBy(() -> JudgeResultFixture.requireValid(RUN_RESULT))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("judge-result.schema.json");
    }

    @Test
    @DisplayName("실행 결과에서 출력을 빠뜨리면 run 계약이 막는다")
    void aRunResultWithoutOutputIsRejected() {
        // 출력이 없으면 돌려 볼 이유가 없다 - test_judge.py 의 같은 규칙이다.
        assertThatThrownBy(() -> JudgeResultFixture.requireValidRun("""
                {"status": "ACCEPTED", "passed": 1, "total": 1, "executionMs": 40,
                 "memoryKb": 10240, "failedCaseId": null, "stderr": null,
                 "cases": [{"id": 1, "status": "ACCEPTED", "executionMs": 40}]}
                """))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("run-judge-result.schema.json");
    }

    @Test
    @DisplayName("필수 필드를 생략하면 막는다 - 생략은 null 과 다르다")
    void omittingARequiredFieldIsRejected() {
        assertThatThrownBy(() -> JudgeResultFixture.requireValid("""
                {"status": "ACCEPTED", "passed": 6, "total": 6, "executionMs": 90,
                 "memoryKb": 20480, "stderr": null, "cases": []}
                """))
                .isInstanceOf(AssertionError.class);
    }
}
