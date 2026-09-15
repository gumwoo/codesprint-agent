package dev.codesprint.generator;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.yaml.snakeyaml.Yaml;

/**
 * 문제 초안 생성기의 설정. <b>애플리케이션과 같은 {@code application.yml} 에서 읽는다.</b>
 *
 * <p>명령은 Reviewer 와 <b>같은 것</b>이다 - {@code application.yml} 이 YAML 앵커로
 * 한 번만 적는다. 따로 적으면 한쪽만 도구 권한({@code --tools ""})이 열린 채로 남는다.
 * 생성기 프롬프트에는 기존 문제 본문이 들어가므로 Reviewer 와 같은 이유로 모델이
 * 도구를 가지면 안 된다.
 *
 * <p>Spring 을 띄우지 않는다. 생성은 오프라인 저작이라 DB 가 필요 없다.
 */
public final class GeneratorSettings {

    private GeneratorSettings() {
    }

    /** {@code codesprint.generator} 아래의 값. */
    public record Values(List<String> command, long timeoutSeconds, String promptVersion) {
    }

    public static Values load() {
        return load(System::getenv);
    }

    @SuppressWarnings("unchecked")
    static Values load(Function<String, String> env) {
        Map<String, Object> generator;
        try (InputStream in = GeneratorSettings.class.getClassLoader()
                .getResourceAsStream("application.yml")) {
            if (in == null) {
                throw new IllegalStateException("application.yml 이 classpath 에 없다");
            }
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> codesprint = (Map<String, Object>) root.get("codesprint");
            generator = (Map<String, Object>) codesprint.get("generator");
            if (generator == null) {
                throw new IllegalStateException("application.yml 에 codesprint.generator 가 없다");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("application.yml 을 읽지 못했다", e);
        }

        List<String> command = new ArrayList<>();
        for (Object arg : (List<Object>) generator.get("command")) {
            // 빈 문자열 인자를 잃으면 다음 인자가 앞 옵션의 값으로 먹힌다.
            command.add(resolve(String.valueOf(arg), env));
        }
        return new Values(
                List.copyOf(command),
                Long.parseLong(resolve(String.valueOf(generator.get("timeout-seconds")), env)),
                resolve(String.valueOf(generator.get("prompt-version")), env));
    }

    /** {@code ${NAME:default}} 만 푼다. 흉내가 넓어지면 앱과 다른 설정을 같다고 믿게 된다. */
    private static String resolve(String raw, Function<String, String> env) {
        if (!raw.startsWith("${") || !raw.endsWith("}")) {
            return raw;
        }
        String body = raw.substring(2, raw.length() - 1);
        int colon = body.indexOf(':');
        if (colon < 0) {
            throw new IllegalStateException("기본값 없는 placeholder 는 다루지 않는다: " + raw);
        }
        String value = env.apply(body.substring(0, colon));
        return value == null || value.isBlank() ? body.substring(colon + 1) : value;
    }
}
