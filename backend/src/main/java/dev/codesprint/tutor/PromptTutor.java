package dev.codesprint.tutor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import dev.codesprint.reviewer.LlmClient;
import dev.codesprint.reviewer.PromptTemplate;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 프롬프트로 모델에게 묻는 Tutor. 정본: ADR-0044.
 *
 * <p>Reviewer 와 같은 규칙을 따른다 - 요청 계약을 프롬프트에 그대로 넣고, 답이 계약을 어기면
 * 고쳐 쓰지 않고 버린다.
 */
public class PromptTutor implements TutorPort {

    private static final Logger log = LoggerFactory.getLogger(PromptTutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FENCE = "```";
    private static final String CONTRACT = "contracts/tutor-answer.llm.schema.json";

    private final LlmClient llm;
    private final PromptTemplate template;
    private final String version;
    private final String schemaText;
    private final JsonSchema outputSchema;

    public PromptTutor(LlmClient llm, PromptTemplate template, String version) {
        this.llm = llm;
        this.template = template;
        this.version = version;
        this.schemaText = readContract();
        this.outputSchema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(schemaText);
    }

    private static String readContract() {
        try (InputStream in = PromptTutor.class.getClassLoader().getResourceAsStream(CONTRACT)) {
            if (in == null) {
                throw new IllegalStateException("Tutor 출력 계약이 jar 에 없다: " + CONTRACT);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Tutor 출력 계약을 읽지 못했다", e);
        }
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public String promptVersion() {
        return version;
    }

    @Override
    public Optional<Answer> answer(Request request) {
        Map<String, String> values = new HashMap<>();
        values.put("skillCode", request.skillCode());
        values.put("skillName", request.skillName());
        values.put("conceptSummary", request.conceptSummary());
        values.put("conceptPoints", String.join("\n",
                request.conceptPoints().stream().map(point -> "- " + point).toList()));
        values.put("question", request.question());
        values.put("outputSchema", schemaText);

        String reply;
        try {
            reply = llm.complete(template.render(values));
        } catch (RuntimeException e) {
            log.warn("Tutor 모델을 부르지 못했다: {}", e.getMessage());
            return Optional.empty();
        }
        return parse(reply);
    }

    private Optional<Answer> parse(String reply) {
        String json = stripFence(reply);
        JsonNode node;
        try {
            node = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            log.warn("Tutor 응답이 JSON 이 아니다");
            return Optional.empty();
        }
        var violations = outputSchema.validate(node);
        if (!violations.isEmpty()) {
            // 계약을 어긴 응답이다. 빠진 필드를 기본값으로 채우면 "모델이 답하지 않은 것" 과
            // "모델이 그렇게 답한 것" 을 구분할 수 없게 된다.
            log.warn("Tutor 응답이 계약을 어겼다: {}", violations);
            return Optional.empty();
        }
        return Optional.of(new Answer(node.get("answer").asText(),
                node.get("followUpQuestion").isNull() ? null
                        : node.get("followUpQuestion").asText()));
    }

    /** 모델이 ```json 으로 감싸 답하는 경우가 흔하다. 그것만 벗긴다. */
    private static String stripFence(String reply) {
        String trimmed = reply == null ? "" : reply.strip();
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
}
