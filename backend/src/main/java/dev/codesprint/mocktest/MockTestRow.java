package dev.codesprint.mocktest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 모의 시험 하나. 정본: ADR-0043.
 *
 * <p>끝나는 시각은 만들 때 정한다. <b>끝남은 {@code endsAt} 으로 판정한다</b> -
 * {@code finishedAt} 은 일찍 끝냈거나 다음 시험을 만들며 닫은 시각일 뿐, 비어 있어도 시간이
 * 지났으면 끝난 시험이다.
 */
@Entity
@Table(name = "mock_tests")
public class MockTestRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected MockTestRow() {
    }

    public MockTestRow(Long userId, Instant startedAt, Instant endsAt) {
        this.userId = userId;
        this.startedAt = startedAt;
        this.endsAt = endsAt;
    }

    public Long id() {
        return id;
    }

    public Long userId() {
        return userId;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant endsAt() {
        return endsAt;
    }

    public Instant finishedAt() {
        return finishedAt;
    }

    /** 시험이 실제로 끝난 시각. 일찍 끝냈으면 그 시각, 아니면 끝나기로 한 시각이다. */
    public Instant closedAt() {
        return finishedAt != null && finishedAt.isBefore(endsAt) ? finishedAt : endsAt;
    }

    public boolean isOver(Instant now) {
        return finishedAt != null || !now.isBefore(endsAt);
    }

    /** 닫는다. 이미 닫혔으면 처음 닫은 시각을 그대로 둔다. */
    public void close(Instant at) {
        if (finishedAt == null) {
            finishedAt = at.isBefore(endsAt) ? at : endsAt;
        }
    }
}
