package dev.codesprint.mocktest;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 시험에 든 문제. */
public interface MockTestProblemRepository
        extends JpaRepository<MockTestProblemRow, MockTestProblemRow.Key> {

    List<MockTestProblemRow> findByMockTestIdOrderByLabelAsc(Long mockTestId);
}
