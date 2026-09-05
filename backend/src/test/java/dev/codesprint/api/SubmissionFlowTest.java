package dev.codesprint.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import dev.codesprint.judge.JudgePort;
import dev.codesprint.learning.domain.JudgeStatus;
import dev.codesprint.learning.persistence.SkillEvidenceRepository;
import dev.codesprint.learning.persistence.UserRepository;
import dev.codesprint.learning.persistence.UserRow;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 제출 하나가 흐름 전체를 지나는지 본다 - Milestone 1 데모의 뼈대(Addendum §89).
 *
 * <pre>
 *   POST /submit -&gt; Judge -&gt; Evidence 저장 -&gt; mastery 재계산 -&gt; 다음 행동
 * </pre>
 *
 * <p><b>Judge 는 가짜다.</b> 여기서 검증하려는 것은 "샌드박스가 안전한가" 가 아니라
 * "판정을 받아 Evidence 로 옮기고 다음 행동을 정하는가" 다. 격리는
 * {@code judge/tests/test_judge.py} 가 실물 Docker 로 따로 검증한다.
 *
 * <p>DB 는 가짜로 하지 않는다. 여기서 확인하는 것 중 상당수 - 멱등 저장, jsonb
 * 왕복, mastery/status 짝 제약 - 가 PostgreSQL 과 마이그레이션의 동작이다.
 */
@SpringBootTest
class SubmissionFlowTest {

    private static final String EXTERNAL_URL = System.getenv("TEST_DB_URL");
    private static PostgreSQLContainer<?> container;

    static {
        if (EXTERNAL_URL == null) {
            container = new PostgreSQLContainer<>("postgres:16-alpine");
            container.start();
        }
    }

    /** 판정을 시나리오대로 돌려준다. 호출된 순서대로 하나씩 꺼낸다. */
    static class FakeJudge implements JudgePort {

        private final List<Result> scripted = new ArrayList<>();
        int calls;

        void willReturn(Result result) {
            scripted.add(result);
        }

        /**
         * 테스트마다 비운다. 이 bean 은 컨텍스트에 하나뿐이라 정해둔 판정이 다음
         * 테스트로 새면, 앞 테스트의 WRONG_ANSWER 가 뒤 테스트의 ACCEPTED 자리에
         * 나온다. 실제로 그렇게 두 건이 깨졌다.
         */
        void reset() {
            scripted.clear();
            calls = 0;
        }

        @Override
        public Result judge(String problemCode, String sourceCode) {
            calls++;
            if (scripted.isEmpty()) {
                throw new IllegalStateException("판정을 정해두지 않았다");
            }
            return scripted.size() == 1 ? scripted.get(0) : scripted.remove(0);
        }
    }

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        FakeJudge fakeJudge() {
            return new FakeJudge();
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
        // 문제는 jar 가 아니라 저장소에서 읽는다(ADR-0008).
        registry.add("codesprint.problems-dir", () -> repoRoot().resolve("problems").toString());
        registry.add("codesprint.repo-root", () -> repoRoot().toString());
    }

    private static Path repoRoot() {
        String root = System.getProperty("codesprint.repoRoot");
        if (root == null) {
            throw new IllegalStateException(
                    "codesprint.repoRoot 시스템 프로퍼티가 없다. build.gradle.kts 를 확인한다.");
        }
        return Path.of(root);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private FakeJudge judge;

    @Autowired
    private UserRepository users;

    @Autowired
    private SkillEvidenceRepository evidence;

    private MockMvc mvc;
    private Long userId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        userId = users.save(new UserRow(
                "flow-" + System.nanoTime() + "@codesprint.dev", "흐름테스트")).id();
        judge.reset();
    }

