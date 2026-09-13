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
import dev.codesprint.support.MovableClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 에이전트가 가리키는 대로만 따라 걷는다.
 *
 * <p><b>이 테스트는 아무것도 고르지 않는다.</b> 문제 목록을 보지 않고, 어떤 문제를
 * 풀지 정하지 않는다. 진단이 준 첫 문제에서 출발해, 그다음부터는 매번
 * {@code nextAction} 이 가리키는 곳으로만 간다.
 *
 * <p>나머지 테스트는 규칙을 하나씩 본다 — 복습이 일정으로 정해지는가, 개념 자료가
 * 붙는가, 다음 Skill 이 열리는가. 전부 <b>테스트가 문제를 손으로 골라 놓고</b> 그
 * 규칙만 확인한다. 그래서 지금까지 아무도 확인하지 않은 것이 하나 남아 있었다 —
 * <b>그 규칙들을 이어 붙이면 실제로 어딘가에 도착하는가.</b>
 *
 * <p>이 저장소의 주장은 "가장 짧은 경로로 독립 풀이 가능한 상태까지" 다. 그 주장이
 * 검사받는 자리가 여기다.
 */
@SpringBootTest
@Import(MovableClock.Installed.class)
class GuidedJourneyTest {

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

    /**
     * 걸음 수 상한. 넘으면 실패다.
     *
     * <p>상한이 있어야 하는 이유는 <b>제자리를 도는 것도 실패이기 때문이다.</b> 갈 곳이
     * 늘 있는데 아무 데도 도착하지 않는 길은, 갈 곳이 없는 길보다 알아채기 어렵다.
     */
    private static final int STEP_LIMIT = 60;

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

    private MockMvc mvc;
    private Long userId;

