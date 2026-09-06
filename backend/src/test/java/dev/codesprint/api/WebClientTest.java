package dev.codesprint.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 화면이 실재하는 API 만 부르는가.
 *
 * <p>빌드 도구가 없으므로(ADR-0017) 타입 검사도 없다. 화면이 없는 엔드포인트를 불러도
 * <b>배포까지 아무도 모른다</b> - 눌러 봐야 404 로 드러난다.
 *
 * <p>그래서 화면이 부르는 경로를 실제 매핑과 대조한다. 프레임워크를 들이지 않고
 * 얻을 수 있는 검사 중 값이 가장 큰 것이다.
 */
@SpringBootTest
class WebClientTest {

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

    /** 화면이 쓰는 fetch 대상. 템플릿 리터럴의 `${...}` 는 경로 변수로 바꿔 본다. */
    private static final Pattern API_CALL = Pattern.compile("[\"`](/api/[^\"`\\s]*)");

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private static Path staticDir() {
        return Path.of(System.getProperty("codesprint.repoRoot"))
                .resolve("backend/src/main/resources/static");
    }

    private static String read(String name) throws Exception {
        return Files.readString(staticDir().resolve(name), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("화면이 부르는 API 가 전부 실재한다")
    void everyCalledEndpointExists() throws Exception {
        Set<String> mapped = new LinkedHashSet<>();
        handlerMapping.getHandlerMethods().keySet().stream()
                .map(RequestMappingInfo::getPathPatternsCondition)
                .filter(java.util.Objects::nonNull)
                .forEach(condition -> condition.getPatterns()
                        .forEach(pattern -> mapped.add(normalise(pattern.getPatternString()))));

        Set<String> called = new LinkedHashSet<>();
        Matcher matcher = API_CALL.matcher(read("app.js"));
        while (matcher.find()) {
            called.add(normalise(matcher.group(1)));
        }

        assertThat(called).as("화면이 API 를 하나도 부르지 않는다 - 정규식이 깨졌다")
                .isNotEmpty();
        assertThat(mapped).as("서버 매핑을 읽지 못했다").isNotEmpty();
        assertThat(called)
                .as("화면이 없는 엔드포인트를 부른다. 실재하는 것: %s", mapped)
                .isSubsetOf(mapped);
    }

    /**
     * 줄 전체가 주석인 줄을 뺀다.
     *
     * <p>규칙은 <b>코드</b>에 대한 것이다. 이 파일의 주석은 왜 화면이 판단하지 않는지를
     * 설명하느라 그 단어들을 쓰므로, 그대로 검사하면 설명을 지워야 통과하게 된다.
     * 줄 끝 주석과 문자열 안의 {@code //} 는 다루지 않는다 - 지금 파일에 없고,
     * 흉내를 늘리면 검사보다 흉내가 먼저 틀린다.
     */
    private static String withoutComments(String script) {
        return script.lines()
                .filter(line -> !line.strip().startsWith("//"))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    /** 경로 변수와 템플릿 자리를 같은 모양으로 만든다. {@code /api/problems/{}} 처럼. */
    private static String normalise(String path) {
        return path
                .replaceAll("\\$\\{[^}]*\\}", "{}")
                .replaceAll("\\{[^}]*\\}", "{}");
    }

    @Test
    @DisplayName("화면이 참조하는 정적 파일이 전부 서빙된다")
    void staticFilesAreServed() throws Exception {
        assertThat(mvc.perform(get("/")).andReturn().getResponse().getStatus()).isEqualTo(200);

        Matcher matcher = Pattern.compile("(?:src|href)=\"(/[^\"]+)\"").matcher(read("index.html"));
        int found = 0;
        while (matcher.find()) {
            found++;
            assertThat(mvc.perform(get(matcher.group(1)))
                    .andReturn().getResponse().getStatus())
                    .as("%s 를 서빙하지 못한다", matcher.group(1))
                    .isEqualTo(200);
        }
        assertThat(found).as("참조하는 파일이 하나도 없다 - 정규식이 깨졌다").isPositive();
    }

    @Test
    @DisplayName("문제 목록이 계약을 지킨다")
    void problemListMatchesContract() throws Exception {
        String json = mvc.perform(get("/api/problems"))
                .andReturn().getResponse().getContentAsString();

        Path contract = Path.of(System.getProperty("codesprint.repoRoot"))
                .resolve("contracts/problem-list.schema.json");
        var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(Files.readString(contract, StandardCharsets.UTF_8));

        assertThat(schema.validate(MAPPER.readTree(json)))
                .as("problem-list 계약 위반").isEmpty();
        var problems = MAPPER.readTree(json).get("problems");
        assertThat(problems).isNotEmpty();

        // 순서를 고정한다. 카탈로그의 Map 은 순서를 보장하지 않아, 정렬하지 않으면
        // 같은 데이터인데 실행할 때마다 목록이 다르게 보인다.
        var codes = new java.util.ArrayList<String>();
        problems.forEach(problem -> codes.add(problem.get("code").asText()));
        assertThat(codes).isSorted();
    }

    @Test
    @DisplayName("화면은 판정도 점수도 만들지 않는다")
    void theClientDoesNotDecide() throws Exception {
        // 경계는 프롬프트가 아니라 검사로 지킨다(ADR-0001). 화면이 mastery 를 계산하거나
        // 다음 행동을 고르기 시작하면, 서버가 정한 것과 화면이 보여주는 것이 갈린다.
        String script = withoutComments(read("app.js"));
        for (String forbidden : new String[] {"mastery", "confidence *=", "MICRO_DRILL",
                "CHANGE_SKILL", "RETRY_VARIANT", "CONFIRMED", "PROBABLE"}) {
            assertThat(Pattern.compile(forbidden).matcher(script).find())
                    .as("화면이 %s 를 스스로 다룬다 - 그 판단은 서버 몫이다", forbidden)
                    .isFalse();
        }
    }
}
