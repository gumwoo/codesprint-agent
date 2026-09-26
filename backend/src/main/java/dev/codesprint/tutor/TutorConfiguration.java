package dev.codesprint.tutor;

import dev.codesprint.reviewer.ClaudeCliLlmClient;
import dev.codesprint.reviewer.PromptTemplate;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Tutor 를 붙일지 정한다. <b>기본은 꺼져 있다</b> - Reviewer 와 같은 이유다(로컬 Claude CLI 의
 * 로그인 세션을 쓰고, API 키를 받지 않는다).
 *
 * <p>한 bean 에서 스위치를 읽어 둘 중 하나를 만든다. 두 곳에 반대 조건을 걸면 평가 순서에 따라
 * bean 이 둘이 될 수 있다 - DisabledReviewer 가 실제로 그렇게 깨졌다.
 *
 * <pre>
 *   CODESPRINT_TUTOR_ENABLED=true    # 로컬 Claude CLI 가 있고 로그인돼 있을 때
 * </pre>
 */
@Configuration
@EnableConfigurationProperties(TutorProperties.class)
public class TutorConfiguration {

    @Bean
    public TutorPort tutor(TutorProperties properties) {
        if (!properties.enabled()) {
            return new DisabledTutor();
        }
        return new PromptTutor(
                new ClaudeCliLlmClient(properties.command(),
                        Duration.ofSeconds(properties.timeoutSeconds())),
                PromptTemplate.load(properties.promptVersion()), properties.promptVersion());
    }
}
