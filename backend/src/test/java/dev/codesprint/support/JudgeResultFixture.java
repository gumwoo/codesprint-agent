package dev.codesprint.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 테스트가 Worker 대신 채점 결과를 큐에 쓴다. <b>쓰기 전에 계약에 대고 검증한다.</b>
 *
 * <p>테스트는 Worker 를 흉내 내려고 {@code judge_jobs.result} 를 손으로 적는다. 그 모양이
 * {@code contracts/judge-result.schema.json} 과 갈리면 반영 단계가 파싱에 실패해
 * {@code SYSTEM_ERROR} 로 처리되고, 테스트는 엉뚱한 답을 본다.
 *
 * <p>실제로 겪었다(PR #40). {@code cases} 의 필드를 {@code caseId} 로 적고
 * {@code executionMs} 를 빠뜨려서, 두 테스트가 판정 대신 {@code CONTINUE} 를 받았다 -
 * 무엇이 틀렸는지는 말해 주지 않았다. E2E stub 에는 같은 문을 이미 붙였고(PR #34),
 * 이것이 Java 쪽의 같은 문이다.
 */
public final class JudgeResultFixture {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SUBMISSION = "judge-result.schema.json";
    /**
     * 제출 전 실행(kind RUN)의 결과. case 에 입력 · 기대 출력 · 실제 출력이 실린다.
     * 제출 채점 결과와 계약을 나눈 이유는 run-judge-result.schema.json 에 적었다.
     */
    private static final String RUN = "run-judge-result.schema.json";
    private static final java.util.Map<String, JsonSchema> SCHEMAS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private JudgeResultFixture() {
    }

    /** Worker 가 제출 채점을 끝낸 것처럼 쓴다. 계약을 어기면 쓰기 전에 실패한다. */
    public static void finish(JdbcTemplate jdbc, String resultJson, long jobId) {
        requireValid(resultJson);
        write(jdbc, resultJson, jobId);
    }

    /** Worker 가 제출 전 실행을 끝낸 것처럼 쓴다. run 계약에 대고 검증한다. */
    public static void finishRun(JdbcTemplate jdbc, String resultJson, long jobId) {
        requireValid(RUN, resultJson);
        write(jdbc, resultJson, jobId);
    }

    private static void write(JdbcTemplate jdbc, String resultJson, long jobId) {
        jdbc.update("UPDATE judge_jobs SET status = 'DONE', result = ?::jsonb,"
                + " lease_expires_at = NULL WHERE id = ?", resultJson, jobId);
    }

    /** 제출 채점 결과 계약에 맞지 않으면 무엇이 틀렸는지 말하며 실패한다. */
    public static void requireValid(String resultJson) {
        requireValid(SUBMISSION, resultJson);
    }

    /** 제출 전 실행 결과 계약에 맞지 않으면 실패한다. */
    public static void requireValidRun(String resultJson) {
        requireValid(RUN, resultJson);
    }

    private static void requireValid(String contract, String resultJson) {
        JsonNode node;
        try {
            node = MAPPER.readTree(resultJson);
        } catch (IOException e) {
            throw new AssertionError("채점 결과 fixture 가 JSON 이 아니다: " + e.getMessage());
        }
        Set<ValidationMessage> errors = schema(contract).validate(node);
        if (!errors.isEmpty()) {
            throw new AssertionError("채점 결과 fixture 가 " + contract + " 을 어긴다: "
                    + errors + "\n  fixture: " + resultJson.strip());
        }
    }

    private static JsonSchema schema(String contract) {
        return SCHEMAS.computeIfAbsent(contract, name -> {
            String root = System.getProperty("codesprint.repoRoot");
            if (root == null) {
                throw new IllegalStateException("codesprint.repoRoot 시스템 프로퍼티가 없다.");
            }
            try {
                return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                        .getSchema(Files.readString(Path.of(root, "contracts", name)));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }
}
