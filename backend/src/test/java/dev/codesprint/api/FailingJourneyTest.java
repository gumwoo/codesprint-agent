package dev.codesprint.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codesprint.judge.JudgeJobRepository;
import dev.codesprint.judge.JudgeJobRow;
import dev.codesprint.learning.persistence.UserRepository;
import dev.codesprint.learning.persistence.UserRow;
import dev.codesprint.learning.service.JudgeResultPoller;
import dev.codesprint.reviewer.ReviewerOutput;
import dev.codesprint.reviewer.ReviewerPort;
import dev.codesprint.support.MovableClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
 * 틀리면서 걷는다. {@link GuidedJourneyTest} 의 반대편이다.
 *
 * <p>ADR-0028 이 남긴 위험이 "한 갈래만 걷는다" 였다 — 전부 {@code ACCEPTED} 로 푸는
 * 길이다. 틀리는 길에는 <b>정답 길에 없는 분기가 셋</b> 있다.
 *
 * <pre>
 *   같은 문제 3회 연속 실패   -&gt; REVIEW_CONCEPT (자료를 보여준다)
 *   확정된 Mistake            -&gt; MICRO_DRILL   (드릴 문제로 보낸다)
 *   그 밖의 실패              -&gt; 같은 Skill 안에서 교정
 * </pre>
 *
 * <p>여기서도 <b>아무것도 고르지 않는다.</b> 진단이 준 첫 문제에서 출발해 매번
 * {@code nextAction} 이 가리키는 곳으로만 간다.
 */
@SpringBootTest
@Import(MovableClock.Installed.class)
class FailingJourneyTest {

    private static final String EXTERNAL_URL = System.getenv("TEST_DB_URL");
    private static PostgreSQLContainer<?> container;

    static {
        if (EXTERNAL_URL == null) {
            container = new PostgreSQLContainer<>("postgres:16-alpine");
            container.start();
        }
    }

    /**
     * 대본대로 답하는 Reviewer.
     *
     * <p>진짜 모델을 부르지 않는다. 여기서 보는 것은 <b>확정된 Mistake 가 드릴로
     * 이어지는가</b>이지 모델이 그 Mistake 를 맞히는가가 아니다. 후자는
     * {@code gradle evalReviewer} 가 실제 모델로 따로 잰다(ADR-0016).
     */
    static class ScriptedReviewer implements ReviewerPort {

        ReviewerOutput scripted;

        @Override
        public Optional<ReviewerOutput> review(Request request) {
            return Optional.ofNullable(scripted);
        }

        @Override
        public String promptVersion() {
            return "journey-v1";
        }
    }

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        ScriptedReviewer scriptedReviewer() {
            return new ScriptedReviewer();
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
    private static final int STEP_LIMIT = 30;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository users;

    @Autowired
    private JudgeJobRepository jobs;

    @Autowired
    private JudgeResultPoller poller;

    @Autowired
    private MovableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ScriptedReviewer reviewer;

    private MockMvc mvc;
    private Long userId;

    private final List<String> trail = new ArrayList<>();

    /** 걸으면서 만난 행동들. 무엇을 실제로 지나왔는지 이것으로 말한다. */
    private final Set<String> actionsSeen = new LinkedHashSet<>();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        userId = users.save(new UserRow(
                "fail-" + System.nanoTime() + "@codesprint.dev", "실패여정")).id();
        reviewer.scripted = null;
    }

    private JsonNode getJson(String url, Object... args) throws Exception {
        return MAPPER.readTree(mvc.perform(get(url, args))
                .andReturn().getResponse().getContentAsString());
    }

