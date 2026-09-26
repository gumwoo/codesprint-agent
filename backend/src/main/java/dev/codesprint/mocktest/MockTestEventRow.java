package dev.codesprint.mocktest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 시험 중에 사용자가 서버에 무언가를 보낸 순간. <b>시간 관리 평가의 정본이다</b>(ADR-0043).
 *
 * <p>서버가 볼 수 있는 것은 이 순간들뿐이다 - 문제를 연 때, 실행한 때, 제출한 때. 읽기가
 * 언제 끝나고 코드를 언제 쓰기 시작했는지는 여기 없고, 그래서 보고서에도 없다.
 */
@Entity
@Table(name = "mock_test_events")
public class MockTestEventRow {

    public enum Kind { OPENED, RUN, SUBMITTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "mock_test_id", nullable = false)
    private Long mockTestId;

    @Column(nullable = false, length = 1)
    private String label;

    @Column(nullable = false, length = 10)
    private String kind;

    @Column(name = "submission_id")
    private Long submissionId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected MockTestEventRow() {
    }

    public MockTestEventRow(Long mockTestId, String label, Kind kind, Long submissionId,
            Instant occurredAt) {
        this.mockTestId = mockTestId;
        this.label = label;
        this.kind = kind.name();
        this.submissionId = submissionId;
        this.occurredAt = occurredAt;
    }

    public Long mockTestId() {
        return mockTestId;
    }

    public String label() {
        return label;
    }

    public Kind kind() {
        return Kind.valueOf(kind);
    }

    public Long submissionId() {
        return submissionId;
    }

    public Instant occurredAt() {
        return occurredAt;
    }
}
