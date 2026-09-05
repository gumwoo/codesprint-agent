package dev.codesprint.reviewer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 로컬에 설치된 Claude CLI 를 프로세스로 부른다.
 *
 * <p><b>왜 HTTP API 가 아닌가.</b> 이 프로젝트는 배포하지 않는다 - 만든 사람이 자기
 * PC 에서 돌리는 포트폴리오다. Platform API 는 구독과 별개로 과금되지만 CLI 는 이미
 * 로그인된 세션을 쓴다. 쓰지 않을 배포 경로를 위해 API 클라이언트를 함께 들고 있으면,
 * 한 번도 실행되지 않는 코드가 저장소에 남는다.
 *
 * <p>필요해지면 {@link LlmClient} 구현을 하나 더 만들면 된다. 이 인터페이스가
 * {@code complete(String) -> String} 하나뿐인 이유가 그것이다 - Reviewer 도
 * 확정 규칙도 Decision 도 전송 방식을 모른다.
 *
 * <p><b>프롬프트를 명령행 인자로 넘기지 않는다.</b> 사용자가 낸 코드가 들어 있어 길고,
 * 무엇보다 프로세스 목록에 그대로 보인다. stdin 으로 보낸다.
 */
public class ClaudeCliLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCliLlmClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<String> command;
    private final Duration timeout;

    /**
     * @param command 실행할 명령. 기본은 {@code claude -p --output-format json} 이며,
     *     프롬프트는 stdin 으로 간다.
     */
    public ClaudeCliLlmClient(List<String> command, Duration timeout) {
        this.command = List.copyOf(command);
        this.timeout = timeout;
    }

    @Override
    public String complete(String prompt) {
        Path workdir = null;
        try {
            workdir = Files.createTempDirectory("codesprint-reviewer-");
            Path out = workdir.resolve("stdout");
            Path err = workdir.resolve("stderr");

            // 출력을 파일로 받는다. 파이프에서 읽으면서 기다리면 버퍼가 차서 교착이
            // 되고, 먼저 다 읽으면 그 호출이 막혀 timeout 이 아무 일도 하지 않는다.
            // ProcessJudgeClient 에서 같은 실수를 한 적이 있다.
            Process process = new ProcessBuilder(command)
                    .redirectOutput(out.toFile())
                    .redirectError(err.toFile())
                    .start();

            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(prompt.getBytes(StandardCharsets.UTF_8));
            }

            if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new LlmUnavailable(
                        "Claude CLI 가 " + timeout.toSeconds() + "초 안에 끝나지 않았다", null);
            }
            if (process.exitValue() != 0) {
                // stderr 를 통째로 남기지 않는다. CLI 가 프롬프트를 되돌려 찍는 경우가
                // 있고, 거기에는 사용자가 낸 코드가 들어 있다.
                throw new LlmUnavailable(
                        "Claude CLI 가 실패했다 (exit=" + process.exitValue() + "): "
                                + preview(Files.readString(err, StandardCharsets.UTF_8)), null);
            }
            return unwrap(Files.readString(out, StandardCharsets.UTF_8));

        } catch (IOException e) {
            throw new LlmUnavailable("Claude CLI 를 부르지 못했다", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmUnavailable("Claude CLI 호출이 중단됐다", e);
        } finally {
            deleteQuietly(workdir);
        }
    }

    /**
     * CLI 가 감싸 준 봉투를 벗긴다.
     *
     * <p>{@code --output-format json} 이면 실행 메타데이터와 함께 {@code result} 필드에
     * 모델 텍스트가 온다. 그 형식이 아니면 출력을 그대로 돌려준다 - 여기서 JSON 인지
     * 판단하지 않는다. 모델이 무엇을 답했는지는 {@link PromptReviewer} 가 계약으로 본다.
     */
    private static String unwrap(String stdout) {
        String trimmed = stdout.strip();
        if (!trimmed.startsWith("{")) {
            return trimmed;
        }
        try {
            JsonNode node = MAPPER.readTree(trimmed);
            JsonNode result = node.get("result");
            return result == null || result.isNull() ? trimmed : result.asText();
        } catch (IOException e) {
            // 봉투가 아니라 모델이 바로 JSON 을 낸 경우다. 그대로 넘긴다.
            return trimmed;
        }
    }

    private static String preview(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.strip();
        return trimmed.length() <= 300 ? trimmed : trimmed.substring(0, 300) + "…";
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("임시 파일을 지우지 못했다: {}", path);
                }
            });
        } catch (IOException e) {
            log.warn("임시 디렉터리를 지우지 못했다: {}", dir);
        }
    }
}
