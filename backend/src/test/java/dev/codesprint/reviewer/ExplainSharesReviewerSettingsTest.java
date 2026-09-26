package dev.codesprint.reviewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Explain Back 이 Reviewer 와 같은 경계 위에 있는가. 정본: ADR-0050.
 *
 * <p>Explain Back 프롬프트에도 저장소 데이터(개념 자료)와 사용자 입력(설명)이 들어간다. 그래서 모델은 도구를
 * 가지면 안 되고, 명령이 Reviewer 와 갈리면 한쪽만 도구 권한이 열린 채로 남는다.
 */
class ExplainSharesReviewerSettingsTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(String name) throws Exception {
        try (InputStream in = ExplainSharesReviewerSettingsTest.class.getClassLoader()
                .getResourceAsStream("application.yml")) {
            Map<String, Object> root = new Yaml().load(in);
            return (Map<String, Object>) ((Map<String, Object>) root.get("codesprint")).get(name);
        }
    }

    @Test
    @DisplayName("Explain Back 은 Reviewer 와 같은 명령을 쓰고, 기본은 꺼져 있다")
    void explainUsesTheSameCommand() throws Exception {
        List<Object> explain = (List<Object>) section("explain").get("command");
        assertThat(explain).isEqualTo(section("reviewer").get("command")).contains("--tools", "");
        assertThat(String.valueOf(section("explain").get("enabled")))
                .isEqualTo("${CODESPRINT_EXPLAIN_ENABLED:false}");
    }

    @Test
    @DisplayName("Explain Back 프롬프트의 자리를 코드가 전부 채운다")
    void everyPlaceholderIsFilled() {
        Map<String, String> values = new HashMap<>();
        for (String name : new String[] {"problemTitle", "skillCode", "skillName", "conceptSummary",
                "conceptPoints", "question", "explanation", "outputSchema"}) {
            values.put(name, "x");
        }
        assertThatCode(() -> PromptTemplate.load("explain-v1").render(values))
                .doesNotThrowAnyException();
    }
}
