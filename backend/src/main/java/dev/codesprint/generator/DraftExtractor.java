package dev.codesprint.generator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 모델 응답에서 초안 JSON 객체를 꺼낸다.
 *
 * <p>JSON 만 답하라고 해도 모델은 코드 펜스나 앞뒤 문장을 붙이곤 한다. 첫 {@code {}
 * 부터 마지막 {@code }} 까지를 JSON 으로 읽는다. <b>고쳐 읽지 않는다</b> - 깨진 JSON 을
 * 추측해서 살리면 모델이 말하지 않은 초안이 만들어진다. 못 읽으면 거절이다.
 */
public final class DraftExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DraftExtractor() {
    }

    /** @throws IllegalArgumentException 읽을 수 있는 JSON 객체가 없을 때. 이유가 거절 사유가 된다. */
    public static JsonNode extract(String response) {
        if (response == null || response.isBlank()) {
            throw new IllegalArgumentException("모델 응답이 비어 있다");
        }
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("응답에서 JSON 객체를 찾지 못했다");
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(response.substring(start, end + 1));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("응답이 JSON 이 아니다: " + e.getOriginalMessage());
        }
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("응답이 JSON 객체가 아니다");
        }
        return node;
    }
}