    private JsonNode submit(String problemCode, String body) throws Exception {
        String json = mvc.perform(post("/api/problems/{code}/submit", problemCode)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(json);
    }

    private String requestBody(int hintLevel) {
        return """
                {"userId": %d, "language": "PYTHON", "sourceCode": "print(1)",
                 "hintLevel": %d, "solutionViewed": false, "solveSeconds": 120}
                """.formatted(userId, hintLevel);
    }

    @Test
    @DisplayName("AC 하나가 Evidence 와 mastery 와 다음 행동까지 만든다")
    void acceptedSubmissionFlowsThrough() throws Exception {
        judge.willReturn(new JudgePort.Result(JudgeStatus.ACCEPTED, 5, 5, 120, 20480, null, null));

        JsonNode response = submit("P02_GRID_TRAVERSAL", requestBody(0));

        assertThat(response.get("submissionId").asLong()).isPositive();
        assertThat(response.get("judge").get("status").asText()).isEqualTo("ACCEPTED");

        // 문제의 Skill 마다 갱신이 하나씩 나온다. before 는 null 이다 - 처음이라
        // 평가된 적이 없다. 0.0(평가했고 못함)과 다르다.
        assertThat(response.get("skillUpdates")).isNotEmpty();
        JsonNode first = response.get("skillUpdates").get(0);
        assertThat(first.get("before").isNull()).as("첫 제출의 before 는 null 이다").isTrue();
        assertThat(first.get("after").asDouble()).isPositive();

        assertThat(response.get("nextAction").get("type").asText()).isNotBlank();

        // Reviewer 가 아직 없으므로 null 이다. 필드를 생략하지 않는다.
        assertThat(response.has("review")).isTrue();
        assertThat(response.get("review").isNull()).isTrue();
        assertThat(response.has("promptVersion")).isTrue();
        assertThat(response.get("promptVersion").isNull()).isTrue();
    }

    @Test
    @DisplayName("응답이 계약(submit-response.schema.json)을 지킨다")
    void responseMatchesContract() throws Exception {
        judge.willReturn(new JudgePort.Result(
                JudgeStatus.WRONG_ANSWER, 3, 5, 90, 20480, 4, null));

        JsonNode response = submit("P02_GRID_TRAVERSAL", requestBody(0));

        JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(Files.readString(
                        repoRoot().resolve("contracts").resolve("submit-response.schema.json")));
        assertThat(schema.validate(response))
                .as("계약 위반")
                .isEmpty();
    }

    @Test
    @DisplayName("COMPILE_ERROR 는 알고리즘 Skill 에 Evidence 를 남기지 않는다")
    void compileErrorLeavesNoEvidence() throws Exception {
        // 문법 오류를 알고리즘 Skill 의 penalty 로 쓰면 안 된다(Addendum §12).
        judge.willReturn(new JudgePort.Result(
                JudgeStatus.COMPILE_ERROR, 0, 5, null, null, null, "SyntaxError"));

        // 선수 조건이 없는 진입점 문제를 쓴다. BFS 계열로 하면 신규 사용자에게는
        // 선수 조건이 전부 미충족이라 CHANGE_SKILL 이 먼저 나오고, 이 테스트가
        // 확인하려는 "COMPILE_ERROR 의 처리" 가 가려진다.
        JsonNode response = submit("P01_QUEUE_BASIC", requestBody(0));

        assertThat(response.get("judge").get("status").asText()).isEqualTo("COMPILE_ERROR");
        assertThat(evidence.countByUserIdAndSkillCode(userId, "PYTHON_DEQUE_BASIC"))
                .as("Evidence 가 생기면 안 된다")
                .isZero();
        // 그래도 다음 행동은 나온다 - 같은 문제를 고쳐 다시 내면 된다.
        assertThat(response.get("nextAction").get("type").asText()).isEqualTo("CONTINUE");
        assertThat(response.get("skillUpdates").get(0).get("after").isNull())
                .as("mastery 가 생기면 안 된다")
                .isTrue();
    }

    @Test
    @DisplayName("채점이 실패하면 학습 경로를 바꾸지 않는다")
    void systemErrorDoesNotRerouteTheUser() throws Exception {
        // 우리 하네스가 죽은 것이다. 선수 조건이 실제로 미충족이더라도, 그것을
        // 알리는 계기가 우리 장애여서는 안 된다 - 사용자는 아무것도 하지 않았는데
        // 화면이 다른 Skill 로 튄다.
        judge.willReturn(new JudgePort.Result(
                JudgeStatus.SYSTEM_ERROR, 0, 1, null, null, null, null));

        JsonNode response = submit("P05_SHORTEST_PATH", requestBody(0));

        assertThat(response.get("nextAction").get("type").asText()).isEqualTo("CONTINUE");
        assertThat(evidence.countByUserIdAndSkillCode(userId, "BFS_SHORTEST_PATH")).isZero();
    }

    @Test
    @DisplayName("선수 조건을 못 채운 Skill 은 첫 실패에서 선수 Skill 로 보낸다")
    void firstFailureOnLockedSkillRedirects() throws Exception {
        // 이 흐름이 정확히 PR 6 리뷰에서 잡힌 것이다. Decision Engine 이 받는
        // evidenceCount 에는 방금 저장한 이번 실패가 이미 들어 있다. 제출 전 개수를
        // 따로 넘기지 않으면 여기서 RETRY_VARIANT 가 나온다 - 준비되지 않은 문제를
        // 계속 주는 것이다.
        judge.willReturn(new JudgePort.Result(
                JudgeStatus.WRONG_ANSWER, 0, 5, 90, 20480, 1, null));

        JsonNode response = submit("P05_SHORTEST_PATH", requestBody(0));

        assertThat(response.get("nextAction").get("type").asText())
                .as("선수 조건을 못 채웠다")
                .isEqualTo("CHANGE_SKILL");
        assertThat(response.get("nextAction").get("targetSkill").asText()).isNotBlank();
    }

    @Test
    @DisplayName("같은 제출을 두 번 처리해도 Evidence 는 한 번만 쌓인다")
    void evidenceIsIdempotentPerSubmission() throws Exception {
        judge.willReturn(new JudgePort.Result(JudgeStatus.ACCEPTED, 5, 5, 120, 20480, null, null));

        submit("P02_GRID_TRAVERSAL", requestBody(0));
        long after1 = evidence.countByUserIdAndSkillCode(userId, "BFS_GRID_TRAVERSAL");

        submit("P02_GRID_TRAVERSAL", requestBody(0));
        long after2 = evidence.countByUserIdAndSkillCode(userId, "BFS_GRID_TRAVERSAL");

        // 서로 다른 제출이므로 2건이다. 같은 제출이 두 번 처리되는 경우는
        // sourceEventId 가 같아 UNIQUE 제약과 saveIfAbsent 가 막는다.
        assertThat(after1).isEqualTo(1);
        assertThat(after2).isEqualTo(2);
        assertThat(judge.calls).isEqualTo(2);
    }

    @Test
    @DisplayName("없는 문제는 404 다 - 채점 실패와 구분한다")
    void unknownProblemIsNotFound() throws Exception {
        int status = mvc.perform(post("/api/problems/{code}/submit", "NO_SUCH_PROBLEM")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(0)))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(404);
        assertThat(judge.calls).as("채점을 시도하지 않는다").isZero();
    }
}
