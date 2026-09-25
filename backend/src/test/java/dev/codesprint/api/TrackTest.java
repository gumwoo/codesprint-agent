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
import dev.codesprint.curriculum.CurriculumCatalog;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 학습 트랙이 켜지는 Skill 범위를 실제로 정하는가. 정본: PRD §129, ADR-0035.
 *
 * <p>트랙은 데이터 한 줄이지만 진단 · Skill 지도 · 다음 행동이 전부 그 범위 위에서 돈다.
 * 여기서 보는 것은 "트랙을 저장했는가" 가 아니라 <b>범위 밖이 실제로 보이지 않는가</b> 다.
 */
@SpringBootTest
class TrackTest {

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
    private CurriculumCatalog catalog;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private static JsonSchema schema(String name) throws Exception {
        Path path = Path.of(System.getProperty("codesprint.repoRoot"), "contracts", name);
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(Files.readString(path, StandardCharsets.UTF_8));
    }

    private JsonNode createUser(String track) throws Exception {
        String body = mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\": \"트랙\", \"track\": \"" + track + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(body);
    }

    private List<String> skillCodesOf(long userId) throws Exception {
        JsonNode map = MAPPER.readTree(mvc.perform(get("/api/users/{id}/skills", userId))
                .andReturn().getResponse().getContentAsString());
        List<String> codes = new ArrayList<>();
        map.get("skills").forEach(skill -> codes.add(skill.get("skillCode").asText()));
        return codes;
    }

    @Test
    @DisplayName("목표 없이는 시작할 수 없다 - 기본값이 없다")
    void aTrackIsRequired() throws Exception {
        int status = mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\": \"무목표\"}"))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(400);
    }

    @Test
    @DisplayName("없는 목표는 받지 않는다")
    void anUnknownTrackIsRejected() throws Exception {
        int status = mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\": \"x\", \"track\": \"NOT_A_TRACK\"}"))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(400);
    }

    @Test
    @DisplayName("사용자 응답과 목표 목록이 계약을 지킨다")
    void responsesFollowTheContracts() throws Exception {
        JsonNode user = createUser("JOB");
        assertThat(schema("user.schema.json").validate(user)).isEmpty();
        assertThat(user.get("track").asText()).isEqualTo("JOB");

        JsonNode tracks = MAPPER.readTree(mvc.perform(get("/api/tracks"))
                .andReturn().getResponse().getContentAsString());
        assertThat(schema("track-list.schema.json").validate(tracks)).isEmpty();
        // Skill 수는 서버가 커리큘럼에서 센 값이다.
        for (JsonNode track : tracks.get("tracks")) {
            assertThat(track.get("skillCount").asInt())
                    .isEqualTo(catalog.skillCodesFor(track.get("code").asText()).size());
        }
    }

    @Test
    @DisplayName("입문 트랙에는 BFS 가 보이지 않는다 - 진단도 묻지 않는다")
    void theIntroTrackHidesBfs() throws Exception {
        long userId = createUser("INTRO").get("userId").asLong();

        List<String> skills = skillCodesOf(userId);
        assertThat(skills).isNotEmpty()
                .containsExactlyInAnyOrderElementsOf(catalog.skillCodesFor("INTRO"))
                .noneMatch(code -> code.startsWith("BFS_"));

        JsonNode step = MAPPER.readTree(mvc.perform(get("/api/users/{id}/diagnostic", userId))
                .andReturn().getResponse().getContentAsString());
        assertThat(step.get("targetSkill").asText()).isIn(catalog.skillCodesFor("INTRO"));
        assertThat(step.get("total").asInt()).isEqualTo(skills.size());
    }

    @Test
    @DisplayName("목표를 바꾸면 켜지는 범위가 따라 바뀐다")
    void changingTheTrackChangesTheScope() throws Exception {
        long userId = createUser("INTRO").get("userId").asLong();
        assertThat(skillCodesOf(userId)).doesNotContain("BFS_BASIC");

        JsonNode changed = MAPPER.readTree(mvc.perform(put("/api/users/{id}/track", userId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"track\": \"JOB\"}"))
                .andReturn().getResponse().getContentAsString());
        assertThat(changed.get("track").asText()).isEqualTo("JOB");
        assertThat(skillCodesOf(userId)).contains("BFS_BASIC");

        JsonNode read = MAPPER.readTree(mvc.perform(get("/api/users/{id}", userId))
                .andReturn().getResponse().getContentAsString());
        assertThat(read.get("track").asText()).isEqualTo("JOB");
    }

    @Test
    @DisplayName("없는 목표로 바꾸려 하면 400 이고 기존 목표가 남는다")
    void changingToAnUnknownTrackKeepsTheOldOne() throws Exception {
        long userId = createUser("INTRO").get("userId").asLong();
        int status = mvc.perform(put("/api/users/{id}/track", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"track\": \"NOT_A_TRACK\"}"))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(400);
        JsonNode read = MAPPER.readTree(mvc.perform(get("/api/users/{id}", userId))
                .andReturn().getResponse().getContentAsString());
        assertThat(read.get("track").asText()).isEqualTo("INTRO");
    }
}
