package dev.codesprint.judge;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 채점 큐를 읽고 쓴다.
 *
 * <p><b>job 을 집어가는 쿼리는 여기 없다.</b> 그건 Worker(Python)의 몫이고
 * {@code FOR UPDATE SKIP LOCKED} 로 한다(ADR-0013). Java 는 넣는 쪽과 결과를
 * 반영하는 쪽만 본다.
 */
public interface JudgeJobRepository extends JpaRepository<JudgeJobRow, Long> {

    Optional<JudgeJobRow> findBySubmissionId(Long submissionId);

    /**
     * 채점이 끝났는데 아직 학습 상태에 반영하지 않은 job 들.
     *
     * <p>오래된 것부터 처리한다. Evidence 는 시간순으로 접히므로(EMA) 순서가
     * 뒤집히면 같은 입력에서 다른 mastery 가 나온다.
     */
    @Query("""
            select j from JudgeJobRow j
            where j.appliedAt is null and j.status in ('DONE', 'FAILED')
            order by j.id asc
            """)
    List<JudgeJobRow> findUnapplied(org.springframework.data.domain.Pageable page);

    /** 아직 채점되지 않은 것이 얼마나 쌓여 있는가. Worker 가 떠 있지 않으면 이 값이 는다. */
    @Query("select count(j) from JudgeJobRow j where j.status = :status")
    long countByStatus(@Param("status") String status);
}
