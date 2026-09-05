package dev.codesprint;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * CodeSprint Agent 백엔드.
 *
 * <p>사용자 코드를 실행하지 않는다. 그것은 별도 Judge Worker 의 일이며 이 애플리케이션은
 * 큐로만 이야기한다 - 근거: docs/adr/0011-language-boundary.md
 */
@SpringBootApplication
// 채점이 끝난 job 을 주기적으로 찾아 학습 상태에 반영한다(ADR-0013).
@EnableScheduling
public class CodeSprintApplication {

    public static void main(String[] args) {
        SpringApplication.run(CodeSprintApplication.class, args);
    }
}
