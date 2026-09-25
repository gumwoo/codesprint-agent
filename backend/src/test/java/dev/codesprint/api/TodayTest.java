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
import dev.codesprint.judge.JudgeJobRepository;
import dev.codesprint.judge.JudgeJobRow;
import dev.codesprint.learning.persistence.UserRepository;
import dev.codesprint.learning.persistence.UserRow;
import dev.codesprint.learning.service.JudgeResultPoller;
import dev.codesprint.support.JudgeResultFixture;
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
 * 오늘의 계획 · 설정 · 오답 요약 API. 정본: PRD §80 · §115 · §121 · §123 · §152, ADR-0038.
 */
@SpringBootTest
class TodayTest {

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

    private MockMvc mvc;
    private long userId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        userId = users.save(new UserRow(
                "today-" + System.nanoTime() + "@codesprint.dev", "오늘", "JOB")).id();
    }

    private static JsonSchema schema(String name) throws Exception {
        Path path = Path.of(System.getProperty("codesprint.repoRoot"), "contracts", name);
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(Files.readString(path, StandardCharsets.UTF_8));
    }

    private int putSettings(String json) throws Exception {
        return mvc.perform(put("/api/users/{id}/settings", userId)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andReturn().getResponse().getStatus();
    }

    private JsonNode today() throws Exception {
        return MAPPER.readTree(mvc.perform(get("/api/users/{id}/today", userId))
                .andReturn().getResponse().getContentAsString());
    }

    @Test
    @DisplayName("설정은 두 키를 모두 보내야 한다 - null 은 정하지 않았다, 생략은 400")
    void settingsNeedBothKeys() throws Exception {
        assertThat(putSettings("{\"dailyMinutes\": 60}")).isEqualTo(400);
        assertThat(putSettings("{\"dailyMinutes\": 5, \"examDate\": null}")).isEqualTo(400);
        assertThat(putSettings("{\"dailyMinutes\": 60, \"examDate\": \"내일\"}")).isEqualTo(400);
        assertThat(putSettings("{\"dailyMinutes\": null, \"examDate\": null}")).isEqualTo(200);

        JsonNode user = MAPPER.readTree(mvc.perform(get("/api/users/{id}", userId))
                .andReturn().getResponse().getContentAsString());
        assertThat(schema("user.schema.json").validate(user)).isEmpty();
        assertThat(user.get("dailyMinutes").isNull()).isTrue();
    }

    @Test
    @DisplayName("하루 시간을 정하지 않으면 계획은 시간을 나누지 않는다")
    void unbudgetedPlan() throws Exception {
        JsonNode plan = today();
        assertThat(schema("today.schema.json").validate(plan)).isEmpty();
        assertThat(plan.get("totalMinutes").isNull()).isTrue();
        assertThat(plan.get("blocks").size()).isBetween(1, 3);
        // 신규 사용자는 진단 중이다. 계획도 진단이 가리키는 곳을 먼저 가리킨다(ADR-0019).
        JsonNode step = MAPPER.readTree(mvc.perform(get("/api/users/{id}/diagnostic", userId))
                .andReturn().getResponse().getContentAsString());
        assertThat(plan.get("blocks").get(0).get("type").asText()).isEqualTo("DIAGNOSE");
        assertThat(plan.get("blocks").get(0).get("problem").get("code").asText())
                .isEqualTo(step.get("problem").get("code").asText());
    }

    @Test
    @DisplayName("하루 시간을 정하면 그 안에 채운다")
    void budgetedPlan() throws Exception {
        assertThat(putSettings("{\"dailyMinutes\": 90, \"examDate\": null}")).isEqualTo(200);
        JsonNode plan = today();
        assertThat(schema("today.schema.json").validate(plan)).isEmpty();
        assertThat(plan.get("totalMinutes").asInt()).isEqualTo(90);
        int sum = 0;
        for (JsonNode block : plan.get("blocks")) {
            sum += block.get("minutes").asInt();
        }
        assertThat(sum).isLessThanOrEqualTo(90);
        assertThat(plan.get("mode").asText()).isEqualTo("NORMAL");
    }

    @Test
    @DisplayName("시험이 가까우면 시험 모드이고, 지난 시험은 없는 시험이다")
    void examDate() throws Exception {
        String soon = java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(3).toString();
        assertThat(putSettings("{\"dailyMinutes\": 60, \"examDate\": \"" + soon + "\"}"))
                .isEqualTo(200);
        JsonNode plan = today();
        assertThat(plan.get("examInDays").asInt()).isEqualTo(3);
        assertThat(plan.get("mode").asText()).isEqualTo("EXAM");

        String past = java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1).toString();
        assertThat(putSettings("{\"dailyMinutes\": 60, \"examDate\": \"" + past + "\"}"))
                .isEqualTo(200);
        JsonNode after = today();
        assertThat(after.get("examInDays").isNull()).isTrue();
        assertThat(after.get("mode").asText()).isEqualTo("NORMAL");
    }

    @Test
    @DisplayName("오답 요약이 계약을 지키고, 제출이 없으면 비어 있다")
    void mistakeSummary() throws Exception {
        JsonNode summary = MAPPER.readTree(mvc.perform(get("/api/users/{id}/mistakes", userId))
                .andReturn().getResponse().getContentAsString());
        assertThat(schema("mistake-summary.schema.json").validate(summary)).isEmpty();
        assertThat(summary.get("submissions").asInt()).isZero();
        assertThat(summary.get("mistakes").size()).isZero();
    }

    @Test
    @DisplayName("없는 사용자는 404 다")
    void unknownUser() throws Exception {
        assertThat(mvc.perform(get("/api/users/{id}/today", 999999999L))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(get("/api/users/{id}/mistakes", 999999999L))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
    }
}
