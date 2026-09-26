package dev.codesprint.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import dev.codesprint.learning.persistence.UserRepository;
import dev.codesprint.learning.persistence.UserRow;
import dev.codesprint.reviewer.PromptTemplate;
import dev.codesprint.explain.PromptExplainer;
import dev.codesprint.explain.ExplainPort;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Explain Back. 정본: PRD §148, ADR-0050.
 *
 * <p>진짜 모델은 부르지 않는다. 실제 {@link PromptExplainer} 에 대본을 읽는 가짜 모델을 끼워, 프롬프트
 * 렌더링 · 계약 검증 · 거절 경로를 그대로 지나게 한다.
 */
@SpringBootTest
class ExplainTest {

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

    /** 대본을 읽는 모델과, 켜고 끌 수 있는 Explain Back. */
    static class ScriptedExplainer implements ExplainPort {
        String reply = "";
        String lastPrompt;
        boolean on = true;
        private final PromptExplainer real = new PromptExplainer(prompt -> {
            lastPrompt = prompt;
            return reply;
        }, PromptTemplate.load("explain-v1"), "explain-v1");

        @Override
        public boolean enabled() {
            return on;
        }

        @Override
        public Optional<Analysis> analyze(Request request) {
            return real.analyze(request);
        }

        @Override
        public String promptVersion() {
            return real.promptVersion();
        }
    }

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        ScriptedExplainer scriptedExplainer() {
            return new ScriptedExplainer();
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROBLEM = "P02_GRID_TRAVERSAL";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ScriptedExplainer explainer;

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
                "explain-" + System.nanoTime() + "@codesprint.dev", "설명테스트", "JOB")).id();
        explainer.on = true;
        explainer.reply = reply("큐에 넣을 때 방문 표시를 한다", "격자 밖 좌표를 먼저 거른다",
                "격자 밖을 먼저 거르지 않으면 무엇이 깨지는가?");
        explainer.lastPrompt = null;
    }

    private static String reply(String covered, String missing, String follow) {
        var node = MAPPER.createObjectNode();
        node.set("coveredPoints", MAPPER.createArrayNode().add(covered));
        node.set("missingPoints", MAPPER.createArrayNode().add(missing));
        node.putNull("misconception");
        node.put("followUpQuestion", follow);
        return node.toString();
    }

    private void solve(String status) throws Exception {
        String submit = MAPPER.createObjectNode().put("userId", userId).put("language", "PYTHON")
                .put("sourceCode", "x").toString();
        long id = MAPPER.readTree(mvc.perform(post("/api/problems/{code}/submit", PROBLEM)
                        .contentType(MediaType.APPLICATION_JSON).content(submit))
                .andReturn().getResponse().getContentAsString()).get("submissionId").asLong();
        var job = jobs.findBySubmissionId(id).orElseThrow();
        boolean ok = "ACCEPTED".equals(status);
        var result = MAPPER.createObjectNode().put("status", status).put("passed", ok ? 6 : 3)
                .put("total", 6).put("executionMs", 100).put("memoryKb", 20480);
        if (ok) {
            result.putNull("failedCaseId");
        } else {
            result.put("failedCaseId", 1);
        }
        result.putNull("stderr");
        result.set("cases", MAPPER.createArrayNode());
        dev.codesprint.support.JudgeResultFixture.finish(jdbc, result.toString(), job.id());
        poller.applyFinishedJobs();
    }

    private org.springframework.mock.web.MockHttpServletResponse explain(String text) throws Exception {
        String body = MAPPER.createObjectNode().put("userId", userId).put("explanation", text)
                .toString();
        return mvc.perform(post("/api/problems/{code}/explanations", PROBLEM)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse();
    }

    private String user() {
        return MAPPER.createObjectNode().put("userId", userId).toString();
    }

    private int evidence() {
        return jdbc.queryForObject("select count(*) from skill_evidence where user_id = ?",
                Integer.class, userId);
    }

    @Test
    @DisplayName("푼 문제의 설명을 분석하고, 무엇도 기록하지 않는다 - 질문은 시스템이 정한다")
    void analysesASolvedProblemAndRecordsNothing() throws Exception {
        solve("ACCEPTED");
        int before = evidence();

        var response = explain("큐에 넣을 때 방문 표시를 해서 같은 칸을 두 번 넣지 않기 때문이다.");
        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        java.nio.file.Path path = java.nio.file.Path.of(System.getProperty("codesprint.repoRoot"),
                "contracts", "explain-back.schema.json");
        assertThat(com.networknt.schema.JsonSchemaFactory.getInstance(
                        com.networknt.schema.SpecVersion.VersionFlag.V202012)
                .getSchema(java.nio.file.Files.readString(path, java.nio.charset.StandardCharsets.UTF_8))
                .validate(body)).isEmpty();
        assertThat(body.get("skillCode").asText()).isEqualTo("BFS_GRID_TRAVERSAL");
        assertThat(body.get("missingPoints").get(0).asText()).contains("격자 밖");
        assertThat(body.get("promptVersion").asText()).isEqualTo("explain-v1");
        assertThat(body.get("question").asText()).startsWith("왜 이 문제에서");
        // 프롬프트에 시스템이 정한 질문과 사용자의 설명이 그대로 들어간다
        assertThat(explainer.lastPrompt).contains(body.get("question").asText())
                .contains("같은 칸을 두 번 넣지 않기");
        assertThat(evidence()).as("분석은 Evidence 가 되지 않는다").isEqualTo(before);
    }

    @Test
    @DisplayName("풀지 않은 문제는 409 - 빠진 요점이 힌트 사다리 밖의 힌트가 된다")
    void unsolvedProblemIsWithheld() throws Exception {
        assertThat(explain("아직 안 풀었다").getStatus()).as("제출 없음").isEqualTo(409);
        solve("WRONG_ANSWER");
        assertThat(explain("틀린 뒤").getStatus()).as("틀리기만 했다").isEqualTo(409);
        assertThat(explainer.lastPrompt).as("모델을 부르지 않았다").isNull();
        solve("ACCEPTED");
        assertThat(explain("푼 뒤").getStatus()).as("대조: 풀면 받는다").isEqualTo(200);
    }

    @Test
    @DisplayName("점수를 붙여 온 답은 버린다(502), 꺼져 있으면 503, 빈 설명은 400, 시험 중에는 409")
    void rejections() throws Exception {
        solve("ACCEPTED");
        var scored = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(
                reply("a", "b", null));
        scored.put("score", 0.9);
        explainer.reply = scored.toString();
        assertThat(explain("점수를 달라").getStatus()).as("계약에 없는 score").isEqualTo(502);

        explainer.reply = reply("a", "b", null) + " {\"score\": 0.9}";
        assertThat(explain("뒤에 붙은 점수").getStatus()).as("정상 JSON 뒤에 붙은 내용도 계약 위반")
                .isEqualTo(502);

        explainer.on = false;
        assertThat(explain("꺼짐").getStatus()).isEqualTo(503);
        explainer.on = true;
        assertThat(explain("   ").getStatus()).isEqualTo(400);

        long testId = MAPPER.readTree(mvc.perform(post("/api/users/{id}/mock-tests", userId))
                .andReturn().getResponse().getContentAsString()).get("mockTestId").asLong();
        assertThat(explain("시험 중").getStatus()).isEqualTo(409);
        mvc.perform(post("/api/mock-tests/{id}/finish", testId)
                .contentType(MediaType.APPLICATION_JSON).content(user()));
        explainer.reply = reply("a", "b", null);
        assertThat(explain("끝난 뒤").getStatus()).as("대조: 시험이 끝나면 받는다").isEqualTo(200);
    }
}
