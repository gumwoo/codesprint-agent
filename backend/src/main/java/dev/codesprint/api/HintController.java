package dev.codesprint.api;

import dev.codesprint.learning.service.HintService;
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
 * 단계별 힌트. 정본: PRD §118, ADR-0026.
 *
 * <p><b>POST 다.</b> 내용을 읽는 것처럼 보이지만 서버에 기록이 남는다 - 누가 몇 단계를
 * 봤는가가 곧 mastery 에 들어가므로, 이 호출은 조회가 아니라 상태 변경이다.
 * GET 으로 두면 프리페치나 새로고침이 힌트를 본 것으로 기록한다.
 */
@RestController
@RequestMapping("/api")
public class HintController {

    private final HintService hints;

    public HintController(HintService hints) {
        this.hints = hints;
    }

    public record RevealRequest(Long userId) {
    }

    /**
     * @param level 1..5 는 {@code hints.yaml} 의 사다리, 6 은 전체 풀이다.
     *     한 단계씩만 열 수 있다 - 건너뛰면 400.
     */
    @PostMapping("/problems/{problemCode}/hints/{level}")
    public HintService.Hint reveal(@PathVariable String problemCode, @PathVariable int level,
            @RequestBody RevealRequest request) {
        return hints.reveal(request.userId(), problemCode, level);
    }

    @ExceptionHandler(HintService.NotFound.class)
    public ResponseEntity<Map<String, String>> notFound(HintService.NotFound e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(HintService.SkippedLadder.class)
    public ResponseEntity<Map<String, String>> skipped(HintService.SkippedLadder e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
