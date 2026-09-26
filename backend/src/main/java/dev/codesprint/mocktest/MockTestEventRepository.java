package dev.codesprint.mocktest;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 시험 중의 관측. */
public interface MockTestEventRepository extends JpaRepository<MockTestEventRow, Long> {

    List<MockTestEventRow> findByMockTestIdOrderByOccurredAtAscIdAsc(Long mockTestId);

    Optional<MockTestEventRow> findFirstBySubmissionId(Long submissionId);
}