    /** 걸어온 길. 실패했을 때 <b>어디서 멈췄는지</b>를 이것으로 말한다. */
    private final List<String> trail = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        userId = users.save(new UserRow(
                "journey-" + System.nanoTime() + "@codesprint.dev", "여정")).id();
    }

    private JsonNode getJson(String url, Object... args) throws Exception {
        return MAPPER.readTree(mvc.perform(get(url, args))
                .andReturn().getResponse().getContentAsString());
    }

    /** 제출하고 채점까지 끝낸다. 시각은 지금 시계다. */
    private long solve(String problemCode, String judgeStatus) throws Exception {
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
        jdbc.update("UPDATE judge_jobs SET status = 'DONE', result = ?::jsonb WHERE id = ?",
                """
                {"status": "%s", "passed": 6, "total": 6, "executionMs": 90,
                 "memoryKb": 20480, "failedCaseId": null, "stderr": null, "cases": []}
                """.formatted(judgeStatus), job.id());
        poller.applyFinishedJobs();
        return submissionId;
    }

    private String statusOf(String skillCode) {
        List<String> found = jdbc.queryForList(
                "SELECT status FROM user_skills WHERE user_id = ? AND skill_code = ?",
                String.class, userId, skillCode);
        return found.isEmpty() ? "UNASSESSED" : found.get(0);
    }

    private Set<String> masteredSkills() {
        return new LinkedHashSet<>(jdbc.queryForList(
                "SELECT skill_code FROM user_skills WHERE user_id = ? AND status = 'MASTERED'"
                        + " ORDER BY skill_code",
                String.class, userId));
    }

    /**
     * 지금 어디로 가야 하는가. <b>순서가 곧 화면의 순서다</b> — 진단이 먼저고, 그다음이
     * 직전 제출의 결정이다. 만기 복습은 결정 안에 이미 들어 있다(ADR-0019, ADR-0021).
     *
     * @return 풀어야 할 문제 code, 또는 풀 것이 없으면 null
     */
    private String whereNext(Long lastSubmissionId) throws Exception {
        JsonNode diagnostic = getJson("/api/users/{id}/diagnostic", userId);
        if (!diagnostic.get("done").asBoolean() && !diagnostic.get("problem").isNull()) {
            String code = diagnostic.get("problem").get("code").asText();
            trail.add("진단 -> " + code);
            return code;
        }
        // **만기된 복습은 화면에 따로 입구가 있다.** 직전 제출의 결정만 보면 이것을
        // 놓친다 - 그 결정은 제출 시점에 얼려져 있고, 복습은 그 뒤에 만기가 된다.
        JsonNode dueReview = dueReview();
        if (dueReview != null) {
            String code = dueReview.get("problem").get("code").asText();
            trail.add("복습(" + dueReview.get("skillCode").asText() + ") -> " + code);
            return code;
        }

        if (lastSubmissionId == null) {
            return null;
        }

        JsonNode next = getJson("/api/submissions/{id}/next-problem", lastSubmissionId);
        String action = next.get("action").asText();

        if (!next.get("problem").isNull()) {
            String code = next.get("problem").get("code").asText();
            trail.add(action + " -> " + code);
            return code;
        }

        // 문제를 주지 않는 행동. **이유가 있어야 한다** — 여기가 비어 있으면 사용자는
        // "다음" 을 눌렀는데 아무 일도 일어나지 않는 화면을 본다.
        assertThat(next.get("reason").asText())
                .as("갈 곳이 없으면 이유라도 있어야 한다. 걸어온 길: " + trail)
                .isNotBlank();

        if ("REVIEW_CONCEPT".equals(action)) {
            // 개념을 보고 나면 같은 문제를 다시 푼다. 자료가 없으면 막다른 길이다.
            assertThat(next.get("concept").isNull())
                    .as("REVIEW_CONCEPT 인데 자료가 없다. 걸어온 길: " + trail)
                    .isFalse();
            trail.add("REVIEW_CONCEPT -> 자료를 읽었다");
            return null;
        }
        trail.add(action + " -> (문제 없음: " + next.get("reason").asText() + ")");
        return null;
    }

    /** 지금 만기이고 풀 문제가 붙어 있는 복습. 없으면 null. */
    private JsonNode dueReview() throws Exception {
        for (JsonNode review : getJson("/api/users/{id}/reviews", userId).get("reviews")) {
            if (review.get("due").asBoolean() && !review.get("problem").isNull()) {
                return review;
            }
        }
        return null;
    }

    /**
     * 잡혀 있는 복습이 있으면 시계를 그 만기까지 민다.
     *
     * <p><b>이것이 테스트가 하는 유일한 개입이다.</b> 간격 복습은 최소 하루를 기다려야
     * 하고(ADR-0021), 그러지 않으면 이 길은 절대 끝나지 않는다. 무엇을 풀지는 여전히
     * 고르지 않는다 — 시간만 흐르게 한다.
     *
     * @return 시계를 밀었으면 true
     */
    private boolean waitForTheNextReview() throws Exception {
        JsonNode reviews = getJson("/api/users/{id}/reviews", userId);
        for (JsonNode review : reviews.get("reviews")) {
            if (!review.get("due").asBoolean()) {
                clock.advance(Duration.ofDays(review.get("intervalDays").asInt() + 1L));
                trail.add("시계를 " + review.get("skillCode").asText() + " 만기까지 민다");
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("에이전트가 가리키는 대로만 따라가면 MASTERED 에 닿는다")
    void followingOnlyTheAgentReachesMastered() throws Exception {
        walkUntilMastered();
    }

    /**
     * MASTERED 가 나올 때까지 걷는다.
     *
     * @return <b>MASTERED 를 만든 마지막 제출</b>. 그 제출의 결정이 "다음은 어디인가" 를
     *     들고 있으므로, 그것을 검사하려면 id 가 필요하다.
     */
    private long walkUntilMastered() throws Exception {
        Long lastSubmissionId = null;

        for (int step = 0; step < STEP_LIMIT; step++) {
            if (!masteredSkills().isEmpty()) {
                break;
            }

            String code = whereNext(lastSubmissionId);
            if (code == null) {
                // 지금 풀 것이 없다. 기다리면 생기는가?
                if (waitForTheNextReview()) {
                    continue;
                }
                if (lastSubmissionId != null) {
                    // 개념을 읽은 뒤에는 같은 문제를 다시 푼다. 그것도 아니면 막다른 길이다.
                    JsonNode next = getJson(
                            "/api/submissions/{id}/next-problem", lastSubmissionId);
                    String action = next.get("action").asText();
                    assertThat(action)
                            .as("갈 곳이 없는데 기다려도 생기지 않는다. 걸어온 길: " + trail)
                            .isIn("REVIEW_CONCEPT", "CONTINUE");
                    code = lastProblemOf(lastSubmissionId);
                } else {
                    throw new AssertionError("첫 걸음부터 갈 곳이 없다. 걸어온 길: " + trail);
                }
            }
            // **힌트를 쓰지 않는다.** 독립 풀이여야 MASTERED 의 네 조건을 채운다.
            lastSubmissionId = solve(code, "ACCEPTED");
            clock.advance(Duration.ofHours(1));
        }

        assertThat(masteredSkills())
                .as("걸어온 길: " + trail)
                .isNotEmpty();
        assertThat(lastSubmissionId)
                .as("걸어온 길: " + trail)
                .isNotNull();
        return lastSubmissionId;
    }

    private String lastProblemOf(long submissionId) {
        return jdbc.queryForObject(
                "SELECT p.code FROM submissions s JOIN problems p ON p.id = s.problem_id"
                        + " WHERE s.id = ?",
                String.class, submissionId);
    }

    @Test
    @DisplayName("걷는 동안 갈 곳 없는 걸음이 한 번도 없다")
    void noStepEverLeadsNowhere() throws Exception {
        // 위 테스트가 도착만 본다면 이쪽은 **가는 동안**을 본다. 도착했다는 것이
        // 중간에 빈 화면이 없었다는 뜻은 아니다 - 사용자는 그 빈 화면에서 멈춘다.
        Long lastSubmissionId = null;
        int solved = 0;

        for (int step = 0; step < STEP_LIMIT && masteredSkills().isEmpty(); step++) {
            String code = whereNext(lastSubmissionId);
            if (code == null) {
                if (waitForTheNextReview()) {
                    continue;
                }
                if (lastSubmissionId == null) {
                    break;
                }
                code = lastProblemOf(lastSubmissionId);
            }
            lastSubmissionId = solve(code, "ACCEPTED");
            solved++;
            clock.advance(Duration.ofHours(1));
        }

        assertThat(solved)
                .as("한 문제도 풀지 못했다. 걸어온 길: " + trail)
                .isGreaterThan(0);
        assertThat(trail)
                .as("걸어온 길: " + trail)
                .noneMatch(stepText -> stepText.contains("문제 없음")
                        && !stepText.startsWith("SCHEDULE_REVIEW")
                        && !stepText.startsWith("END_SESSION"));
    }

    @Test
    @DisplayName("MASTERED 다음에도 갈 곳을 준다")
    void afterMasteredThereIsStillSomewhereToGo() throws Exception {
        long lastSubmissionId = walkUntilMastered();

        String mastered = masteredSkills().iterator().next();
        assertThat(statusOf(mastered)).isEqualTo("MASTERED");

        // **여기서 Skill 목록이 비어 있지 않은지만 보면 아무것도 보지 않는 것이다.**
        // 처음에 그렇게 썼고, 리뷰에서 잡혔다. 재현했다 - UNLOCK_NEXT 의 targetSkill 을
        // null 로 만들어도 세 테스트가 전부 통과했다.
        //
        // 실제로 확인해야 하는 것은 그 제출의 결정이 어디를 가리키는가다.
        JsonNode next = getJson("/api/submissions/{id}/next-problem", lastSubmissionId);

        assertThat(next.get("action").asText())
                .as("MASTERED 다음은 UNLOCK_NEXT 다. 걸어온 길: " + trail)
                .isEqualTo("UNLOCK_NEXT");
        assertThat(next.get("targetSkill").isNull())
                .as("어느 Skill 이 열렸는지 말해 준다(ADR-0022)")
                .isFalse();
        assertThat(next.get("targetSkill").asText())
                .as("방금 숙달한 것을 다시 가리키지 않는다")
                .isNotEqualTo(mastered);
        assertThat(next.get("problem").isNull())
                .as("그 Skill 에 실제로 풀 문제까지 있어야 한다. 없으면 '다음으로 가라' 고만"
                        + " 말하고 어디로 가는지는 말하지 않는 액션이 된다")
                .isFalse();
    }
}
