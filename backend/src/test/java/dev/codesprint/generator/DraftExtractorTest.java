package dev.codesprint.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DraftExtractorTest {

    @Test
    @DisplayName("코드 펜스와 앞뒤 문장이 붙어도 JSON 객체를 꺼낸다")
    void extractsTheObjectFromNoise() {
        String response = "초안입니다.\n```json\n{\"title\": \"합\", \"hints\": [\"a\"]}\n```\n끝.";
        assertThat(DraftExtractor.extract(response).get("title").asText()).isEqualTo("합");
    }

    @Test
    @DisplayName("비어 있으면 거절한다")
    void rejectsEmpty() {
        assertThatThrownBy(() -> DraftExtractor.extract("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("비어 있다");
    }

    @Test
    @DisplayName("JSON 객체가 없으면 거절한다")
    void rejectsNoObject() {
        assertThatThrownBy(() -> DraftExtractor.extract("[1, 2, 3]"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("깨진 JSON 을 추측해서 살리지 않는다")
    void doesNotRepairBrokenJson() {
        // 고쳐 읽으면 모델이 말하지 않은 초안이 만들어진다.
        assertThatThrownBy(() -> DraftExtractor.extract("{\"title\": \"합\", }"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON 이 아니다");
    }
}
