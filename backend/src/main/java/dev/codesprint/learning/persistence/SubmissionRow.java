package dev.codesprint.learning.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * submissions 한 행. 제출 하나와 그 판정.
 *
 * <p>이 행의 id 가 Evidence 의 {@code sourceEventId} 가 된다. 같은 제출이 두 번
 * 처리되어도 같은 키가 나오므로 Evidence 가 두 번 쌓이지 않는다(ADR-0009).
 *
 * <p>제출한 <b>코드 본문은 두지 않는다.</b> 신뢰할 수 없는 입력이고, 판정에 필요한
 * 것은 결과뿐이다.
 */
@Entity
@Table(name = "submissions")
public class SubmissionRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "problem_id", nullable = false, updatable = false)
    private Long problemId;

    @Column(nullable = false, length = 20)
    private String language;

    @Column(nullable = false, length = 20)
    private String status;

    private Integer passed;
    private Integer total;

    @Column(name = "execution_ms")
    private Integer executionMs;

    @Column(name = "memory_kb")
    private Integer memoryKb;

    @Column(name = "failed_case_id")
    private Integer failedCaseId;

    /** 같은 AC 라도 이 값에 따라 독립 풀이 점수가 갈린다(Addendum 11). */
    @Column(name = "hint_level", nullable = false)
    private int hintLevel;

    @Column(name = "solution_viewed", nullable = false)
    private boolean solutionViewed;

    @Column(name = "solve_seconds")
    private Integer solveSeconds;

    @Column(name = "submitted_at", insertable = false, updatable = false)
    private Instant submittedAt;

    // 결정 결과. 채점이 요청 밖으로 나가면서 사용자가 나중에 다시 물어보게 됐는데,
    // 그때 다음 행동을 새로 계산하면 안 된다 - 그 사이 다른 제출이 바꿔 놓은 상태를
    // 보게 되어 같은 제출을 두 번 조회했을 때 다른 답이 나온다.
    @Column(name = "next_action_type", length = 30)
    private String nextActionType;

    @Column(name = "next_action_target", length = 100)
    private String nextActionTarget;

    @Column(name = "next_action_reason")
    private String nextActionReason;

    // before 값(이번 제출 전의 mastery)은 반영하는 그 순간에만 알 수 있다.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "skill_updates", columnDefinition = "jsonb")
    private String skillUpdates;

    protected SubmissionRow() {
    }

    public SubmissionRow(Long userId, Long problemId, String language, String status,
            int hintLevel, boolean solutionViewed, Integer solveSeconds) {
        this.userId = userId;
        this.problemId = problemId;
        this.language = language;
        this.status = status;
        this.hintLevel = hintLevel;
        this.solutionViewed = solutionViewed;
        this.solveSeconds = solveSeconds;
    }

    /** 판정이 난 뒤에 붙인다. 제출 자체는 판정보다 먼저 존재한다. */
    public void applyJudgement(String status, Integer passed, Integer total,
            Integer executionMs, Integer memoryKb, Integer failedCaseId) {
        this.status = status;
        this.passed = passed;
        this.total = total;
        this.executionMs = executionMs;
        this.memoryKb = memoryKb;
        this.failedCaseId = failedCaseId;
    }

    public Long id() {
        return id;
    }

    public Long userId() {
        return userId;
    }

    public Long problemId() {
        return problemId;
    }

    public String status() {
        return status;
    }

    public int hintLevel() {
        return hintLevel;
    }

    public boolean solutionViewed() {
        return solutionViewed;
    }

    public Integer solveSeconds() {
        return solveSeconds;
    }

    /** 결정과 갱신 내역을 함께 남긴다. 반영은 한 번뿐이므로 부분 갱신 경로를 두지 않는다. */
    public void applyOutcome(String nextActionType, String nextActionTarget,
            String nextActionReason, String skillUpdates) {
        this.nextActionType = nextActionType;
        this.nextActionTarget = nextActionTarget;
        this.nextActionReason = nextActionReason;
        this.skillUpdates = skillUpdates;
    }

    public Instant submittedAt() {
        return submittedAt;
    }

    public Integer passed() {
        return passed;
    }

    public Integer total() {
        return total;
    }

    public Integer executionMs() {
        return executionMs;
    }

    public Integer memoryKb() {
        return memoryKb;
    }

    public Integer failedCaseId() {
        return failedCaseId;
    }

    public String nextActionType() {
        return nextActionType;
    }

    public String nextActionTarget() {
        return nextActionTarget;
    }

    public String nextActionReason() {
        return nextActionReason;
    }

    public String skillUpdates() {
        return skillUpdates;
    }
}
