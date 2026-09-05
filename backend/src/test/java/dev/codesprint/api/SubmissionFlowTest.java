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
import dev.codesprint.learning.persistence.SkillEvidenceRepository;
import dev.codesprint.learning.persistence.UserRepository;
import dev.codesprint.learning.persistence.UserRow;
import dev.codesprint.learning.service.JudgeResultPoller;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 제출 하나가 흐름 전체를 지나는지 본다 - Milestone 1 데모의 뼈대(Addendum §89).
 *
 * <pre>
 *   POST /submit -&gt; 큐 -&gt; (Worker) -&gt; 결과 반영 -&gt; GET /submissions/{id}
 * </pre>
 *
 * <p><b>Worker 는 흉내 낸다.</b> 실제 Worker(judge/worker.py)가 하는 일은 큐에서 job 을
 * 꺼내 샌드박스를 돌리고 결과를 그 행에 쓰는 것뿐이므로, 여기서는 결과를 직접 쓴다.
 * 흉내가 얕은 것이 아니라 <b>경계가 그 행 하나</b>이기 때문이다(ADR-0013).
 *
 * <p>샌드박스 격리는 {@code judge/tests/test_judge.py} 가 실물 Docker 로 따로 본다.
 * DB 는 가짜로 하지 않는다 - 멱등 저장, jsonb 왕복, 제약이 전부 PostgreSQL 의 동작이다.
 */
