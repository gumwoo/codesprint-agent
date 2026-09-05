package dev.codesprint.reviewer;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Reviewer 가 설정되지 않았을 때의 기본 구현. <b>아무것도 분석하지 않는다.</b>
 *
 * <p>모델을 붙이지 않은 채로도 나머지 흐름이 돌아야 한다. 그때 응답의 {@code review}
 * 는 null 이고 Mistake 는 확정되지 않으며 자동 드릴도 발동하지 않는다 - 판정과
 * mastery 와 나머지 결정은 그대로 동작한다.
 *
 * <p><b>조용히 넘어가지 않는다.</b> 한 번은 로그를 남긴다. 그러지 않으면 "Reviewer 가
 * 붙어 있는데 아무것도 확정되지 않는다" 와 "애초에 붙어 있지 않다" 를 구분할 수 없다.
 */
@Configuration
public class DisabledReviewer {

    private static final Logger log = LoggerFactory.getLogger(DisabledReviewer.class);

    /**
     * <b>{@code @ConditionalOnMissingBean} 을 쓰지 않는다.</b> 일반 {@code @Configuration}
     * 에서는 평가 순서에 의존하므로, 이 클래스가 먼저 등록되면 실제 어댑터가 붙어도
     * 이 bean 이 남는다 - 그러면 ReviewerPort 가 둘이 되어 애플리케이션이 뜨지 않는다.
     * 실제로 그렇게 깨졌다.
     *
     * <p>{@link ReviewerConfiguration} 과 <b>같은 조건을 반대로</b> 건다. 두 곳이 같은
     * 스위치를 보므로 어느 쪽이 먼저 평가되든 결과가 같다.
     */
    @Bean
    @ConditionalOnProperty(name = "codesprint.reviewer.enabled", havingValue = "false",
            matchIfMissing = true)
    public ReviewerPort disabledReviewerPort() {
        AtomicBoolean warned = new AtomicBoolean(false);
        return new ReviewerPort() {

            @Override
            public Optional<ReviewerOutput> review(Request request) {
                if (warned.compareAndSet(false, true)) {
                    log.warn("Reviewer 가 설정되지 않았다. 오답 원인 분석 없이 진행한다 -"
                            + " Mistake 는 확정되지 않고 자동 드릴도 발동하지 않는다.");
                }
                return Optional.empty();
            }

            @Override
            public String promptVersion() {
                return "disabled";
            }
        };
    }
}
