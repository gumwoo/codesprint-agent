package dev.codesprint.learning.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * user_skills 한 행. <b>Evidence 로부터 재계산된 캐시이며 정본이 아니다</b>(ADR-0009).
 *
 * <p>이 행과 Evidence 가 어긋나면 Evidence 가 이긴다. 그래서 여기에는 갱신 메서드
 * 하나만 두고, 그 인자는 재계산 결과 전체다 - 필드 하나만 따로 고치는 경로를 만들면
 * 캐시가 Evidence 와 다른 이야기를 하기 시작한다.
 */
@Entity
@Table(name = "user_skills")
@IdClass(UserSkillId.class)
public class UserSkillRow {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Id
    @Column(name = "skill_code", nullable = false, updatable = false, length = 100)
    private String skillCode;

    @Column(name = "concept_score", precision = 5, scale = 4)
    private BigDecimal conceptScore;

    @Column(name = "recognition_score", precision = 5, scale = 4)
    private BigDecimal recognitionScore;

    @Column(name = "implementation_score", precision = 5, scale = 4)
    private BigDecimal implementationScore;

    @Column(name = "independent_score", precision = 5, scale = 4)
    private BigDecimal independentScore;

    @Column(name = "retention_score", precision = 5, scale = 4)
    private BigDecimal retentionScore;

    @Column(name = "speed_score", precision = 5, scale = 4)
    private BigDecimal speedScore;

    /** 신규 Skill 은 null 이다. 0.0(평가했고 못함)과 다르다. */
    @Column(name = "mastery_score", precision = 5, scale = 4)
    private BigDecimal masteryScore;

    @Column(name = "confidence_score", nullable = false, precision = 5, scale = 4)
    private BigDecimal confidenceScore = BigDecimal.ZERO;

    @Column(name = "evidence_count", nullable = false)
    private int evidenceCount;

    @Column(nullable = false, length = 30)
    private String status = "UNASSESSED";

    @Column(name = "last_studied_at")
    private Instant lastStudiedAt;

    @Column(name = "next_review_at")
    private Instant nextReviewAt;

    @Column(name = "recomputed_at", nullable = false)
    private Instant recomputedAt = Instant.now();

    protected UserSkillRow() {
    }

    public UserSkillRow(Long userId, String skillCode) {
        this.userId = userId;
        this.skillCode = skillCode;
    }

    /** 재계산 결과로 통째로 덮어쓴다. 부분 갱신 경로를 만들지 않는다. */
    public void replaceWith(BigDecimal concept, BigDecimal recognition,
            BigDecimal implementation, BigDecimal independent, BigDecimal retention,
            BigDecimal speed, BigDecimal mastery, BigDecimal confidence,
            int evidenceCount, String status, Instant lastStudiedAt, Instant recomputedAt) {
        this.conceptScore = concept;
        this.recognitionScore = recognition;
        this.implementationScore = implementation;
        this.independentScore = independent;
        this.retentionScore = retention;
        this.speedScore = speed;
        this.masteryScore = mastery;
        this.confidenceScore = confidence;
        this.evidenceCount = evidenceCount;
        this.status = status;
        this.lastStudiedAt = lastStudiedAt;
        this.recomputedAt = recomputedAt;
    }

    public String skillCode() {
        return skillCode;
    }

    public BigDecimal masteryScore() {
        return masteryScore;
    }

    public BigDecimal confidenceScore() {
        return confidenceScore;
    }

    public int evidenceCount() {
        return evidenceCount;
    }

    public String status() {
        return status;
    }

    public Instant nextReviewAt() {
        return nextReviewAt;
    }
}
