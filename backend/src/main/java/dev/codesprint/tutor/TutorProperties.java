package dev.codesprint.tutor;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tutor 설정. 명령은 Reviewer 와 같은 앵커를 가리킨다(application.yml) - 따로 적으면 한쪽만 도구
 * 권한이 열린 채로 남는다.
 *
 * @param promptVersion 파일 이름이 버전이다(tutor/prompts/). 답에 그대로 실어 보낸다.
 */
@ConfigurationProperties(prefix = "codesprint.tutor")
public record TutorProperties(
        @DefaultValue("false") boolean enabled,
        List<String> command,
        @DefaultValue("120") long timeoutSeconds,
        @DefaultValue("tutor-v1") String promptVersion) {

    public TutorProperties {
        // 켜 놓고 명령이 없으면 프로세스를 만들 수 없다. 뜬 뒤에 매 질문마다 실패하는 것보다
        // 시작할 때 멈추는 편이 낫다.
        if (enabled && (command == null || command.isEmpty())) {
            throw new IllegalArgumentException(
                    "codesprint.tutor.enabled=true 인데 command 가 비어 있다");
        }
        command = command == null ? List.of() : List.copyOf(command);
    }
}
