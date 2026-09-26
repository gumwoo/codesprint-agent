package dev.codesprint.mocktest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/** 시험에 든 문제 하나. 라벨은 문제 번호 순으로 A 부터다(ADR-0043). */
@Entity
@Table(name = "mock_test_problems")
@IdClass(MockTestProblemRow.Key.class)
public class MockTestProblemRow {

    @Id
    @Column(name = "mock_test_id", nullable = false)
    private Long mockTestId;

    @Id
    @Column(nullable = false, length = 1)
    private String label;

    @Column(name = "problem_code", nullable = false, length = 100)
    private String problemCode;

    protected MockTestProblemRow() {
    }

    public MockTestProblemRow(Long mockTestId, String label, String problemCode) {
        this.mockTestId = mockTestId;
        this.label = label;
        this.problemCode = problemCode;
    }

    public Long mockTestId() {
        return mockTestId;
    }

    public String label() {
        return label;
    }

    public String problemCode() {
        return problemCode;
    }

    public static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        private Long mockTestId;
        private String label;

        public Key() {
        }

        public Key(Long mockTestId, String label) {
            this.mockTestId = mockTestId;
            this.label = label;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && Objects.equals(mockTestId, key.mockTestId)
                    && Objects.equals(label, key.label);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mockTestId, label);
        }
    }
}
