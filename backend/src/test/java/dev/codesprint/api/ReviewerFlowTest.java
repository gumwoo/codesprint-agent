package dev.codesprint.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import dev.codesprint.judge.JudgeJobRepository;
import dev.codesprint.judge.JudgeJobRow;
import dev.codesprint.learning.persistence.UserRepository;
import dev.codesprint.learning.persistence.UserRow;
import dev.codesprint.learning.service.JudgeResultPoller;
import dev.codesprint.reviewer.MistakeDetectionRepository;
import dev.codesprint.reviewer.ReviewerOutput;
import dev.codesprint.reviewer.ReviewerPort;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 실패한 제출이 Reviewer 를 거쳐 Mistake 확정과 자동 드릴까지 가는지 본다.
 * 정본: Addendum §19~21 / ADR-0004 / ADR-0014.
 *
 * <pre>
 *   실패 -&gt; Reviewer -&gt; 검증 -&gt; 확정 -&gt; MICRO_DRILL
 * </pre>
 *
 * <p><b>Reviewer 는 가짜다.</b> 여기서 검증할 것은 모델의 분석 품질이 아니라
 * "그 분석을 어디까지 믿고 무엇을 하는가" 다. 품질은 별도 평가 하네스의 몫이고,
 * 그 라벨이 {@code mistake_detections} 에 쌓이도록 만드는 것이 이 PR 의 일부다.
 */
@SpringBootTest
class ReviewerFlowTest {

    private static final String EXTERNAL_URL = System.getenv("TEST_DB_URL");
    private static PostgreSQLContainer<?> container;

    static {
        if (EXTERNAL_URL == null) {
            container = new PostgreSQLContainer<>("postgres:16-alpine");
            container.start();
        }
    }

    /** 정해둔 분석을 그대로 돌려준다. */
    static class FakeReviewer implements ReviewerPort {

        ReviewerOutput scripted;
        RuntimeException failure;
        int calls;

        @Override
        public Optional<ReviewerOutput> review(Request request) {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return Optional.ofNullable(scripted);
        }

        @Override
        public String promptVersion() {
            return "test-v1";
        }

        void reset() {
            scripted = null;
            failure = null;
            calls = 0;
        }
    }

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        FakeReviewer fakeReviewer() {
            return new FakeReviewer();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        if (EXTERNAL_URL != null) {
            registry.add("spring.datasource.url", () -> EXTERNAL_URL);
            registry.add("spring.datasource.username",
                    () -> System.getenv().getOrDefault("TEST_DB_USER", "codesprint"));
            registry.add("spring.datasource.password",
                    () -> System.getenv().getOrDefault("TEST_DB_PASSWORD", "codesprint"));
        } else {
            registry.add("spring.datasource.url", container::getJdbcUrl);
            registry.add("spring.datasource.username", container::getUsername);
            registry.add("spring.datasource.password", container::getPassword);
        }
        registry.add("codesprint.problems-dir", () -> repoRoot().resolve("problems").toString());
        registry.add("codesprint.repo-root", () -> repoRoot().toString());
        registry.add("codesprint.judge.apply-interval-ms", () -> "3600000");
    }

    private static Path repoRoot() {
        String root = System.getProperty("codesprint.repoRoot");
        if (root == null) {
            throw new IllegalStateException("codesprint.repoRoot 시스템 프로퍼티가 없다.");
        }
        return Path.of(root);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository users;

    @Autowired
    private JudgeJobRepository jobs;

    @Autowired
    private JudgeResultPoller poller;

    @Autowired
    private MistakeDetectionRepository detections;

    @Autowired
    private FakeReviewer reviewer;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mvc;
    private Long userId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        userId = users.save(new UserRow(
                "rev-" + System.nanoTime() + "@codesprint.dev", "리뷰테스트")).id();
        reviewer.reset();
    }

    /** 실패한 case 4번을 근거로 드는 분석. 자동 드릴 대상 Mistake 다. */
    private static ReviewerOutput analysis(String mistake, double confidence, int citedCase) {
        return new ReviewerOutput("WRONG", mistake, List.of(), confidence,
                List.of("BFS_GRID_TRAVERSAL"), List.of(citedCase), false, true,
                "경계 검사를 빠뜨렸다");
    }

    /**
     * case 를 하나도 실행하지 못한 채점. 실패의 모양이 없으므로 §21-A 는 성립하지
     * 않고, 이 파일의 다른 테스트들이 보는 것은 §21-B(재발)다.
     */
    private long submitAndJudge(String problemCode, String judgeStatus, Integer failedCaseId)
            throws Exception {
        return submitAndJudge(problemCode, judgeStatus, failedCaseId, "[]");
    }

