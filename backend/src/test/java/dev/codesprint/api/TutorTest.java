package dev.codesprint.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import dev.codesprint.learning.persistence.UserRepository;
import dev.codesprint.learning.persistence.UserRow;
import dev.codesprint.reviewer.PromptTemplate;
import dev.codesprint.tutor.PromptTutor;
import dev.codesprint.tutor.TutorPort;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 자유 질문 Tutor. 정본: PRD §90 · §150 · §151, ADR-0044.
 *
 * <p>진짜 모델은 부르지 않는다. 실제 {@link PromptTutor} 에 대본을 읽는 가짜 모델을 끼워, 프롬프트
 * 렌더링 · 계약 검증 · 거절 경로를 그대로 지나게 한다.
 */
@SpringBootTest
class TutorTest {

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

    /** 대본을 읽는 모델과, 켜고 끌 수 있는 Tutor. */
    static class ScriptedTutor implements TutorPort {
        String reply = "";
        String lastPrompt;
        boolean on = true;
        private final PromptTutor real = new PromptTutor(prompt -> {
            lastPrompt = prompt;
            return reply;
        }, PromptTemplate.load("tutor-v1"), "tutor-v1");

        @Override
        public boolean enabled() {
            return on;
        }

        @Override
        public Optional<Answer> answer(Request request) {
            return real.answer(request);
        }

        @Override
        public String promptVersion() {
            return real.promptVersion();
        }
    }

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        ScriptedTutor scriptedTutor() {
            return new ScriptedTutor();
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ScriptedTutor tutor;

    private MockMvc mvc;
    private long userId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        userId = users.save(new UserRow(
                "tutor-" + System.nanoTime() + "@codesprint.dev", "튜터테스트", "JOB")).id();
        tutor.on = true;
        tutor.reply = "{\"answer\": \"큐에 넣을 때 방문 표시를 한다.\","
                + " \"followUpQuestion\": \"꺼낼 때 표시하면 무엇이 달라지는가?\"}";
        tutor.lastPrompt = null;
    }

    private void mode(String mode) throws Exception {
        mvc.perform(put("/api/users/{id}/learning-mode", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\": \"%s\"}".formatted(mode)));
    }

    private MockHttpServletResponse ask(String skill, String question) throws Exception {
        return mvc.perform(post("/api/tutor/questions").contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(java.util.Map.of(
                                "userId", userId, "skillCode", skill, "question", question))))
                .andReturn().getResponse();
    }

    @Test
    @DisplayName("FREE 모드에서 개념 자료와 함께 묻고, 계약을 지키는 답을 돌려준다")
    void answersInFreeMode() throws Exception {
        mode("FREE");

        MockHttpServletResponse response = ask("BFS_BASIC", "방문 표시는 언제 하나요?");

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        Path contract = Path.of(System.getProperty("codesprint.repoRoot"),
                "contracts", "tutor-answer.schema.json");
        assertThat(JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(Files.readString(contract, StandardCharsets.UTF_8)).validate(body))
                .isEmpty();
        assertThat(body.get("promptVersion").asText()).isEqualTo("tutor-v1");
        assertThat(tutor.lastPrompt).as("저장소의 개념 자료를 함께 준다")
                .contains("BFS_BASIC").contains("방문 표시는 언제 하나요?");
    }

    @Test
    @DisplayName("답은 Evidence 가 되지 않는다 - 설명을 들은 것은 풀 수 있다는 관측이 아니다")
    void answersLeaveNoEvidence() throws Exception {
        mode("FREE");
        assertThat(ask("BFS_BASIC", "BFS 가 뭔가요?").getStatus()).isEqualTo(200);

        Integer evidence = jdbc.queryForObject(
                "select count(*) from skill_evidence where user_id = ?", Integer.class, userId);
        assertThat(evidence).isZero();
    }

    @Test
    @DisplayName("FREE 가 아니면 409 다 - 다른 모드는 힌트 사다리를 거친다")
    void otherModesAreWithheld() throws Exception {
        for (String mode : new String[] {"NORMAL", "GUIDED", "STRICT", "EXAM"}) {
            mode(mode);
            assertThat(ask("BFS_BASIC", "BFS 가 뭔가요?").getStatus()).as(mode).isEqualTo(409);
        }
        assertThat(tutor.lastPrompt).as("모델을 부르지 않았다").isNull();
    }

    @Test
    @DisplayName("모델 답이 계약을 어기면 고쳐 쓰지 않고 502 다 - 점수를 끼워 넣어도 버린다")
    void contractViolationsAreDropped() throws Exception {
        mode("FREE");

        tutor.reply = "{\"answer\": \"설명\", \"followUpQuestion\": null, \"mastery\": 0.9}";
        assertThat(ask("BFS_BASIC", "질문").getStatus()).as("계약에 없는 자리").isEqualTo(502);

        tutor.reply = "그냥 글로 답한다";
        assertThat(ask("BFS_BASIC", "질문").getStatus()).as("JSON 이 아니다").isEqualTo(502);

        // 대조: 같은 경로에 올바른 답을 주면 통과한다 - 막은 것이 계약이라는 것을 보인다
        tutor.reply = "```json\n{\"answer\": \"설명\", \"followUpQuestion\": null}\n```";
        assertThat(ask("BFS_BASIC", "질문").getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("꺼져 있으면 503, 없는 Skill 은 404, 빈 질문은 400 이다")
    void disabledUnknownAndBlank() throws Exception {
        mode("FREE");
        assertThat(ask("NO_SUCH_SKILL", "질문").getStatus()).isEqualTo(404);
        assertThat(ask("DIJKSTRA", "질문").getStatus()).as("JOB 트랙 밖 Skill").isEqualTo(409);
        assertThat(ask("BFS_BASIC", " ").getStatus()).isEqualTo(400);
        assertThat(ask("BFS_BASIC", "x".repeat(1001)).getStatus()).isEqualTo(400);

        tutor.on = false;
        assertThat(ask("BFS_BASIC", "질문").getStatus()).isEqualTo(503);
    }

    @Test
    @DisplayName("시험 중에는 FREE 여도 답하지 않는다")
    void noAnswersDuringAMockTest() throws Exception {
        mode("FREE");
        assertThat(mvc.perform(post("/api/users/{id}/mock-tests", userId)).andReturn()
                .getResponse().getStatus()).isEqualTo(201);

        assertThat(ask("BFS_BASIC", "질문").getStatus()).isEqualTo(409);
    }
}
