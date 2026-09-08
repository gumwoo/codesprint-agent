package dev.codesprint.learning.service;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 시계. 간격 복습이 시간에 의존하므로 주입 가능해야 한다(ADR-0021).
 *
 * <p><b>테스트가 시간을 옮기려고 두는 것이지, 시간을 앞당기는 기능이 아니다.</b>
 * 프로덕션에는 이 빈을 바꾸는 경로가 없다 — 있으면 그것으로 {@code MASTERED} 를
 * 만들 수 있다. 복습이 재는 것은 "시간이 지난 뒤에도 되는가" 인데, 시간을 옮길 수
 * 있으면 아무것도 재지 못한다.
 *
 * <p>테스트는 {@code @TestConfiguration} 으로 이 빈을 갈음한다.
 */
@Configuration
public class ClockConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
