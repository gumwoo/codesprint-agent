package dev.codesprint.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 테스트가 옮길 수 있는 시계.
 *
 * <p>간격 복습은 최소 하루를 기다려야 한다(ADR-0021). 이것이 없으면 이 저장소에서
 * 복습도 {@code MASTERED} 도 한 번도 검증할 수 없다.
 *
 * <p><b>이 우회는 테스트에만 있다.</b> 프로덕션에 시간을 앞당기는 수단을 두면
 * 그것으로 {@code MASTERED} 를 만들 수 있다.
 */
public class MovableClock extends Clock {

    private Instant now = Instant.parse("2026-09-08T09:00:00Z");

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneOffset getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    public void advance(Duration by) {
        now = now.plus(by);
    }

    /** 이 시계를 실제 시계 대신 끼운다. */
    @TestConfiguration
    public static class Installed {

        @Bean
        @Primary
        public MovableClock movableClock() {
            return new MovableClock();
        }
    }
}
