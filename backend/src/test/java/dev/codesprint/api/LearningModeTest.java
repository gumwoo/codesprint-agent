package dev.codesprint.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import dev.codesprint.learning.persistence.UserRepository;
import dev.codesprint.learning.persistence.UserRow;
import dev.codesprint.problem.ProblemCatalog;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 학습 모드. 정본: PRD §151, ADR-0043.
 *
 * <p>모드는 <b>무엇을 내주는가</b>만 바꾼다 - 힌트 단계, 유형 표시, 개념 자료. 막힌 힌트는
 * 본 것으로 기록되지 않아야 한다 - 받지 않은 도움으로 mastery 가 깎이면 안 된다(ADR-0027).
 */
@SpringBootTest
class LearningModeTest {

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
    private static final String PROBLEM = "P02_GRID_TRAVERSAL";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ProblemCatalog catalog;

    private MockMvc mvc;
    private long userId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        userId = users.save(new UserRow(
                "mode-" + System.nanoTime() + "@codesprint.dev", "모드테스트", "JOB")).id();
    }

    /** $ref 로 다른 계약을 가리키므로 저장소의 contracts/ 로 이어 준다. */
    private static JsonSchema schema(String name) throws Exception {
        Path contracts = Path.of(System.getProperty("codesprint.repoRoot")).resolve("contracts");
        return JsonSchemaFactory
                .builder(JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012))
                .schemaMappers(mappers -> mappers.mapPrefix(
                        "https://codesprint.dev/contracts/", contracts.toUri().toString()))
                .build()
                .getSchema(Files.readString(contracts.resolve(name), StandardCharsets.UTF_8));
    }

    private MockHttpServletResponse setMode(String mode) throws Exception {
        return mvc.perform(put("/api/users/{id}/learning-mode", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\": \"%s\"}".formatted(mode)))
                .andReturn().getResponse();
    }

    private int hint(int level) throws Exception {
        return mvc.perform(post("/api/problems/{code}/hints/{level}", PROBLEM, level)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\": %d}".formatted(userId)))
                .andReturn().getResponse().getStatus();
    }

    private Integer seenLevel() {
        return jdbc.queryForObject("""
                select max(h.hint_level) from hint_usage h join problems p on p.id = h.problem_id
                where h.user_id = ? and p.code = ?
                """, Integer.class, userId, PROBLEM);
    }

    private JsonNode problemView() throws Exception {
        return MAPPER.readTree(mvc.perform(get("/api/problems/{code}", PROBLEM)
                .param("userId", "" + userId)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("기본은 NORMAL 이고, 모르는 모드는 400 이다")
    void defaultAndUnknown() throws Exception {
        JsonNode user = MAPPER.readTree(mvc.perform(get("/api/users/{id}", userId))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(user.get("learningMode").asText()).isEqualTo("NORMAL");
        assertThat(schema("user.schema.json").validate(user)).isEmpty();

        assertThat(setMode("HARD").getStatus()).isEqualTo(400);
        assertThat(setMode("normal").getStatus()).as("대소문자를 바꿔 받지 않는다").isEqualTo(400);
        JsonNode changed = MAPPER.readTree(setMode("STRICT").getContentAsString(StandardCharsets.UTF_8));
        assertThat(changed.get("learningMode").asText()).isEqualTo("STRICT");
    }

    @Test
    @DisplayName("STRICT 는 H2 까지만 주고, 막힌 단계는 본 것으로 남기지 않는다")
    void strictStopsAtTwo() throws Exception {
        setMode("STRICT");

        assertThat(hint(1)).isEqualTo(200);
        assertThat(hint(2)).isEqualTo(200);
        assertThat(hint(3)).isEqualTo(409);
        assertThat(seenLevel()).as("H3 은 기록되지 않았다").isEqualTo(2);

        // 대조: NORMAL 로 돌리면 같은 요청이 열린다 - 막은 것이 모드라는 것을 보인다
        setMode("NORMAL");
        assertThat(hint(3)).isEqualTo(200);
    }

    @Test
    @DisplayName("EXAM 은 힌트를 주지 않고 문제의 유형(Skill)을 숨긴다")
    void examHidesTypeAndHints() throws Exception {
        setMode("EXAM");

        assertThat(hint(1)).isEqualTo(409);
        assertThat(seenLevel()).isNull();

        JsonNode view = problemView();
        assertThat(view.get("skills")).isEmpty();
        assertThat(schema("problem-view.schema.json").validate(view)).isEmpty();

        JsonNode anonymous = MAPPER.readTree(mvc.perform(get("/api/problems/{code}", PROBLEM))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(anonymous.get("skills")).as("사용자를 모르면 NORMAL 과 같다").isNotEmpty();
    }

    @Test
    @DisplayName("GUIDED 는 PRIMARY Skill 의 개념 자료를 문제와 함께 주고, NORMAL 은 주지 않는다")
    void guidedAttachesConcept() throws Exception {
        assertThat(problemView().get("concept").isNull()).as("NORMAL").isTrue();

        setMode("GUIDED");
        JsonNode view = problemView();
        assertThat(view.get("concept").get("skillCode").asText())
                .isEqualTo(catalog.find(PROBLEM).primarySkill());
        assertThat(schema("problem-view.schema.json").validate(view)).isEmpty();
    }
}
