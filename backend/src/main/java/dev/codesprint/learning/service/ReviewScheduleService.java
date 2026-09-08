package dev.codesprint.learning.service;

import dev.codesprint.learning.persistence.ReviewScheduleRepository;
import dev.codesprint.learning.persistence.ReviewScheduleRow;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 간격 복습 일정. 정본: PRD §79, ADR-0021.
 *
 * <p><b>여기가 "복습인가" 를 정하는 유일한 곳이다.</b> 문제 종류를 보지 않는다 —
 * 일정이 없으면 {@code kind: REVIEW} 를 풀어도 평범한 제출이고, 만기된 일정이 있으면
 * 평범한 문제도 복습이다.
 *
 * <p>{@link Clock} 을 주입받는다. 테스트가 시간을 옮겨야 간격 복습을 검증할 수 있다 —
 * 그러지 않으면 이 저장소에서 하루를 기다려야 한다. <b>프로덕션 경로에는 시간을
 * 앞당기는 수단을 두지 않는다</b> — 두면 그것으로 {@code MASTERED} 를 만들 수 있다.
 */
@Service
public class ReviewScheduleService {

    private final ReviewScheduleRepository schedules;
    private final Clock clock;

    public ReviewScheduleService(ReviewScheduleRepository schedules, Clock clock) {
        this.schedules = schedules;
        this.clock = clock;
    }

    public Instant now() {
        return clock.instant();
    }

    /**
     * 복습을 잡는다. 이미 있으면 그대로 둔다.
     *
     * <p>다시 잡지 않는 이유는 <b>간격이 되돌아가기 때문이다.</b> 점수가 문턱을 넘은
     * 제출마다 새로 잡으면 30일 간격까지 올라간 일정이 매번 1일로 리셋되고,
     * 사용자는 영원히 첫 칸에 머문다.
     */
    @Transactional
    public ReviewScheduleRow scheduleIfAbsent(Long userId, String skillCode, Instant observedAt) {
        return schedules.findByUserIdAndSkillCode(userId, skillCode)
                .orElseGet(() -> schedules.save(
                        new ReviewScheduleRow(userId, skillCode, observedAt)));
    }

    /** 지금 만기인 일정들. 없으면 빈 목록이다. */
    @Transactional(readOnly = true)
    public List<ReviewScheduleRow> due(Long userId) {
        return schedules.dueFor(userId, now());
    }

    /** 이 Skill 에 복습이 이미 잡혀 있는가. 또 잡을 것인지 정하는 데만 쓴다. */
    @Transactional(readOnly = true)
    public boolean isScheduled(Long userId, String skillCode) {
        return schedules.findByUserIdAndSkillCode(userId, skillCode).isPresent();
    }

    /** 지금 만기인 것 중 하나. Decision Engine 이 입력으로 받는다. */
    @Transactional(readOnly = true)
    public Optional<ReviewScheduleRow> nextDue(Long userId) {
        return due(userId).stream().findFirst();
    }

    /**
     * 만기된 복습을 이 제출이 가져간다. <b>제출 시점에 부른다.</b>
     *
     * <p>반영 시점에 정하면 <b>채점 완료 순서가 학습 결과를 바꾼다.</b> Poller 는 끝난
     * job 만 가져가므로, 먼저 낸 A 가 아직 채점 중이고 뒤에 낸 B 가 먼저 끝나면 B 가
     * 복습을 가져간다 — 사용자는 같은 순서로 냈는데 Worker 사정에 따라 mastery 와
     * 간격이 달라진다.
     *
     * @return 가져갔으면 true
     */
    @Transactional
    public boolean claim(Long userId, String skillCode, Long submissionId, Instant submittedAt) {
        return schedules.claimForReview(userId, skillCode, submissionId, submittedAt) == 1;
    }

    /** 이 제출이 가져간 복습. 없으면 비어 있다. */
    @Transactional(readOnly = true)
    public Optional<ReviewScheduleRow> claimedBy(Long submissionId) {
        return schedules.findByClaimedSubmissionId(submissionId);
    }

    /**
     * 가져간 복습을 되돌린다. 채점이 우리 잘못으로 끝났을 때 쓴다.
     *
     * <p>풀어 주지 않으면 그 사용자는 <b>다시는 이 Skill 을 복습할 수 없다</b>.
     */
    @Transactional
    public void release(ReviewScheduleRow schedule) {
        schedule.releaseClaim();
        schedules.save(schedule);
    }

    /**
     * 복습을 마쳤다고 기록하고 다음 간격을 잡는다.
     *
     * @return 이번 복습의 {@code daysSinceLast}. Evidence 가 이 값을 쓴다.
     */
    @Transactional
    public int complete(ReviewScheduleRow schedule, boolean succeeded, Instant at) {
        int daysSince = schedule.daysSince(at);
        schedule.completed(succeeded, at);
        schedules.save(schedule);
        return daysSince;
    }
}
