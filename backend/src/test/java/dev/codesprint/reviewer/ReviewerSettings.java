package dev.codesprint.reviewer;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.yaml.snakeyaml.Yaml;

/**
 * 평가 하네스가 쓸 Reviewer 설정. <b>애플리케이션과 같은 파일에서 읽는다.</b>
 *
 * <p>처음에는 명령 · timeout · 프롬프트 버전을 하네스에 그대로 적어 뒀다. 그러면
 * 정본이 둘이 되고 조용히 갈라진다 - 실제로 갈라져 있었다. 앱은
 * {@code CODESPRINT_PROMPT_VERSION} 을 보는데 하네스는 다른 이름을 봤고, timeout
 * 기본값도 120 대 180 으로 달랐다. 앱을 v2 로 바꿔 두고 평가를 돌리면 <b>앱은 v2 인데
 * 재는 것은 v1</b> 이 된다.
 *
 * <p>커리큘럼을 빌드 시점에 한 곳에서 가져오는 것과 같은 이유다(ADR-0012). 여기서는
 * Spring 을 띄우지 않으므로(DB 가 필요하다) {@code application.yml} 을 직접 읽고
 * {@code ${VAR:default}} 만 해석한다.
 */
final class ReviewerSettings {

    private ReviewerSettings() {
    }

    /** 이 값들은 {@code application.yml} 의 {@code codesprint.reviewer} 아래에서 온다. */
    record Values(List<String> command, long timeoutSeconds, String promptVersion) {
    }

    static Values load() {
        return load(System::getenv);
    }

    /** @param env 환경변수 조회. 테스트가 갈아 끼운다. */
    @SuppressWarnings("unchecked")
    static Values load(Function<String, String> env) {
        Map<String, Object> reviewer;
        try (InputStream in = ReviewerSettings.class.getClassLoader()
                .getResourceAsStream("application.yml")) {
            if (in == null) {
                throw new IllegalStateException(
                        "application.yml 이 classpath 에 없다 - 평가가 앱과 같은 설정을 "
                                + "쓰는지 확인할 수 없다.");
            }
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> codesprint =
                    (Map<String, Object>) root.get("codesprint");
            reviewer = (Map<String, Object>) codesprint.get("reviewer");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("application.yml 을 읽지 못했다", e);
        }

        List<String> command = new ArrayList<>();
        for (Object arg : (List<Object>) reviewer.get("command")) {
            // 빈 문자열 인자를 잃으면 다음 인자가 앞 옵션의 값으로 먹힌다 -
            // `--tools ""` 가 사라지면 도구가 살아난다.
            command.add(resolve(String.valueOf(arg), env));
        }
        return new Values(
                List.copyOf(command),
                Long.parseLong(resolve(String.valueOf(reviewer.get("timeout-seconds")), env)),
                resolve(String.valueOf(reviewer.get("prompt-version")), env));
    }

    /**
     * {@code ${NAME:default}} 를 푼다. Spring 이 하는 것 중 이 파일이 쓰는 형태만 다룬다.
     *
     * <p>더 넓게 흉내 내지 않는다. 흉내가 어긋나면 <b>평가가 앱과 다른 설정을 쓰면서도
     * 같다고 믿게 된다</b> - 그럴 바에는 여기서 터지는 편이 낫다.
     */
    private static String resolve(String raw, Function<String, String> env) {
        if (!raw.startsWith("${") || !raw.endsWith("}")) {
            return raw;
        }
        String body = raw.substring(2, raw.length() - 1);
        int colon = body.indexOf(':');
        if (colon < 0) {
            throw new IllegalStateException(
                    "기본값 없는 placeholder 는 다루지 않는다: " + raw);
        }
        String value = env.apply(body.substring(0, colon));
        return value == null || value.isBlank() ? body.substring(colon + 1) : value;
    }
}
