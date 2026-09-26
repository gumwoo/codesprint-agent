package dev.codesprint.api;

import dev.codesprint.learning.service.RunService;
import dev.codesprint.learning.service.SubmissionIntakeService;
import dev.codesprint.mocktest.MockTestService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
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
 * 모의 시험. 정본: PRD §84~86, ADR-0043. 계약: contracts/mock-test*.schema.json.
 *
 * <p>시험 중에는 문제를 <b>라벨로만</b> 부른다 - code · 제목 · Skill 은 유형을 알려 주므로
 * 끝난 뒤 보고서에서만 보인다. 힌트 · Reviewer 분석 · 다음 행동도 끝난 뒤다.
 *
 * <p>인증이 없어 {@code userId} 를 받는다(ADR-0017). 남의 시험은 404 다.
 */
@RestController
@RequestMapping("/api")
public class MockTestController {

    private final MockTestService mockTests;

    public MockTestController(MockTestService mockTests) {
        this.mockTests = mockTests;
    }

    public record UserRequest(@NotNull Long userId) {
    }

    public record CodeRequest(@NotNull Long userId, @NotBlank String language,
            @NotBlank String sourceCode) {
    }

    public record SubmitAccepted(long submissionId) {
    }

    public record RunAccepted(long runId) {
    }

    /** 실행 결과. 일반 실행 조회와 달리 문제 code 가 없다. */
    public record RunView(long runId, String status, com.fasterxml.jackson.databind.JsonNode judged,
            String failureReason) {
    }

    /** 시험을 만든다. 진행 중인 시험이 있거나 고를 문제가 모자라면 409. */
    @PostMapping("/users/{userId}/mock-tests")
    public ResponseEntity<MockTestService.Overview> create(@PathVariable long userId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(mockTests.create(userId));
    }

    /** 가장 최근의 시험. 한 번도 없으면 404. */
    @GetMapping("/users/{userId}/mock-tests/latest")
    public ResponseEntity<MockTestService.Overview> latest(@PathVariable long userId) {
        return mockTests.latest(userId).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/mock-tests/{mockTestId}")
    public MockTestService.Overview overview(@PathVariable long mockTestId,
            @RequestParam long userId) {
        return mockTests.overview(mockTestId, userId);
    }

    /** 문제를 연다. <b>POST 다</b> - 연 시각이 기록된다(ADR-0043). */
    @PostMapping("/mock-tests/{mockTestId}/problems/{label}/open")
    public MockTestService.ProblemSheet open(@PathVariable long mockTestId,
            @PathVariable String label, @Valid @RequestBody UserRequest request) {
        return mockTests.open(mockTestId, label, request.userId());
    }

    @PostMapping("/mock-tests/{mockTestId}/problems/{label}/run")
    public ResponseEntity<RunAccepted> run(@PathVariable long mockTestId,
            @PathVariable String label, @Valid @RequestBody CodeRequest request) {
        return ResponseEntity.accepted().body(new RunAccepted(mockTests.run(mockTestId, label,
                request.userId(), request.language(), request.sourceCode())));
    }

    @GetMapping("/mock-tests/{mockTestId}/runs/{runId}")
    public RunView runResult(@PathVariable long mockTestId, @PathVariable long runId,
            @RequestParam long userId) {
        RunService.Result result = mockTests.runResult(mockTestId, runId, userId);
        return new RunView(result.runId(), result.status(), result.judged(),
                result.failureReason());
    }

    @PostMapping("/mock-tests/{mockTestId}/problems/{label}/submit")
    public ResponseEntity<SubmitAccepted> submit(@PathVariable long mockTestId,
            @PathVariable String label, @Valid @RequestBody CodeRequest request) {
        return ResponseEntity.accepted().body(new SubmitAccepted(mockTests.submit(mockTestId,
                label, request.userId(), request.language(), request.sourceCode())));
    }

    /** 판정만 준다. 분석과 다음 행동은 보고서 뒤다. */
    @GetMapping("/mock-tests/{mockTestId}/submissions/{submissionId}")
    public MockTestService.Verdict verdict(@PathVariable long mockTestId,
            @PathVariable long submissionId, @RequestParam long userId) {
        return mockTests.verdict(mockTestId, submissionId, userId);
    }

    @PostMapping("/mock-tests/{mockTestId}/finish")
    public MockTestService.Overview finish(@PathVariable long mockTestId,
            @Valid @RequestBody UserRequest request) {
        return mockTests.finish(mockTestId, request.userId());
    }

    /** 끝나기 전에는 409 다. */
    @GetMapping("/mock-tests/{mockTestId}/report")
    public MockTestService.Report report(@PathVariable long mockTestId,
            @RequestParam long userId) {
        return mockTests.report(mockTestId, userId);
    }

    @ExceptionHandler(MockTestService.NotFound.class)
    public ResponseEntity<Map<String, String>> notFound(MockTestService.NotFound e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(MockTestService.Conflict.class)
    public ResponseEntity<Map<String, String>> conflict(MockTestService.Conflict e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler({SubmissionIntakeService.UnsupportedLanguage.class,
            RunService.UnsupportedLanguage.class})
    public ResponseEntity<Map<String, String>> unsupported(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }
}
