package dev.codesprint.explain;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Explain Back 설정. 명령은 Reviewer · Tutor 와 같은 앵커를 가리킨다(application.yml) - 따로 적으면 한쪽만 도구
 * 권한이 열린 채로 남는다.
 *
 * @param promptVersion 파일 이름이 버전이다(explain/prompts/). 분석에 그대로 실어 보낸다.
 */
@ConfigurationProperties(prefix = "codesprint.explain")
public record ExplainProperties(
        @DefaultValue("false") boolean enabled,
        List<String> command,
        @DefaultValue("120") long timeoutSeconds,
        @DefaultValue("explain-v1") String promptVersion) {

    public ExplainProperties {
        if (enabled && (command == null || command.isEmpty())) {
            throw new IllegalArgumentException(
                    "codesprint.explain.enabled=true 인데 command 가 비어 있다");
        }
        command = command == null ? List.of() : List.copyOf(command);
    }
}
