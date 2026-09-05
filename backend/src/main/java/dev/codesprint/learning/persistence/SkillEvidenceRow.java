package dev.codesprint.learning.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * skill_evidence 한 행. <b>append-only 이며 갱신하지 않는다.</b>
 *
 * <p>이것이 학습의 정본이다. user_skills 는 이 행들로부터 재계산된 캐시일 뿐이고,
 * 두 값이 어긋나면 이쪽이 이긴다(ADR-0009).
 *
 * <p>setter 를 두지 않는다. 한번 쓴 Evidence 를 고치면 과거를 다시 접었을 때 다른
 * 값이 나오고, 그러면 "같은 Evidence 면 같은 mastery" 가 성립하지 않는다.
 */
@Entity
@Table(name = "skill_evidence")
public class SkillEvidenceRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 멱등성 키. DB 의 UNIQUE(user_id, source_event_id, skill_code) 와 짝을 이룬다. */
    @Column(name = "source_event_id", nullable = false, updatable = false, length = 200)
    private String sourceEventId;

    @Column(name = "evidence_id", nullable = false, updatable = false, length = 64)
    private String evidenceId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "skill_code", nullable = false, updatable = false, length = 100)
    private String skillCode;

    @Column(name = "evidence_type", nullable = false, updatable = false, length = 40)
    private String evidenceType;

    /** TIMESTAMPTZ 다. 시간대 없는 값은 타입 단계에서 들어올 수 없다. */
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "weight", nullable = false, updatable = false, precision = 6, scale = 4)
    private BigDecimal weight;

    @Column(name = "source_confidence", updatable = false, precision = 5, scale = 4)
    private BigDecimal sourceConfidence;

    // 관측하지 못한 차원은 null 이다. 0 과 다르다(Addendum 4).
    @Column(name = "observed_concept", updatable = false, precision = 5, scale = 4)
    private BigDecimal observedConcept;

    @Column(name = "observed_recognition", updatable = false, precision = 5, scale = 4)
    private BigDecimal observedRecognition;

    @Column(name = "observed_implementation", updatable = false, precision = 5, scale = 4)
    private BigDecimal observedImplementation;

    @Column(name = "observed_independent", updatable = false, precision = 5, scale = 4)
    private BigDecimal observedIndependent;

    @Column(name = "observed_retention", updatable = false, precision = 5, scale = 4)
    private BigDecimal observedRetention;

    @Column(name = "observed_speed", updatable = false, precision = 5, scale = 4)
    private BigDecimal observedSpeed;

    /**
     * 판단 근거. 산식을 고쳐 과거를 다시 접을 때 필요하다.
     *
     * <p>jsonb 로 저장한다. String 을 그대로 바인딩하면 PostgreSQL 이
     * "column context is of type jsonb but expression is of type character varying"
     * 로 거부한다 - 타입을 명시해야 한다.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "context", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String context;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected SkillEvidenceRow() {
        // JPA 용
    }

    public SkillEvidenceRow(
            String sourceEventId,
            String evidenceId,
            Long userId,
            String skillCode,
            String evidenceType,
            Instant occurredAt,
            BigDecimal weight,
            BigDecimal sourceConfidence,
            BigDecimal observedConcept,
            BigDecimal observedRecognition,
            BigDecimal observedImplementation,
            BigDecimal observedIndependent,
            BigDecimal observedRetention,
            BigDecimal observedSpeed,
            String context) {
        this.sourceEventId = sourceEventId;
        this.evidenceId = evidenceId;
        this.userId = userId;
        this.skillCode = skillCode;
        this.evidenceType = evidenceType;
        this.occurredAt = occurredAt;
        this.weight = weight;
        this.sourceConfidence = sourceConfidence;
        this.observedConcept = observedConcept;
        this.observedRecognition = observedRecognition;
        this.observedImplementation = observedImplementation;
        this.observedIndependent = observedIndependent;
        this.observedRetention = observedRetention;
        this.observedSpeed = observedSpeed;
        this.context = context;
    }

    public Long id() {
        return id;
    }

    public String sourceEventId() {
        return sourceEventId;
    }

    public String evidenceId() {
        return evidenceId;
    }

    public String skillCode() {
        return skillCode;
    }

    public String evidenceType() {
        return evidenceType;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public BigDecimal weight() {
        return weight;
    }

    public BigDecimal sourceConfidence() {
        return sourceConfidence;
    }

    public BigDecimal observedConcept() {
        return observedConcept;
    }

    public BigDecimal observedRecognition() {
        return observedRecognition;
    }

    public BigDecimal observedImplementation() {
        return observedImplementation;
    }

    public BigDecimal observedIndependent() {
        return observedIndependent;
    }

    public BigDecimal observedRetention() {
        return observedRetention;
    }

    public BigDecimal observedSpeed() {
        return observedSpeed;
    }

    public String context() {
        return context;
    }
}
