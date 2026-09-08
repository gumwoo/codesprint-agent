package dev.codesprint.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codesprint.judge.JudgeJobRepository;
import dev.codesprint.judge.JudgeJobRow;
import dev.codesprint.learning.persistence.ReviewScheduleRepository;
import dev.codesprint.learning.persistence.ReviewScheduleRow;
import dev.codesprint.learning.persistence.UserRepository;
import dev.codesprint.learning.persistence.UserRow;
import dev.codesprint.learning.service.JudgeResultPoller;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * 간격 복습. 정본: PRD §79, ADR-0021.
 *
 * <p>여기서 확인하는 것은 둘이다 — <b>복습을 문제 종류가 아니라 일정이 정하는가</b>,
 * 그리고 <b>그 길 끝에 실제로 {@code MASTERED} 가 있는가.</b>
 *
 * <p>시계를 갈아 끼운다. 그러지 않으면 이 저장소에서 간격 복습을 한 번도 검증할 수
 * 없다 — 최소 하루를 기다려야 한다. <b>이 우회는 테스트에만 있다</b>: 프로덕션에
 * 시간을 앞당기는 수단을 두면 그것으로 {@code MASTERED} 를 만들 수 있다.
 */
@SpringBootTest
class ReviewLoopTest {

    /** 테스트가 옮길 수 있는 시계. */
    static class MovableClock extends Clock {