    /** 제출하고 <b>틀린 것으로</b> 채점을 끝낸다. */
    private long fail(String problemCode) throws Exception {
        String body = """
                {"userId": %d, "language": "PYTHON", "sourceCode": "print(1)",
                 "solveSeconds": 90}
                """.formatted(userId);
        long submissionId = MAPPER.readTree(
                mvc.perform(post("/api/problems/{code}/submit", problemCode)
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andReturn().getResponse().getContentAsString())
                .get("submissionId").asLong();

        JudgeJobRow job = jobs.findBySubmissionId(submissionId).orElseThrow();
        // case 4 만 실패시킨다. 전부 실패시키면 어떤 태그든 만족해 버려서 실패의
        // 모양이 근거가 되지 못한다(ADR-0015 의 대조군).
        jdbc.update("UPDATE judge_jobs SET status = 'DONE', result = ?::jsonb WHERE id = ?",
                """
                {"status": "WRONG_ANSWER", "passed": 5, "total": 6, "executionMs": 90,
                 "memoryKb": 20480, "failedCaseId": 4, "stderr": null,
                 "cases": [{"id": 1, "status": "ACCEPTED", "executionMs": 10},
                           {"id": 2, "status": "ACCEPTED", "executionMs": 10},
                           {"id": 3, "status": "ACCEPTED", "executionMs": 10},
                           {"id": 4, "status": "WRONG_ANSWER", "executionMs": 10}]}
                """, job.id());
        poller.applyFinishedJobs();
        return submissionId;
    }

    private String lastProblemOf(long submissionId) {
        return jdbc.queryForObject(
                "SELECT p.code FROM submissions s JOIN problems p ON p.id = s.problem_id"
                        + " WHERE s.id = ?",
                String.class, submissionId);
    }

    /**
     * 지금 어디로 가야 하는가. {@link GuidedJourneyTest} 와 같은 입구를 쓴다.
     *
     * @return 풀 문제 code. 문제를 주지 않는 행동이면 null 이고, 그때는 <b>그 행동이
     *     무엇을 대신 주는지</b>를 여기서 확인한다.
     */
    private String whereNext(Long lastSubmissionId) throws Exception {
        JsonNode diagnostic = getJson("/api/users/{id}/diagnostic", userId);
        if (!diagnostic.get("done").asBoolean() && !diagnostic.get("problem").isNull()) {
            String code = diagnostic.get("problem").get("code").asText();
            trail.add("진단 -> " + code);
            return code;
        }
        if (lastSubmissionId == null) {
            return null;
        }

        JsonNode next = getJson("/api/submissions/{id}/next-problem", lastSubmissionId);
        String action = next.get("action").asText();
        actionsSeen.add(action);

        if (!next.get("problem").isNull()) {
            String code = next.get("problem").get("code").asText();
            trail.add(action + " -> " + code);
            return code;
        }

        assertThat(next.get("reason").asText())
                .as("갈 곳이 없으면 이유라도 있어야 한다. 걸어온 길: " + trail)
                .isNotBlank();

        if ("REVIEW_CONCEPT".equals(action)) {
            // **자료가 그 자리의 갈 곳이다.** 없으면 사용자는 "개념을 다시 보라" 는
            // 말만 듣고 아무것도 받지 못한다.
            assertThat(next.get("concept").isNull())
                    .as("REVIEW_CONCEPT 인데 자료가 없다. 걸어온 길: " + trail)
                    .isFalse();
            assertThat(next.get("concept").get("keyPoints")).isNotEmpty();
            trail.add("REVIEW_CONCEPT -> 자료(" + next.get("targetSkill").asText() + ")");
            return null;
        }

        trail.add(action + " -> (문제 없음: " + next.get("reason").asText() + ")");
        return null;
    }

    @Test
    @DisplayName("계속 틀려도 매 걸음 갈 곳이 있다")
    void everyFailingStepLeadsSomewhere() throws Exception {
        Long last = null;

        for (int step = 0; step < STEP_LIMIT; step++) {
            String code = whereNext(last);
            if (code == null) {
                if (last == null) {
                    throw new AssertionError("첫 걸음부터 갈 곳이 없다. 걸어온 길: " + trail);
                }
                // 개념을 읽었으면 그 문제를 다시 푼다. 그것이 이 행동이 기대하는 다음이다.
                code = lastProblemOf(last);
            }
            last = fail(code);
            clock.advance(Duration.ofHours(1));
        }

        assertThat(trail).as("걸어온 길").isNotEmpty();
        assertThat(trail)
                .as("갈 곳도 자료도 주지 않은 걸음이 있다. 걸어온 길: " + trail)
                .noneMatch(step -> step.contains("문제 없음"));
    }

    @Test
    @DisplayName("같은 문제를 세 번 틀리면 개념 자료로 데려간다")
    void threeFailuresLeadToTheConceptMaterial() throws Exception {
        // 진단이 준 문제에서 시작해 같은 문제만 계속 틀린다.
        String code = whereNext(null);
        assertThat(code).as("진단이 첫 문제를 준다").isNotNull();

        Long last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            last = fail(code);
            clock.advance(Duration.ofHours(1));
        }

        JsonNode next = getJson("/api/submissions/{id}/next-problem", last);
        assertThat(next.get("action").asText())
                .as("3회 연속 실패의 다음은 개념이다")
                .isEqualTo("REVIEW_CONCEPT");
        assertThat(next.get("concept").isNull())
                .as("그 자리의 갈 곳은 자료다")
                .isFalse();
        assertThat(next.get("concept").get("skillCode").asText())
                .isEqualTo(next.get("targetSkill").asText());
    }

    @Test
    @DisplayName("확정된 실수는 드릴 문제로 데려간다")
    void aConfirmedMistakeLeadsToADrill() throws Exception {
        // 같은 실수가 서로 다른 문제에서 반복되면 확정된다(§21-B). 두 문제 모두
        // BOUNDARY_CHECK 를 commonMistakes 에 갖고 있어야 한다(ADR-0029).
        reviewer.scripted = new ReviewerOutput("WRONG", "BOUNDARY_CHECK", List.of(), 0.85,
                List.of("BFS_GRID_TRAVERSAL"), List.of(4), false, true, "경계를 빠뜨렸다");

        fail("P02_GRID_TRAVERSAL");
        clock.advance(Duration.ofHours(1));
        long second = fail("P03_CONNECTED_COMPONENT");

        JsonNode next = getJson("/api/submissions/{id}/next-problem", second);
        assertThat(next.get("action").asText()).isEqualTo("MICRO_DRILL");
        assertThat(next.get("problem").isNull())
                .as("드릴은 실제로 풀 문제를 준다 - 없으면 갈 곳 없는 액션이다")
                .isFalse();
        assertThat(next.get("problem").get("kind").asText()).isEqualTo("MICRO_DRILL");
    }
}
