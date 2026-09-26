package dev.codesprint.api;

import dev.codesprint.tutor.TutorService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 자유 질문. 정본: PRD §90 · §151, ADR-0044. 계약: contracts/tutor-answer.schema.json.
 *
 * <p>POST 인 이유는 모델을 부르기 때문이다 - 같은 질문이라도 매번 새로 부르고 비용이 든다.
 */
@RestController
@RequestMapping("/api")
public class TutorController {

    private final TutorService tutor;

    public TutorController(TutorService tutor) {
        this.tutor = tutor;
    }

    public record QuestionRequest(@NotNull Long userId, @NotBlank String skillCode,
            @NotBlank String question) {
    }

    @PostMapping("/tutor/questions")
    public TutorService.Result ask(@Valid @RequestBody QuestionRequest request) {
        return tutor.ask(request.userId(), request.skillCode(), request.question());
    }

    @ExceptionHandler(TutorService.NotFound.class)
    public ResponseEntity<Map<String, String>> notFound(TutorService.NotFound e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(TutorService.BadQuestion.class)
    public ResponseEntity<Map<String, String>> bad(TutorService.BadQuestion e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(TutorService.Withheld.class)
    public ResponseEntity<Map<String, String>> withheld(TutorService.Withheld e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(TutorService.Disabled.class)
    public ResponseEntity<Map<String, String>> disabled(TutorService.Disabled e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(TutorService.Unusable.class)
    public ResponseEntity<Map<String, String>> unusable(TutorService.Unusable e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("message", e.getMessage()));
    }
}
