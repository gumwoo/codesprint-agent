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

    /** {@code fetch(url, { method: "POST" })} 의 그 자리. 없으면 GET 이다. */
    private static final Pattern METHOD = Pattern.compile("method:\\s*\"([A-Z]+)\"");

    /** 부르는 곳 하나. <b>경로만으로는 부족하다</b> - 같은 경로에 GET 과 POST 가 따로 있다. */
    private record Call(String method, String path) {

        @Override
        public String toString() {
            return method + " " + path;
        }
    }

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
    @DisplayName("화면이 부르는 API 가 method 까지 실재한다")
    void everyCalledEndpointExists() throws Exception {
        Set<Call> mapped = new LinkedHashSet<>();
        handlerMapping.getHandlerMethods().keySet().forEach(info -> {
            var paths = info.getPathPatternsCondition();
            if (paths == null) {
                return;
            }
            var methods = info.getMethodsCondition().getMethods();
            paths.getPatterns().forEach(pattern -> {
                String path = normalise(pattern.getPatternString());
                if (methods.isEmpty()) {
                    // method 를 안 건 매핑은 아무 method 나 받는다.
                    for (String any : new String[] {"GET", "POST", "PUT", "PATCH", "DELETE"}) {
                        mapped.add(new Call(any, path));
                    }
                } else {
                    methods.forEach(method -> mapped.add(new Call(method.name(), path)));
                }
            });
        });

        Set<Call> called = callsIn(read("app.js"));

        assertThat(called).as("화면이 API 를 하나도 부르지 않는다 - 정규식이 깨졌다")
                .isNotEmpty();
        assertThat(mapped).as("서버 매핑을 읽지 못했다").isNotEmpty();
        assertThat(called)
                .as("화면이 없는 엔드포인트를 부른다. 실재하는 것: %s", mapped)
                .isSubsetOf(mapped);
    }

    /**
     * 화면이 부르는 (method, 경로) 들.
     *
     * <p><b>경로만 보면 부족하다.</b> {@code /api/users} 는 POST 만 있는데 GET 으로 불러도
     * 경로는 맞으므로 통과한다 - 눌러 보면 405 다.
     *
     * <p>method 는 {@code fetch(url, {...})} 의 두 번째 인자에서 읽는다. 없으면 GET 이다
     * (fetch 의 기본값). 다음 {@code /api/} 가 나오기 전까지만 찾아, 뒤에 오는 다른
     * 호출의 method 를 잘못 집지 않게 한다.
     */
    private static Set<Call> callsIn(String script) {
        Set<Call> calls = new LinkedHashSet<>();
        Matcher matcher = API_CALL.matcher(script);
        while (matcher.find()) {
            int from = matcher.end();
            int next = script.indexOf("/api/", from);
            String window = script.substring(from, next < 0 ? script.length() : next);
            Matcher method = METHOD.matcher(window);
            calls.add(new Call(method.find() ? method.group(1) : "GET",
                    normalise(matcher.group(1))));
        }
        return calls;
    }

    /**
     * 주석을 뺀다.
     *
     * <p>규칙은 <b>코드</b>에 대한 것이다. 이 파일의 주석은 왜 화면이 판단하지 않는지를
     * 설명하느라 그 단어들을 쓰므로, 그대로 검사하면 설명을 지워야 통과하게 된다.
     *
     * <p>처음에는 {@code //} 로 시작하는 줄만 뺐는데, {@code /** ... *}{@code /} 블록의
     * {@code * mastery} 가 "연산자 뒤의 점수" 로 잡혔다 - <b>주석을 지우려고 만든 것이
     * 주석에 걸린 셈이다.</b> 블록 주석도 함께 뺀다.
     *
     * <p>문자열 안의 {@code //} 나 {@code /*} 는 다루지 않는다 - 지금 파일에 없고,
     * 흉내를 늘리면 검사보다 흉내가 먼저 틀린다.
     */
    private static String withoutComments(String script) {
        String withoutBlocks = script.replaceAll("(?s)/\\*.*?\\*/", "");
        return withoutBlocks.lines()
                .filter(line -> !line.strip().startsWith("//"))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    /** 경로 변수와 템플릿 자리를 같은 모양으로 만든다. {@code /api/problems/{}} 처럼. */
    private static String normalise(String path) {
        // 질의 문자열은 떼어 낸다. ?userId=... 가 붙어도 같은 엔드포인트이며,
        // 매핑은 경로로만 잡힌다 - 붙은 채로 대조하면 실재하는 것을 없다고 한다.
        return path.split("[?]", 2)[0]
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
    @DisplayName("바깥에서 가져오는 것은 버전이 고정돼 있고 integrity 가 붙어 있다")
    void externalAssetsArePinned() throws Exception {
        // 빌드가 없으므로 lock 파일도 없다. 버전을 적어 두지 않으면 CDN 이 바꾸는
        // 대로 화면이 바뀌고, integrity 가 없으면 그 바뀐 것을 그대로 실행한다.
        Matcher matcher = Pattern.compile("(?:src|href)=\"(https://[^\"]+)\"([^>]*)>")
                .matcher(read("index.html"));
        int found = 0;
        while (matcher.find()) {
            found++;
            String url = matcher.group(1);
            String attributes = matcher.group(2);
            assertThat(url)
                    .as("%s: 버전이 고정돼 있지 않다 (latest 나 범위 지정)", url)
                    .matches(".*/\\d+\\.\\d+\\.\\d+/.*");
            assertThat(attributes)
                    .as("%s: integrity 가 없다", url)
                    .contains("integrity=\"sha384-");
            assertThat(attributes)
                    .as("%s: integrity 를 검사하려면 crossorigin 이 필요하다", url)
                    .contains("crossorigin=");
        }
        assertThat(found).as("바깥에서 가져오는 것이 하나도 없다 - 정규식이 깨졌다")
                .isPositive();
    }

    @Test
    @DisplayName("편집기를 못 가져와도 코드를 쓸 자리가 남는다")
    void theEditorIsOptional() throws Exception {
        // CDN 이 막혔거나 오프라인이면 CodeMirror 가 오지 않는다. 그때 화면이 안
        // 뜨는 것은 받아들일 수 없다 - 편집기는 편의지 이 화면의 목적이 아니다.
        String script = read("app.js");

        assertThat(script)
                .as("CodeMirror 가 없을 때를 다루지 않는다")
                .contains("typeof window.CodeMirror");
        assertThat(script)
                .as("편집기가 없으면 textarea 에서 읽어야 한다")
                .contains("editor ? editor.getValue() : $(\"sourceCode\").value");
        assertThat(read("index.html"))
                .as("textarea 자체가 없으면 대안이 없다")
                .contains("id=\"sourceCode\"");
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

    /**
     * 화면이 스스로 판단하면 나타나는 모양들.
     *
     * <p><b>보여주는 것과 만드는 것은 다르다.</b> 처음에는 {@code mastery} 라는 낱말을
     * 금지했는데, 그 값을 화면에 <b>표시</b>하기 시작하자 걸렸다 - 규칙이 잘못된
     * 것이었다. 서버가 정한 값을 옮겨 적는 것은 이 경계를 넘지 않는다.
     *
     * <p>그래서 <b>계산과 선택</b>만 막는다.
     *
     * <ul>
     *   <li>점수에 산술을 하거나 임계값과 비교한다 - 그 순간 화면이 두 번째 산식이 된다
     *   <li>액션 · 확정 상태를 문자열로 들고 있다 - 무엇을 할지 화면이 고르기 시작한 것이다
     * </ul>
     */
    private static final String[] FORBIDDEN = {
        "(mastery|confidence)\\s*[<>]=?",
        "(mastery|confidence)\\s*[-+*/]",
        "[-+*/]\\s*(mastery|confidence)",
        "MICRO_DRILL", "CHANGE_SKILL", "RETRY_VARIANT", "REVIEW_CONCEPT",
        "CONFIRMED", "PROBABLE",
    };

    @Test
    @DisplayName("화면은 판정도 점수도 만들지 않는다")
    void theClientDoesNotDecide() throws Exception {
        // 경계는 프롬프트가 아니라 검사로 지킨다(ADR-0001). 화면이 점수를 계산하거나
        // 다음 행동을 고르기 시작하면, 서버가 정한 것과 화면이 보여주는 것이 갈린다 -
        // 그리고 사용자가 보는 쪽이 이긴다.
        String script = withoutComments(read("app.js"));
        for (String forbidden : FORBIDDEN) {
            assertThat(Pattern.compile(forbidden).matcher(script).find())
                    .as("화면이 %s 를 스스로 다룬다 - 그 판단은 서버 몫이다", forbidden)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("이 검사가 계산은 잡고 표시는 놓아준다")
    void theRuleCatchesComputationNotDisplay() {
        // 규칙을 낱말 금지에서 계산 금지로 바꿨으므로, 그것이 여전히 일하는지 본다.
        // 잡지 못하는 검사를 두면 경계가 있다고 믿는 것만 남는다.
        String computing = "const level = mastery * 0.6 + confidence * 0.4;\n"
                + "if (mastery >= 0.9) { pick(\"MICRO_DRILL\"); }";
        String displaying = "cell.textContent = state.mastery === null "
                + "? \"-\" : state.mastery.toFixed(2);\n"
                + "status.textContent = state.status;";

        assertThat(java.util.Arrays.stream(FORBIDDEN)
                .anyMatch(rule -> Pattern.compile(rule).matcher(computing).find()))
                .as("계산하는 코드를 잡아야 한다").isTrue();
        assertThat(java.util.Arrays.stream(FORBIDDEN)
                .anyMatch(rule -> Pattern.compile(rule).matcher(displaying).find()))
                .as("보여주기만 하는 코드는 잡으면 안 된다").isFalse();
    }
}
