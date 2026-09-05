package dev.codesprint.reviewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 프롬프트를 만들고 모델 응답을 해석하는 부분.
 *
 * <p><b>실제 모델을 부르지 않는다.</b> 여기서 검증할 것은 분석 품질이 아니라
 * "무엇을 보내고, 돌아온 것을 어디까지 받아들이는가" 다. 품질은 별도 평가 하네스의
 * 몫이고, 그 라벨이 {@code mistake_detections} 에 쌓이고 있다.
 *
 * <p>CI 에서 실제 API 를 부르면 안 된다 - 비결정적이고, 느리고, 돈이 들고, 포크한
 * PR 에는 키가 없어 깨진다.
 */
class PromptReviewerTest {

    /** 정해둔 응답을 돌려준다. 마지막으로 받은 프롬프트를 남긴다. */
    static class FakeLlm implements LlmClient {

        String answer;
        RuntimeException failure;
        String lastPrompt;

        @Override
        public String complete(String prompt) {
            lastPrompt = prompt;
            if (failure != null) {
                throw failure;
            }
            return answer;
        }
    }

    private final FakeLlm llm = new FakeLlm();
    private final PromptReviewer reviewer =
            new PromptReviewer(llm, PromptTemplate.load("reviewer-v1"));

    private static ReviewerPort.Request request(String source) {
        return new ReviewerPort.Request(
                "P02_GRID_TRAVERSAL", "격자 순회", source, "WRONG_ANSWER", 4,
                "3 3\n1 1 0\n", "5\n", null, "print(1)",
                List.of("BFS_GRID_TRAVERSAL", "BFS_BASIC"));
    }

    private static final String VALID_ANSWER = """
            {"algorithmSelection": "WRONG",
             "primaryMistake": "BOUNDARY_CHECK",
             "secondaryMistakes": [],
             "confidence": 0.82,
             "affectedSkills": ["BFS_GRID_TRAVERSAL"],
             "failedCaseRefs": [4],
             "conceptIssue": false,
             "implementationIssue": true,
             "explanation": "경계 검사를 빠뜨렸다"}
            """;

    @Nested
    @DisplayName("프롬프트")
    class Prompt {

        @Test
        @DisplayName("Judge 근거를 함께 보낸다")
        void includesJudgeEvidence() {
            llm.answer = VALID_ANSWER;
            reviewer.review(request("DEV_FIXTURE"));

            // 실패한 case 의 입력이 없으면 모델은 코드만 훑고 "있을 법한 실수" 를
            // 나열하게 된다. 그 분석은 실제로 일어난 실패를 설명하지 못한다.
            assertThat(llm.lastPrompt)
                    .contains("P02_GRID_TRAVERSAL")
                    .contains("WRONG_ANSWER")
                    .contains("3 3")
                    .contains("BFS_GRID_TRAVERSAL")
                    .contains("print(1)");
        }

        @Test
        @DisplayName("요청용 계약을 그대로 넣는다")
        void includesTheOutputContract() {
            // 스키마를 손으로 옮겨 적으면 계약과 프롬프트가 갈라지고, 그때 모델은
            // 프롬프트를 따른다.
            llm.answer = VALID_ANSWER;
            reviewer.review(request("DEV_FIXTURE"));

            assertThat(llm.lastPrompt)
                    .contains("failedCaseRefs")
                    .contains("IMPLEMENTATION_MISC");
        }

        @Test
        @DisplayName("점수와 다음 행동을 묻지 않는다")
        void neverAsksForScoresOrActions() {
            // 요청 스키마에 그 자리가 없어야 모델이 만들어낼 수 없다(ADR-0001).
            llm.answer = VALID_ANSWER;
            reviewer.review(request("DEV_FIXTURE"));

            assertThat(llm.lastPrompt)
                    .doesNotContain("\"mastery\"")
                    .doesNotContain("\"nextAction\"")
                    .doesNotContain("\"score\"");
        }

        @Test
        @DisplayName("실서비스 문제에서는 기대 출력을 보내지 않는다")
        void curatedProblemsKeepTheirAnswers() {
            // 정답을 모델 제공자에게 보내는 일이다. 지금 저장소에는 DEV_FIXTURE
            // 밖에 없지만(ADR-0008), CURATED 가 들어오는 날 이 자리가 닫혀야 한다.
            llm.answer = VALID_ANSWER;
            reviewer.review(request("CURATED"));

            assertThat(llm.lastPrompt).doesNotContain("5\n```");
            assertThat(llm.lastPrompt).contains("정답은 제공하지 않는다");
        }

