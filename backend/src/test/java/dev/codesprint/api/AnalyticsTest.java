package dev.codesprint.api;

import dev.codesprint.support.JudgeResultFixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codesprint.judge.JudgeJobRepository;
import dev.codesprint.judge.JudgeJobRow;
import dev.codesprint.learning.persistence.UserRepository;
import dev.codesprint.learning.persistence.UserRow;
import dev.codesprint.learning.service.JudgeResultPoller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * 학습 분석. 정본: PRD §99 · §160, ADR-0049. 계약: contracts/analytics.schema.json.
 *
 * <p>화면이 세지 않게 서버가 저장된 관측(제출 · Skill 상태 · 끝난 모의 시험)을 센다. 독립 풀이는 Evidence 와
 * 같은 기준(힌트 H4 미만 · 전체 풀이 없음)이다.
 */
@SpringBootTest
class AnalyticsTest {

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
                "analytics-" + System.nanoTime() + "@codesprint.dev", "분석테스트", "JOB")).id();
    }

    private long submit(String code) throws Exception {
        return MAPPER.readTree(mvc.perform(post("/api/problems/{code}/submit", code)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\": %d, \"language\": \"PYTHON\", \"sourceCode\": \"x\"}"
                                .formatted(userId)))
                .andReturn().getResponse().getContentAsString()).get("submissionId").asLong();
    }

    private void judge(long submissionId, String status, int passed, int total) {
        JudgeJobRow job = jobs.findBySubmissionId(submissionId).orElseThrow();
        JudgeResultFixture.finish(jdbc, """
                {"status": "%s", "passed": %d, "total": %d, "executionMs": 100,
                 "memoryKb": 20480, "failedCaseId": %s, "stderr": null, "cases": []}
                """.formatted(status, passed, total, passed == total ? "null" : "1"), job.id());
        poller.applyFinishedJobs();
    }

    private void hint(String code, int level) throws Exception {
        mvc.perform(post("/api/problems/{code}/hints/{level}", code, level)
                .contentType(MediaType.APPLICATION_JSON).content("{\"userId\": %d}".formatted(userId)));
    }

    private JsonNode analytics(long user) throws Exception {
        return MAPPER.readTree(mvc.perform(get("/api/users/{id}/analytics", user))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static int count(JsonNode array, String status) {
        for (JsonNode item : array) {
            if (status.equals(item.get("status").asText())) {
                return item.get("count").asInt();
            }
        }
        throw new AssertionError("칸이 없다: " + status);
    }

    @Test
    @DisplayName("판정 · 푼 문제 · 독립 풀이 · 이번 주를 제출 기록에서 센다 - 도움을 받은 풀이는 독립 풀이가 아니다")
    void countsObservedSubmissions() throws Exception {
        judge(submit("P02_GRID_TRAVERSAL"), "WRONG_ANSWER", 3, 6);
        judge(submit("P02_GRID_TRAVERSAL"), "ACCEPTED", 6, 6);
        // P03 은 힌트를 H4 까지 본 뒤 푼다 - Evidence 가 독립 풀이로 치지 않는 기준
        for (int level = 1; level <= 4; level++) {
            hint("P03_CONNECTED_COMPONENT", level);
        }
        judge(submit("P03_CONNECTED_COMPONENT"), "ACCEPTED", 6, 6);
        submit("P04_AREA_SIZE"); // 채점 전

        JsonNode a = analytics(userId);
        Path path = Path.of(System.getProperty("codesprint.repoRoot"), "contracts", "analytics.schema.json");
        assertThat(JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(Files.readString(path, StandardCharsets.UTF_8)).validate(a)).isEmpty();

        assertThat(a.get("submissions").get("total").asInt()).isEqualTo(4);
        assertThat(a.get("submissions").get("judging").asInt()).as("채점 전 제출").isEqualTo(1);
        assertThat(count(a.get("submissions").get("verdicts"), "ACCEPTED")).isEqualTo(2);
        assertThat(count(a.get("submissions").get("verdicts"), "WRONG_ANSWER")).isEqualTo(1);
        assertThat(count(a.get("submissions").get("verdicts"), "TIME_LIMIT")).as("0 인 판정도 칸이 있다").isZero();
        assertThat(a.get("solved").get("problems").asInt()).isEqualTo(2);
        assertThat(a.get("solved").get("independent").asInt()).as("P03 은 힌트 H4 를 봤다").isEqualTo(1);

        JsonNode weeks = a.get("weeks");
        assertThat(weeks).hasSize(8);
        JsonNode thisWeek = weeks.get(weeks.size() - 1);
        assertThat(thisWeek.get("submissions").asInt()).isEqualTo(4);
        assertThat(thisWeek.get("accepted").asInt()).isEqualTo(2);
        assertThat(weeks.get(0).get("submissions").asInt()).isZero();

        int skills = 0;
        for (JsonNode item : a.get("skills")) {
            skills += item.get("count").asInt();
        }
        int trackSkills = MAPPER.readTree(mvc.perform(get("/api/users/{id}/today", userId))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).get("total").asInt();
        assertThat(skills).as("트랙 안 Skill 수와 같다").isEqualTo(trackSkills).isPositive();
        assertThat(a.get("mockTests")).isEmpty();
    }

    @Test
    @DisplayName("제출이 없는 사용자는 0 이고, 없는 사용자는 404, 시험 중에는 409 다")
    void emptyMissingAndLocked() throws Exception {
        JsonNode a = analytics(userId);
        assertThat(a.get("submissions").get("total").asInt()).isZero();
        assertThat(a.get("solved").get("problems").asInt()).isZero();
        assertThat(mvc.perform(get("/api/users/{id}/analytics", 987654321L)).andReturn()
                .getResponse().getStatus()).isEqualTo(404);

        long testId = MAPPER.readTree(mvc.perform(post("/api/users/{id}/mock-tests", userId))
                .andReturn().getResponse().getContentAsString()).get("mockTestId").asLong();
        assertThat(mvc.perform(get("/api/users/{id}/analytics", userId)).andReturn()
                .getResponse().getStatus()).as("시험 중").isEqualTo(409);
        mvc.perform(post("/api/mock-tests/{id}/finish", testId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"userId\": %d}".formatted(userId)));
        JsonNode after = analytics(userId);
        assertThat(after.get("mockTests")).as("끝난 시험이 기록에 들어간다").hasSize(1);
        assertThat(after.get("mockTests").get(0).get("mockTestId").asLong()).isEqualTo(testId);
        assertThat(after.get("mockTests").get(0).get("solved").asInt()).isZero();
    }
}
