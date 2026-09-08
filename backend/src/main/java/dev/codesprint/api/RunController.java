package dev.codesprint.api;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codesprint.learning.service.RunService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 제출 전 실행. 계약: contracts/run-result.schema.json. 정본: PRD §117, ADR-0020.
 *
 * <p><b>이것은 제출이 아니다.</b> Evidence 도 mastery 도 다음 행동도 만들지 않는다.
 * 공개 case 만 돌고, 결과는 그 자리에서 보고 버린다.
 *
 * <p>그래서 응답에 {@code nextAction} 이 없다. 실행에 다음 행동을 붙이면 사용자는
 * 돌려 보기만 해도 학습 경로가 움직이는 것으로 읽는다.
 */
@RestController
@RequestMapping("/api")
public class RunController {

    private final RunService runs;

    public RunController(RunService runs) {
        this.runs = runs;
    }

    public record RunRequest(Long userId, String language, String sourceCode) {
    }

    public record AcceptedResponse(long runId) {
    }

    /**
     * @param judged 아직 안 끝났으면 null 이다. <b>생략하지 않는다</b> - 생략은
     *     "모른다", null 은 "아직 없다".
     */
    public record RunResponse(long runId, String problemCode, String status, JsonNode judged,
            String failureReason) {
    }

    /** 접수하고 큐에 넣는다. 채점과 같은 Worker 가 가져간다(ADR-0013). */
    @PostMapping("/problems/{code}/run")
    public ResponseEntity<AcceptedResponse> run(@PathVariable String code,
            @RequestBody RunRequest request) {

        long runId = runs.accept(new RunService.Request(
                request.userId(), code, request.language(), request.sourceCode()));
        return ResponseEntity.accepted().body(new AcceptedResponse(runId));
    }

    /**
     * 결과를 확인한다.
     *
     * <p>{@code userId} 를 받는다. 인증이 없어서 화면이 직접 보내는데(ADR-0017),
     * 그렇더라도 <b>남의 실행 결과가 보이면 안 된다</b> - 거기에는 그 사람이 쓴
     * 코드의 출력이 담긴다. 인증이 붙으면 이 파라미터는 사라진다.
     */
    @GetMapping("/runs/{runId}")
    public ResponseEntity<RunResponse> result(@PathVariable long runId,
            @RequestParam long userId) {

        return runs.find(runId, userId)
                .map(result -> ResponseEntity.ok(new RunResponse(result.runId(),
                        result.problemCode(), result.status(), result.judged(),
                        result.failureReason())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @ExceptionHandler(RunService.NotFound.class)
    ResponseEntity<String> notFound(RunService.NotFound e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
    }

    @ExceptionHandler(RunService.UnsupportedLanguage.class)
    ResponseEntity<String> unsupported(RunService.UnsupportedLanguage e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }
}
