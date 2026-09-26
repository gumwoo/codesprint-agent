package dev.codesprint.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
import dev.codesprint.support.JudgeResultFixture;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
 * 오늘의 계획 · 설정 · 오답 요약 API. 정본: PRD §80 · §115 · §121 · §123 · §152, ADR-0038.
 */
@SpringBootTest
class TodayTest {

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
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Autowired
    private dev.codesprint.judge.JudgeJobRepository jobs;

    @Autowired
    private dev.codesprint.learning.service.JudgeResultPoller poller;

    private MockMvc mvc;
    private long userId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        userId = users.save(new UserRow(
                "today-" + System.nanoTime() + "@codesprint.dev", "오늘", "JOB")).id();
    }

    private static JsonSchema schema(String name) throws Exception {
        Path path = Path.of(System.getProperty("codesprint.repoRoot"), "contracts", name);
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(Files.readString(path, StandardCharsets.UTF_8));
    }

    private int putSettings(String json) throws Exception {
        return mvc.perform(put("/api/users/{id}/settings", userId)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andReturn().getResponse().getStatus();
    }

    private JsonNode today() throws Exception {
        return MAPPER.readTree(mvc.perform(get("/api/users/{id}/today", userId))
                .andReturn().getResponse().getContentAsString());
    }

    @Test
    @DisplayName("설정은 두 키를 모두 보내야 한다 - null 은 정하지 않았다, 생략은 400")
    void settingsNeedBothKeys() throws Exception {
        assertThat(putSettings("{\"dailyMinutes\": 60}")).isEqualTo(400);
        assertThat(putSettings("{\"dailyMinutes\": 5, \"examDate\": null}")).isEqualTo(400);
        assertThat(putSettings("{\"dailyMinutes\": 60, \"examDate\": \"내일\"}")).isEqualTo(400);
        assertThat(putSettings("{\"dailyMinutes\": null, \"examDate\": null}")).isEqualTo(200);

        JsonNode user = MAPPER.readTree(mvc.perform(get("/api/users/{id}", userId))
                .andReturn().getResponse().getContentAsString());
        assertThat(schema("user.schema.json").validate(user)).isEmpty();
        assertThat(user.get("dailyMinutes").isNull()).isTrue();
    }

    @Test
    @DisplayName("하루 시간을 정하지 않으면 계획은 시간을 나누지 않는다")
    void unbudgetedPlan() throws Exception {
        JsonNode plan = today();
        assertThat(schema("today.schema.json").validate(plan)).isEmpty();
        assertThat(plan.get("totalMinutes").isNull()).isTrue();
        assertThat(plan.get("blocks").size()).isBetween(1, 3);
        // 신규 사용자는 진단 중이다. 계획도 진단이 가리키는 곳을 먼저 가리킨다(ADR-0019).
        JsonNode step = MAPPER.readTree(mvc.perform(get("/api/users/{id}/diagnostic", userId))
                .andReturn().getResponse().getContentAsString());
        assertThat(plan.get("blocks").get(0).get("type").asText()).isEqualTo("DIAGNOSE");
        assertThat(plan.get("blocks").get(0).get("problem").get("code").asText())
                .isEqualTo(step.get("problem").get("code").asText());
    }

    @Test
    @DisplayName("하루 시간을 정하면 그 안에 채운다")
    void budgetedPlan() throws Exception {
        assertThat(putSettings("{\"dailyMinutes\": 90, \"examDate\": null}")).isEqualTo(200);
        JsonNode plan = today();
        assertThat(schema("today.schema.json").validate(plan)).isEmpty();
        assertThat(plan.get("totalMinutes").asInt()).isEqualTo(90);
        int sum = 0;
        for (JsonNode block : plan.get("blocks")) {
            sum += block.get("minutes").asInt();
        }
        assertThat(sum).isLessThanOrEqualTo(90);
        assertThat(plan.get("mode").asText()).isEqualTo("NORMAL");
    }

    @Test
    @DisplayName("시험이 가까우면 시험 모드이고, 지난 시험은 없는 시험이다")
    void examDate() throws Exception {
        String soon = java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(3).toString();
        assertThat(putSettings("{\"dailyMinutes\": 60, \"examDate\": \"" + soon + "\"}"))
                .isEqualTo(200);
        JsonNode plan = today();
        assertThat(plan.get("examInDays").asInt()).isEqualTo(3);
        assertThat(plan.get("mode").asText()).isEqualTo("EXAM");

        String past = java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1).toString();
        assertThat(putSettings("{\"dailyMinutes\": 60, \"examDate\": \"" + past + "\"}"))
                .isEqualTo(200);
        JsonNode after = today();
        assertThat(after.get("examInDays").isNull()).isTrue();
        assertThat(after.get("mode").asText()).isEqualTo("NORMAL");
    }

    /** 틀린 제출 하나를 채점까지 반영한다. 그 Skill 에 Evidence 가 생겨 계획이 연습 블록을 준다. */
    private void wrong(String code) throws Exception {
        String body = MAPPER.createObjectNode().put("userId", userId).put("language", "PYTHON")
                .put("sourceCode", "x").toString();
        long id = MAPPER.readTree(mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/api/problems/{code}/submit", code)
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString()).get("submissionId").asLong();
        var result = MAPPER.createObjectNode().put("status", "WRONG_ANSWER").put("passed", 1)
                .put("total", 6).put("executionMs", 100).put("memoryKb", 20480)
                .put("failedCaseId", 2);
        result.putNull("stderr");
        result.set("cases", MAPPER.createArrayNode());
        dev.codesprint.support.JudgeResultFixture.finish(jdbc, result.toString(),
                jobs.findBySubmissionId(id).orElseThrow().id());
        poller.applyFinishedJobs();
    }

    private void judge(String code, String status) throws Exception {
        String body = MAPPER.createObjectNode().put("userId", userId).put("language", "PYTHON")
                .put("sourceCode", "x").toString();
        long id = MAPPER.readTree(mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/api/problems/{code}/submit", code)
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString()).get("submissionId").asLong();
        boolean ok = "ACCEPTED".equals(status);
        var result = MAPPER.createObjectNode().put("status", status).put("passed", ok ? 6 : 1)
                .put("total", 6).put("executionMs", 100).put("memoryKb", 20480);
        if (ok) {
            result.putNull("failedCaseId");
        } else {
            result.put("failedCaseId", 2);
        }
        result.putNull("stderr");
        result.set("cases", MAPPER.createArrayNode());
        dev.codesprint.support.JudgeResultFixture.finish(jdbc, result.toString(),
                jobs.findBySubmissionId(id).orElseThrow().id());
        poller.applyFinishedJobs();
    }

    @Test
    @DisplayName("시험 모드에서 진단을 따라가는 내내 진단 블록이 남고, 진단이 묻는 문제는 곧 만들 시험에 들어가지 않는다")
    void diagnosisStaysAndIsNotInTheUpcomingTest() throws Exception {
        // 검증 에이전트의 재현: INTRO 사용자가 진단을 일곱 걸음 맞히면 진단 문제(P33)가 시험 후보와 겹쳐, 계획에서
        // 진단 블록이 사라지고 그 문제가 시험에 들어갔다.
        userId = users.save(new UserRow(
                "diag-exam-" + System.nanoTime() + "@codesprint.dev", "진단시험", "INTRO")).id();
        String soon = java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(3).toString();
        assertThat(putSettings("{\"dailyMinutes\": 600, \"examDate\": \"" + soon + "\"}"))
                .isEqualTo(200);
        for (int step = 0; step < 12; step++) {
            JsonNode diag = MAPPER.readTree(mvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                    .get("/api/users/{id}/diagnostic", userId))
                    .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
            if (diag.get("done").asBoolean() || diag.get("problem").isNull()) {
                break;
            }
            String probe = diag.get("problem").get("code").asText();
            JsonNode plan = today();
            boolean diagnose = false;
            for (JsonNode block : plan.get("blocks")) {
                if ("DIAGNOSE".equals(block.get("type").asText())) {
                    diagnose = true;
                    assertThat(block.get("problem").get("code").asText()).as("걸음 " + step).isEqualTo(probe);
                }
            }
            assertThat(diagnose).as("걸음 " + step + " - 진단 블록은 빠질 수 없다(" + probe + ")").isTrue();
            judge(probe, "ACCEPTED");
        }
    }

    @Test
    @DisplayName("시험 모드에서 최근 7 일 안에 본 모의 시험이 없으면 모의 시험 블록을 주고, 보면 사라진다")
    void examModeAsksForAMockTest() throws Exception {
        // 여러 Skill 에 틀린 제출을 남겨 계획이 연습 블록(다음 문제)을 보여 주게 한다. 연습 문제는 아직 내지 않은
        // 문제라 모의 시험 후보와 겹칠 수 있다 - 겹치지 않는지 보려면 겹칠 수 있는 상황이어야 한다.
        for (String code : new String[] {"P02_GRID_TRAVERSAL", "P05_SHORTEST_PATH",
            "P14_GRAPH_REACHABLE", "P13_EDGE_CELLS", "P11_LIST_BASIC", "P12_GRID_COORDINATE"}) {
            wrong(code);
        }
        String soon = java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(3).toString();
        assertThat(putSettings("{\"dailyMinutes\": 600, \"examDate\": \"" + soon + "\"}"))
                .isEqualTo(200);
        JsonNode plan = today();
        assertThat(schema("today.schema.json").validate(plan)).isEmpty();
        JsonNode mock = null;
        for (JsonNode block : plan.get("blocks")) {
            if ("MOCK_TEST".equals(block.get("type").asText())) {
                mock = block;
            }
        }
        assertThat(mock).as("시험 3 일 전, 모의 시험을 본 적 없음").isNotNull();
        assertThat(mock.get("skillCode").isNull()).isTrue();
        assertThat(mock.get("problem").isNull()).isTrue();

        // 계획이 말한 시간이 시험 탭이 여는 시험의 시간과 같다 - 같은 규칙으로 잰다
        JsonNode test = MAPPER.readTree(mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/api/users/{id}/mock-tests", userId))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        long minutes = java.time.Duration.between(java.time.Instant.parse(test.get("startedAt").asText()),
                java.time.Instant.parse(test.get("endsAt").asText())).toMinutes();
        assertThat(mock.get("minutes").asLong()).isEqualTo(minutes);

        // 계획이 보여 준 문제는 곧 만든 시험에 없다 - 있으면 시작하기 전에 유형이 드러난다(검증 에이전트)
        java.util.Set<String> shown = new java.util.HashSet<>();
        for (JsonNode block : plan.get("blocks")) {
            if (!block.get("problem").isNull()) {
                shown.add(block.get("problem").get("code").asText());
            }
        }
        assertThat(shown).as("대조가 성립하려면 계획이 문제를 보여 줘야 한다").isNotEmpty();
        java.util.List<String> inTest = jdbc.queryForList(
                "select problem_code from mock_test_problems where mock_test_id = ?", String.class,
                test.get("mockTestId").asLong());
        assertThat(inTest).isNotEmpty().doesNotContainAnyElementsOf(shown);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/mock-tests/{id}/finish", test.get("mockTestId").asLong())
                .contentType(MediaType.APPLICATION_JSON).content("{\"userId\": " + userId + "}"));

        JsonNode after = today();
        for (JsonNode block : after.get("blocks")) {
            assertThat(block.get("type").asText()).as("방금 모의 시험을 봤다").isNotEqualTo("MOCK_TEST");
        }

        // 대조: 시험일이 없으면(평소) 모의 시험 블록이 없다 - 막은 것이 시험 전략이다
        long other = users.save(new UserRow(
                "mock-normal-" + System.nanoTime() + "@codesprint.dev", "평소", "JOB")).id();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/users/{id}/settings", other).contentType(MediaType.APPLICATION_JSON)
                .content("{\"dailyMinutes\": 600, \"examDate\": null}"));
        JsonNode normal = MAPPER.readTree(mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/users/{id}/today", other))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        for (JsonNode block : normal.get("blocks")) {
            assertThat(block.get("type").asText()).isNotEqualTo("MOCK_TEST");
        }
    }

    @Test
    @DisplayName("오답 요약이 계약을 지키고, 제출이 없으면 비어 있다")
    void mistakeSummary() throws Exception {
        JsonNode summary = MAPPER.readTree(mvc.perform(get("/api/users/{id}/mistakes", userId))
                .andReturn().getResponse().getContentAsString());
        assertThat(schema("mistake-summary.schema.json").validate(summary)).isEmpty();
        assertThat(summary.get("submissions").asInt()).isZero();
        assertThat(summary.get("mistakes").size()).isZero();
    }

    @Test
    @DisplayName("없는 사용자는 404 다")
    void unknownUser() throws Exception {
        assertThat(mvc.perform(get("/api/users/{id}/today", 999999999L))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(get("/api/users/{id}/mistakes", 999999999L))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("그 문제에서 일어날 수 없다고 선언된 탐지는 오답 요약에서 세지 않는다")
    void undeclaredDetectionsAreNotCounted() throws Exception {
        // 검증 에이전트가 재현했다. ADR-0029 는 이런 탐지를 확정에도 재발에도 쓰지 않는데, 요약이
        // 그대로 세서 큐 문제의 격자 경계 주장이 "탐지 1" 로 보였다.
        long submissionId = MAPPER.readTree(mvc.perform(post("/api/problems/{code}/submit",
                        "P01_QUEUE_BASIC").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\": " + userId + ", \"language\": \"PYTHON\","
                                + " \"sourceCode\": \"print(1)\", \"solveSeconds\": 60}"))
                .andReturn().getResponse().getContentAsString()).get("submissionId").asLong();
        String insert = "insert into mistake_detections (submission_id, user_id, mistake_code, role,"
                + " confidence, status, reason, prompt_version, declared_for_problem)"
                + " values (?, ?, ?, 'PRIMARY', 0.7, 'POSSIBLE', 'test', 'reviewer-v1', ?)";
        jdbc.update(insert, submissionId, userId, "BOUNDARY_CHECK", false);
        jdbc.update(insert, submissionId, userId, "OUTPUT_FORMAT", true);   // 대조군

        JsonNode summary = MAPPER.readTree(mvc.perform(get("/api/users/{id}/mistakes", userId))
                .andReturn().getResponse().getContentAsString());
        assertThat(schema("mistake-summary.schema.json").validate(summary)).isEmpty();
        assertThat(summary.get("mistakes").findValuesAsText("mistakeCode"))
                .containsExactly("OUTPUT_FORMAT");
    }

    @Test
    @DisplayName("계약의 날짜 모양이 아닌 시험일은 받지 않는다")
    void examDateShape() throws Exception {
        assertThat(putSettings("{\"dailyMinutes\": 60, \"examDate\": \"+10000-01-01\"}"))
                .isEqualTo(400);
        assertThat(putSettings("{\"dailyMinutes\": 60, \"examDate\": 20260101}")).isEqualTo(400);
    }
}
