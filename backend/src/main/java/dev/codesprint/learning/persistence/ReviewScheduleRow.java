package dev.codesprint.learning.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;

/**
 * 간격 복습 일정 한 행. 정본: PRD §79, ADR-0021.
 *
 * <p><b>이 행이 "복습인가" 를 정한다.</b> 문제 종류가 아니다 — {@code kind: REVIEW} 를
 * 목록에서 찾아 연달아 풀어도 복습이 아니고, 만기된 일정이 있으면 평범한 문제도
 * 복습이다. 그러지 않으면 사용자가 자기 {@code MASTERED} 를 만들 수 있다.
 */
@Entity
@Table(name = "review_schedules")
public class ReviewScheduleRow {

    /** PRD §79. 성공하면 다음 칸, 실패하면 앞 칸으로. */
    public static final int[] INTERVALS = {1, 3, 7, 14, 30};

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "skill_code", nullable = false, updatable = false, length = 100)
    private String skillCode;

    @Column(name = "due_at", nullable = false)
    private Instant dueAt;

    @Column(name = "last_observed_at", nullable = false)
    private Instant lastObservedAt;

    @Column(name = "interval_days", nullable = false)
    private int intervalDays;

    @Column(name = "updated_at", insertable = false)
    private Instant updatedAt;

    protected ReviewScheduleRow() {
    }

    /**
     * 첫 일정. 점수가 문턱을 넘었을 때 잡는다.
     *
     * @param observedAt 지금까지의 마지막 관측 시각. 여기서부터 간격을 센다.
     */
    public ReviewScheduleRow(Long userId, String skillCode, Instant observedAt) {
        this.userId = userId;
        this.skillCode = skillCode;
        this.lastObservedAt = observedAt;
        this.intervalDays = INTERVALS[0];
        this.dueAt = observedAt.plus(Duration.ofDays(INTERVALS[0]));
    }

    public Long id() {
        return id;
    }

    public Long userId() {
        return userId;
    }

    public String skillCode() {
        return skillCode;
    }

    public Instant dueAt() {
        return dueAt;
    }

    public Instant lastObservedAt() {
        return lastObservedAt;
    }

    public int intervalDays() {
        return intervalDays;
    }

    public boolean isDue(Instant now) {
        return !now.isBefore(dueAt);
    }

    /**
     * 직전 관측으로부터 지난 날. <b>서버가 여기서 잰다</b> — 클라이언트가 보내지 않는다.
     *
     * <p>내림한다. 1일 간격을 23시간 59분 만에 채웠다면 아직 하루가 아니다.
     */
    public int daysSince(Instant now) {
        long days = Duration.between(lastObservedAt, now).toDays();
        return (int) Math.max(0, days);
    }

    /**
     * 복습을 마쳤다. 성공하면 간격이 늘고 실패하면 줄어든다(PRD §79).
     *
     * <p><b>0 으로 내려가지 않는다.</b> 내려가면 같은 자리에서 두 번 제출하는 것이
     * 간격 복습이 되고, retention 이 "시간이 지나도 되는가" 를 재지 못한다.
     */
    public void completed(boolean succeeded, Instant now) {
        int index = indexOf(intervalDays);
        int next = succeeded
                ? Math.min(index + 1, INTERVALS.length - 1)
                : Math.max(index - 1, 0);

        this.intervalDays = INTERVALS[next];
        this.lastObservedAt = now;
        this.dueAt = now.plus(Duration.ofDays(this.intervalDays));
    }

    /** 지금 간격이 표의 몇 번째 칸인가. 표에 없는 값이면 가장 가까운 아래 칸으로 본다. */
    private static int indexOf(int days) {
        int index = 0;
        for (int i = 0; i < INTERVALS.length; i++) {
            if (days >= INTERVALS[i]) {
                index = i;
            }
        }
        return index;
    }
}