    /**
     * @param casesJson 실행한 case 의 개별 결과. 여기 없는 case 는 <b>실행되지 않은
     *     것</b>이지 실패한 것이 아니다.
     */
    private long submitAndJudge(String problemCode, String judgeStatus, Integer failedCaseId,
            String casesJson) throws Exception {
        String body = """
                {"userId": %d, "language": "PYTHON", "sourceCode": "print(1)",
                 "hintLevel": 0, "solutionViewed": false, "solveSeconds": 120}
                """.formatted(userId);
        String json = mvc.perform(post("/api/problems/{code}/submit", problemCode)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();
        long submissionId = MAPPER.readTree(json).get("submissionId").asLong();

        JudgeJobRow job = jobs.findBySubmissionId(submissionId).orElseThrow();
        String result = """
                {"status": "%s", "passed": 0, "total": 5, "executionMs": 90,
                 "memoryKb": 20480, "failedCaseId": %s, "stderr": null, "cases": %s}
                """.formatted(judgeStatus, failedCaseId == null ? "null" : failedCaseId,
                casesJson);
        jdbc.update("UPDATE judge_jobs SET status = 'DONE', result = ?::jsonb WHERE id = ?",
                result, job.id());

        poller.applyFinishedJobs();
        return submissionId;
    }

    /**
     * 이 Skill 을 이미 배우고 있는 상태로 만든다.
     *
     * <p>신규 사용자에게는 선수 조건이 전부 미충족이라 <b>어떤 실패든 CHANGE_SKILL
     * 이 먼저 나온다</b>(PR #6~7). 그건 맞는 동작이므로, 드릴 분기를 보려면 그
     * Skill 을 이미 시작한 상태여야 한다.
     */
    private void alreadyLearning(String problemCode) throws Exception {
        ReviewerOutput scripted = reviewer.scripted;
        reviewer.scripted = null;          // 준비 제출에서는 분석을 남기지 않는다
        submitAndJudge(problemCode, "WRONG_ANSWER", 4);
        reviewer.scripted = scripted;
        reviewer.calls = 0;
    }

    private JsonNode resultOf(long submissionId) throws Exception {
        String json = mvc.perform(get("/api/submissions/{id}", submissionId))
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(json);
    }

    @Test
    @DisplayName("서로 다른 문제에서 재발하면 확정되고 자동 드릴로 이어진다")
    void recurringMistakeTriggersDrill() throws Exception {
        // 확정 조건 B. 지금 확정이 일어나는 유일한 경로다 - A 의 독립 근거가 아직 없다.
        reviewer.scripted = analysis("BOUNDARY_CHECK", 0.85, 4);
        alreadyLearning("P02_GRID_TRAVERSAL");

        // 다른 문제에서 같은 실수. 두 문제에서 나왔으므로 재발이다.
        submitAndJudge("P09_BFS_VARIANT_A", "WRONG_ANSWER", 4);
        long submissionId = submitAndJudge("P02_GRID_TRAVERSAL", "WRONG_ANSWER", 4);
        JsonNode result = resultOf(submissionId).get("result");

        JsonNode review = result.get("review");
        assertThat(review.isNull()).as("Reviewer 를 불렀다").isFalse();
        assertThat(review.get("primaryMistake").asText()).isEqualTo("BOUNDARY_CHECK");
        assertThat(review.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(result.get("promptVersion").asText()).isEqualTo("test-v1");

        // 자동 드릴 대상은 커리큘럼이 정한다 - 코드에 하드코딩하지 않는다.
        JsonNode action = result.get("nextAction");
        assertThat(action.get("type").asText()).isEqualTo("MICRO_DRILL");
        assertThat(action.get("targetSkill").asText()).isEqualTo("GRID_BOUNDARY_CHECK");
    }

    /**
     * P02 의 case 2 는 {@code cases.json} 에서 BOUNDARY_CHECK 를 겨냥한다.
     * 그것이 실패하고, 겨냥하지 않는 case 3 이 통과한 모양.
     */
    private static final String BOUNDARY_SHAPED = """
            [{"id": 1, "status": "WRONG_ANSWER", "executionMs": 10},
             {"id": 2, "status": "RUNTIME_ERROR", "executionMs": 10},
             {"id": 3, "status": "ACCEPTED", "executionMs": 10}]
            """;

    @Test
    @DisplayName("겨냥한 case 가 실패하고 대조군이 통과하면 처음 보는 실수도 확정된다")
    void caseShapeCorroboratesOnFirstSight() throws Exception {
        // 확정 조건 A. 재발을 기다리지 않는다 - 실패의 모양이 Reviewer 밖의 근거다.
        reviewer.scripted = analysis("BOUNDARY_CHECK", 0.95, 4);
        alreadyLearning("P02_GRID_TRAVERSAL");

        long submissionId = submitAndJudge(
                "P02_GRID_TRAVERSAL", "RUNTIME_ERROR", 2, BOUNDARY_SHAPED);
        JsonNode result = resultOf(submissionId).get("result");

        assertThat(result.get("review").get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(result.get("nextAction").get("type").asText()).isEqualTo("MICRO_DRILL");
        assertThat(result.get("nextAction").get("targetSkill").asText())
                .isEqualTo("GRID_BOUNDARY_CHECK");
    }

    @Test
    @DisplayName("뒷받침돼도 confidence 가 0.90 미만이면 확정하지 않는다")
    void corroborationAloneDoesNotConfirm() throws Exception {
        // 태그는 "그 실수와 무관한 실패는 아니다" 까지만 말한다. 그 case 를
        // 실패시키는 다른 원인이 얼마든지 있으므로 확신 조건을 함께 요구한다.
        reviewer.scripted = analysis("BOUNDARY_CHECK", 0.85, 4);
        alreadyLearning("P02_GRID_TRAVERSAL");

        long submissionId = submitAndJudge(
                "P02_GRID_TRAVERSAL", "RUNTIME_ERROR", 2, BOUNDARY_SHAPED);

        assertThat(resultOf(submissionId).get("result").get("review").get("status").asText())
                .isEqualTo("PROBABLE");
    }

    @Test
    @DisplayName("겨냥한 case 까지 가지 못했으면 뒷받침이 아니다")
    void unexecutedProbeIsNotAFailure() throws Exception {
        // case 1 에서 멈췄다. case 2 는 실패한 것이 아니라 실행되지 않았다.
        // 둘을 섞으면 앞에서 멈춘 제출이 뒤쪽 태그까지 만족한 것으로 읽힌다.
        reviewer.scripted = analysis("BOUNDARY_CHECK", 0.95, 4);
        alreadyLearning("P02_GRID_TRAVERSAL");

        long submissionId = submitAndJudge("P02_GRID_TRAVERSAL", "TIME_LIMIT", 1,
                """
                [{"id": 1, "status": "TIME_LIMIT", "executionMs": 2000}]
                """);

        assertThat(resultOf(submissionId).get("result").get("review").get("status").asText())
                .isEqualTo("PROBABLE");
    }

    @Test
    @DisplayName("전부 실패하면 대조군이 없어 뒷받침이 아니다")
    void everythingFailingCorroboratesNothing() throws Exception {
        // 통과한 case 가 하나도 없으면 어떤 태그든 만족한다 - "무엇이 틀렸든
        // 그 Mistake" 가 된다.
        reviewer.scripted = analysis("BOUNDARY_CHECK", 0.99, 4);
        alreadyLearning("P02_GRID_TRAVERSAL");

        long submissionId = submitAndJudge("P02_GRID_TRAVERSAL", "WRONG_ANSWER", 1,
                """
                [{"id": 1, "status": "WRONG_ANSWER", "executionMs": 10},
                 {"id": 2, "status": "WRONG_ANSWER", "executionMs": 10},
                 {"id": 3, "status": "WRONG_ANSWER", "executionMs": 10}]
                """);

        assertThat(resultOf(submissionId).get("result").get("review").get("status").asText())
                .isEqualTo("PROBABLE");
    }

    @Test
    @DisplayName("겨냥한 case 가 없는 실수는 모양으로 뒷받침되지 않는다")
    void untaggedMistakeIsNotCorroborated() throws Exception {
        // P02 는 NO_VISITED 를 겨냥한 case 가 없다. 태그가 없는 것은
        // "뒷받침한다" 가 아니라 "말할 수 없다" 다.
        reviewer.scripted = analysis("NO_VISITED", 0.99, 4);
        alreadyLearning("P02_GRID_TRAVERSAL");

        long submissionId = submitAndJudge(
                "P02_GRID_TRAVERSAL", "RUNTIME_ERROR", 2, BOUNDARY_SHAPED);

        assertThat(resultOf(submissionId).get("result").get("review").get("status").asText())
                .isEqualTo("PROBABLE");
    }

    @Test
    @DisplayName("실패한 case 를 그대로 인용해도 그것만으로는 확정되지 않는다")
    void echoingTheFailedCaseIsNotEvidence() throws Exception {
        // 요청에 failedCaseId = 4 를 넣어 보내므로, 모델은 받은 번호를 그대로
        // 돌려주기만 하면 된다. case 4 가 실제로 BOUNDARY_CHECK 를 보여주는
        // case 인지는 아무도 확인하지 않았다 - 이것을 근거로 인정하면
        // "확신만으로 확정한다" 와 사실상 같아진다(ADR-0014).
        reviewer.scripted = analysis("BOUNDARY_CHECK", 0.99, 4);
        alreadyLearning("P02_GRID_TRAVERSAL");

        long submissionId = submitAndJudge("P02_GRID_TRAVERSAL", "WRONG_ANSWER", 4);
        JsonNode result = resultOf(submissionId).get("result");

        assertThat(result.get("review").get("status").asText()).isEqualTo("PROBABLE");
        assertThat(result.get("nextAction").get("type").asText())
                .as("확정되지 않았으므로 드릴이 아니다")
                .isNotEqualTo("MICRO_DRILL");
    }

    @Test
    @DisplayName("같은 문제에서 반복한 것은 재발이 아니다")
    void repeatingTheSameProblemIsNotRecurrence() throws Exception {
        // "최근 3문제에서 2회" 는 문제 단위다. 같은 문제를 두 번 틀린 것을 재발로
        // 세면 한 문제에서 고전하는 사용자가 곧바로 확정을 받는다.
        reviewer.scripted = analysis("BOUNDARY_CHECK", 0.85, 4);
        alreadyLearning("P02_GRID_TRAVERSAL");

        submitAndJudge("P02_GRID_TRAVERSAL", "WRONG_ANSWER", 4);
        long submissionId = submitAndJudge("P02_GRID_TRAVERSAL", "WRONG_ANSWER", 4);

        assertThat(resultOf(submissionId).get("result").get("review").get("status").asText())
                .isEqualTo("PROBABLE");
    }

    @Test
    @DisplayName("사이에 깨끗한 문제가 끼면 창 밖의 실수는 세지 않는다")
    void oldMistakeOutsideTheWindowIsNotCounted() throws Exception {
        // P02 에서 한 번 탐지되고, 그 뒤로 다른 문제 세 개를 푼다. 실제 최근
        // 3문제 안에는 P02 가 없으므로 이번 것과 묶이면 안 된다.
        //
        // 탐지 기록에서 창을 뽑으면 깨끗한 문제들이 보이지 않아, 아주 오래된
        // 실수가 현재 실수와 나란히 서서 2회가 된다.
        reviewer.scripted = analysis("BOUNDARY_CHECK", 0.85, 4);
        alreadyLearning("P02_GRID_TRAVERSAL");
        submitAndJudge("P02_GRID_TRAVERSAL", "WRONG_ANSWER", 4);   // 여기서 1회

        reviewer.scripted = null;                                   // 깨끗한 문제 셋
        submitAndJudge("P03_CONNECTED_COMPONENT", "WRONG_ANSWER", 4);
        submitAndJudge("P04_AREA_SIZE", "WRONG_ANSWER", 4);
        submitAndJudge("P10_BFS_REVIEW", "WRONG_ANSWER", 4);

        reviewer.scripted = analysis("BOUNDARY_CHECK", 0.85, 4);
        long submissionId = submitAndJudge("P09_BFS_VARIANT_A", "WRONG_ANSWER", 4);

        assertThat(resultOf(submissionId).get("result").get("review").get("status").asText())
                .as("최근 3문제 안에서는 이번 1회뿐이다")
                .isEqualTo("PROBABLE");
    }

    @Test
    @DisplayName("낮은 confidence 는 기록만 하고 경로를 바꾸지 않는다")
    void lowConfidenceIsLoggedOnly() throws Exception {
        reviewer.scripted = analysis("BOUNDARY_CHECK", 0.42, 4);
        alreadyLearning("P02_GRID_TRAVERSAL");

        long submissionId = submitAndJudge("P02_GRID_TRAVERSAL", "WRONG_ANSWER", 4);
        JsonNode result = resultOf(submissionId).get("result");

        assertThat(result.get("review").get("status").asText()).isEqualTo("LOGGED_ONLY");
        assertThat(result.get("nextAction").get("type").asText()).isNotEqualTo("MICRO_DRILL");
        // 그래도 남는다. 재발을 세려면 남아 있어야 하고, 나중에 정확도를 재는
        // 라벨이 되기도 한다.
        assertThat(detections.findBySubmissionId(submissionId)).hasSize(1);
    }

    @Test
    @DisplayName("Reviewer 가 부여할 수 없는 Mistake 를 주장하면 분석을 버린다")
    void systemAssignedMistakeIsRejected() throws Exception {
        // SYNTAX_ERROR 는 assigned_by: SYSTEM 이다. 스키마 enum 에도 없지만,
        // 스키마 검증을 거치지 않는 경로가 생길 수 있어 여기서도 막는다.
        reviewer.scripted = analysis("SYNTAX_ERROR", 0.99, 4);

        long submissionId = submitAndJudge("P02_GRID_TRAVERSAL", "WRONG_ANSWER", 4);
        JsonNode result = resultOf(submissionId).get("result");

        assertThat(result.get("review").isNull()).as("분석을 버린다").isTrue();
        assertThat(result.get("promptVersion").isNull()).isTrue();
        assertThat(detections.findBySubmissionId(submissionId)).isEmpty();
        // 판정과 mastery 는 그대로 처리된다. Reviewer 가 없어도 나머지는 돈다.
        assertThat(result.get("judge").get("status").asText()).isEqualTo("WRONG_ANSWER");
        assertThat(result.get("skillUpdates")).isNotEmpty();
    }

    @Test
    @DisplayName("커리큘럼에 없는 Skill 을 지목하면 분석을 버린다")
    void unknownSkillIsRejected() throws Exception {
        reviewer.scripted = new ReviewerOutput("WRONG", "BOUNDARY_CHECK", List.of(), 0.95,
                List.of("NO_SUCH_SKILL"), List.of(4), false, true, null);

        long submissionId = submitAndJudge("P02_GRID_TRAVERSAL", "WRONG_ANSWER", 4);

        assertThat(resultOf(submissionId).get("result").get("review").isNull()).isTrue();
    }

    @Test
    @DisplayName("Reviewer 가 죽어도 제출 처리는 끝난다")
    void reviewerFailureDoesNotBlockTheSubmission() throws Exception {
        reviewer.failure = new IllegalStateException("모델이 응답하지 않는다");

        long submissionId = submitAndJudge("P02_GRID_TRAVERSAL", "WRONG_ANSWER", 4);
        JsonNode status = resultOf(submissionId);

        assertThat(status.get("state").asText()).isEqualTo("COMPLETE");
        assertThat(status.get("result").get("review").isNull()).isTrue();
        assertThat(status.get("result").get("nextAction").get("type").asText()).isNotBlank();
    }

    @Test
    @DisplayName("정답과 문법 오류에서는 Reviewer 를 부르지 않는다")
    void reviewerIsNotCalledWithoutFailedCases() throws Exception {
        reviewer.scripted = analysis("BOUNDARY_CHECK", 0.95, 4);

        // ACCEPTED: 설명할 실패가 없다.
        submitAndJudge("P02_GRID_TRAVERSAL", "ACCEPTED", null);
        // COMPILE_ERROR: case 를 하나도 실행하지 못해 근거로 들 것이 없다(ADR-0004).
        submitAndJudge("P02_GRID_TRAVERSAL", "COMPILE_ERROR", null);
        // SYSTEM_ERROR: 우리 잘못이다.
        submitAndJudge("P02_GRID_TRAVERSAL", "SYSTEM_ERROR", null);

        assertThat(reviewer.calls).isZero();
    }

    @Test
    @DisplayName("응답이 계약을 지킨다 - review 가 채워진 경우")
    void responseWithReviewMatchesContract() throws Exception {
        reviewer.scripted = new ReviewerOutput("WRONG", "BOUNDARY_CHECK",
                List.of("INDEX_ERROR"), 0.95, List.of("BFS_GRID_TRAVERSAL"), List.of(4),
                true, true, "경계 검사를 빠뜨렸다");

        long submissionId = submitAndJudge("P02_GRID_TRAVERSAL", "WRONG_ANSWER", 4);
        JsonNode status = resultOf(submissionId);

        JsonSchema schema = JsonSchemaFactory
                .builder(JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012))
                .schemaMappers(mappers -> mappers.mapPrefix(
                        "https://codesprint.dev/contracts/",
                        repoRoot().resolve("contracts").toUri().toString()))
                .build()
                .getSchema(Files.readString(repoRoot().resolve("contracts")
                        .resolve("submission-status.schema.json")));

        assertThat(schema.validate(status)).as("계약 위반").isEmpty();
        assertThat(status.get("result").get("review").get("secondaryMistakes"))
                .hasSize(1);
    }
}
