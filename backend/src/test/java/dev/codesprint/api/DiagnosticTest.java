package dev.codesprint.api;

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

    private MockMvc mvc;
    private Long userId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        userId = users.save(new UserRow(
                "diag-" + System.nanoTime() + "@codesprint.dev", "진단테스트")).id();
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
        jdbc.update("UPDATE judge_jobs SET status = 'DONE', result = ?::jsonb WHERE id = ?",
                """
                {"status": "%s", "passed": %d, "total": %d, "executionMs": 100,
                 "memoryKb": 20480, "failedCaseId": null, "stderr": null, "cases": []}
                """.formatted(judgeStatus, passed, total), job.id());
        poller.applyFinishedJobs();
        return submissionId;
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
        assertThat(step().get("targetSkill").asText()).isEqualTo("BFS_SHORTEST_PATH");
    }

    @Test
    @DisplayName("통과하면 그 아래는 묻지 않는다")
    void passingImpliesThePrerequisites() throws Exception {
        JsonNode first = step();
        assertThat(first.get("targetSkill").asText()).isEqualTo("BFS_SHORTEST_PATH");

        solve(first.get("problem").get("code").asText(), "ACCEPTED", 6, 6);

        // 최상위를 해냈으면 그 아래 전부가 함께 확인된 것이다. 한 문제로 끝난다.
        JsonNode after = step();
        assertThat(after.get("done").asBoolean()).as("한 문제로 그래프가 덮인다").isTrue();
        assertThat(after.get("targetSkill").isNull()).isTrue();
        assertThat(after.get("problem").isNull()).isTrue();
        assertThat(after.get("assessed").asInt()).isEqualTo(after.get("total").asInt());
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

        // **BFS_GRID_TRAVERSAL 이 아니다.** 제출 하나는 그 문제의 SECONDARY Skill 에도
        // Evidence 를 남기므로, P05 를 낸 순간 바로 아래 셋은 이미 물어본 것이 된다.
        // 진단이 채우는 것은 **모르는 칸**이지, 아는 것을 다시 확인하는 것이 아니다.
        // 그래서 한 단계가 아니라 Evidence 가 닿지 않은 곳까지 내려간다.
        assertThat(after.get("targetSkill").asText())
                .as("Evidence 가 닿지 않은 곳까지 내려간다").isEqualTo("BFS_BASIC");
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

        JsonNode first = step();
        solve(first.get("problem").get("code").asText(), "ACCEPTED", 6, 6);

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
        // 한 문제로 그래프가 덮이는 경로다. 그 다음 제출부터는 진단이 개입하지 않는다.
        solve(step().get("problem").get("code").asText(), "ACCEPTED", 6, 6);
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
        solve(step().get("problem").get("code").asText(), "ACCEPTED", 6, 6);
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
        for (int step = 0; step < 10; step++) {
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
        throw new AssertionError("진단이 10 단계 안에 끝나지 않았다");
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
