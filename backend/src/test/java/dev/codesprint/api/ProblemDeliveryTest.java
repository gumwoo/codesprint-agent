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
 * 결정이 <b>실제 문제</b>로 이어지는지 본다. 이것이 붙으면서 학습 루프가 처음으로 닫힌다.
 *
 * <pre>
 *   제출 -&gt; 채점 -&gt; Evidence -&gt; mastery -&gt; 결정 -&gt; 다음 문제 -&gt; 다시 제출
 * </pre>
 *
 * <p>Reviewer 는 가짜다. 여기서 볼 것은 분석 품질이 아니라 "결정된 행동이 풀 수 있는
 * 문제로 옮겨지는가" 이며, 그 앞단은 결정론적이다 - LLM 을 붙이기 전에 이 부분을
 * 끝까지 확인해 두면 나중에 문제가 생겼을 때 어디가 틀렸는지 나눠 볼 수 있다.
 */
@SpringBootTest
class ProblemDeliveryTest {

    private static final String EXTERNAL_URL = System.getenv("TEST_DB_URL");
    private static PostgreSQLContainer<?> container;

    static {
        if (EXTERNAL_URL == null) {
            container = new PostgreSQLContainer<>("postgres:16-alpine");
            container.start();
        }
    }

    static class FakeReviewer implements ReviewerPort {

        ReviewerOutput scripted;

        @Override
        public Optional<ReviewerOutput> review(Request request) {
            return Optional.ofNullable(scripted);
        }

        @Override
        public String promptVersion() {
            return "test-v1";
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
    private FakeReviewer reviewer;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mvc;
    private Long userId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        userId = users.save(new UserRow(
                "pd-" + System.nanoTime() + "@codesprint.dev", "문제제공테스트")).id();
        reviewer.scripted = null;
    }

