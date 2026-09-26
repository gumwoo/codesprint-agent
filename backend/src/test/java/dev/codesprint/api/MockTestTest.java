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
import dev.codesprint.mocktest.MockTestProblemRepository;
import dev.codesprint.mocktest.MockTestProblemRow;
import dev.codesprint.problem.ProblemCatalog;
import dev.codesprint.support.JudgeResultFixture;
import dev.codesprint.support.MovableClock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 모의 시험의 길. 정본: PRD §84~86, ADR-0043.
 *
 * <p>여기서 보는 것은 셋이다 - 시험 중에 <b>도움과 유형이 새지 않는가</b>, 관측이 <b>한 시계로</b>
 * 남는가, 끝난 뒤 보고서가 <b>관측한 것만</b> 말하는가.
 */
@SpringBootTest
@Import(MovableClock.Installed.class)
class MockTestTest {

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

    @Autowired
    private MovableClock clock;

    @Autowired
    private MockTestProblemRepository mockProblems;

    @Autowired
    private ProblemCatalog catalog;

    private MockMvc mvc;
    private long userId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        userId = users.save(new UserRow(
                "mock-" + System.nanoTime() + "@codesprint.dev", "시험테스트", "JOB")).id();
    }

    /** $ref 로 다른 계약을 가리키므로 저장소의 contracts/ 로 이어 준다. */
    private static JsonSchema schema(String name) throws Exception {
        Path contracts = Path.of(System.getProperty("codesprint.repoRoot")).resolve("contracts");
        return JsonSchemaFactory
                .builder(JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012))
                .schemaMappers(mappers -> mappers.mapPrefix(
                        "https://codesprint.dev/contracts/", contracts.toUri().toString()))
                .build()
                .getSchema(Files.readString(contracts.resolve(name), StandardCharsets.UTF_8));
    }

    private static JsonNode json(MockHttpServletResponse response) throws Exception {
        return MAPPER.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }

    private MockHttpServletResponse create(long user) throws Exception {
        return mvc.perform(post("/api/users/{id}/mock-tests", user)).andReturn().getResponse();
    }

    private JsonNode start() throws Exception {
        MockHttpServletResponse response = create(userId);
        assertThat(response.getStatus()).as(response.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(201);
        return json(response);
    }

    private MockHttpServletResponse postJson(String url, String body, Object... vars)
            throws Exception {
        return mvc.perform(post(url, vars).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse();
    }

    private String userBody() {
        return "{\"userId\": %d}".formatted(userId);
    }

    private String codeBody() {
        return "{\"userId\": %d, \"language\": \"PYTHON\", \"sourceCode\": \"print(1)\"}"
                .formatted(userId);
    }

    private String codeOf(long mockTestId, String label) {
        return mockProblems.findById(new MockTestProblemRow.Key(mockTestId, label))
                .orElseThrow().problemCode();
    }

    private void judge(long submissionId, String status) {
        JudgeJobRow job = jobs.findBySubmissionId(submissionId).orElseThrow();
        JudgeResultFixture.finish(jdbc, """
                {"status": "%s", "passed": 6, "total": 6, "executionMs": 100,
                 "memoryKb": 20480, "failedCaseId": null, "stderr": null, "cases": []}
                """.formatted(status), job.id());
        poller.applyFinishedJobs();
    }

    @Test
    @DisplayName("처음 보는 문제로 시험을 만들고, 진행 중이면 하나 더 만들지 않는다")
    void createsFromUnseenProblems() throws Exception {
        // 이 사용자가 이미 낸 문제는 시험에 들지 않는다 - 답을 기억하고 있을 수 있다
        postJson("/api/problems/{code}/submit", codeBody(), "P01_QUEUE_BASIC");

        JsonNode test = start();
        assertThat(schema("mock-test.schema.json").validate(test)).as("mock-test 계약").isEmpty();
        assertThat(test.get("state").asText()).isEqualTo("IN_PROGRESS");

        long id = test.get("mockTestId").asLong();
        List<MockTestProblemRow> problems = mockProblems.findByMockTestIdOrderByLabelAsc(id);
        assertThat(problems).hasSizeBetween(2, 4);
        assertThat(problems).extracting(MockTestProblemRow::problemCode)
                .doesNotContain("P01_QUEUE_BASIC");
        int expected = problems.stream()
                .mapToInt(p -> catalog.find(p.problemCode()).expectedSolveSeconds()).sum();
        long minutes = Math.max(30, Math.min(180, (expected + 59) / 60));
        assertThat(test.get("remainingSeconds").asLong()).isEqualTo(minutes * 60);

        assertThat(create(userId).getStatus()).as("진행 중인 시험이 있다").isEqualTo(409);
    }

    @Test
    @DisplayName("시험 중에는 유형 · 힌트 · 분석 · 다음 행동이 새지 않는다")
    void nothingLeaksDuringTheTest() throws Exception {
        long id = start().get("mockTestId").asLong();
        String code = codeOf(id, "A");

        JsonNode sheet = json(postJson("/api/mock-tests/{id}/problems/{label}/open", userBody(),
                id, "A"));
        assertThat(schema("mock-test-problem.schema.json").validate(sheet)).isEmpty();
        assertThat(sheet.get("statement").asText()).isEqualTo(catalog.find(code).statement());
        assertThat(sheet.toString()).as("code 에 유형이 들어 있다").doesNotContain(code);

        // 문제 code 를 알아내 직접 부르는 길도 닫는다
        assertThat(postJson("/api/problems/{code}/hints/1", userBody(), code).getStatus())
                .as("힌트").isEqualTo(409);
        assertThat(postJson("/api/problems/{code}/submit", codeBody(), code).getStatus())
                .as("일반 제출").isEqualTo(409);
        assertThat(postJson("/api/problems/{code}/run", codeBody(), code).getStatus())
                .as("일반 실행").isEqualTo(409);

        long runId = json(postJson("/api/mock-tests/{id}/problems/{label}/run", codeBody(),
                id, "A")).get("runId").asLong();
        assertThat(mvc.perform(get("/api/runs/{id}", runId).param("userId", "" + userId))
                .andReturn().getResponse().getStatus()).as("일반 실행 조회에는 code 가 있다")
                .isEqualTo(409);
        JsonNode run = json(mvc.perform(get("/api/mock-tests/{id}/runs/{run}", id, runId)
                .param("userId", "" + userId)).andReturn().getResponse());
        assertThat(schema("mock-test-run.schema.json").validate(run)).isEmpty();

        long submissionId = json(postJson("/api/mock-tests/{id}/problems/{label}/submit",
                codeBody(), id, "A")).get("submissionId").asLong();
        judge(submissionId, "WRONG_ANSWER");

        JsonNode verdict = json(mvc.perform(get("/api/mock-tests/{id}/submissions/{s}", id,
                submissionId).param("userId", "" + userId)).andReturn().getResponse());
        assertThat(schema("mock-test-verdict.schema.json").validate(verdict)).isEmpty();
        assertThat(verdict.get("status").asText()).isEqualTo("WRONG_ANSWER");

        assertThat(mvc.perform(get("/api/submissions/{id}", submissionId)).andReturn()
                .getResponse().getStatus()).as("일반 조회에는 분석과 다음 행동이 있다").isEqualTo(409);
        assertThat(mvc.perform(get("/api/submissions/{id}/next-problem", submissionId))
                .andReturn().getResponse().getStatus()).as("다음 문제").isEqualTo(409);
        assertThat(mvc.perform(get("/api/mock-tests/{id}/report", id).param("userId", "" + userId))
                .andReturn().getResponse().getStatus()).as("보고서는 끝난 뒤").isEqualTo(409);

        // 끝나면 막힌 것이 풀린다
        postJson("/api/mock-tests/{id}/finish", userBody(), id);
        assertThat(mvc.perform(get("/api/submissions/{id}", submissionId)).andReturn()
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(postJson("/api/problems/{code}/hints/1", userBody(), code).getStatus())
                .isEqualTo(200);
        assertThat(postJson("/api/mock-tests/{id}/problems/{label}/submit", codeBody(), id, "A")
                .getStatus()).as("끝난 뒤의 제출").isEqualTo(409);
    }

    @Test
    @DisplayName("보고서는 관측한 순간을 시작 후 초로 주고, 결과를 넷으로 가른다")
    void reportTellsOnlyWhatWasObserved() throws Exception {
        JsonNode test = start();
        long id = test.get("mockTestId").asLong();

        clock.advance(Duration.ofSeconds(60));
        postJson("/api/mock-tests/{id}/problems/{label}/open", userBody(), id, "B");
        clock.advance(Duration.ofSeconds(120));
        postJson("/api/mock-tests/{id}/problems/{label}/run", codeBody(), id, "B");
        clock.advance(Duration.ofSeconds(60));
        long wrong = json(postJson("/api/mock-tests/{id}/problems/{label}/submit", codeBody(),
                id, "B")).get("submissionId").asLong();
        judge(wrong, "WRONG_ANSWER");
        clock.advance(Duration.ofSeconds(60));
        long right = json(postJson("/api/mock-tests/{id}/problems/{label}/submit", codeBody(),
                id, "B")).get("submissionId").asLong();
        judge(right, "ACCEPTED");
        clock.advance(Duration.ofSeconds(30));
        postJson("/api/mock-tests/{id}/problems/{label}/open", userBody(), id, "A");
        clock.advance(Duration.ofSeconds(30));
        long attempt = json(postJson("/api/mock-tests/{id}/problems/{label}/submit", codeBody(),
                id, "A")).get("submissionId").asLong();
        judge(attempt, "TIME_LIMIT");
        clock.advance(Duration.ofSeconds(40));
        postJson("/api/mock-tests/{id}/finish", userBody(), id);

        JsonNode report = json(mvc.perform(get("/api/mock-tests/{id}/report", id)
                .param("userId", "" + userId)).andReturn().getResponse());
        assertThat(schema("mock-test-report.schema.json").validate(report)).as("report 계약")
                .isEmpty();

        JsonNode b = byLabel(report, "B");
        assertThat(b.get("outcome").asText()).isEqualTo("SOLVED");
        assertThat(b.get("openedAtSeconds").asLong()).isEqualTo(60);
        assertThat(b.get("firstRunAtSeconds").asLong()).isEqualTo(180);
        assertThat(b.get("firstSubmitAtSeconds").asLong()).isEqualTo(240);
        assertThat(b.get("solvedAtSeconds").asLong()).as("처음 ACCEPTED 를 낸 제출 시각")
                .isEqualTo(300);
        assertThat(b.get("submissions").asInt()).isEqualTo(2);
        assertThat(b.get("timeSpentSeconds").asLong()).isEqualTo(240);
        assertThat(b.get("problemCode").asText()).as("끝난 뒤에는 code 를 보여 준다")
                .isEqualTo(codeOf(id, "B"));

        JsonNode a = byLabel(report, "A");
        assertThat(a.get("outcome").asText()).as("냈지만 못 풀었다").isEqualTo("ATTEMPTED");
        assertThat(a.get("timeSpentSeconds").asLong()).as("일찍 끝낸 시각까지").isEqualTo(70);

        for (JsonNode problem : report.get("problems")) {
            if (!List.of("A", "B").contains(problem.get("label").asText())) {
                assertThat(problem.get("outcome").asText()).isEqualTo("UNOPENED");
                assertThat(problem.get("openedAtSeconds").isNull()).isTrue();
            }
        }
        assertThat(report.get("solved").asInt()).isEqualTo(1);
        assertThat(report.get("openOrder")).extracting(JsonNode::asText).containsExactly("B", "A");
        assertThat(report.get("closedAt").asText()).as("일찍 끝냈다")
                .isNotEqualTo(report.get("startedAt").asText());
    }

    private static JsonNode byLabel(JsonNode report, String label) {
        for (JsonNode problem : report.get("problems")) {
            if (label.equals(problem.get("label").asText())) {
                return problem;
            }
        }
        throw new AssertionError(label + " 가 보고서에 없다");
    }

    @Test
    @DisplayName("시간이 지나면 끝낸 것과 같다 - 제출을 막고, 다음 시험을 만들 수 있다")
    void timeRunsOut() throws Exception {
        JsonNode test = start();
        long id = test.get("mockTestId").asLong();

        clock.advance(Duration.ofSeconds(test.get("remainingSeconds").asLong()));

        JsonNode overview = json(mvc.perform(get("/api/mock-tests/{id}", id)
                .param("userId", "" + userId)).andReturn().getResponse());
        assertThat(overview.get("state").asText()).isEqualTo("FINISHED");
        assertThat(overview.get("remainingSeconds").asLong()).isZero();
        assertThat(postJson("/api/mock-tests/{id}/problems/{label}/open", userBody(), id, "A")
                .getStatus()).isEqualTo(409);
        assertThat(mvc.perform(get("/api/mock-tests/{id}/report", id).param("userId", "" + userId))
                .andReturn().getResponse().getStatus()).isEqualTo(200);

        assertThat(create(userId).getStatus()).as("시간이 지난 시험은 닫고 새로 만든다")
                .isEqualTo(201);
    }

    @Test
    @DisplayName("남의 시험은 없는 것과 같다")
    void othersTestIsNotFound() throws Exception {
        long id = start().get("mockTestId").asLong();
        long other = users.save(new UserRow(
                "mock-other-" + System.nanoTime() + "@codesprint.dev", "남", "JOB")).id();

        assertThat(mvc.perform(get("/api/mock-tests/{id}", id).param("userId", "" + other))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(postJson("/api/mock-tests/{id}/problems/{label}/open",
                "{\"userId\": %d}".formatted(other), id, "A").getStatus()).isEqualTo(404);
    }
}