        private Instant now = Instant.parse("2026-09-08T09:00:00Z");

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }
    }

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        MovableClock movableClock() {
            return new MovableClock();
        }
    }

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
    private static final String SKILL = "BFS_SHORTEST_PATH";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository users;

    @Autowired
    private JudgeJobRepository jobs;

    @Autowired
    private JudgeResultPoller poller;

    @Autowired
    private ReviewScheduleRepository schedules;

    @Autowired
    private MovableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mvc;
    private Long userId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        userId = users.save(new UserRow(
                "review-" + System.nanoTime() + "@codesprint.dev", "복습테스트")).id();
    }

    /** 제출하고 채점 결과까지 반영한다. 제출 시각은 지금 시계다. */
    private long solve(String problemCode, String judgeStatus) throws Exception {
        return solve(problemCode, judgeStatus, clock.instant());
    }

    /**
     * 제출 시각과 처리 시각을 <b>따로</b> 준다.
     *
     * <p>채점은 큐를 지나므로 그 둘이 같지 않다. 실제로 다를 수 있다는 것을 테스트가
     * 표현할 수 있어야 만기 경계를 확인할 수 있다.
     */
    private long solve(String problemCode, String judgeStatus, java.time.Instant submittedAt)
            throws Exception {
        String body = """
                {"userId": %d, "language": "PYTHON", "sourceCode": "print(1)",
                 "hintLevel": 0, "solutionViewed": false, "solveSeconds": 90}
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

        // 제출 시각도 시계를 따라야 한다. 그러지 않으면 daysSinceLast 가 실제 시각과
        // 테스트 시각 사이에서 계산되어 아무 의미가 없다.
        jdbc.update("UPDATE submissions SET submitted_at = ? WHERE id = ?",
                java.sql.Timestamp.from(submittedAt), submissionId);
        poller.applyFinishedJobs();
        return submissionId;
    }

    private JsonNode nextAction(long submissionId) throws Exception {
        return MAPPER.readTree(mvc.perform(get("/api/submissions/{id}", submissionId))
                .andReturn().getResponse().getContentAsString())
                .get("result").get("nextAction");
    }

    /**
     * 진단을 끝내고 그 Skill 을 점수 문턱까지 올린다.
     *
     * <p>아홉 번인 이유는 <b>confidence</b> 다. mastery 는 첫 AC 에 이미 0.93 이지만
     * confidence 는 제출마다 0.069 씩만 오르고, 문턱이 0.60 이다(Addendum §17).
     * "잘할 것 같다" 와 "그 판단을 믿을 수 있다" 를 나눠 두었기 때문이다.
     */
    private void reachTheThreshold() throws Exception {
        for (int i = 0; i < 9; i++) {
            solve("P05_SHORTEST_PATH", "ACCEPTED");
            clock.advance(Duration.ofHours(1));
        }
    }

    @Test
    @DisplayName("점수가 문턱을 넘으면 복습을 예약한다")
    void crossingTheThresholdSchedulesAReview() throws Exception {
        reachTheThreshold();

        ReviewScheduleRow schedule =
                schedules.findByUserIdAndSkillCode(userId, SKILL).orElse(null);
        assertThat(schedule).as("복습이 잡혀야 한다").isNotNull();
        assertThat(schedule.intervalDays()).as("첫 간격은 1일이다(PRD §79)").isEqualTo(1);
        assertThat(schedule.dueAt()).isAfter(clock.instant());
    }

    @Test
    @DisplayName("예약을 다시 잡지 않는다")
    void anExistingScheduleIsNotReset() throws Exception {
        reachTheThreshold();
        ReviewScheduleRow first =
                schedules.findByUserIdAndSkillCode(userId, SKILL).orElseThrow();

        // 만기 전에 같은 Skill 을 또 풀어도 일정이 되돌아가면 안 된다. 되돌아가면
        // 30일까지 올라간 간격이 매번 1일로 리셋되어 영원히 첫 칸에 머문다.
        clock.advance(Duration.ofHours(2));
        solve("P09_BFS_VARIANT_A", "ACCEPTED");

        ReviewScheduleRow after =
                schedules.findByUserIdAndSkillCode(userId, SKILL).orElseThrow();
        assertThat(after.id()).isEqualTo(first.id());
        assertThat(after.dueAt()).isEqualTo(first.dueAt());
    }

    @Test
    @DisplayName("만기 전에는 REVIEW 문제를 풀어도 복습이 아니다")
    void solvingAReviewProblemEarlyIsNotAReview() throws Exception {
        // **이것이 ADR-0021 의 존재 이유다.** 목록에서 kind: REVIEW 를 찾아 바로 풀면
        // 복습으로 세어지는 구조였다면, 사용자가 자기 MASTERED 를 만들 수 있다.
        reachTheThreshold();
        clock.advance(Duration.ofHours(1));

        solve("P10_BFS_REVIEW", "ACCEPTED");

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM skill_evidence WHERE user_id = ? "
                        + "AND evidence_type = 'REVIEW_RESULT'", Long.class, userId))
                .as("만기 전 REVIEW 문제는 복습 Evidence 가 아니다").isZero();
    }

    @Test
    @DisplayName("만기가 되면 복습으로 보낸다")
    void aDueReviewBecomesTheNextAction() throws Exception {
        reachTheThreshold();
        clock.advance(Duration.ofDays(2));

        long submissionId = solve("P02_GRID_TRAVERSAL", "WRONG_ANSWER");

        JsonNode action = nextAction(submissionId);
        assertThat(action.get("type").asText()).isEqualTo("REVIEW_DUE");
        assertThat(action.get("targetSkill").asText()).isEqualTo(SKILL);
    }

    @Test
    @DisplayName("만기 뒤의 제출은 종류와 무관하게 복습이다")
    void afterTheDueDateAnyProblemOfThatSkillCounts() throws Exception {
        // 복습은 **일정**이 정한다. kind: REVIEW 가 있는 Skill 은 지금 하나뿐이라,
        // 종류를 요구하면 나머지 일곱은 영원히 MASTERED 가 될 수 없다.
        reachTheThreshold();
        clock.advance(Duration.ofDays(2));

        solve("P09_BFS_VARIANT_A", "ACCEPTED");   // NORMAL 문제다

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM skill_evidence WHERE user_id = ? AND skill_code = ? "
                        + "AND evidence_type = 'REVIEW_RESULT'", Long.class, userId, SKILL))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("복습에 성공하면 간격이 늘고 실패하면 줄어든다")
    void theIntervalGrowsOnSuccessAndShrinksOnFailure() throws Exception {
        reachTheThreshold();

        clock.advance(Duration.ofDays(2));
        solve("P09_BFS_VARIANT_A", "ACCEPTED");
        assertThat(schedules.findByUserIdAndSkillCode(userId, SKILL).orElseThrow()
                .intervalDays()).as("1 → 3").isEqualTo(3);

        clock.advance(Duration.ofDays(4));
        solve("P09_BFS_VARIANT_A", "WRONG_ANSWER");
        assertThat(schedules.findByUserIdAndSkillCode(userId, SKILL).orElseThrow()
                .intervalDays()).as("실패하면 3 → 1").isEqualTo(1);

        // **0 으로 내려가지 않는다.** 내려가면 같은 자리에서 두 번 제출하는 것이
        // 간격 복습이 되고, retention 이 아무것도 재지 못한다.
        clock.advance(Duration.ofDays(2));
        solve("P09_BFS_VARIANT_A", "WRONG_ANSWER");
        assertThat(schedules.findByUserIdAndSkillCode(userId, SKILL).orElseThrow()
                .intervalDays()).as("바닥은 1일이다").isEqualTo(1);
    }

    @Test
    @DisplayName("만기 직전에 낸 제출은 늦게 반영돼도 복습이 아니다")
    void aSubmissionMadeBeforeTheDueDateIsNotAReview() throws Exception {
        // 채점은 큐를 지나므로 제출 시각과 처리 시각이 다르다. 처리 시각으로 판정하면
        // **만기 전에 낸 제출이 복습이 된다** - 그리고 daysSinceLast 는 제출 시각으로
        // 재므로 분류와 계산의 기준이 서로 달라진다.
        reachTheThreshold();
        ReviewScheduleRow before =
                schedules.findByUserIdAndSkillCode(userId, SKILL).orElseThrow();

        Instant justBeforeDue = before.dueAt().minus(Duration.ofMinutes(1));
        clock.advance(Duration.ofDays(2));          // 처리 시각은 만기를 지났다

        solve("P09_BFS_VARIANT_A", "ACCEPTED", justBeforeDue);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM skill_evidence WHERE user_id = ? "
                        + "AND evidence_type = 'REVIEW_RESULT'", Long.class, userId))
                .as("만기 전 제출이다").isZero();
        ReviewScheduleRow after =
                schedules.findByUserIdAndSkillCode(userId, SKILL).orElseThrow();
        assertThat(after.dueAt()).as("일정이 소비되면 안 된다").isEqualTo(before.dueAt());
    }

    @Test
    @DisplayName("우리 잘못이나 문법 오류로는 복습이 실패하지 않는다")
    void ourFailuresDoNotConsumeTheReview() throws Exception {
        // 일반 제출은 producesEvidence() 로 이미 이것을 막고 있었는데, 복습 경로만
        // 그 검사를 우회했다. 그대로 두면 **하네스가 죽었다는 이유로 복습이 실패로
        // 기록되고 간격이 줄어든다** - 사용자는 아무것도 하지 않았는데.
        reachTheThreshold();
        clock.advance(Duration.ofDays(2));
        ReviewScheduleRow before =
                schedules.findByUserIdAndSkillCode(userId, SKILL).orElseThrow();

        for (String status : new String[] {"SYSTEM_ERROR", "COMPILE_ERROR"}) {
            solve("P09_BFS_VARIANT_A", status);

            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM skill_evidence WHERE user_id = ? "
                            + "AND evidence_type = 'REVIEW_RESULT'", Long.class, userId))
                    .as(status + " 로는 복습 Evidence 를 만들지 않는다").isZero();

            ReviewScheduleRow after =
                    schedules.findByUserIdAndSkillCode(userId, SKILL).orElseThrow();
            assertThat(after.intervalDays()).as(status + " 로 간격이 줄면 안 된다")
                    .isEqualTo(before.intervalDays());
            assertThat(after.dueAt()).as(status + " 로 일정이 밀리면 안 된다")
                    .isEqualTo(before.dueAt());
        }

        // 그리고 그 뒤에 제대로 풀면 복습은 여전히 살아 있다.
        solve("P09_BFS_VARIANT_A", "ACCEPTED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM skill_evidence WHERE user_id = ? "
                        + "AND evidence_type = 'REVIEW_RESULT'", Long.class, userId))
                .as("복습 기회가 사라지지 않았다").isEqualTo(1L);
    }

    @Test
    @DisplayName("복습에 성공하면 MASTERED 에 도달한다")
    void aSuccessfulReviewReachesMastered() throws Exception {
        // **이 경로가 이 PR 의 존재 이유다.** 그전까지 reviewSucceeded 를 만드는
        // 런타임 경로가 없어 어떤 사용자도 MASTERED 가 될 수 없었다.
        reachTheThreshold();
        clock.advance(Duration.ofDays(2));

        long submissionId = solve("P09_BFS_VARIANT_A", "ACCEPTED");

        String status = MAPPER.readTree(
                mvc.perform(get("/api/users/{id}/skills", userId))
                        .andReturn().getResponse().getContentAsString())
                .get("skills").findValues("skillCode").toString();
        assertThat(status).contains(SKILL);

        assertThat(jdbc.queryForObject(
                "SELECT status FROM user_skills WHERE user_id = ? AND skill_code = ?",
                String.class, userId, SKILL))
                .as("복습까지 마쳤으면 MASTERED 다")
                .isEqualTo("MASTERED");

        assertThat(nextAction(submissionId).get("type").asText())
                .as("MASTERED 다음은 UNLOCK_NEXT 다").isEqualTo("UNLOCK_NEXT");
    }
}
