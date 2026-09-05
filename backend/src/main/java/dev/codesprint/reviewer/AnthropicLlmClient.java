package dev.codesprint.reviewer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Anthropic Messages API 로 한 번 묻는다.
 *
 * <p><b>이 클래스는 전송만 한다.</b> 프롬프트도 해석도 판단도 여기 없다 - 모델을
 * 바꿀 때 갈아끼울 지점을 이 하나로 좁혀 둔다.
 *
 * <p><b>API 키를 로그에 남기지 않는다.</b> 실패 로그에 요청을 통째로 찍으면 헤더가
 * 함께 간다. 여기서는 상태 코드와 본문 앞부분만 남긴다.
 */
public class AnthropicLlmClient implements LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";

    private final HttpClient http;
    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final Duration timeout;

    public AnthropicLlmClient(String apiKey, String model, int maxTokens, Duration timeout) {
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public String complete(String prompt) {
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(ENDPOINT))
                            .header("content-type", "application/json")
                            .header("x-api-key", apiKey)
                            .header("anthropic-version", API_VERSION)
                            .timeout(timeout)
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    body(prompt), StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() / 100 != 2) {
                // 본문을 통째로 남기지 않는다. 프롬프트가 되돌아오는 응답이 있고,
                // 거기에는 사용자가 낸 코드가 들어 있다.
                throw new LlmUnavailable(
                        "모델이 " + response.statusCode() + " 를 냈다: "
                                + preview(response.body()), null);
            }
            return textOf(response.body());
        } catch (IOException e) {
            throw new LlmUnavailable("모델을 부르지 못했다", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmUnavailable("모델 호출이 중단됐다", e);
        }
    }

    private String body(String prompt) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", maxTokens);

        ArrayNode messages = root.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        message.put("content", prompt);
        return root.toString();
    }

    /** 응답에서 텍스트만 꺼낸다. 모양이 다르면 부르지 못한 것과 같이 다룬다. */
    private static String textOf(String body) {
        try {
            JsonNode content = MAPPER.readTree(body).path("content");
            if (!content.isArray() || content.isEmpty()) {
                throw new LlmUnavailable("모델 응답에 content 가 없다: " + preview(body), null);
            }
            StringBuilder text = new StringBuilder();
            content.forEach(block -> {
                if ("text".equals(block.path("type").asText())) {
                    text.append(block.path("text").asText());
                }
            });
            if (text.isEmpty()) {
                throw new LlmUnavailable("모델 응답에 텍스트가 없다: " + preview(body), null);
            }
            return text.toString();
        } catch (IOException e) {
            throw new LlmUnavailable("모델 응답을 읽지 못했다", e);
        }
    }

    private static String preview(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 300 ? value : value.substring(0, 300) + "…";
    }
}
