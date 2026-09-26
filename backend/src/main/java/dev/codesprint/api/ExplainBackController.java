package dev.codesprint.api;

import dev.codesprint.explain.ExplainService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Explain Back. 정본: PRD §148, ADR-0050. 계약: contracts/explain-back.schema.json.
 *
 * <p>POST 인 이유는 모델을 부르기 때문이다. 분석은 기록되지 않는다(ADR-0050).
 */
@RestController
@RequestMapping("/api")
public class ExplainBackController {

    private final ExplainService explain;

    public ExplainBackController(ExplainService explain) {
        this.explain = explain;
    }

    public record ExplanationRequest(@NotNull Long userId, @NotBlank String explanation) {
    }

    @PostMapping("/problems/{problemCode}/explanations")
    public ExplainService.Result explain(@PathVariable String problemCode,
            @Valid @RequestBody ExplanationRequest request) {
        return explain.explain(request.userId(), problemCode, request.explanation());
    }

    @ExceptionHandler(ExplainService.NotFound.class)
    public ResponseEntity<Map<String, String>> notFound(ExplainService.NotFound e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(ExplainService.BadExplanation.class)
    public ResponseEntity<Map<String, String>> bad(ExplainService.BadExplanation e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(ExplainService.Withheld.class)
    public ResponseEntity<Map<String, String>> withheld(ExplainService.Withheld e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(ExplainService.Disabled.class)
    public ResponseEntity<Map<String, String>> disabled(ExplainService.Disabled e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(ExplainService.Unusable.class)
    public ResponseEntity<Map<String, String>> unusable(ExplainService.Unusable e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("message", e.getMessage()));
    }
}
