package dev.codesprint.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import dev.codesprint.learning.persistence.UserRepository;
import dev.codesprint.learning.persistence.UserRow;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 힌트를 내주고 기록하는 길. 정본: ADR-0026, PRD §73.
 *
 * <p>여기서 보는 것은 힌트 <b>내용</b>이 아니라 <b>기록</b>이다. 문구는 저장소 파일에
 * 이미 있고 아무나 읽을 수 있다. 측정되어야 하는 것은 "누가 몇 단계까지 봤는가" 이고,
 * 그것은 내주는 쪽만 알 수 있다.
 */
@SpringBootTest
class HintFlowTest {

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
        registry.add("codesprint.problems-dir", () -> repoRoot().resolve("problems").toString());
        registry.add("codesprint.repo-root", () -> repoRoot().toString());
        registry.add("codesprint.judge.apply-interval-ms", () -> "3600000");
    }

    private static Path repoRoot() {
        String root = System.getProperty("codesprint.repoRoot");
        if (root == null) {
            throw new IllegalStateException("codesprint.repoRoot 시스템 프로퍼티가 없다.");
        }
        return Path.of(root);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROBLEM = "P02_GRID_TRAVERSAL";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository users;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Autowired
    private dev.codesprint.learning.service.HintService hintService;

    private MockMvc mvc;
    private Long userId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        userId = users.save(new UserRow(
                "hint-" + System.nanoTime() + "@codesprint.dev", "힌트테스트")).id();
    }

    private MockHttpServletResponse ask(String problem, int level) throws Exception {
        return mvc.perform(post("/api/problems/{code}/hints/{level}", problem, level)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\": %d}".formatted(userId)))
                .andReturn().getResponse();
    }

    private JsonNode reveal(int level) throws Exception {
        MockHttpServletResponse response = ask(PROBLEM, level);
        assertThat(response.getStatus()).isEqualTo(200);
        return MAPPER.readTree(response.getContentAsString());
    }

    /** 제출 행에 남은 힌트 단계. 신고받은 값이 아니라 서버가 기록에서 읽은 값이다. */
    private int recordedHintLevel(long submissionId) {
        return jdbc.queryForObject(
                "select hint_level from submissions where id = ?", Integer.class, submissionId);
    }

    private boolean recordedSolutionViewed(long submissionId) {
        return jdbc.queryForObject(
                "select solution_viewed from submissions where id = ?", Boolean.class,
                submissionId);
    }

    private long submit() throws Exception {
        String body = """
                {"userId": %d, "language": "PYTHON", "sourceCode": "print(1)",
                 "solveSeconds": 120}
                """.formatted(userId);
        MockHttpServletResponse response = mvc.perform(
                        post("/api/problems/{code}/submit", PROBLEM)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(202);
        return MAPPER.readTree(response.getContentAsString()).get("submissionId").asLong();
    }

    @Test
    @DisplayName("힌트 응답이 계약(hint-view)을 지킨다")
    void responseMatchesContract() throws Exception {
        JsonNode hint = reveal(1);

        var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(Files.readString(repoRoot().resolve("contracts/hint-view.schema.json")));
        assertThat(schema.validate(hint)).isEmpty();
    }

    @Test
    @DisplayName("사다리는 한 단계씩만 열린다")
    void ladderOpensOneStepAtATime() throws Exception {
        // H1 도 안 봤는데 H5 를 달라고 하면 400 이다. 허용하면 받은 도움의 양이
        // 다른데 같은 값이 기록된다 - 그러면 숫자 하나가 도움의 양을 대신하지 못한다.
        assertThat(ask(PROBLEM, 5).getStatus()).isEqualTo(400);

        assertThat(reveal(1).get("level").asInt()).isEqualTo(1);
        assertThat(ask(PROBLEM, 3).getStatus()).as("한 칸 건너뛰는 것도 막는다").isEqualTo(400);
        assertThat(reveal(2).get("highestLevel").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 단계를 다시 열어도 더 많이 본 것이 되지 않는다")
    void reopeningDoesNotCountAgain() throws Exception {
        reveal(1);
        reveal(2);
        JsonNode again = reveal(1);

        assertThat(again.get("level").asInt()).isEqualTo(1);
        assertThat(again.get("highestLevel").asInt())
                .as("다시 본 단계가 최고 단계를 끌어내리지 않는다")
                .isEqualTo(2);

        long submissionId = submit();
        assertThat(recordedHintLevel(submissionId))
                .as("기록은 본 횟수가 아니라 최고 단계다")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("제출은 신고가 아니라 기록에서 힌트 단계를 읽는다")
    void submissionReadsTheRecordNotTheRequest() throws Exception {
        // 대조군. 힌트를 보기 전에 낸 제출은 0 이어야 한다 - 그래야 아래의 3 이
        // "힌트를 봐서" 생긴 값임을 알 수 있다.
        long before = submit();
        assertThat(recordedHintLevel(before)).isZero();
        assertThat(recordedSolutionViewed(before)).isFalse();

        reveal(1);
        reveal(2);
        reveal(3);

        long after = submit();
        assertThat(recordedHintLevel(after)).isEqualTo(3);
        assertThat(recordedSolutionViewed(after)).isFalse();
    }

    @Test
    @DisplayName("이미 낸 제출은 나중에 본 힌트를 따라 바뀌지 않는다")
    void pastSubmissionsAreFrozen() throws Exception {
        reveal(1);
        long earlier = submit();
        assertThat(recordedHintLevel(earlier)).isEqualTo(1);

        reveal(2);
        reveal(3);

        // Evidence 는 append-only 정본이다(ADR-0009). 이미 접힌 관측이 나중 행동을
        // 따라 바뀌면 같은 제출이 시점에 따라 다른 mastery 를 만든다.
        assertThat(recordedHintLevel(earlier))
                .as("지난 제출은 그대로다")
                .isEqualTo(1);
        assertThat(recordedHintLevel(submit()))
                .as("새 제출만 새 기록을 읽는다")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("힌트를 본 문제는 다음 제출에서도 힌트를 본 것이다")
    void aSeenHintCannotBeUnseen() throws Exception {
        reveal(1);
        reveal(2);
        submit();

        // 두 번째 제출에서는 힌트를 새로 열지 않았다. 그래도 그 사람은 여전히
        // H2 를 알고 있다 - "힌트 없는 풀이" 로 세면 독립 풀이를 실제보다 높게 기록한다.
        assertThat(recordedHintLevel(submit())).isEqualTo(2);
    }

    @Test
    @DisplayName("전체 풀이(H6)는 reference.py 이고 solutionViewed 로 기록된다")
    void theLastRungIsTheReferenceSolution() throws Exception {
        for (int level = 1; level <= 6; level++) {
            reveal(level);
        }
        JsonNode solution = reveal(6);

        String reference = Files.readString(
                repoRoot().resolve("problems").resolve(PROBLEM).resolve("reference.py"));
        assertThat(solution.get("text").asText())
                .as("복사본이 아니라 그 파일이다(ADR-0012)")
                .isEqualTo(reference);
        assertThat(solution.get("topLevel").asInt()).isEqualTo(6);

        long submissionId = submit();
        // Evidence 는 해설을 본 것을 힌트 최고 단계(5)보다 위로 친다.
        assertThat(recordedSolutionViewed(submissionId)).isTrue();
    }

    /** 여덟이 동시에 같은 힌트를 연다. 실패한 것이 있으면 그 이유를 그대로 돌려준다. */
    private List<String> eightAtOnce(String problemCode) throws Exception {
        int threads = 8;
        CyclicBarrier gate = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                gate.await();
                try {
                    hintService.reveal(userId, problemCode, 1);
                    return "OK";
                } catch (Exception e) {
                    Throwable root = e;
                    while (root.getCause() != null) {
                        root = root.getCause();
                    }
                    return root.getClass().getSimpleName() + ": "
                            + String.valueOf(root.getMessage());
                }
            });
        }
        try {
            List<String> results = new ArrayList<>();
            for (Future<String> result : pool.invokeAll(tasks)) {
                results.add(result.get());
            }
            return results;
        } finally {
            pool.shutdown();
        }
    }

    @Test
    @DisplayName("그 문제를 처음 건드리는 요청이 동시에 와도 전부 성공한다")
    void concurrentFirstTouchOfAProblemAllSucceed() throws Exception {
        // **이 테스트가 없으면 아래 것이 반쪽이다.** 아래는 P02 를 쓰는데, 같은
        // 클래스의 다른 테스트가 이미 그 problems 행을 만들어 두므로 hint_usage
        // 충돌만 보게 된다. 그 앞에 problems.code UNIQUE 가 있고, 깨끗한 DB 에서는
        // 거기서 먼저 터진다 - 실제로 여덟 중 일곱이 그랬다.
        //
        // 그래서 아무도 건드리지 않은 상태를 만들고 시작한다.
        String untouched = "P13_EDGE_CELLS";
        jdbc.update("delete from hint_usage where problem_id in "
                + "(select id from problems where code = ?)", untouched);
        jdbc.update("delete from problems where code = ?", untouched);

        assertThat(eightAtOnce(untouched)).containsOnly("OK");
        assertThat(jdbc.queryForObject(
                "select count(*) from problems where code = ?", Integer.class, untouched))
                .as("행은 하나만 생긴다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("같은 단계를 동시에 요청해도 전부 성공한다")
    void concurrentRequestsForTheSameLevelAllSucceed() throws Exception {
        // 읽고 확인한 뒤 쓰면 여덟이 나란히 "아직 없다" 를 보고 여덟이 다 넣는다.
        // 그때 일곱이 UNIQUE 위반으로 터졌다 - 실제로 재현했다.
        //
        // 더블클릭 · 재시도 · 응답을 못 받아 다시 누르는 경우에 그대로 일어나고,
        // 무엇보다 이 서비스는 "다시 열어도 새로 기록하지 않는다" 를 약속한다.
        // 순차 재요청에서만 지켜지는 약속은 약속이 아니다.
        assertThat(eightAtOnce(PROBLEM)).containsOnly("OK");

        Integer rows = jdbc.queryForObject(
                "select count(*) from hint_usage where user_id = ?", Integer.class, userId);
        assertThat(rows).as("여덟 번 눌러도 한 번 본 것이다").isEqualTo(1);
    }

    @Test
    @DisplayName("userId 없는 요청은 400 이다 - 서버 잘못이 아니다")
    void missingUserIdIsABadRequest() throws Exception {
        for (String body : List.of("{}", "{\"userId\": null}")) {
            int status = mvc.perform(post("/api/problems/{code}/hints/{level}", PROBLEM, 1)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn().getResponse().getStatus();
            assertThat(status).as(body).isEqualTo(400);
        }
    }

    @Test
    @DisplayName("없는 문제나 없는 단계는 404 고 기록도 남지 않는다")
    void unknownTargetsLeaveNoTrace() throws Exception {
        assertThat(ask("P99_NOT_A_PROBLEM", 1).getStatus()).isEqualTo(404);
        assertThat(ask(PROBLEM, 7).getStatus()).isEqualTo(404);

        Integer rows = jdbc.queryForObject(
                "select count(*) from hint_usage where user_id = ?", Integer.class, userId);
        assertThat(rows).isZero();
    }
}
