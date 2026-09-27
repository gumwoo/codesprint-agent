package dev.codesprint.mocktest;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 시험 중의 관측. */
public interface MockTestEventRepository extends JpaRepository<MockTestEventRow, Long> {

    List<MockTestEventRow> findByMockTestIdOrderByOccurredAtAscIdAsc(Long mockTestId);

    Optional<MockTestEventRow> findFirstBySubmissionId(Long submissionId);

    /**
     * 다음 행동이 정해진 가장 최근의 <b>일반</b> 제출(시험에서 낸 것이 아닌)이 가리킨 다음 문제. 결과 패널이 보여 준
     * 문제다 - 모의 시험 후보에서 뺀다(ADR-0053). 다음 문제가 없었으면 null 한 칸이다.
     */
    @org.springframework.data.jpa.repository.Query("""
            select s.nextProblemCode from SubmissionRow s
            where s.userId = :userId and s.nextActionType is not null
              and not exists (select e.id from MockTestEventRow e where e.submissionId = s.id)
            order by s.submittedAt desc, s.id desc
            """)
    List<String> latestNormalNextProblem(
            @org.springframework.data.repository.query.Param("userId") Long userId,
            org.springframework.data.domain.Pageable page);
}
