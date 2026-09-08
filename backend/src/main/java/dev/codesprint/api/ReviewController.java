package dev.codesprint.api;

import dev.codesprint.learning.persistence.ReviewScheduleRow;
import dev.codesprint.learning.persistence.UserRepository;
import dev.codesprint.learning.service.NextProblemService;
import dev.codesprint.learning.service.ReviewScheduleService;
import dev.codesprint.problem.ProblemCatalog;
import dev.codesprint.problem.ProblemCatalog.ProblemDefinition;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 예약된 복습. 계약: contracts/reviews.schema.json. 정본: PRD §79, ADR-0021.
 *
 * <p><b>간격 복습은 "다시 왔을 때" 가 전부인 기능인데, 다시 와서는 볼 수가 없었다.</b>
 * 일정은 잡히지만 그것을 알 방법이 제출뿐이었다 — 아무 문제나 하나 내야 결과 패널에
 * {@code REVIEW_DUE} 가 떴다. 그 사이 아무 문제나 풀면 그것이 복습을 가져간다.
 *
 * <p>화면은 <b>만기 여부를 계산하지 않는다.</b> 서버가 {@code due} 를 정해서 내려준다 -
 * 시각 비교를 화면에서 하면 브라우저 시계가 학습 기록을 정하게 된다.
 */
@RestController
@RequestMapping("/api")
public class ReviewController {

    private final ReviewScheduleService schedules;
    private final NextProblemService nextProblem;
    private final ProblemCatalog problems;
    private final UserRepository users;

    public ReviewController(ReviewScheduleService schedules, NextProblemService nextProblem,
            ProblemCatalog problems, UserRepository users) {
        this.schedules = schedules;
        this.nextProblem = nextProblem;
        this.problems = problems;
        this.users = users;
    }

    /**
     * @param due 지금 복습할 차례인가. <b>서버가 정한다.</b>
     * @param problem 만기일 때 풀 문제. 아니면 null 이다 - 생략하지 않는다.
     * @param intervalDays 지금 간격. 성공하면 늘고 실패하면 준다(PRD §79).
     */
    public record ReviewView(String skillCode, String dueAt, boolean due, int intervalDays,
            ProblemView problem) {
    }

    public record ProblemView(String code, String title) {
    }

    public record ReviewsResponse(long userId, String now, List<ReviewView> reviews) {
    }

    /** 이 사용자의 복습 일정. 만기가 먼저 온다. */
    @GetMapping("/users/{userId}/reviews")
    public ResponseEntity<ReviewsResponse> reviews(@PathVariable long userId) {
        if (!users.existsById(userId)) {
            return ResponseEntity.notFound().build();
        }
        Instant now = schedules.now();
        List<ReviewView> views = schedules.all(userId).stream()
                .map(schedule -> toView(userId, schedule, now))
                .toList();
        return ResponseEntity.ok(new ReviewsResponse(userId, now.toString(), views));
    }

    private ReviewView toView(long userId, ReviewScheduleRow schedule, Instant now) {
        boolean due = schedule.isDue(now) && schedule.claimedSubmissionId() == null;
        // 만기가 아니면 문제를 주지 않는다. 주면 사용자는 지금 풀어도 되는 것으로 읽고,
        // 그 제출은 복습으로 세어지지 않는다 - 화면이 말한 것과 기록이 어긋난다.
        ProblemDefinition problem = due
                ? problems.find(nextProblem.forReview(userId, schedule.skillCode(), null)
                        .problemCode())
                : null;
        return new ReviewView(schedule.skillCode(), schedule.dueAt().toString(), due,
                schedule.intervalDays(),
                problem == null ? null : new ProblemView(problem.code(), problem.title()));
    }
}
