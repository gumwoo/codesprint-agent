package dev.codesprint.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import dev.codesprint.judge.JudgeJobRepository;
import dev.codesprint.judge.JudgeJobRow;
import dev.codesprint.learning.persistence.UserRepository;
import dev.codesprint.learning.persistence.UserRow;
import dev.codesprint.learning.service.JudgeResultPoller;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 제출 전 실행. 정본: ADR-0020.
 *
 * <p>여기서 확인하는 것은 하나로 줄어든다 — <b>실행이 학습 상태를 건드리지 않는가.</b>
 * 채점이 맞는지는 이미 다른 곳에서 본다.
 */
@SpringBootTest
class RunTest {

    private static final String EXTERNAL_URL = System.getenv("TEST_DB_URL");
    private static PostgreSQLContainer<?> container;

    static {
        if (EXTERNAL_URL == null) {
            container = new PostgreSQLContainer<>("postgres:16-alpine");
            container.start();
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
        String root = System.getProperty("codesprint.repoRoot");
        registry.add("codesprint.problems-dir", () -> root + "/problems");
        registry.add("codesprint.repo-root", () -> root);
        registry.add("codesprint.judge.apply-interval-ms", () -> "3600000");
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
    private JdbcTemplate jdbc;

    private MockMvc mvc;
    private Long userId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        userId = users.save(new UserRow(
                "run-" + System.nanoTime() + "@codesprint.dev", "실행테스트")).id();
    }

    private long startRun(String problemCode) throws Exception {
        String body = """
                {"userId": %d, "language": "PYTHON", "sourceCode": "print(1)"}
                """.formatted(userId);
        var response = mvc.perform(post("/api/problems/{code}/run", problemCode)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(202);
        return MAPPER.readTree(response.getContentAsString()).get("runId").asLong();
    }

    /** Worker 가 끝낸 것처럼 결과를 큐에 쓴다. */
    private void workerFinishes(long runId) {
        jdbc.update("UPDATE judge_jobs SET status = 'DONE', result = ?::jsonb WHERE id = ?",
                """
                {"status": "WRONG_ANSWER", "passed": 0, "total": 1, "executionMs": 40,
                 "memoryKb": 10240, "failedCaseId": 1, "stderr": null,
                 "cases": [{"id": 1, "status": "WRONG_ANSWER", "executionMs": 40,
                            "input": "1 1\\n1\\n", "expectedOutput": "1\\n",
                            "stdout": "1\\n", "stderr": null}]}
                """, runId);
    }

    private JsonNode read(long runId) throws Exception {
        return MAPPER.readTree(mvc.perform(get("/api/runs/{id}", runId).param("userId",
                        String.valueOf(userId)))
                .andReturn().getResponse().getContentAsString());
    }

    @Test
    @DisplayName("실행은 제출을 만들지 않는다")
    void runningCreatesNoSubmission() throws Exception {
        // **이것이 이 기능의 존재 이유다.** 지금까지 코드를 시험해 보려면 제출해야
        // 했고, 그러면 Evidence 가 생겨 mastery 가 깎였다 - 돌려 보는 것과 답을
        // 내는 것이 같은 일이었다.
        long runId = startRun("P02_GRID_TRAVERSAL");
        workerFinishes(runId);
        poller.applyFinishedJobs();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM submissions WHERE user_id = ?", Long.class, userId))
                .as("제출이 생기면 안 된다").isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM skill_evidence WHERE user_id = ?", Long.class, userId))
                .as("Evidence 가 생기면 안 된다").isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM user_skills WHERE user_id = ?", Long.class, userId))
                .as("Skill 상태가 생기면 안 된다").isZero();
    }

    @Test
    @DisplayName("Poller 가 실행 결과를 집어가지 않는다")
    void thePollerLeavesRunsAlone() throws Exception {
        long runId = startRun("P02_GRID_TRAVERSAL");
        workerFinishes(runId);
        poller.applyFinishedJobs();

        // 반영하지 않았으므로 applied_at 이 비어 있어야 한다. 집어갔다면 그 자리에
        // 시각이 찍히고, 우리는 실행 하나를 학습 이력으로 삼은 것이다.
        assertThat(jdbc.queryForObject(
                "SELECT applied_at FROM judge_jobs WHERE id = ?", java.sql.Timestamp.class,
                runId)).isNull();
    }

    @Test
    @DisplayName("실행 행에는 제출을 붙일 수 없다")
    void aRunRowCannotPointAtASubmission() throws Exception {
        // **코드가 아니라 행 모양이 막는다.** Poller 의 kind 조건은 지워질 수 있고,
        // 지워지면 실행 한 번이 Evidence 가 되어 mastery 를 깎는다. 결과를 되돌리는
        // 경로가 submission_id 로만 이어지므로, 그 값이 들어갈 수 없으면 갈 곳이 없다.
        long runId = startRun("P02_GRID_TRAVERSAL");

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE judge_jobs SET submission_id = 1 WHERE id = ?", runId))
                .hasMessageContaining("judge_jobs_submission_matches_kind");
    }

    @Test
    @DisplayName("남의 실행 결과는 보이지 않는다")
    void anotherUsersRunIsNotVisible() throws Exception {
        // 실행 결과에는 그 사람이 쓴 코드의 출력이 담긴다.
        long runId = startRun("P02_GRID_TRAVERSAL");
        long other = users.save(new UserRow(
                "other-" + System.nanoTime() + "@codesprint.dev", "남")).id();

        assertThat(mvc.perform(get("/api/runs/{id}", runId)
                        .param("userId", String.valueOf(other)))
                .andReturn().getResponse().getStatus())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("결과가 계약을 지킨다")
    void theResponseMatchesItsContract() throws Exception {
        long runId = startRun("P02_GRID_TRAVERSAL");

        var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(Files.readString(
                        Path.of(System.getProperty("codesprint.repoRoot"), "contracts",
                                "run-result.schema.json"),
                        StandardCharsets.UTF_8));

        // 아직 안 끝났을 때. judged 는 null 이고, 생략되지 않는다.
        assertThat(schema.validate(read(runId))).as("대기 중 응답").isEmpty();

        workerFinishes(runId);
        assertThat(schema.validate(read(runId))).as("끝난 뒤 응답").isEmpty();
    }

    @Test
    @DisplayName("실행 결과에는 다음 행동이 없다")
    void aRunCarriesNoNextAction() throws Exception {
        // 실행에 다음 행동을 붙이면 사용자는 돌려 보기만 해도 학습 경로가 움직이는
        // 것으로 읽는다. 실제로 움직이지 않으므로 화면이 거짓을 말하게 된다.
        long runId = startRun("P02_GRID_TRAVERSAL");
        workerFinishes(runId);

        assertThat(read(runId).has("nextAction")).isFalse();
    }

    @Test
    @DisplayName("실행도 Python 만 받는다")
    void onlyPythonRuns() throws Exception {
        String body = """
                {"userId": %d, "language": "JAVA", "sourceCode": "class A {}"}
                """.formatted(userId);

        assertThat(mvc.perform(post("/api/problems/{code}/run", "P02_GRID_TRAVERSAL")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("없는 문제는 404 다")
    void unknownProblemIsNotFound() throws Exception {
        String body = """
                {"userId": %d, "language": "PYTHON", "sourceCode": "print(1)"}
                """.formatted(userId);

        assertThat(mvc.perform(post("/api/problems/{code}/run", "P99_NOPE")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("빠뜨린 값은 400 이다")
    void missingFieldsAreRejected() throws Exception {
        // 검증이 없으면 null 이 서비스까지 내려가 500 이 나간다. 사용자 입력
        // 문제를 서버 잘못으로 보고하면, 무엇이 진짜 장애인지 구분할 수 없다.
        for (String body : new String[] {
                "{\"language\": \"PYTHON\", \"sourceCode\": \"print(1)\"}",
                "{\"userId\": %d, \"sourceCode\": \"print(1)\"}".formatted(userId),
                "{\"userId\": %d, \"language\": \"PYTHON\"}".formatted(userId)}) {

            assertThat(mvc.perform(post("/api/problems/{code}/run", "P02_GRID_TRAVERSAL")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn().getResponse().getStatus())
                    .as(body)
                    .isEqualTo(400);
        }
    }

    @Test
    @DisplayName("제출 job 을 실행으로 읽을 수 없다")
    void aSubmitJobIsNotARun() throws Exception {
        String body = """
                {"userId": %d, "language": "PYTHON", "sourceCode": "print(1)",
                 "solveSeconds": 60}
                """.formatted(userId);
        long submissionId = MAPPER.readTree(
                mvc.perform(post("/api/problems/{code}/submit", "P02_GRID_TRAVERSAL")
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andReturn().getResponse().getContentAsString())
                .get("submissionId").asLong();
        JudgeJobRow job = jobs.findBySubmissionId(submissionId).orElseThrow();

        // 제출 결과에는 숨은 case 가 들어 있다. 실행 조회로 새어 나가면 제출하지
        // 않고도 채점 결과를 얻는 길이 된다.
        assertThat(mvc.perform(get("/api/runs/{id}", job.id())
                        .param("userId", String.valueOf(userId)))
                .andReturn().getResponse().getStatus())
                .isEqualTo(404);
    }
}
