package dev.codesprint.api;

import dev.codesprint.learning.persistence.SubmissionRepository;
import dev.codesprint.learning.persistence.UserRepository;
import dev.codesprint.learning.service.TodayService;
import dev.codesprint.problem.ProblemCatalog.ProblemDefinition;
import dev.codesprint.reviewer.MistakeDetectionRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 대시보드 - 오늘의 계획과 최근 오답. 정본: PRD §115 · §121 · §123, ADR-0038.
 * 계약: contracts/today.schema.json, contracts/mistake-summary.schema.json.
 *
 * <p><b>이 응답은 계산하지 않는다.</b> 계획은 {@code DailyPlanner} 가, 숫자는 서비스가 정한
 * 것을 옮길 뿐이다. 화면이 블록을 다시 고르거나 분을 나누기 시작하면 서버의 계획과 갈린다.
 */
@RestController
@RequestMapping("/api")
public class TodayController {

    /** 최근 몇 개의 제출에서 오답을 세는가(PRD §123 "최근 50개 제출"). */
    static final int RECENT_SUBMISSIONS = 50;

    private final UserRepository users;
    private final TodayService today;
    private final SubmissionRepository submissions;
    private final MistakeDetectionRepository detections;

    public TodayController(UserRepository users, TodayService today,
            SubmissionRepository submissions, MistakeDetectionRepository detections) {
        this.users = users;
        this.today = today;
        this.submissions = submissions;
        this.detections = detections;
    }

    public record ProblemView(String code, String title) {
    }

    public record BlockView(String type, String skillCode, ProblemView problem, int minutes,
            String reason) {
    }

    public record TodayResponse(long userId, String date, Integer examInDays,
            Integer totalMinutes, String mode, List<BlockView> blocks, String reason,
            int mastered, int total) {
    }

    @GetMapping("/users/{userId}/today")
    public ResponseEntity<TodayResponse> today(@PathVariable long userId) {
        return users.findById(userId).map(user -> {
            TodayService.Today plan = today.today(user);
            List<BlockView> blocks = plan.blocks().stream()
                    .map(block -> new BlockView(block.type(), block.skillCode(),
                            view(block.problem()), block.minutes(), block.reason()))
                    .toList();
            return ResponseEntity.ok(new TodayResponse(userId, plan.date(), plan.examInDays(),
                    plan.totalMinutes(), plan.mode(), blocks, plan.reason(), plan.mastered(),
                    plan.total()));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * @param total 이 실수가 PRIMARY 로 탐지된 횟수
     * @param confirmed 그중 시스템이 확정한 횟수. 나머지는 주장일 뿐이다(ADR-0014)
     */
    public record MistakeCount(String mistakeCode, int total, int confirmed) {
    }

    public record MistakeSummary(long userId, int submissions, List<MistakeCount> mistakes) {
    }

    @GetMapping("/users/{userId}/mistakes")
    @Transactional(readOnly = true)
    public ResponseEntity<MistakeSummary> mistakes(@PathVariable long userId) {
        if (!users.existsById(userId)) {
            return ResponseEntity.notFound().build();
        }
        List<Long> recent = submissions.recentSubmissionIds(userId,
                PageRequest.of(0, RECENT_SUBMISSIONS));
        Map<String, int[]> counts = new LinkedHashMap<>();
        if (!recent.isEmpty()) {
            for (Object[] row : detections.countPrimaryByCodeAndStatus(userId, recent)) {
                int[] c = counts.computeIfAbsent((String) row[0], k -> new int[2]);
                int n = ((Number) row[2]).intValue();
                c[0] += n;
                if ("CONFIRMED".equals(row[1])) {
                    c[1] += n;
                }
            }
        }
        List<MistakeCount> list = new ArrayList<>();
        counts.forEach((code, c) -> list.add(new MistakeCount(code, c[0], c[1])));
        // 많은 것부터, 같으면 code 순. 화면이 정렬하지 않는다.
        list.sort(Comparator.comparingInt(MistakeCount::total).reversed()
                .thenComparing(MistakeCount::mistakeCode));
        return ResponseEntity.ok(new MistakeSummary(userId, recent.size(), list));
    }

    private static ProblemView view(ProblemDefinition problem) {
        return problem == null ? null : new ProblemView(problem.code(), problem.title());
    }
}
