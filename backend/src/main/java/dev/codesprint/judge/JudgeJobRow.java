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

    @Column(name = "submission_id", nullable = false, updatable = false)
    private Long submissionId;

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

    public JudgeJobRow(Long submissionId, String problemCode, String language,
            String sourceCode) {
        this.submissionId = submissionId;
        this.problemCode = problemCode;
        this.language = language;
        this.sourceCode = sourceCode;
        this.status = "QUEUED";
    }

    /**
     * 결과를 학습 상태에 반영했다고 표시한다.
     *
     * <p>status 와 따로 두는 이유는 둘이 다른 것을 말하기 때문이다. DONE 은 "채점이
     * 끝났다", 이 값은 "그 결과를 우리가 처리했다" 다. 하나로 합치면 반영 도중
     * 실패했을 때 다시 시도할 방법이 없다.
     */
    public void markApplied(Instant at) {
        this.appliedAt = at;
        this.updatedAt = at;
    }

    public Long id() {
        return id;
    }

    public Long submissionId() {
        return submissionId;
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
