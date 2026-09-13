package dev.codesprint.learning.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 힌트를 한 단계 내준 기록. 정본: ADR-0026.
 *
 * <p><b>이 행이 "몇 단계를 봤는가" 의 정본이다.</b> 클라이언트가 신고하지 않는다 -
 * 서버가 내주면서 남기고, 제출이 그 기록에서 읽는다(ADR-0001 이 그은 선과 같다:
 * 내용은 만들어 줄 수 있어도 관측은 시스템이 한다).
 *
 * <p>(사용자, 문제, 단계) 당 하나다. 같은 단계를 다시 열어도 새 기록이 되지 않는다 -
 * 그러면 여러 번 본 사람이 <b>더 많이 본 것으로</b> 계산된다.
 */
@Entity
@Table(name = "hint_usage")
public class HintUsageRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "problem_id", nullable = false)
    private Long problemId;

    @Column(name = "hint_level", nullable = false)
    private int hintLevel;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected HintUsageRow() {
    }

    public HintUsageRow(Long userId, Long problemId, int hintLevel, Instant createdAt) {
        this.userId = userId;
        this.problemId = problemId;
        this.hintLevel = hintLevel;
        this.createdAt = createdAt;
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

    public int hintLevel() {
        return hintLevel;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
