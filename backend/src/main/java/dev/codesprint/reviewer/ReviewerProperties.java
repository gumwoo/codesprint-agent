package dev.codesprint.reviewer;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Reviewer 설정. 정본: application.yml 의 {@code codesprint.reviewer}.
 *
 * <p><b>{@code @Value} 로 받으면 안 된다.</b> {@code command} 는 YAML 리스트이고,
 * Spring 은 그것을 {@code command[0]}, {@code command[1]} … 로 펼쳐 둔다.
 * {@code ${codesprint.reviewer.command}} 라는 키는 존재하지 않으므로 placeholder 해석이
 * 실패하고, <b>Reviewer 를 켜는 순간 애플리케이션이 뜨지 않는다.</b>
 *
 * <p>실제로 그랬다. 나머지 테스트가 전부 Reviewer 를 끈 채로 돌아서 아무도 몰랐다 -
 * 이 PR 의 목적이 "실제 구현을 붙인다" 인데 정작 붙였을 때를 확인하지 않았다.
 *
 * @param command 실행할 명령. 기본값과 각 플래그의 이유는 application.yml 에 있다.
 * @param promptVersion 이 값이 그대로 기록에 남는다(PRD §135).
 */
@ConfigurationProperties(prefix = "codesprint.reviewer")
public record ReviewerProperties(
        @DefaultValue("false") boolean enabled,
        List<String> command,
        @DefaultValue("120") long timeoutSeconds,
        @DefaultValue("reviewer-v1") String promptVersion) {

    public ReviewerProperties {
        // 켜 놓고 명령이 없으면 프로세스를 만들 수 없다. 뜬 뒤에 매 제출마다
        // 실패하는 것보다 시작할 때 멈추는 편이 낫다.
        if (enabled && (command == null || command.isEmpty())) {
            throw new IllegalArgumentException(
                    "codesprint.reviewer.enabled=true 인데 command 가 비어 있다");
        }
        command = command == null ? List.of() : List.copyOf(command);
    }
}
