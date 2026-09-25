package dev.codesprint.api;

import dev.codesprint.support.JudgeResultFixture;
import static org.assertj.core.api.Assertions.assertThat;
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
 * 초기 진단. 정본: ADR-0018.
 *
 * <p>여기서 확인하는 것은 <b>무엇을 묻는가</b> 와 <b>언제 그만 묻는가</b> 다.
 * 점수가 어떻게 계산되는지는 진단의 관심사가 아니다 - 그건 평소 제출과 같은 길을
 * 지나고 이미 다른 곳에서 검사한다.
 */
@SpringBootTest
class DiagnosticTest {

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
    private dev.codesprint.curriculum.CurriculumCatalog catalog;

    @Autowired
    private dev.codesprint.problem.ProblemCatalog problemCatalog;

    /** 이 Skill 아래의 선수 전부(이행적). 진단 규칙을 커리큘럼에서 다시 계산해 기대값으로 쓴다. */
    private java.util.Set<String> below(String skill) {
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        java.util.Deque<String> queue = new java.util.ArrayDeque<>(java.util.List.of(skill));
        while (!queue.isEmpty()) {
            for (var edge : catalog.prerequisitesOf(queue.poll())) {
                if (seen.add(edge.requires())) {
                    queue.add(edge.requires());
                }
            }
        }
        return seen;
    }

    /**
     * 아무것도 모를 때 가장 많이 밝혀 주는 Skill - 트랙 안에서 선수를 가장 많이 거느린 것.
     * 도메인이 늘 때마다 이 이름이 바뀌므로 **고정하지 않고 커리큘럼에서 구한다**(ADR-0034).
     */
    private String mostInformative() {
        var scope = catalog.skillCodesFor("JOB");
        return scope.stream()
                .max(java.util.Comparator.<String>comparingInt(code -> 1 + (int) below(code).stream()
                                .filter(scope::contains).count())
                        .thenComparing(java.util.Comparator.reverseOrder()))
                .orElseThrow();
    }

