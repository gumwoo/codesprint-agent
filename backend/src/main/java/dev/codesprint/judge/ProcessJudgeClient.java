package dev.codesprint.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codesprint.learning.domain.JudgeStatus;
import dev.codesprint.problem.ProblemCatalog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@code judge/run_submission.py} 를 프로세스로 부른다.
 *
 * <p><b>임시 구현이다.</b> 정상 구조는 큐를 통한 Judge Worker 이며(Addendum §47),
 * 채점은 API 요청 스레드에서 일어나면 안 된다 - 컨테이너 기동에 초 단위가 걸리고
 * 그동안 요청 스레드가 묶인다. 슬라이스 1 의 데모 흐름을 끝까지 잇기 위해 먼저
 * 동기로 붙였고, 큐가 붙으면 이 클래스만 교체한다.
 *
 * <p>제출 코드를 <b>읽지 않는다.</b> 파일로 써서 그대로 넘긴다 - 신뢰 경계 바깥이다.
 */
@Component
public class ProcessJudgeClient implements JudgePort {

    private static final Logger log = LoggerFactory.getLogger(ProcessJudgeClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProblemCatalog problems;
    private final Path repoRoot;
    private final String python;
    private final long timeoutSeconds;

    public ProcessJudgeClient(
            ProblemCatalog problems,
            @Value("${codesprint.repo-root}") String repoRoot,
            @Value("${codesprint.judge.python:python}") String python,
            @Value("${codesprint.judge.timeout-seconds:120}") long timeoutSeconds) {
        this.problems = problems;
        this.repoRoot = Path.of(repoRoot);
        this.python = python;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public Result judge(String problemCode, String sourceCode) {
        Path workdir = null;
        try {
            workdir = Files.createTempDirectory("codesprint-submit-");
            Path solution = workdir.resolve("solution.py");
            Files.writeString(solution, sourceCode, StandardCharsets.UTF_8);

            // 출력을 파일로 받는다. 파이프에서 읽으면서 기다리면 두 가지가 겹친다 -
            // 버퍼가 차면 상대가 막혀 교착이 되고, readAllBytes 로 먼저 다 읽으면
            // 그 호출 자체가 무한정 막혀 아래 timeout 이 아무 일도 하지 않는다.
            Path stdoutFile = workdir.resolve("judge-stdout.json");
            Path stderrFile = workdir.resolve("judge-stderr.txt");
            Process process = new ProcessBuilder(List.of(
                    python,
                    repoRoot.resolve("judge").resolve("run_submission.py").toString(),
                    solution.toString(),
                    problems.casesFile(problemCode).toString()))
                    .directory(repoRoot.toFile())
                    .redirectOutput(stdoutFile.toFile())
                    .redirectError(stderrFile.toFile())
                    .start();

            // 하네스 자체가 멈춘 경우의 마지막 방어선. run_submission.py 안에도
            // watchdog 이 있지만, 그 프로세스가 통째로 굳으면 여기서 끊어야 한다.
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.error("채점 하네스가 {}초 안에 끝나지 않았다: {}", timeoutSeconds, problemCode);
                return systemError();
            }
            if (process.exitValue() != 0) {
                log.error("채점 하네스가 실패했다 (exit={}): {}", process.exitValue(),
                        Files.readString(stderrFile, StandardCharsets.UTF_8));
                return systemError();
            }
            return parse(Files.readString(stdoutFile, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.error("채점 하네스를 부르지 못했다", e);
            return systemError();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return systemError();
        } finally {
            deleteQuietly(workdir);
        }
    }

    private static Result parse(String stdout) {
        try {
            JsonNode node = MAPPER.readTree(stdout);
            return new Result(
                    JudgeStatus.valueOf(node.get("status").asText()),
                    node.get("passed").asInt(),
                    node.get("total").asInt(),
                    integer(node, "executionMs"),
                    integer(node, "memoryKb"),
                    integer(node, "failedCaseId"),
                    text(node, "stderr"));
        } catch (IOException | IllegalArgumentException | NullPointerException e) {
            // 알 수 없는 status 를 그대로 통과시키지 않는다. 그러면 Evidence 매핑이
            // 관측값 없는 Evidence 를 만들어 confidence 만 올린다.
            log.error("채점 결과를 읽지 못했다: {}", stdout, e);
            return systemError();
        }
    }

    /**
     * 채점 실패는 <b>우리 잘못이다.</b> 사용자 점수를 건드리지 않는 판정으로 돌려준다 -
     * SYSTEM_ERROR 는 Evidence 를 만들지 않는다(Addendum §12).
     */
    private static Result systemError() {
        return new Result(JudgeStatus.SYSTEM_ERROR, 0, 1, null, null, null, null);
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asInt();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
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
