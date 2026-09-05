package dev.codesprint.reviewer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Reviewer 가 탐지한 Mistake 하나. 확정 여부와 무관하게 남는다.
 *
 * <p><b>확정된 것만 남기면 재발을 셀 수 없다</b>(Addendum §21-B). 그리고 이 행들이
 * 나중에 Reviewer 정확도를 재는 라벨이 된다 - 그때 가서 남기기 시작하면 이미
 * 지나간 것은 되살릴 수 없다.
 */
@Entity
@Table(name = "mistake_detections")
public class MistakeDetectionRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "submission_id", nullable = false, updatable = false)
    private Long submissionId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "mistake_code", nullable = false, updatable = false, length = 60)
    private String mistakeCode;

    /** PRIMARY / SECONDARY. 재발 계수와 자동 드릴은 PRIMARY 만 본다. */
    @Column(nullable = false, updatable = false, length = 20)
    private String role;

    @Column(nullable = false, updatable = false, precision = 5, scale = 4)
    private BigDecimal confidence;

    @Column(nullable = false, updatable = false, length = 20)
    private String status;

    @Column(updatable = false)
    private String reason;

    @Column(name = "prompt_version", nullable = false, updatable = false, length = 40)
    private String promptVersion;

    @Column(name = "detected_at", insertable = false, updatable = false)
    private Instant detectedAt;

    protected MistakeDetectionRow() {
    }

    public MistakeDetectionRow(Long submissionId, Long userId, String mistakeCode, String role,
            BigDecimal confidence, String status, String reason, String promptVersion) {
        this.submissionId = submissionId;
        this.userId = userId;
        this.mistakeCode = mistakeCode;
        this.role = role;
        this.confidence = confidence;
        this.status = status;
        this.reason = reason;
        this.promptVersion = promptVersion;
    }

    public Long id() {
        return id;
    }

    public String mistakeCode() {
        return mistakeCode;
    }

    public String status() {
        return status;
    }

    public String role() {
        return role;
    }
}