    private MockMvc mvc;
    private Long userId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        userId = users.save(new UserRow(
                "diag-" + System.nanoTime() + "@codesprint.dev", "진단테스트", "JOB")).id();
    }

    private JsonNode step() throws Exception {
        return MAPPER.readTree(mvc.perform(get("/api/users/{id}/diagnostic", userId))
                .andReturn().getResponse().getContentAsString());
    }

    /** 제출하고 채점 결과까지 반영한다. */
    private long solve(String problemCode, String judgeStatus, int passed, int total)
            throws Exception {

        String body = """
                {"userId": %d, "language": "PYTHON", "sourceCode": "print(1)",
                 "solveSeconds": 120}
                """.formatted(userId);
        long submissionId = MAPPER.readTree(
                mvc.perform(post("/api/problems/{code}/submit", problemCode)
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andReturn().getResponse().getContentAsString())
                .get("submissionId").asLong();

        JudgeJobRow job = jobs.findBySubmissionId(submissionId).orElseThrow();
        JudgeResultFixture.finish(jdbc,
                """
                {"status": "%s", "passed": %d, "total": %d, "executionMs": 100,
                 "memoryKb": 20480, "failedCaseId": null, "stderr": null, "cases": []}
                """.formatted(judgeStatus, passed, total), job.id());
        poller.applyFinishedJobs();
        return submissionId;
    }

    /**
     * 진단을 끝낸다 - 진단이 가리키는 문제를 통과시키며 끝까지 따라간다.
     *
     * <p>슬라이스 1 은 그래프가 한 갈래라 P05 하나를 통과하면 진단이 끝났다. 도메인이 늘면
     * 갈래마다 한 번씩 물어야 하므로 **몇 번인지 여기서 정하지 않는다**(ADR-0034).
     */
    private void finishDiagnostic() throws Exception {
        for (int i = 0; i < 60; i++) {
            JsonNode step = step();
            if (step.get("done").asBoolean()) {
                return;
            }
            solve(step.get("problem").get("code").asText(), "ACCEPTED", 6, 6);
        }
        throw new AssertionError("진단이 60 단계 안에 끝나지 않았다");
    }

    @Test
    @DisplayName("신규 사용자에게 시작점을 준다")
    void aNewUserGetsSomewhereToStart() throws Exception {
        // **이것이 이 기능의 존재 이유다.** 지금까지 신규 사용자는 Skill 이 전부 비어
        // 있고 READY 가 둘인 화면을 받았고, 그 둘 중 무엇부터인지 아무도 말해 주지
        // 않았다.
        JsonNode step = step();

        assertThat(step.get("done").asBoolean()).isFalse();
        assertThat(step.get("targetSkill").asText()).isNotBlank();
        assertThat(step.get("problem").get("code").asText()).startsWith("P");
        assertThat(step.get("assessed").asInt()).isZero();
        assertThat(step.get("total").asInt()).isPositive();
    }

    @Test
    @DisplayName("아무것도 모를 때는 가장 많이 밝혀 주는 것부터 묻는다")
    void theFirstQuestionIsTheMostInformativeOne() throws Exception {
        // 뿌리부터 하나씩 올라가면 그래프 깊이만큼 문제를 내야 하는데, 그건 진단이
        // 아니라 학습이다. 위에서 시작해 통과하면 아래가 함께 확인된다.
        String expected = mostInformative();
        assertThat(below(expected)).as("선수가 있는 Skill 이어야 이 규칙을 본다").isNotEmpty();
        assertThat(step().get("targetSkill").asText()).isEqualTo(expected);
    }

    @Test
    @DisplayName("통과하면 그 아래는 묻지 않는다")
    void passingImpliesThePrerequisites() throws Exception {
        JsonNode first = step();
        assertThat(first.get("targetSkill").asText()).isEqualTo(mostInformative());

        solve(first.get("problem").get("code").asText(), "ACCEPTED", 6, 6);

        // 최상위를 해냈으면 그 아래 전부가 함께 확인된 것이다. 한 문제로 그 갈래가 덮인다.
        // 갈래가 여럿이므로 진단은 아직 끝나지 않고, 다음 질문은 **다른 갈래**에서 온다.
        JsonNode after = step();
        int covered = 1 + below(first.get("targetSkill").asText()).size();
        assertThat(after.get("assessed").asInt()).as("그 갈래가 한 문제로 덮인다")
                .isGreaterThanOrEqualTo(covered);
        // 갈래가 여럿이므로 한 문제로 끝나면 안 된다 - 끝났다면 다른 갈래를 묻지 않은 것이다.
        assertThat(after.get("done").asBoolean()).as("다른 갈래가 남아 있다").isFalse();
        java.util.Set<String> branch = new java.util.HashSet<>(below(first.get("targetSkill").asText()));
        branch.add(first.get("targetSkill").asText());
        assertThat(after.get("targetSkill").asText())
                .as("덮인 갈래를 다시 묻지 않는다").isNotIn(branch);
    }

    @Test
    @DisplayName("실패하면 선수로 내려간다")
    void failingDescendsToThePrerequisites() throws Exception {
        JsonNode first = step();
        String failed = first.get("targetSkill").asText();

        solve(first.get("problem").get("code").asText(), "WRONG_ANSWER", 1, 6);

        JsonNode after = step();
        // 물어본 것을 다시 묻지 않는다 - 답은 얻었다. 다만 그 답이 "못한다" 이므로
        // 아래 어디서 막혔는지가 다음 질문이다.
        assertThat(after.get("done").asBoolean()).isFalse();
        assertThat(after.get("targetSkill").asText())
                .as("실패한 Skill 을 다시 묻지 않는다").isNotEqualTo(failed);

        // 다음 질문은 **실패한 Skill 의 선수 가운데** 아직 Evidence 가 없는 것이다. 다른 갈래로
        // 건너뛰지 않는다(ADR-0036 §3). 제출 하나는 SECONDARY 에도 Evidence 를 남기므로, 바로
        // 아래가 이미 물어본 것이면 Evidence 가 닿지 않은 곳까지 내려간다.
        String next = after.get("targetSkill").asText();
        assertThat(below(failed)).as("실패한 갈래의 선수로 내려간다").contains(next);
        JsonNode map = MAPPER.readTree(mvc.perform(get("/api/users/{id}/skills", userId))
                .andReturn().getResponse().getContentAsString());
        for (JsonNode skill : map.get("skills")) {
            if (skill.get("skillCode").asText().equals(next)) {
                assertThat(skill.get("evidenceCount").asInt())
                        .as("Evidence 가 닿지 않은 곳이다").isZero();
            }
        }
    }

    @Test
    @DisplayName("작은 갈래에서 틀리면 큰 갈래로 건너뛰지 않고 그 선수부터 묻는다")
    void aFailureInASmallBranchStaysInThatBranch() throws Exception {
        // ADR-0036 §3. 위 테스트는 가장 큰 갈래의 꼭대기에서 틀리므로, 규칙이 없어도 "가장 많이
        // 밝혀 주는 것" 이 그 갈래 안에 있어 통과한다 - 대조군으로 규칙을 빼 보자 실제로 통과했다.
        // 그래서 작은 갈래(조합 → 완전 탐색 → 리스트)에서 틀린다. 규칙이 없으면 다음 질문은
        // 가장 큰 갈래의 꼭대기로 간다.
        String failed = "COMBINATORIAL_ENUMERATION";
        String problem = problemCatalog.byPrimarySkill(failed, "NORMAL").get(0).code();
        solve(problem, "WRONG_ANSWER", 0, 6);

        String next = step().get("targetSkill").asText();
        assertThat(mostInformative()).as("대조: 규칙이 없으면 가는 곳은 다른 갈래다")
                .isNotIn(below(failed));
        assertThat(below(failed)).as("틀린 갈래의 선수를 묻는다").contains(next);
    }

    @Test
    @DisplayName("여러 번 불러도 진도가 앞서 나가지 않는다")
    void askingTwiceDoesNotAdvance() throws Exception {
        // 진단에 세션이 없으므로(ADR-0018) 조회는 부작용이 없어야 한다. 화면이 다시
        // 그리거나 사용자가 새로고침했다고 문제가 넘어가면, 푼 적 없는 Skill 이
        // 확인된 것으로 남는다.
        assertThat(step().get("targetSkill")).isEqualTo(step().get("targetSkill"));
        assertThat(step().get("assessed").asInt()).isZero();
    }

    @Test
    @DisplayName("응답이 계약을 지킨다")
    void theResponseMatchesItsContract() throws Exception {
        Path contracts = Path.of(System.getProperty("codesprint.repoRoot"), "contracts");
        var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(Files.readString(
                        contracts.resolve("diagnostic-step.schema.json"),
                        StandardCharsets.UTF_8));

        assertThat(schema.validate(step())).as("진행 중 응답").isEmpty();

        finishDiagnostic();
        assertThat(step().get("done").asBoolean()).as("끝난 응답을 검사한다").isTrue();

        // 끝났을 때는 targetSkill 과 problem 이 null 이다. **그 모양도 계약이다** -
        // 생략해 버리면 화면은 "아직 안 정해졌다" 와 구분할 수 없다.
        assertThat(schema.validate(step())).as("끝난 뒤 응답").isEmpty();
    }

    @Test
    @DisplayName("진단 중에는 두 화면이 같은 문제를 가리킨다")
    void theTwoPanelsAgreeWhileDiagnosing() throws Exception {
        // **이것이 ADR-0019 의 존재 이유다.** 진단 카드와 결과 패널이 각각 다음 문제를
        // 가리키는데, 서로 다른 것을 가리키면 사용자는 어느 쪽을 눌러야 할지 모른다.
        // 실제로 진단은 BFS_BASIC 을, 결정은 BFS_GRID_TRAVERSAL 을 가리켰다.
        JsonNode first = step();
        long submissionId = solve(first.get("problem").get("code").asText(),
                "WRONG_ANSWER", 1, 6);

        JsonNode next = MAPPER.readTree(
                mvc.perform(get("/api/submissions/{id}/next-problem", submissionId))
                        .andReturn().getResponse().getContentAsString());
        JsonNode diagnostic = step();

        assertThat(next.get("action").asText()).isEqualTo("DIAGNOSTIC_PROBE");
        assertThat(next.get("targetSkill").asText())
                .isEqualTo(diagnostic.get("targetSkill").asText());
        assertThat(next.get("problem").get("code").asText())
                .as("두 화면이 같은 문제를 가리킨다")
                .isEqualTo(diagnostic.get("problem").get("code").asText());
    }

    @Test
    @DisplayName("진단이 끝나면 평소 결정으로 돌아온다")
    void afterTheDiagnosticTheNormalRulesResume() throws Exception {
        // 진단을 끝낸다. 그 다음 제출부터는 진단이 개입하지 않는다.
        finishDiagnostic();
        assertThat(step().get("done").asBoolean()).isTrue();

        long submissionId = solve("P02_GRID_TRAVERSAL", "WRONG_ANSWER", 1, 6);
        JsonNode next = MAPPER.readTree(
                mvc.perform(get("/api/submissions/{id}/next-problem", submissionId))
                        .andReturn().getResponse().getContentAsString());

        assertThat(next.get("action").asText()).isNotEqualTo("DIAGNOSTIC_PROBE");
    }

    @Test
    @DisplayName("진단을 실패로 마치면 선수 조건 규칙이 다시 산다")
    void thePrerequisiteRuleResumesAfterTheDiagnostic() throws Exception {
        // **이 커버리지를 일부러 채운다.** 진단이 선수 조건 규칙을 대체하면서
        // CHANGE_SKILL 이 서비스 수준에서 아무 테스트에도 안 걸리게 됐다 - 도달할 수
        // 없어진 것이 아니라 도달하기 어려워진 것이라, 그대로 두면 그 경로가 죽어도
        // 아무도 모른다.
        //
        // 통과로 진단을 끝낸다. 함의된 Skill 들은 **Evidence 가 없다** - 진단이
        // "묻지 않기로" 했을 뿐 재 본 것이 아니다(ADR-0018).
        finishDiagnostic();
        assertThat(step().get("done").asBoolean()).as("진단이 끝나야 한다").isTrue();

        // 그래서 그 중 하나를 실제로 풀어 보면 선수 조건이 막혀 있다. 함의는
        // 진단의 판단이고, 선수 조건은 여전히 진짜 mastery 를 요구한다.
        long submissionId = solve("P14_GRAPH_REACHABLE", "WRONG_ANSWER", 0, 6);
        JsonNode next = MAPPER.readTree(
                mvc.perform(get("/api/submissions/{id}/next-problem", submissionId))
                        .andReturn().getResponse().getContentAsString());

        assertThat(next.get("action").asText()).isEqualTo("CHANGE_SKILL");
        assertThat(next.get("problem").get("kind").asText()).isEqualTo("NORMAL");
    }

    @Test
    @DisplayName("진단이 끝날 때까지 매 단계 두 화면이 일치한다")
    void thePanelsAgreeAtEveryStep() throws Exception {
        // 한 단계만 보면 놓친다. 진단은 그래프를 내려가면서 **선수가 없는 뿌리 Skill**
        // 에도 닿는데, 그 Skill 에는 막힌 선수가 없어서 선수 조건 규칙이 아예 돌지
        // 않는다 - 진단이 개입할 자리도 같이 사라진다.
        for (int step = 0; step < 60; step++) {
            JsonNode diagnostic = step();
            if (diagnostic.get("done").asBoolean()) {
                return;
            }
            String problemCode = diagnostic.get("problem").get("code").asText();
            String expected = diagnostic.get("targetSkill").asText();

            long submissionId = solve(problemCode, "WRONG_ANSWER", 0, 6);

            JsonNode after = step();
            if (after.get("done").asBoolean()) {
                return;
            }
            JsonNode next = MAPPER.readTree(
                    mvc.perform(get("/api/submissions/{id}/next-problem", submissionId))
                            .andReturn().getResponse().getContentAsString());

            assertThat(next.get("problem").get("code").asText())
                    .as("%s 을(를) 낸 뒤 - 진단은 %s 를 가리킨다",
                            expected, after.get("problem").get("code").asText())
                    .isEqualTo(after.get("problem").get("code").asText());
        }
        throw new AssertionError("진단이 60 단계 안에 끝나지 않았다");
    }

    @Test
    @DisplayName("막힌 선수가 없는 Skill 을 풀어도 두 화면이 갈리지 않는다")
    void thePanelsAgreeEvenWithoutABlockedPrerequisite() throws Exception {
        // 문제는 목록에서 직접 고를 수도 있다. 진단이 BFS_SHORTEST_PATH 를 가리키는
        // 동안 사용자가 뿌리 Skill 인 PYTHON_DEQUE_BASIC 문제를 골라 틀리면,
        // **그 Skill 에는 막힌 선수가 없어서** 선수 조건 규칙이 아예 돌지 않는다 -
        // 진단이 개입할 자리도 같이 사라진다.
        JsonNode before = step();
        assertThat(before.get("done").asBoolean()).isFalse();

        long submissionId = solve("P01_QUEUE_BASIC", "WRONG_ANSWER", 0, 6);

        JsonNode diagnostic = step();
        assertThat(diagnostic.get("done").asBoolean()).as("아직 진단 중이다").isFalse();

        JsonNode next = MAPPER.readTree(
                mvc.perform(get("/api/submissions/{id}/next-problem", submissionId))
                        .andReturn().getResponse().getContentAsString());

        assertThat(next.get("problem").get("code").asText())
                .as("결과 패널과 진단 카드가 같은 문제를 가리켜야 한다")
                .isEqualTo(diagnostic.get("problem").get("code").asText());
    }

    @Test
    @DisplayName("없는 사용자는 404 다")
    void unknownUserIsNotFound() throws Exception {
        assertThat(mvc.perform(get("/api/users/{id}/diagnostic", 999999L))
                .andReturn().getResponse().getStatus())
                .isEqualTo(404);
    }
}
