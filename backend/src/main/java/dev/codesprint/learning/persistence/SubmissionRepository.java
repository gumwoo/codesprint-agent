package dev.codesprint.learning.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SubmissionRepository extends JpaRepository<SubmissionRow, Long> {

    /**
     * 같은 문제를 몇 번째 시도하는가. Decision Engine 이 "같은 문제 3회 실패" 를
     * 개념 복습으로 돌리는 데 쓴다(Addendum 43).
     */
    long countByUserIdAndProblemId(Long userId, Long problemId);
}