@SpringBootTest
class SubmissionFlowTest {

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
        // 문제는 jar 가 아니라 저장소에서 읽는다(ADR-0008).
        registry.add("codesprint.problems-dir", () -> repoRoot().resolve("problems").toString());
        registry.add("codesprint.repo-root", () -> repoRoot().toString());
        // 폴러가 테스트 도중 제멋대로 돌면 "언제 반영됐는가" 를 확인할 수 없다.
        // 테스트가 직접 한 번씩 부른다.
        registry.add("codesprint.judge.apply-interval-ms", () -> "3600000");
    }

    private static Path repoRoot() {
        String root = System.getProperty("codesprint.repoRoot");
        if (root == null) {
            throw new IllegalStateException(
                    "codesprint.repoRoot 시스템 프로퍼티가 없다. build.gradle.kts 를 확인한다.");
        }
        return Path.of(root);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository users;

    @Autowired
    private SkillEvidenceRepository evidence;

    @Autowired
    private JudgeJobRepository jobs;

    @Autowired
    private JudgeResultPoller poller;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private MockMvc mvc;
    private Long userId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        userId = users.save(new UserRow(
                "flow-" + System.nanoTime() + "@codesprint.dev", "흐름테스트")).id();
    }

    private String requestBody(int hintLevel) {
        return """
                {"userId": %d, "language": "PYTHON", "sourceCode": "print(1)",
                 "hintLevel": %d, "solutionViewed": false, "solveSeconds": 120}
                """.formatted(userId, hintLevel);
    }

    /** POST 는 접수만 한다. 202 와 submissionId 를 돌려준다. */
    private JsonNode accept(String problemCode, String body) throws Exception {
        var response = mvc.perform(post("/api/problems/{code}/submit", problemCode)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse();
        assertThat(response.getStatus()).as("접수는 202 다").isEqualTo(202);
        return MAPPER.readTree(response.getContentAsString());
    }

    /**
     * Worker 를 흉내 낸다. 실제 Worker 가 하는 일도 이것뿐이다 - 큐 행에 결과를 쓴다.
     *
     * <p>엔티티에 결과를 쓰는 setter 를 두지 않았다. 그 컬럼을 채우는 것은 Python
     * Worker 의 일이고, Java 쪽에 통로를 열어두면 "누가 쓰는 값인가" 가 흐려진다.
     * 그래서 여기서는 Worker 와 똑같이 SQL 로 쓴다.
     */
    private void workerFinishes(long submissionId, String resultJson) {
        JudgeJobRow job = jobs.findBySubmissionId(submissionId).orElseThrow(
                () -> new AssertionError("큐에 job 이 없다: submission " + submissionId));
        jdbc.update("UPDATE judge_jobs SET status = 'DONE', result = ?::jsonb,"
                + " lease_expires_at = NULL WHERE id = ?", resultJson, job.id());
    }

    private JsonNode statusOf(long submissionId) throws Exception {
        String json = mvc.perform(get("/api/submissions/{id}", submissionId))
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(json);
    }

    private static String judged(String status, int passed, int total) {
        return """
                {"status": "%s", "passed": %d, "total": %d, "executionMs": 120,
                 "memoryKb": 20480, "failedCaseId": null, "stderr": null, "cases": []}
                """.formatted(status, passed, total);
    }

    @Test
    @DisplayName("제출은 즉시 접수되고 채점 전에는 PENDING 이다")
    void submissionIsAcceptedBeforeJudging() throws Exception {
        JsonNode accepted = accept("P02_GRID_TRAVERSAL", requestBody(0));

        assertThat(accepted.get("state").asText()).isEqualTo("PENDING");
        assertThat(accepted.get("result").isNull()).isTrue();

        long submissionId = accepted.get("submissionId").asLong();
        // 아직 아무도 채점하지 않았다. 조회해도 PENDING 이다.
        assertThat(statusOf(submissionId).get("state").asText()).isEqualTo("PENDING");
        assertThat(evidence.countByUserIdAndSkillCode(userId, "BFS_GRID_TRAVERSAL"))
                .as("채점 전에는 Evidence 가 없다")
                .isZero();
    }

    @Test
    @DisplayName("Worker 가 끝내면 Evidence 와 mastery 와 다음 행동이 만들어진다")
    void resultIsAppliedAfterWorkerFinishes() throws Exception {
        long submissionId = accept("P02_GRID_TRAVERSAL", requestBody(0))
                .get("submissionId").asLong();

        workerFinishes(submissionId, judged("ACCEPTED", 5, 5));
        assertThat(poller.applyFinishedJobs()).isEqualTo(1);

        JsonNode status = statusOf(submissionId);
        assertThat(status.get("state").asText()).isEqualTo("COMPLETE");

        JsonNode result = status.get("result");
        assertThat(result.get("judge").get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(result.get("skillUpdates")).isNotEmpty();
        // before 는 null 이다 - 처음이라 평가된 적이 없다. 0.0(평가했고 못함)과 다르다.
        assertThat(result.get("skillUpdates").get(0).get("before").isNull()).isTrue();
        assertThat(result.get("nextAction").get("type").asText()).isNotBlank();

        // Reviewer 가 아직 없으므로 null 이다. 필드를 생략하지 않는다.
        assertThat(result.get("review").isNull()).isTrue();
        assertThat(result.get("promptVersion").isNull()).isTrue();
    }

    @Test
    @DisplayName("응답이 계약(submission-status + submit-response)을 지킨다")
    void responseMatchesContract() throws Exception {
        long submissionId = accept("P02_GRID_TRAVERSAL", requestBody(0))
                .get("submissionId").asLong();
        workerFinishes(submissionId, judged("WRONG_ANSWER", 3, 5));
        poller.applyFinishedJobs();

        assertThat(schema("submission-status.schema.json").validate(statusOf(submissionId)))
                .as("계약 위반")
                .isEmpty();
    }

    @Test
    @DisplayName("Evidence 를 만들지 않는 판정도 계약을 지킨다")
    void compileErrorResponseMatchesContract() throws Exception {
        // after 가 null 인 경로다. WA 하나만 검증하던 시절에는 이 경우가 계약을
        // 어겼는데도(after 가 non-null 이었다) 아무도 몰랐다.
        long submissionId = accept("P01_QUEUE_BASIC", requestBody(0))
                .get("submissionId").asLong();
        workerFinishes(submissionId, judged("COMPILE_ERROR", 0, 5));
        poller.applyFinishedJobs();

        JsonNode status = statusOf(submissionId);
        assertThat(schema("submission-status.schema.json").validate(status))
                .as("계약 위반")
                .isEmpty();
        assertThat(status.get("result").get("skillUpdates").get(0).get("after").isNull())
                .as("문법 오류로는 아무것도 측정하지 않는다")
                .isTrue();
        assertThat(evidence.countByUserIdAndSkillCode(userId, "PYTHON_DEQUE_BASIC")).isZero();
    }

    @Test
    @DisplayName("채점하지 못한 job 은 SYSTEM_ERROR 로 반영되고 경로를 바꾸지 않는다")
    void failedJobBecomesSystemError() throws Exception {
        // Worker 가 시도 횟수를 다 쓰고 포기한 경우다. 조용히 큐에 남겨두면 그
        // 제출은 영영 PENDING 이고, 사용자는 실패했다는 사실조차 모른다.
        long submissionId = accept("P05_SHORTEST_PATH", requestBody(0))
                .get("submissionId").asLong();
        JudgeJobRow job = jobs.findBySubmissionId(submissionId).orElseThrow();
        jdbc.update("UPDATE judge_jobs SET status = 'FAILED', failure_reason = ?"
                + " WHERE id = ?", "3회 시도했지만 끝내지 못했다", job.id());

        poller.applyFinishedJobs();

        JsonNode result = statusOf(submissionId).get("result");
        assertThat(result.get("judge").get("status").asText()).isEqualTo("SYSTEM_ERROR");
        // 우리 잘못이다. 선수 조건이 미충족이어도 다른 Skill 로 보내지 않는다.
        assertThat(result.get("nextAction").get("type").asText()).isEqualTo("CONTINUE");
        assertThat(evidence.countByUserIdAndSkillCode(userId, "BFS_SHORTEST_PATH")).isZero();
    }

    @Test
    @DisplayName("같은 job 을 두 번 반영해도 Evidence 는 한 번만 쌓인다")
    void applyingTwiceIsIdempotent() throws Exception {
        // 반영 도중 프로세스가 죽으면 같은 job 을 다시 집는다. Evidence 가 두 번
        // 쌓이면 EMA 가 두 번 적용되고 confidence 도 두 번 오른다.
        long submissionId = accept("P02_GRID_TRAVERSAL", requestBody(0))
                .get("submissionId").asLong();
        workerFinishes(submissionId, judged("ACCEPTED", 5, 5));

        poller.applyFinishedJobs();
        long after1 = evidence.countByUserIdAndSkillCode(userId, "BFS_GRID_TRAVERSAL");

        // applied_at 을 지워 "반영 도중 죽었다" 를 만든다.
        jdbc.update("UPDATE judge_jobs SET applied_at = NULL WHERE submission_id = ?",
                submissionId);
        poller.applyFinishedJobs();
        long after2 = evidence.countByUserIdAndSkillCode(userId, "BFS_GRID_TRAVERSAL");

        assertThat(after1).isEqualTo(1);
        assertThat(after2).as("두 번 반영해도 늘지 않는다").isEqualTo(1);
    }

    @Test
    @DisplayName("선수 조건을 못 채운 Skill 은 첫 실패에서 선수 Skill 로 보낸다")
    void firstFailureOnLockedSkillRedirects() throws Exception {
        long submissionId = accept("P05_SHORTEST_PATH", requestBody(0))
                .get("submissionId").asLong();
        workerFinishes(submissionId, judged("WRONG_ANSWER", 0, 5));
        poller.applyFinishedJobs();

        JsonNode action = statusOf(submissionId).get("result").get("nextAction");
        assertThat(action.get("type").asText()).isEqualTo("CHANGE_SKILL");
        assertThat(action.get("targetSkill").asText()).isNotBlank();
    }

    @Test
    @DisplayName("우리 장애는 나중 제출의 경로도 바꾸지 않는다")
    void systemErrorsDoNotAccumulateIntoAttempts() throws Exception {
        for (int i = 0; i < 2; i++) {
            long id = accept("P01_QUEUE_BASIC", requestBody(0)).get("submissionId").asLong();
            workerFinishes(id, judged("SYSTEM_ERROR", 0, 1));
            poller.applyFinishedJobs();
        }
        long third = accept("P01_QUEUE_BASIC", requestBody(0)).get("submissionId").asLong();
        workerFinishes(third, judged("WRONG_ANSWER", 0, 5));
        poller.applyFinishedJobs();

        assertThat(statusOf(third).get("result").get("nextAction").get("type").asText())
                .as("실제 실패는 이번 한 번뿐이다")
                .isNotEqualTo("REVIEW_CONCEPT");
    }

    @Test
    @DisplayName("같은 문제를 세 번 틀리면 개념부터 다시 본다")
    void threeRealFailuresGoToConcept() throws Exception {
        long last = 0;
        for (int i = 0; i < 3; i++) {
            last = accept("P01_QUEUE_BASIC", requestBody(0)).get("submissionId").asLong();
            workerFinishes(last, judged("WRONG_ANSWER", 0, 5));
            poller.applyFinishedJobs();
        }
        assertThat(statusOf(last).get("result").get("nextAction").get("type").asText())
                .isEqualTo("REVIEW_CONCEPT");
    }

    @Test
    @DisplayName("지원하지 않는 언어는 400 이고 큐에 넣지 않는다")
    void unsupportedLanguageIsRejected() throws Exception {
        String body = """
                {"userId": %d, "language": "JAVA", "sourceCode": "class Main {}",
                 "hintLevel": 0, "solutionViewed": false, "solveSeconds": 120}
                """.formatted(userId);

        long before = jobs.count();
        int status = mvc.perform(post("/api/problems/{code}/submit", "P01_QUEUE_BASIC")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(400);
        assertThat(jobs.count()).as("큐에 들어가지 않는다").isEqualTo(before);
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "solveSeconds={0}")
    @org.junit.jupiter.params.provider.ValueSource(ints = {-1, 0})
    @DisplayName("0 이하의 풀이 시간은 400 이고 큐에 넣지 않는다")
    void nonPositiveSolveSecondsIsRejected(int solveSeconds) throws Exception {
        String body = """
                {"userId": %d, "language": "PYTHON", "sourceCode": "print(1)",
                 "hintLevel": 0, "solutionViewed": false, "solveSeconds": %d}
                """.formatted(userId, solveSeconds);

        long before = jobs.count();
        int status = mvc.perform(post("/api/problems/{code}/submit", "P01_QUEUE_BASIC")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(400);
        assertThat(jobs.count()).as("큐에 들어가지 않는다").isEqualTo(before);
    }

    @Test
    @DisplayName("없는 문제는 404 다 - 채점 실패와 구분한다")
    void unknownProblemIsNotFound() throws Exception {
        long before = jobs.count();
        int status = mvc.perform(post("/api/problems/{code}/submit", "NO_SUCH_PROBLEM")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(0)))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(404);
        assertThat(jobs.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("없는 제출을 조회하면 404 다")
    void unknownSubmissionIsNotFound() throws Exception {
        assertThat(mvc.perform(get("/api/submissions/{id}", 999_999_999L))
                .andReturn().getResponse().getStatus())
                .isEqualTo(404);
    }

    /**
     * 계약을 읽는다. {@code $ref} 는 저장소의 {@code contracts/} 로 푼다 - 그러지 않으면
     * 검증기가 $id 의 URL 을 네트워크로 가져오려 하고, 실패하면 조용히 통과한다.
     */
    private static JsonSchema schema(String name) throws Exception {
        String contractsDir = repoRoot().resolve("contracts").toUri().toString();
        JsonSchemaFactory factory = JsonSchemaFactory
                .builder(JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012))
                .schemaMappers(mappers -> mappers.mapPrefix(
                        "https://codesprint.dev/contracts/", contractsDir))
                .build();
        return factory.getSchema(Files.readString(repoRoot().resolve("contracts").resolve(name)));
    }
}
