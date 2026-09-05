package dev.codesprint.learning.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubmissionRepository extends JpaRepository<SubmissionRow, Long> {

    /**
     * 이 문제의 제출 판정을 <b>최근 것부터</b> 돌려준다.
     *
     * <p>개수를 세는 쿼리를 두지 않는다. Decision Engine 이 쓰는 것은 "평생 몇 번
     * 냈는가" 가 아니라 <b>"지금 연속으로 몇 번 막혀 있는가"</b> 이고, 그 계산에는
     * 판정의 <b>순서</b>가 필요하다 - 마지막 정답에서 끊어야 하기 때문이다.
     *
     * <p>같은 시각이면 id 로 순서를 고정한다.
     */
    @Query("""
            select s.status from SubmissionRow s
            where s.userId = :userId and s.problemId = :problemId
            order by s.submittedAt desc, s.id desc
            """)
    List<String> findStatusesNewestFirst(@Param("userId") Long userId,
            @Param("problemId") Long problemId);
}
