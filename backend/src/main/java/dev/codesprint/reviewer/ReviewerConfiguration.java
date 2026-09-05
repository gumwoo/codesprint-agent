package dev.codesprint.reviewer;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Reviewer 를 붙일지 정한다.
 *
 * <p><b>API 키가 없으면 붙지 않는다.</b> 그때는 {@link DisabledReviewer} 가 남고,
 * 분석 없이 나머지가 그대로 돈다 - 판정도 mastery 도 다음 행동도 Reviewer 없이
 * 계산된다.
 *
 * <p>키를 설정 파일에 적지 않는다. 환경변수로만 받는다 - 공개 저장소이고, 한 번
 * 커밋되면 히스토리에서 지우기 어렵다.
 *
 * <pre>
 *   CODESPRINT_LLM_API_KEY=sk-...   설정하면 Reviewer 가 붙는다
 *   (없으면)                         분석 없이 진행한다
 * </pre>
 */
@Configuration
@Conditional(ReviewerConfiguration.ApiKeyPresent.class)
public class ReviewerConfiguration {

    /**
     * 키가 <b>비어 있지 않을 때만</b> 붙인다.
     *
     * <p>{@code @ConditionalOnProperty} 를 쓰면 안 된다. application.yml 이 기본값으로
     * 빈 문자열을 넣으므로 프로퍼티는 언제나 "존재" 하고, 그 조건은 값이 있다고 본다 -
     * 키 없이 Reviewer 가 붙어 모든 호출이 401 로 실패한다.
     */
    static class ApiKeyPresent implements org.springframework.context.annotation.Condition {

        @Override
        public boolean matches(org.springframework.context.annotation.ConditionContext context,
                org.springframework.core.type.AnnotatedTypeMetadata metadata) {
            String key = context.getEnvironment().getProperty("codesprint.reviewer.api-key", "");
            return !key.isBlank();
        }
    }

    private static final Logger log = LoggerFactory.getLogger(ReviewerConfiguration.class);

    @Bean
    public LlmClient llmClient(
            @Value("${codesprint.reviewer.api-key}") String apiKey,
            @Value("${codesprint.reviewer.model}") String model,
            @Value("${codesprint.reviewer.max-tokens:2048}") int maxTokens,
            @Value("${codesprint.reviewer.timeout-seconds:60}") long timeoutSeconds) {

        // 키 자체를 남기지 않는다. 붙었다는 사실과 모델 이름까지만.
        log.info("Reviewer 를 붙인다: model={}", model);
        return new AnthropicLlmClient(apiKey, model, maxTokens, Duration.ofSeconds(timeoutSeconds));
    }

    /**
     * @param promptVersion 프롬프트 파일 이름. 이 값이 그대로 {@code promptVersion} 으로
     *     기록에 남는다(PRD §135). 내용을 고칠 때는 새 파일을 만들고 이 설정을 바꾼다.
     */
    @Bean
    public ReviewerPort promptReviewer(LlmClient llmClient,
            @Value("${codesprint.reviewer.prompt-version:reviewer-v1}") String promptVersion) {
        return new PromptReviewer(llmClient, PromptTemplate.load(promptVersion));
    }
}
