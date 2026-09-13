package dev.codesprint.learning.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 힌트 발급 기록. 정본: ADR-0026. */
public interface HintUsageRepository extends JpaRepository<HintUsageRow, Long> {

    /**
     * 이 사용자가 이 문제에서 <b>본 적 있는</b> 최고 단계. 한 번도 없으면 비어 있다.
     *
     * <p>제출마다 새로 세지 않고 지금까지의 최댓값을 쓰는 이유는, <b>한 번 본 힌트는
     * 되돌릴 수 없기 때문이다.</b> H3 를 보고 실패한 뒤 힌트 없이 다시 내도 그 사람은
     * 여전히 H3 를 알고 있다 - 두 번째 제출을 "힌트 없는 풀이" 로 세면 독립 풀이를
     * 실제보다 높게 기록한다.
     */
    @Query("""
            select max(h.hintLevel) from HintUsageRow h
            where h.userId = :userId and h.problemId = :problemId
            """)
    Optional<Integer> highestLevel(@Param("userId") Long userId,
            @Param("problemId") Long problemId);

    List<HintUsageRow> findByUserIdAndProblemIdOrderByHintLevelAsc(Long userId, Long problemId);

    /**
     * 이 단계를 본 것으로 남긴다. <b>이미 있으면 아무 일도 하지 않는다.</b>
     *
     * <p>읽고 확인한 뒤 쓰면 같은 단계 요청 둘이 나란히 "아직 없다" 를 보고 둘 다
     * 넣는다. 그때 한쪽은 UNIQUE 위반으로 터진다 - 실제로 8개를 동시에 보내
     * <b>7개가 500 이 되는 것을 재현했다.</b> 더블클릭 · 재시도 · 응답을 못 받아
     * 다시 누르는 경우에 그대로 일어난다.
     *
     * <p>같은 단계를 다시 여는 것은 <b>성공해야 한다</b> - 다시 봤다고 더 많이 본
     * 것이 아니고, 그것이 이 서비스가 약속한 성질이다(ADR-0027).
     *
     * <p>예외를 잡아 넘기지 않는다. 제약 위반이 나면 그 트랜잭션은 이미
     * rollback-only 라 같은 트랜잭션에서 이어서 읽고 쓸 수 없다. 충돌 자체를
     * 만들지 않는 쪽이 맞다 - 복습 일정의 조건부 UPDATE 와 같은 이유다(ADR-0021).
     */
    @Modifying
    @Query(value = """
            insert into hint_usage (user_id, problem_id, hint_level, created_at)
            values (:userId, :problemId, :level, :now)
            on conflict (user_id, problem_id, hint_level) do nothing
            """, nativeQuery = true)
    void recordOnce(@Param("userId") Long userId, @Param("problemId") Long problemId,
            @Param("level") int level, @Param("now") Instant now);
}