        @Test
        @DisplayName("채우지 못한 자리가 남으면 보내지 않는다")
        void refusesToSendAnUnfilledPrompt() {
            // 그대로 두면 {{sourceCode}} 같은 문자열이 모델에게 가고, 모델은 그것을
            // 코드로 읽으려 한다.
            assertThatThrownBy(() -> PromptTemplate.load("reviewer-v1")
                    .render(Map.of("problemCode", "P02_GRID_TRAVERSAL")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sourceCode");
        }

        @Test
        @DisplayName("프롬프트 버전이 파일 이름이다")
        void versionIsTheFileName() {
            // 이 값이 그대로 기록에 남는다. 같은 이름으로 내용을 바꾸면 이전
            // 라벨과 이후 라벨이 섞인다.
            assertThat(reviewer.promptVersion()).isEqualTo("reviewer-v1");
        }
    }

    @Nested
    @DisplayName("응답 해석")
    class Parsing {

        @Test
        @DisplayName("계약을 지킨 응답을 읽는다")
        void readsAValidAnswer() {
            llm.answer = VALID_ANSWER;

            Optional<ReviewerOutput> output = reviewer.review(request("DEV_FIXTURE"));

            assertThat(output).isPresent();
            assertThat(output.get().primaryMistake()).isEqualTo("BOUNDARY_CHECK");
            assertThat(output.get().confidence()).isEqualTo(0.82);
            assertThat(output.get().failedCaseRefs()).containsExactly(4);
        }

        @Test
        @DisplayName("코드펜스로 감싼 응답도 읽는다")
        void unwrapsCodeFences() {
            // 모델이 ```json 으로 감싸 답하는 경우가 흔하다.
            llm.answer = "```json\n" + VALID_ANSWER + "\n```";

            assertThat(reviewer.review(request("DEV_FIXTURE"))).isPresent();
        }

        @Test
        @DisplayName("JSON 이 아니면 버린다")
        void rejectsNonJson() {
            llm.answer = "죄송합니다. 이 코드는 경계 검사를 빠뜨린 것 같습니다.";

            assertThat(reviewer.review(request("DEV_FIXTURE"))).isEmpty();
        }

        @Test
        @DisplayName("계약을 어긴 응답은 버린다 - 고쳐 쓰지 않는다")
        void rejectsContractViolations() {
            // 빠진 필드를 기본값으로 채우면 "모델이 답하지 않은 것" 과 "모델이
            // 그렇게 답한 것" 을 구분할 수 없게 된다.
            llm.answer = """
                    {"primaryMistake": "BOUNDARY_CHECK", "confidence": 0.9}
                    """;

            assertThat(reviewer.review(request("DEV_FIXTURE"))).isEmpty();
        }

        @Test
        @DisplayName("근거 없는 분석은 버린다")
        void rejectsAnalysisWithoutEvidence() {
            // failedCaseRefs 는 minItems: 1 이다(ADR-0004).
            llm.answer = VALID_ANSWER.replace("[4]", "[]");

            assertThat(reviewer.review(request("DEV_FIXTURE"))).isEmpty();
        }

        @Test
        @DisplayName("스키마에 없는 Mistake 는 버린다")
        void rejectsUnknownMistakeCodes() {
            llm.answer = VALID_ANSWER.replace("BOUNDARY_CHECK", "MADE_UP_MISTAKE");

            assertThat(reviewer.review(request("DEV_FIXTURE"))).isEmpty();
        }

        @Test
        @DisplayName("점수를 덧붙여 답해도 받아들이지 않는다")
        void rejectsExtraFields() {
            // additionalProperties: false 다. 모델이 mastery 를 끼워 넣으면 그
            // 응답 전체를 버린다 - 한 번 받아주면 다음부터 그 값을 쓰게 된다.
            llm.answer = VALID_ANSWER.replace("\"conceptIssue\"", "\"mastery\": 0.9, \"conceptIssue\"");

            assertThat(reviewer.review(request("DEV_FIXTURE"))).isEmpty();
        }

        @Test
        @DisplayName("모델을 부르지 못해도 예외가 새지 않는다")
        void survivesAnUnavailableModel() {
            // 제출 처리는 계속되어야 한다. Reviewer 는 판정도 점수도 만들지 않는다.
            llm.failure = new LlmClient.LlmUnavailable("timeout", null);

            assertThat(reviewer.review(request("DEV_FIXTURE"))).isEmpty();
        }
    }
}
