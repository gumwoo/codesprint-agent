package dev.codesprint.judge;

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
 * 채점 큐의 한 행. 계약: contracts/judge-job.schema.json.
 *
 * <p><b>언어 경계를 넘는 유일한 메시지다</b>(ADR-0011, ADR-0013). Java 가 쓰고
 * Python Worker 가 읽는다. 그래서 컬럼을 늘릴 때는 계약과 Worker 를 함께 고쳐야 한다.
 */
@Entity
@Table(name = "judge_jobs")
public class JudgeJobRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 이 job 이 채점하는 제출. <b>실행(RUN)이면 null 이다.</b>
     *
     * <p>결과를 학습 상태로 되돌리는 경로가 이 값으로만 이어진다. DB CHECK 가
     * RUN 행에 이 값이 들어가는 것을 막으므로(V7), 실행은 코드가 실수해도
     * Evidence 가 될 수 없다(ADR-0020).
     */
    @Column(name = "submission_id", updatable = false)
    private Long submissionId;

    /** SUBMIT 또는 RUN. */
    @Column(nullable = false, updatable = false, length = 10)
    private String kind;

    /** 실행을 낸 사용자. 제출이면 null 이다 - 그쪽은 제출 행이 이미 안다. */
    @Column(name = "user_id", updatable = false)
    private Long userId;

    @Column(name = "problem_code", nullable = false, updatable = false, length = 100)
    private String problemCode;

    @Column(nullable = false, updatable = false, length = 20)
    private String language;

    @Column(name = "source_code", nullable = false, updatable = false)
    private String sourceCode;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result", columnDefinition = "jsonb")
    private String result;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "applied_at")
    private Instant appliedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false)
    private Instant updatedAt;

    protected JudgeJobRow() {
    }

    /** 채점할 제출 하나. */
    public JudgeJobRow(Long submissionId, String problemCode, String language,
            String sourceCode) {
        this(submissionId, null, "SUBMIT", problemCode, language, sourceCode);
    }

    /**
     * 제출 전 실행 하나(ADR-0020).
     *
     * <p>제출을 가리키지 않는다. 그래서 이 결과는 Evidence 가 되지 않고 mastery 를
     * 건드리지 않는다 - 코드가 아니라 <b>행 모양</b>이 그것을 막는다.
     */
    public static JudgeJobRow forRun(Long userId, String problemCode, String language,
            String sourceCode) {
        return new JudgeJobRow(null, userId, "RUN", problemCode, language, sourceCode);
    }

    private JudgeJobRow(Long submissionId, Long userId, String kind, String problemCode,
            String language, String sourceCode) {
        this.submissionId = submissionId;
        this.userId = userId;
        this.kind = kind;
        this.problemCode = problemCode;
        this.language = language;
        this.sourceCode = sourceCode;
        this.status = "QUEUED";
    }

    // appliedAt 을 여기서 세터로 바꾸지 않는다.
    //
    // 엔티티를 읽어 확인하고 쓰면 두 인스턴스가 나란히 "아직 반영 안 됐다" 를 보고
    // 둘 다 반영한다. 자리 잡기는 조건부 UPDATE 한 번으로 해야 하며,
    // JudgeJobRepository.claimForApply 가 그것이다.
    //
    // status 와 따로 두는 이유는 둘이 다른 것을 말하기 때문이다. DONE 은 "채점이
    // 끝났다", appliedAt 은 "그 결과를 우리가 처리했다" 다. 하나로 합치면 반영 도중
    // 실패했을 때 다시 시도할 방법이 없다.

    public Long id() {
        return id;
    }

    public Long submissionId() {
        return submissionId;
    }

    public String kind() {
        return kind;
    }

    public Long userId() {
        return userId;
    }

    public String problemCode() {
        return problemCode;
    }

    /** 사용자가 낸 코드. Reviewer 가 분석 대상으로 받는다 - 서버는 실행하지 않는다. */
    public String sourceCode() {
        return sourceCode;
    }

    public String status() {
        return status;
    }

    public int attempts() {
        return attempts;
    }

    public String result() {
        return result;
    }

    public String failureReason() {
        return failureReason;
    }

    public Instant appliedAt() {
        return appliedAt;
    }
}
