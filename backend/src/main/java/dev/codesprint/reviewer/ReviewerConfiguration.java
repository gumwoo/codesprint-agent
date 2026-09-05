package dev.codesprint.reviewer;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Reviewer 를 붙일지 정한다.
 *
 * <p><b>기본은 붙이지 않는다.</b> 그때는 {@link DisabledReviewer} 가 남고 분석 없이
 * 나머지가 그대로 돈다 - 판정도 mastery 도 다음 행동도 Reviewer 없이 계산된다.
 * 켜려면 명시적으로 켜야 한다.
 *
 * <pre>
 *   CODESPRINT_REVIEWER_ENABLED=true    # 로컬에 Claude CLI 가 있고 로그인돼 있을 때
 *   (기본)                               # 분석 없이 진행한다
 * </pre>
 *
 * <p><b>API 키를 받지 않는다.</b> 이 프로젝트는 배포하지 않는다 - 만든 사람이 자기
 * PC 에서 돌린다. 로컬 Claude CLI 의 로그인 세션을 쓰므로 저장소에 넣을 비밀이 없다.
 * 배포가 필요해지면 {@link LlmClient} 구현을 하나 더 만든다.
 */
@Configuration
@ConditionalOnProperty(name = "codesprint.reviewer.enabled", havingValue = "true")
@EnableConfigurationProperties(ReviewerProperties.class)
public class ReviewerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ReviewerConfiguration.class);

    @Bean
    public LlmClient llmClient(ReviewerProperties properties) {
        log.info("Reviewer 를 붙인다: command={}", properties.command());
        return new ClaudeCliLlmClient(
                properties.command(), Duration.ofSeconds(properties.timeoutSeconds()));
    }

    @Bean
    public ReviewerPort promptReviewer(LlmClient llmClient, ReviewerProperties properties) {
        return new PromptReviewer(llmClient, PromptTemplate.load(properties.promptVersion()));
    }
}
