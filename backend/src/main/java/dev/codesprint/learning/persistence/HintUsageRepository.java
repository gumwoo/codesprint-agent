package dev.codesprint.learning.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
