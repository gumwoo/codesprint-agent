package dev.codesprint.explain;

import dev.codesprint.reviewer.ClaudeCliLlmClient;
import dev.codesprint.reviewer.PromptTemplate;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Explain Back 을 붙일지 정한다. <b>기본은 꺼져 있다</b> - Reviewer · Tutor 와 같은 이유다(로컬 Claude CLI 의
 * 로그인 세션을 쓰고, API 키를 받지 않는다). 한 bean 에서 스위치를 읽어 둘 중 하나를 만든다.
 *
 * <pre>
 *   CODESPRINT_EXPLAIN_ENABLED=true    # 로컬 Claude CLI 가 있고 로그인돼 있을 때
 * </pre>
 */
@Configuration
@EnableConfigurationProperties(ExplainProperties.class)
public class ExplainConfiguration {

    @Bean
    public ExplainPort explainer(ExplainProperties properties) {
        if (!properties.enabled()) {
            return new DisabledExplainer();
        }
        return new PromptExplainer(
                new ClaudeCliLlmClient(properties.command(),
                        Duration.ofSeconds(properties.timeoutSeconds())),
                PromptTemplate.load(properties.promptVersion()), properties.promptVersion());
    }
}
