package dev.codesprint.reviewer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 프롬프트 파일 하나. <b>파일 이름이 곧 버전이다</b>(PRD §135).
 *
 * <p>내용을 고칠 때는 새 파일을 만든다. 같은 이름으로 내용을 바꾸면 이전에 쌓인
 * 라벨과 이후 라벨이 섞여 Reviewer 정확도를 잴 수 없게 된다 - 그때 가서
 * 되돌릴 방법이 없다.
 *
 * <p>복사본을 두지 않는다. 저장소의 {@code reviewer/prompts/} 를 빌드 시점에
 * 가져온다(ADR-0012 와 같은 이유). 정본이 둘이면 {@code promptVersion} 이 가리키는
 * 내용이 무엇인지 알 수 없다.
 */
public final class PromptTemplate {

    /** {@code {{name}}} 자리. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-zA-Z][a-zA-Z0-9]*)}}");

    private final String version;
    private final String body;

    private PromptTemplate(String version, String body) {
        this.version = version;
        this.body = body;
    }

    /**
     * @param version 파일 이름에서 확장자를 뺀 것. 그대로 {@code promptVersion} 이 된다.
     */
    public static PromptTemplate load(String version) {
        String path = "prompts/" + version + ".md";
        try (InputStream in = PromptTemplate.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                // 프롬프트 없이 뜨면 Reviewer 가 빈 문자열을 모델에 보낸다.
                throw new IllegalStateException(
                        "프롬프트가 jar 에 없다: " + path
                                + ". build.gradle.kts 의 processResources 를 확인한다.");
            }
            return new PromptTemplate(version,
                    new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("프롬프트를 읽지 못했다: " + path, e);
        }
    }

    public String version() {
        return version;
    }

    /**
     * 자리를 채운다.
     *
     * <p><b>채우지 못한 자리가 남으면 거부한다.</b> 그대로 두면 {@code {{sourceCode}}}
     * 같은 문자열이 모델에게 가고, 모델은 그것을 코드로 읽으려 한다. 조용히 빈
     * 문자열로 바꾸는 것도 안 된다 - 근거가 없는 채로 분석이 나온다.
     */
    public String render(Map<String, String> values) {
        Matcher matcher = PLACEHOLDER.matcher(body);
        StringBuilder rendered = new StringBuilder();
        List<String> missing = new ArrayList<>();

        while (matcher.find()) {
            String name = matcher.group(1);
            String value = values.get(name);
            if (value == null) {
                missing.add(name);
                value = "";
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(rendered);

        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "프롬프트에 채우지 못한 자리가 있다: " + missing);
        }
        return rendered.toString();
    }
}
