package dev.codesprint.api;

import dev.codesprint.learning.service.SubmissionIntakeService;
import dev.codesprint.learning.service.SubmissionQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 제출 API. 계약: contracts/submission-status.schema.json + submit-response.schema.json.
 *
 * <p><b>채점은 요청 안에서 일어나지 않는다</b>(ADR-0013). 제출은 큐에 들어가고 즉시
 * {@code 202} 로 끝나며, 결과는 {@code GET /api/submissions/{id}} 로 확인한다.
 * 두 엔드포인트가 같은 모양을 돌려주는 이유는 클라이언트가 "접수 응답" 과 "조회 응답"
 * 을 다르게 다룰 이유가 없기 때문이다.
 *
 * <p>응답의 {@code skillUpdates} 와 {@code nextAction} 은 <b>시스템이 계산한 값이다.</b>
 * LLM 에게 물어보지 않는다(ADR-0001, ADR-0002).
 *
 * <p>인증은 아직 없다. 슬라이스 1 은 userId 를 요청 본문에서 받는다 - 실서비스에서는
 * 토큰에서 온다.
 */
@RestController
@RequestMapping("/api")
public class SubmissionController {

    private final SubmissionIntakeService intake;
    private final SubmissionQueryService queries;

    public SubmissionController(SubmissionIntakeService intake, SubmissionQueryService queries) {
        this.intake = intake;
        this.queries = queries;
    }

    /**
     * @param sourceCode 사용자가 낸 코드. <b>신뢰할 수 없는 입력이다</b> - 서버가 읽거나
     *     실행하지 않고 큐를 통해 샌드박스로 넘긴다.
     * @param language 슬라이스 1 은 {@code PYTHON} 만 받는다. 다른 값은 400 이다 -
     *     Worker 가 무엇을 받든 Python 으로 돌리므로, 받아두면 language 와 실제 판정이
     *     어긋난 기록이 남는다.
     * @param hintLevel 0~6. 같은 AC 라도 이 값에 따라 독립 풀이 점수가 갈린다.
     * @param solveSeconds 재지 않았으면 null 이다. 0 이나 음수는 400 - speed 를 기대
     *     시간 대비 <b>비율</b>로 매기므로 음수 시간이 오히려 최고 점수를 받는다.
     */
    public record SubmitRequest(
            @NotNull Long userId,
            @NotBlank String language,
            @NotBlank String sourceCode,
            @Min(0) @Max(6) int hintLevel,
            boolean solutionViewed,
            @Positive Integer solveSeconds) {
    }

    public record JudgeView(String status, int passed, int total, Integer executionMs,
            Integer memoryKb, Integer failedCaseId, String stderr) {
    }

    public record SkillUpdateView(String skillCode, Double before, Double after,
            double confidence, String status) {
    }

    public record NextActionView(String type, String targetSkill, String reason) {
    }

    /**
     * 계약과 필드가 그대로 맞는다. <b>null 인 필드를 생략하지 않는다</b> - 생략은
     * "모른다", null 은 "확인했고 없었다" 로 다른 뜻이다(contracts/README.md 규칙 5).
     *
     * @param review Reviewer 를 부르지 않았으면 null. 아직 Reviewer 자체가 없다.
     * @param promptVersion 같은 이유로 null 이다. 필드를 지우지 않는 이유는, 평가
     *     하네스가 나중에 필요로 할 때 "그때부터 남기자" 로는 이미 늦기 때문이다.
     */
    public record SubmitResponse(
            long submissionId,
            JudgeView judge,
            Object review,
            List<SkillUpdateView> skillUpdates,
            NextActionView nextAction,
            String promptVersion) {
    }

    /**
     * @param state {@code PENDING} 이면 아직 채점 중이거나 결과를 반영하는 중이다.
     *     둘을 구분해 보여주지 않는다 - 사용자가 할 일이 같다.
     */
    public record SubmissionStatusResponse(long submissionId, String state,
            SubmitResponse result) {
    }

    @PostMapping("/problems/{problemCode}/submit")
    public ResponseEntity<SubmissionStatusResponse> submit(@PathVariable String problemCode,
            @Valid @RequestBody SubmitRequest request) {

        long submissionId = intake.accept(new SubmissionIntakeService.Request(
                request.userId(), problemCode, request.language(), request.sourceCode(),
                request.hintLevel(), request.solutionViewed(), request.solveSeconds()));

        // 202 다. 접수했을 뿐 아직 아무것도 판정하지 않았다.
        return ResponseEntity.accepted()
                .body(new SubmissionStatusResponse(submissionId, "PENDING", null));
    }

    @GetMapping("/submissions/{submissionId}")
    public ResponseEntity<SubmissionStatusResponse> find(@PathVariable long submissionId) {
        return queries.find(submissionId)
                .map(SubmissionController::toResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static SubmissionStatusResponse toResponse(SubmissionQueryService.View view) {
        if (!view.complete()) {
            return new SubmissionStatusResponse(view.submissionId(), "PENDING", null);
        }
        return new SubmissionStatusResponse(view.submissionId(), "COMPLETE", new SubmitResponse(
                view.submissionId(),
                new JudgeView(
                        view.judge().status(),
                        view.judge().passed(),
                        view.judge().total(),
                        view.judge().executionMs(),
                        view.judge().memoryKb(),
                        view.judge().failedCaseId(),
                        view.judge().stderr()),
                null,
                view.skillUpdates().stream()
                        .map(update -> new SkillUpdateView(update.skillCode(), update.before(),
                                update.after(), update.confidence(), update.status()))
                        .toList(),
                new NextActionView(view.nextAction().type(), view.nextAction().targetSkill(),
                        view.nextAction().reason()),
                null));
    }

    @ExceptionHandler(SubmissionIntakeService.UnsupportedLanguage.class)
    public ResponseEntity<Map<String, String>> unsupportedLanguage(
            SubmissionIntakeService.UnsupportedLanguage e) {
        // 사용자 입력 문제다. 채점 실패(SYSTEM_ERROR)와 구분한다 - 그쪽은 우리 잘못이라
        // 판정으로 돌려주고 학습 경로를 건드리지 않는다.
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(SubmissionIntakeService.NotFound.class)
    public ResponseEntity<Map<String, String>> notFound(SubmissionIntakeService.NotFound e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
    }
}
