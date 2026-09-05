package dev.codesprint.reviewer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * <b>Reviewer 를 켠 상태에서 애플리케이션이 뜨는가.</b>
 *
 * <p>나머지 테스트는 전부 Reviewer 가 꺼진 상태(기본값)로 돈다. 그래서 켰을 때만
 * 드러나는 설정 문제를 하나도 보지 못한다 - 이 PR 의 목적이 "실제 Reviewer 구현을
 * 붙인다" 인데, 정작 붙였을 때 뜨는지를 확인하지 않는 셈이다.
 *
 * <p>실제 모델을 부르지 않는다. 설정이 bean 까지 그대로 전달되는지만 본다.
 */
@SpringBootTest(properties = "codesprint.reviewer.enabled=true")
class ReviewerWiringTest {

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
    }

    @Autowired
    private LlmClient llmClient;

    @Autowired
    private ReviewerPort reviewer;

    @Test
    @DisplayName("켜면 실제 어댑터가 붙는다")
    void wiresTheRealAdapter() {
        assertThat(llmClient).isInstanceOf(ClaudeCliLlmClient.class);
        assertThat(reviewer).isInstanceOf(PromptReviewer.class);
    }

    @Test
    @DisplayName("명령이 설정 그대로 전달된다")
    void passesTheConfiguredCommand() {
        // 빈 문자열 인자가 살아 있어야 한다. --tools "" 는 "도구를 전부 없앤다" 는
        // 뜻이고, 그 자리가 사라지면 --no-session-persistence 가 --tools 의 값으로
        // 먹혀 도구가 살아난다.
        assertThat(((ClaudeCliLlmClient) llmClient).command())
                .containsExactly("claude", "-p", "--tools", "",
                        "--no-session-persistence", "--strict-mcp-config",
                        "--setting-sources", "", "--output-format", "json");
    }

    @Test
    @DisplayName("프롬프트 버전이 기록에 남을 값과 같다")
    void exposesThePromptVersion() {
        assertThat(reviewer.promptVersion()).isEqualTo("reviewer-v1");
    }
}
