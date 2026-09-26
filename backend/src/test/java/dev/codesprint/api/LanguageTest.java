package dev.codesprint.api;

import dev.codesprint.support.JudgeResultFixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codesprint.judge.JudgeJobRepository;
import dev.codesprint.judge.JudgeJobRow;
import dev.codesprint.learning.persistence.UserRepository;
import dev.codesprint.learning.persistence.UserRow;
import dev.codesprint.learning.service.JudgeResultPoller;
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
 * 제출 언어. 정본: PRD §67 · §164, ADR-0045.
 *
 * <p>언어는 채점 이미지를 고르고, 어떤 Skill 의 증거가 될 수 있는지 정한다. 한 언어에 매인 Skill
 * (PYTHON_LIST_BASIC 등)은 그 언어의 제출만 잰다.
 */
@SpringBootTest
class LanguageTest {

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
                "lang-" + System.nanoTime() + "@codesprint.dev", "언어테스트", "JOB")).id();
    }

    private org.springframework.mock.web.MockHttpServletResponse submit(String code, String language)
            throws Exception {
        return mvc.perform(post("/api/problems/{code}/submit", code)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\": %d, \"language\": \"%s\", \"sourceCode\": \"x\"}"
                                .formatted(userId, language)))
                .andReturn().getResponse();
    }

    private void accept(long submissionId) {
        JudgeJobRow job = jobs.findBySubmissionId(submissionId).orElseThrow();
        JudgeResultFixture.finish(jdbc, """
                {"status": "ACCEPTED", "passed": 5, "total": 5, "executionMs": 100,
                 "memoryKb": 20480, "failedCaseId": null, "stderr": null, "cases": []}
                """, job.id());
        poller.applyFinishedJobs();
    }

    private Integer evidence(String skill) {
        return jdbc.queryForObject(
                "select count(*) from skill_evidence where user_id = ? and skill_code = ?",
                Integer.class, userId, skill);
    }

    @Test
    @DisplayName("Java · C++ 로 낸 제출은 그 언어로 큐에 들어가고, 모르는 언어는 400 이다")
    void languagesReachTheQueue() throws Exception {
        for (String language : new String[] {"JAVA", "CPP", "python"}) {
            var response = submit("P02_GRID_TRAVERSAL", language);
            assertThat(response.getStatus()).as(language).isEqualTo(202);
            long id = MAPPER.readTree(response.getContentAsString()).get("submissionId").asLong();
            assertThat(jdbc.queryForObject(
                    "select language from judge_jobs where submission_id = ?", String.class, id))
                    .as("큐에는 정규화한 이름으로").isEqualTo(language.toUpperCase());
        }
        assertThat(submit("P02_GRID_TRAVERSAL", "RUBY").getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("PRIMARY 가 Python 전용 Skill 인 문제는 Python 으로만 받는다 - 제출도 실행도")
    void languageBoundProblemsAcceptOnlyTheirLanguage() throws Exception {
        assertThat(submit("P11_LIST_BASIC", "JAVA").getStatus()).isEqualTo(400);
        assertThat(mvc.perform(post("/api/problems/{code}/run", "P11_LIST_BASIC")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\": %d, \"language\": \"CPP\", \"sourceCode\": \"x\"}"
                                .formatted(userId)))
                .andReturn().getResponse().getStatus()).as("실행").isEqualTo(400);
        // 대조: 같은 문제를 Python 으로는 받는다
        assertThat(submit("P11_LIST_BASIC", "PYTHON").getStatus()).isEqualTo(202);
    }

    @Test
    @DisplayName("Java 로 푼 것은 SECONDARY 인 Python list Skill 의 증거가 되지 않는다")
    void languageBoundSecondarySkillsNeedTheirLanguage() throws Exception {
        long java = MAPPER.readTree(submit("P12_GRID_COORDINATE", "JAVA").getContentAsString())
                .get("submissionId").asLong();
        accept(java);
        assertThat(evidence("GRID_COORDINATE")).as("PRIMARY 는 언어와 무관하다").isEqualTo(1);
        assertThat(evidence("PYTHON_LIST_BASIC")).as("Python 전용 SECONDARY").isZero();

        // 대조: 같은 문제를 Python 으로 내면 그 Skill 의 증거가 된다
        long python = MAPPER.readTree(submit("P12_GRID_COORDINATE", "PYTHON").getContentAsString())
                .get("submissionId").asLong();
        accept(python);
        assertThat(evidence("PYTHON_LIST_BASIC")).isEqualTo(1);
    }
}
