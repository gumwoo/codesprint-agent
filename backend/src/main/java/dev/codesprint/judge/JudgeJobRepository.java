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

    /**
     * 이 job 을 <b>반영할 자리를 잡는다.</b> 잡은 쪽만 1 을 받는다.
     *
     * <p>읽고 확인한 뒤 쓰면 두 인스턴스가 나란히 "아직 반영 안 됐다" 를 보고 둘 다
     * 반영한다. 그러면 Reviewer 를 두 번 부르고, 같은 제출에 서로 다른 분석 두 개가
     * 남는다 - 나중에 그 라벨로 정확도를 재는데(ADR-0016) 어느 쪽이 그 제출의 분석인지
     * 알 수 없게 된다.
     *
     * <p>Evidence 는 {@code (sourceEventId, skillCode)} 유일성이 막아 주지만(ADR-0009)
     * 탐지 기록에는 그런 열쇠가 없다.
     *
     * <p><b>먼저 쓰고 시작한다.</b> 이 UPDATE 가 행 잠금을 잡으므로 두 번째 트랜잭션은
     * 여기서 기다렸다가 0 을 받는다. 실패해서 트랜잭션이 되돌아가면 이 표시도 함께
     * 되돌아가므로 다음 주기에 다시 집는다.
     *
     * <h2>끝난 job 만 잡는다</h2>
     *
     * <p>아직 채점 중인 job 에 자리를 내주면 <b>진짜 결과를 영원히 잃는다.</b>
     * 완료되지 않은 job 의 결과를 읽으면 {@code SYSTEM_ERROR} 가 나오는데, 그건
     * 예외가 아니라 정상 처리라 트랜잭션이 <b>커밋된다.</b> {@code applied_at} 이
     * 남아 Poller 가 다시 집지 않으므로, 나중에 Worker 가 채점을 끝내도 그 결과는
     * 반영되지 않는다.
     *
     * <p>Poller 는 완료된 것만 넘겨주지만, 그 조건이 호출자에게만 있으면 잘못된 호출
     * 한 번이 결과를 버린다. <b>버릴 수 있는 쪽에서 막는다.</b>
     *
     * @return 1 이면 이 트랜잭션이 반영한다. 0 이면 이미 반영됐거나, 다른 쪽이
     *     가져갔거나, 아직 채점이 끝나지 않았다.
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("""
            update JudgeJobRow j set j.appliedAt = :at, j.updatedAt = :at
            where j.id = :id and j.appliedAt is null
              and j.status in ('DONE', 'FAILED')
            """)
    int claimForApply(@Param("id") Long id, @Param("at") java.time.Instant at);

    /** 아직 채점되지 않은 것이 얼마나 쌓여 있는가. Worker 가 떠 있지 않으면 이 값이 는다. */
    @Query("select count(j) from JudgeJobRow j where j.status = :status")
    long countByStatus(@Param("status") String status);
}