    private long submitAndJudge(String problemCode, String judgeStatus) throws Exception {
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
                 "memoryKb": 20480, "failedCaseId": 4, "stderr": null, "cases": []}
                """.formatted(judgeStatus);
        jdbc.update("UPDATE judge_jobs SET status = 'DONE', result = ?::jsonb WHERE id = ?",
                result, job.id());
        poller.applyFinishedJobs();
        return submissionId;
    }

    private JsonNode nextProblem(long submissionId) throws Exception {
        String json = mvc.perform(get("/api/submissions/{id}/next-problem", submissionId))
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(json);
    }

    private static ReviewerOutput analysis(String mistake, double confidence) {
        return new ReviewerOutput("WRONG", mistake, List.of(), confidence,
                List.of("BFS_GRID_TRAVERSAL"), List.of(4), false, true, "경계 검사를 빠뜨렸다");
    }

    @Test
    @DisplayName("확정된 실수가 실제 드릴 문제로 이어진다 - 루프가 닫힌다")
    void confirmedMistakeYieldsADrillProblem() throws Exception {
        // 서로 다른 문제에서 같은 실수가 반복되면 확정된다(Addendum 21-B).
        reviewer.scripted = analysis("BOUNDARY_CHECK", 0.85);
        submitAndJudge("P02_GRID_TRAVERSAL", "WRONG_ANSWER");
        submitAndJudge("P09_BFS_VARIANT_A", "WRONG_ANSWER");
        long submissionId = submitAndJudge("P02_GRID_TRAVERSAL", "WRONG_ANSWER");

        JsonNode next = nextProblem(submissionId);

        assertThat(next.get("action").asText()).isEqualTo("MICRO_DRILL");
        assertThat(next.get("targetSkill").asText()).isEqualTo("GRID_BOUNDARY_CHECK");

        JsonNode problem = next.get("problem");
        assertThat(problem.isNull()).as("드릴 문제를 실제로 골라야 한다").isFalse();
        assertThat(problem.get("kind").asText()).isEqualTo("MICRO_DRILL");
        assertThat(problem.get("statement").asText()).isNotBlank();

        // 고른 문제를 실제로 풀 수 있어야 루프가 닫힌 것이다.
        long drill = submitAndJudge(problem.get("code").asText(), "ACCEPTED");
        assertThat(nextProblem(drill).get("submissionId").asLong()).isEqualTo(drill);
    }

    @Test
    @DisplayName("hidden Test Case 는 내보내지 않는다")
    void hiddenCasesAreNeverExposed() throws Exception {
        String json = mvc.perform(get("/api/problems/{code}", "P02_GRID_TRAVERSAL"))
                .andReturn().getResponse().getContentAsString();
        JsonNode problem = MAPPER.readTree(json);

        // 파일에는 hidden case 가 더 있다. 응답에는 공개 case 만 있어야 한다.
        JsonNode cases = MAPPER.readTree(Files.readString(repoRoot()
                .resolve("problems").resolve("P02_GRID_TRAVERSAL").resolve("cases.json")))
                .get("cases");
        long visible = 0;
        for (JsonNode node : cases) {
            if (!node.get("hidden").asBoolean()) {
                visible++;
            }
        }
        assertThat(cases.size()).as("이 문제에는 hidden case 가 있어야 검증이 성립한다")
                .isGreaterThan((int) visible);
        assertThat(problem.get("samples")).hasSize((int) visible);

        // hidden case 의 input 이 응답의 sample 로 나오면 안 된다.
        //
        // 문자열 포함으로 보면 안 된다. hidden 정답이 "1" 같은 짧은 값이면 JSON 의
        // 다른 곳(제한값, Skill 이름)에 우연히 들어 있어 늘 실패한다 - 그러면
        // 테스트가 유출이 아니라 우연을 잡는다.
        java.util.Set<String> exposed = new java.util.HashSet<>();
        problem.get("samples").forEach(sample -> exposed.add(sample.get("input").asText()));

        for (JsonNode node : cases) {
            String input = node.get("input").asText();
            if (node.get("hidden").asBoolean()) {
                assertThat(exposed).as("hidden case 가 노출됐다").doesNotContain(input);
            } else {
                assertThat(exposed).as("공개 case 는 보여야 한다").contains(input);
            }
        }
    }

    @Test
    @DisplayName("선수 조건 미충족이면 그 Skill 의 일반 문제를 준다")
    void changeSkillYieldsANormalProblem() throws Exception {
        // 신규 사용자가 BFS_SHORTEST_PATH 를 틀리면 선수 Skill 로 보낸다.
        long submissionId = submitAndJudge("P05_SHORTEST_PATH", "WRONG_ANSWER");

        JsonNode next = nextProblem(submissionId);
        assertThat(next.get("action").asText()).isEqualTo("CHANGE_SKILL");
        assertThat(next.get("problem").get("kind").asText())
                .as("아직 시작도 안 한 Skill 에 드릴을 주지 않는다")
                .isEqualTo("NORMAL");
        assertThat(next.get("problem").get("skills").get(0).get("skillCode").asText())
                .isEqualTo(next.get("targetSkill").asText());
    }

    @Test
    @DisplayName("이미 맞힌 문제는 다시 주지 않는다")
    void solvedProblemsAreNotHandedOutAgain() throws Exception {
        // P03 을 맞혀 둔다. 같은 Skill 의 후보에서 빠져야 한다.
        submitAndJudge("P03_CONNECTED_COMPONENT", "ACCEPTED");
        long submissionId = submitAndJudge("P05_SHORTEST_PATH", "WRONG_ANSWER");

        JsonNode next = nextProblem(submissionId);
        if (!next.get("problem").isNull()) {
            assertThat(next.get("problem").get("code").asText())
                    .isNotEqualTo("P03_CONNECTED_COMPONENT");
        }
    }

    @Test
    @DisplayName("문제를 요구하지 않는 행동에서는 이유를 남긴다")
    void actionsWithoutProblemsExplainThemselves() throws Exception {
        // COMPILE_ERROR 는 같은 문제를 고쳐 다시 내는 상황이다.
        long submissionId = submitAndJudge("P01_QUEUE_BASIC", "COMPILE_ERROR");

        JsonNode next = nextProblem(submissionId);
        assertThat(next.get("action").asText()).isEqualTo("CONTINUE");
        assertThat(next.get("problem").isNull()).isTrue();
        // 빈 응답으로 두면 "아직 안 끝났다" 와 "줄 문제가 없다" 를 구분할 수 없다.
        assertThat(next.get("reason").asText()).isNotBlank();
    }

    @Test
    @DisplayName("결과가 반영되기 전에는 다음 문제가 없다")
    void pendingSubmissionHasNoNextProblem() throws Exception {
        String body = """
                {"userId": %d, "language": "PYTHON", "sourceCode": "print(1)",
                 "hintLevel": 0, "solutionViewed": false, "solveSeconds": 120}
                """.formatted(userId);
        String json = mvc.perform(post("/api/problems/{code}/submit", "P01_QUEUE_BASIC")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();
        long submissionId = MAPPER.readTree(json).get("submissionId").asLong();

        assertThat(mvc.perform(get("/api/submissions/{id}/next-problem", submissionId))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("없는 문제는 404 다")
    void unknownProblemIsNotFound() throws Exception {
        assertThat(mvc.perform(get("/api/problems/{code}", "NO_SUCH_PROBLEM"))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("응답이 계약을 지킨다")
    void responsesMatchContracts() throws Exception {
        reviewer.scripted = analysis("BOUNDARY_CHECK", 0.85);
        submitAndJudge("P02_GRID_TRAVERSAL", "WRONG_ANSWER");
        submitAndJudge("P09_BFS_VARIANT_A", "WRONG_ANSWER");
        long submissionId = submitAndJudge("P02_GRID_TRAVERSAL", "WRONG_ANSWER");

        assertThat(schema("next-problem.schema.json").validate(nextProblem(submissionId)))
                .as("next-problem 계약 위반").isEmpty();

        JsonNode problem = MAPPER.readTree(mvc
                .perform(get("/api/problems/{code}", "P02_GRID_TRAVERSAL"))
                .andReturn().getResponse().getContentAsString());
        assertThat(schema("problem-view.schema.json").validate(problem))
                .as("problem-view 계약 위반").isEmpty();
    }

    private static JsonSchema schema(String name) throws Exception {
        return JsonSchemaFactory
                .builder(JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012))
                .schemaMappers(mappers -> mappers.mapPrefix(
                        "https://codesprint.dev/contracts/",
                        repoRoot().resolve("contracts").toUri().toString()))
                .build()
                .getSchema(Files.readString(repoRoot().resolve("contracts").resolve(name)));
    }
}
