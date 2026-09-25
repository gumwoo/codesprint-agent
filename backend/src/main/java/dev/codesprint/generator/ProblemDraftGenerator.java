package dev.codesprint.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import dev.codesprint.reviewer.ClaudeCliLlmClient;
import dev.codesprint.reviewer.LlmClient;
import dev.codesprint.reviewer.PromptTemplate;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Skill 하나에 대한 문제 초안을 LLM 에게 받아 {@code generated/drafts/} 에 쓴다. 정본: ADR-0032.
 *
 * <pre>
 *   scripts/local.sh generate PYTHON_LIST_BASIC 2
 * </pre>
 *
 * <p><b>여기서 문제를 만들지 않는다.</b> 초안을 받아 계약에 맞는지만 보고 남긴다. 문제가
 * 되는지는 {@code tools/adopt_problem.py} 가 실제 채점으로 정한다 - 언어 경계대로
 * 생성(LLM 호출)은 Java 가, 샌드박스 검증은 Python 이 한다(ADR-0011).
 *
 * <p>계약을 어긴 응답도 버리지 않고 {@code generated/rejected/} 에 사유와 함께 남긴다.
 * 어떤 프롬프트가 어떤 이유로 실패하는지 재는 근거가 된다.
 *
 * <p>모델 호출은 CI 에 넣지 않는다 - 느리고 비결정적이다(ADR-0016 과 같은 이유).
 */
public final class ProblemDraftGenerator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProblemDraftGenerator() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("사용법: ProblemDraftGenerator <SKILL_CODE> [개수]");
            System.exit(2);
        }
        String skillCode = args[0];
        int count = args.length > 1 ? Integer.parseInt(args[1]) : 1;
        String root = System.getProperty("codesprint.repoRoot");
        if (root == null) {
            System.err.println("codesprint.repoRoot 시스템 프로퍼티가 없다");
            System.exit(2);
        }
        Path repoRoot = Path.of(root);

        GeneratorSettings.Values settings = GeneratorSettings.load();
        LlmClient client = new ClaudeCliLlmClient(
                settings.command(), Duration.ofSeconds(settings.timeoutSeconds()));
        PromptTemplate template = PromptTemplate.load(settings.promptVersion());
        Map<String, String> values = DraftPrompt.values(repoRoot, skillCode);
        // 이번 실행에서 만든 초안도 "이미 있는 문제" 로 보여 준다. 같은 프롬프트로 둘을 만들면
        // 같은 문제가 나온다 - CORE-1 · CORE-2 에서 채택된 문제 중 여덟이 앞 초안과 같은
        // 문제였다(ADR-0036). 초안은 아직 문제가 아니지만 겹치지 말라는 뜻은 같다.
        StringBuilder madeNow = new StringBuilder();
        JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(values.get("draftSchema"));

        int written = 0;
        for (int i = 1; i <= count; i++) {
            String id = skillCode + "-" + Instant.now().toEpochMilli() + "-" + i;
            ObjectNode envelope = MAPPER.createObjectNode();
            envelope.put("id", id);
            envelope.put("skill", skillCode);
            envelope.put("promptVersion", template.version());
            envelope.put("generatedAt", Instant.now().toString());

            String prompt = template.render(DraftPrompt.withDrafts(values, madeNow.toString()));
            String response;
            try {
                response = client.complete(prompt);
            } catch (RuntimeException e) {
                System.out.println("[X] " + id + ": 모델을 부르지 못했다 - " + e.getMessage());
                continue;
            }

            List<String> reasons = List.of();
            JsonNode draft = null;
            try {
                draft = DraftExtractor.extract(response);
                Set<ValidationMessage> errors = schema.validate(draft);
                reasons = errors.stream().map(ValidationMessage::getMessage).sorted().toList();
            } catch (IllegalArgumentException e) {
                reasons = List.of(e.getMessage());
            }

            if (draft != null && reasons.isEmpty()) {
                envelope.set("draft", draft);
                madeNow.append(DraftPrompt.summary(id, draft.path("title").asText(),
                        draft.path("statement").asText()));
                Path out = write(repoRoot.resolve("generated/drafts"), id, envelope);
                System.out.println("[O] 초안 " + repoRoot.relativize(out));
                written++;
            } else {
                envelope.put("stage", "계약");
                envelope.set("reasons", MAPPER.valueToTree(reasons));
                envelope.put("raw", response);
                Path out = write(repoRoot.resolve("generated/rejected"), id, envelope);
                System.out.println("[X] 계약 위반 " + repoRoot.relativize(out) + " - "
                        + String.join(" / ", reasons.subList(0, Math.min(reasons.size(), 3))));
            }
        }
        System.out.println(written + " / " + count + " 초안이 계약을 지켰다 (" + template.version() + ")");
    }

    private static Path write(Path dir, String id, JsonNode envelope) throws IOException {
        Files.createDirectories(dir);
        Path out = dir.resolve(id + ".json");
        Files.writeString(out, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(envelope));
        return out;
    }
}
