package dev.codesprint.reviewer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 프롬프트를 만들고, 모델에게 묻고, 응답을 계약에 대조한다.
 *
 * <p>여기까지가 이 클래스의 일이다. <b>그 분석을 어디까지 믿을지는 정하지 않는다</b> -
 * 그건 {@link MistakeConfirmation} 과 {@link ReviewerOutputValidator} 의 몫이고,
 * 모델을 바꿔도 그 규칙은 그대로여야 한다(ADR-0014).
 *
 * <p><b>모델 응답을 그대로 믿지 않는다.</b> 스키마를 어긴 응답은 버린다. 고쳐서 쓰지
 * 않는다 - 빠진 필드를 기본값으로 채우면 "모델이 답하지 않은 것" 과 "모델이 그렇게
 * 답한 것" 을 구분할 수 없게 된다.
 */
public class PromptReviewer implements ReviewerPort {

    private static final Logger log = LoggerFactory.getLogger(PromptReviewer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 모델이 코드펜스로 감싸 답하는 경우가 흔하다. 그것만 벗긴다. */
    private static final String FENCE = "```";

    private final LlmClient llm;
    private final PromptTemplate template;
    private final JsonSchema outputSchema;
    private final String schemaText;

    public PromptReviewer(LlmClient llm, PromptTemplate template) {
        this.llm = llm;
        this.template = template;
        this.schemaText = readContract();
        this.outputSchema = JsonSchemaFactory
                .getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(schemaText);
    }

    /**
     * 요청용 계약을 jar 에서 읽는다. <b>프롬프트에 그대로 넣는다</b> - 스키마를 손으로
     * 옮겨 적으면 계약과 프롬프트가 갈라지고, 그때 모델은 프롬프트를 따른다.
     */
    private static String readContract() {
        String path = "contracts/reviewer-output.llm.schema.json";
        try (InputStream in = PromptReviewer.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException(
                        "Reviewer 출력 계약이 jar 에 없다: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Reviewer 출력 계약을 읽지 못했다", e);
        }
    }

    @Override
    public String promptVersion() {
        return template.version();
    }

    @Override
    public Optional<ReviewerOutput> review(Request request) {
        String prompt;
        try {
            prompt = template.render(placeholders(request));
        } catch (IllegalArgumentException e) {
            // 채우지 못한 자리가 있다. 그대로 보내면 모델이 그 문자열을 코드로 읽는다.
            log.error("프롬프트를 만들지 못했다: {}", request.problemCode(), e);
            return Optional.empty();
        }

        String answer;
        try {
            answer = llm.complete(prompt);
        } catch (LlmClient.LlmUnavailable e) {
            log.warn("모델을 부르지 못했다: {}", request.problemCode(), e);
            return Optional.empty();
        }
        return parse(answer);
    }

    private Map<String, String> placeholders(Request request) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("problemCode", request.problemCode());
        values.put("problemTitle", nullSafe(request.problemTitle()));
        values.put("judgeStatus", request.judgeStatus());
        values.put("failedCaseId",
                request.failedCaseId() == null ? "특정하지 못함" : request.failedCaseId().toString());
        values.put("skillCodes", String.join(", ", request.skillCodes()));
        values.put("failedInput", nullSafe(request.failedCaseInput()));
        values.put("failedExpectedOutput", expectedOutputFor(request));
        values.put("stderrSection", stderrSection(request));
        values.put("sourceCode", nullSafe(request.sourceCode()));
        values.put("outputSchema", schemaText);
        return values;
    }

    /**
     * 기대 출력을 프롬프트에 넣을지 정한다.
     *
     * <p><b>정답을 모델 제공자에게 보내는 일이다.</b> 샌드박스에 넣지 않는 것과는 다른
     * 축이지만(ADR-0006 은 사용자 코드로부터의 기밀), 실서비스 문제은행이 붙으면
     * 정답표가 외부로 나간다.
     *
     * <p>지금은 이 저장소에 {@code DEV_FIXTURE} 밖에 없다(ADR-0008). 그래도 조건을
     * 코드에 박아 둔다 - {@code CURATED} 가 들어오는 날 이 자리가 자동으로 닫히고,
     * 그때 무엇을 보낼지 다시 정하게 된다.
     */
    private static String expectedOutputFor(Request request) {
        if (!"DEV_FIXTURE".equals(request.problemSource())) {
            return "(정답은 제공하지 않는다 - 이 문제는 " + request.problemSource() + " 다)";
        }
        return nullSafe(request.failedCaseExpectedOutput());
    }

    /**
     * stderr 가 없으면 그 절을 통째로 뺀다. 빈 코드블록을 남기면 모델이 "오류가
     * 없었다" 가 아니라 "비어 있다" 를 해석하려 든다.
     */
    private static String stderrSection(Request request) {
        String stderr = request.stderr();
        if (stderr == null || stderr.isBlank()) {
            return "";
        }
        return "### 표준 오류\n\n```text\n" + stderr + "\n```\n";
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    /**
     * 응답을 읽고 계약에 대조한다.
     *
     * @return 스키마를 어겼거나 JSON 이 아니면 비어 있다. <b>고쳐 쓰지 않는다.</b>
     */
    private Optional<ReviewerOutput> parse(String answer) {
        String json = stripFence(answer);
        JsonNode node;
        try {
            node = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            log.warn("모델 응답이 JSON 이 아니다: {}", preview(json));
            return Optional.empty();
        }

        var violations = outputSchema.validate(node);
        if (!violations.isEmpty()) {
            // 계약을 어긴 응답이다. 빠진 필드를 기본값으로 채우면 "모델이 답하지
            // 않은 것" 과 "모델이 그렇게 답한 것" 을 구분할 수 없게 된다.
            log.warn("모델 응답이 계약을 어겼다: {}", violations);
            return Optional.empty();
        }

        return Optional.of(new ReviewerOutput(
                node.get("algorithmSelection").asText(),
                node.get("primaryMistake").asText(),
                texts(node.get("secondaryMistakes")),
                node.get("confidence").asDouble(),
                texts(node.get("affectedSkills")),
                integers(node.get("failedCaseRefs")),
                node.get("conceptIssue").asBoolean(),
                node.get("implementationIssue").asBoolean(),
                node.get("explanation").isNull() ? null : node.get("explanation").asText()));
    }

    /** 모델이 ```json 으로 감싸 답하는 경우가 흔하다. 그것만 벗기고 나머지는 그대로 둔다. */
    private static String stripFence(String answer) {
        String trimmed = answer == null ? "" : answer.strip();
        if (!trimmed.startsWith(FENCE)) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        int closing = trimmed.lastIndexOf(FENCE);
        if (firstNewline < 0 || closing <= firstNewline) {
            return trimmed;
        }
        return trimmed.substring(firstNewline + 1, closing).strip();
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    private static List<Integer> integers(JsonNode array) {
        List<Integer> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asInt()));
        return values;
    }

    private static String preview(String value) {
        return value.length() <= 200 ? value : value.substring(0, 200) + "…";
    }
}
