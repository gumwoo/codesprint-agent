package dev.codesprint.api;

import dev.codesprint.learning.persistence.UserRepository;
import dev.codesprint.learning.service.AnalyticsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 학습 분석. 정본: PRD §99 · §160, ADR-0049. 계약: contracts/analytics.schema.json.
 *
 * <p>시험 중에는 닫힌다({@code ExamLock}) - Skill 상태 분포와 판정 분포를 시험 제출 전후로 비교하면
 * 시험 문제의 유형이 드러난다(ADR-0043 과 같은 이유).
 */
@RestController
@RequestMapping("/api")
public class AnalyticsController {

    private final UserRepository users;
    private final AnalyticsService analytics;

    public AnalyticsController(UserRepository users, AnalyticsService analytics) {
        this.users = users;
        this.analytics = analytics;
    }

    @GetMapping("/users/{userId}/analytics")
    public ResponseEntity<AnalyticsService.Analytics> analytics(@PathVariable long userId) {
        if (!users.existsById(userId)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(analytics.of(userId));
    }
}
