package dev.codesprint.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
 * 사용자가 자기 Skill 상태를 볼 수 있는가.
 *
 * <p>이 제품의 한 문장은 "무엇을 모르는지 찾아낸다" 다. 그 답이 화면까지 오지 않으면
 * 찾아내는 일을 해도 소용이 없다.
 */
@SpringBootTest
class SkillMapTest {

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
                "skills-" + System.nanoTime() + "@codesprint.dev", "상태테스트")).id();
    }

    private static JsonSchema schema(String name) throws Exception {
        Path path = Path.of(System.getProperty("codesprint.repoRoot"), "contracts", name);
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(Files.readString(path, StandardCharsets.UTF_8));
    }

    private JsonNode skillMap() throws Exception {
        return MAPPER.readTree(mvc.perform(get("/api/users/{id}/skills", userId))
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode skillOf(String code) throws Exception {
        for (JsonNode skill : skillMap().get("skills")) {
            if (code.equals(skill.get("skillCode").asText())) {
                return skill;
            }
        }
        throw new AssertionError(code + " 가 목록에 없다");
    }

    /** 제출하고 채점 결과까지 반영한다. */
    private void solve(String problemCode, String judgeStatus) throws Exception {
        String body = """
                {"userId": %d, "language": "PYTHON", "sourceCode": "print(1)",
                 "hintLevel": 0, "solutionViewed": false, "solveSeconds": 120}
                """.formatted(userId);
        long submissionId = MAPPER.readTree(
                mvc.perform(post("/api/problems/{code}/submit", problemCode)
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andReturn().getResponse().getContentAsString())
                .get("submissionId").asLong();

        JudgeJobRow job = jobs.findBySubmissionId(submissionId).orElseThrow();
        jdbc.update("UPDATE judge_jobs SET status = 'DONE', result = ?::jsonb WHERE id = ?",
                """
                {"status": "%s", "passed": 6, "total": 6, "executionMs": 100,
                 "memoryKb": 20480, "failedCaseId": null, "stderr": null, "cases": []}
                """.formatted(judgeStatus), job.id());
        poller.applyFinishedJobs();
    }

    @Test
    @DisplayName("아직 손대지 않은 Skill 도 빠짐없이 나온다")
    void untouchedSkillsAreStillListed() throws Exception {
        // **이것이 이 API 의 존재 이유다.** Evidence 가 있는 Skill 만 내보내면
        // "무엇을 모르는가" 가 목록에서 사라진다 - 아무것도 안 한 사용자에게는
        // 빈 목록이 오고, 그건 "다 안다" 와 화면에서 같아 보인다.
        JsonNode map = skillMap();

        List<String> listed = new ArrayList<>();
        map.get("skills").forEach(skill -> listed.add(skill.get("skillCode").asText()));

        JsonNode catalog = MAPPER.readTree(mvc.perform(get("/api/skills"))
                .andReturn().getResponse().getContentAsString());
        List<String> defined = new ArrayList<>();
        catalog.get("skills").forEach(skill -> defined.add(skill.get("code").asText()));

        assertThat(listed).as("활성 Skill 전부가 있어야 한다").containsExactlyElementsOf(defined);
    }

    @Test
    @DisplayName("아직 안 본 것과 보고 못하는 것을 구분한다")
    void unassessedIsNotZero() throws Exception {
        JsonNode untouched = skillOf("BFS_SHORTEST_PATH");

        // null 과 0.0 은 다르다. 0.0 으로 채우면 "아직 안 봤다" 가 "완전히 못한다" 가 된다.
        assertThat(untouched.get("mastery").isNull()).as("평가 전에는 null 이다").isTrue();
        assertThat(untouched.get("evidenceCount").asInt()).isZero();
    }

    @Test
    @DisplayName("풀고 나면 그 Skill 이 움직인다")
    void solvingMovesTheSkill() throws Exception {
        assertThat(skillOf("BFS_GRID_TRAVERSAL").get("mastery").isNull()).isTrue();

        solve("P02_GRID_TRAVERSAL", "ACCEPTED");

        JsonNode after = skillOf("BFS_GRID_TRAVERSAL");
        assertThat(after.get("mastery").isNull()).as("이제 평가됐다").isFalse();
        assertThat(after.get("evidenceCount").asInt()).isPositive();
        assertThat(after.get("status").asText()).isNotEqualTo("UNASSESSED");
    }

    @Test
    @DisplayName("선수 조건이 막고 있으면 LOCKED 로 보인다")
    void lockedSkillsAreVisibleAsLocked() throws Exception {
        // 잠긴 것을 숨기지 않는다. 왜 이 문제를 받았는지 설명하려면 "이것이 먼저다" 가
        // 보여야 한다 - 선수 관계는 /api/skills 의 requires 에 있다.
        assertThat(skillOf("BFS_GRID_TRAVERSAL").get("status").asText()).isEqualTo("LOCKED");

        JsonNode catalog = MAPPER.readTree(mvc.perform(get("/api/skills"))
                .andReturn().getResponse().getContentAsString());
        JsonNode grid = null;
        for (JsonNode skill : catalog.get("skills")) {
            if ("BFS_GRID_TRAVERSAL".equals(skill.get("code").asText())) {
                grid = skill;
            }
        }
        assertThat(grid).isNotNull();
        assertThat(grid.get("requires")).as("무엇이 먼저인지 알려준다").isNotEmpty();
    }

    @Test
    @DisplayName("두 응답이 계약을 지킨다")
    void bothResponsesMatchTheirContracts() throws Exception {
        solve("P02_GRID_TRAVERSAL", "WRONG_ANSWER");

        assertThat(schema("skill-catalog.schema.json").validate(
                MAPPER.readTree(mvc.perform(get("/api/skills"))
                        .andReturn().getResponse().getContentAsString())))
                .as("skill-catalog 계약 위반").isEmpty();

        // skill-map 은 user-skill.schema.json 을 $ref 로 가리킨다. 그대로 두면
        // 검증기가 그 URI 를 **네트워크에서** 가져오려 한다 - 저장소의 계약을 보게
        // 하려면 접두사를 로컬 디렉터리로 이어 줘야 한다. Python 쪽에서 RefResolver 에
        // store 를 넘기는 것과 같은 일이다(tools/gen_evidence_golden.py).
        Path contracts = Path.of(System.getProperty("codesprint.repoRoot"), "contracts");
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(
                SpecVersion.VersionFlag.V202012,
                builder -> builder.schemaMappers(mappers -> mappers.mapPrefix(
                        "https://codesprint.dev/contracts/",
                        contracts.toUri().toString())));
        JsonSchema map = factory.getSchema(
                Files.readString(contracts.resolve("skill-map.schema.json"),
                        StandardCharsets.UTF_8));

        assertThat(map.validate(skillMap())).as("skill-map 계약 위반").isEmpty();
    }

    @Test
    @DisplayName("없는 사용자는 404 다")
    void unknownUserIsNotFound() throws Exception {
        assertThat(mvc.perform(get("/api/users/{id}/skills", 999999L))
                .andReturn().getResponse().getStatus())
                .isEqualTo(404);
    }
}
