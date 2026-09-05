package dev.codesprint.learning.persistence;

import java.util.List;
import org.springframework.data.domain.Pageable;
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

    /**
     * 이 사용자가 <b>최근에 푼 문제</b>들. 가장 최근에 제출한 순, 중복 없이.
     *
     * <p>Addendum §21-B 의 "최근 3문제" 를 정하는 기준이다. <b>탐지 기록에서 문제를
     * 뽑으면 안 된다</b> - 실수가 없었던 문제는 창에 들어오지 않기 때문이다.
     *
     * <pre>
     *   P1  BOUNDARY_CHECK 탐지
     *   P2  깨끗
     *   P3  깨끗
     *   P4  깨끗
     *   P5  BOUNDARY_CHECK 탐지   &lt;- 지금
     * </pre>
     *
     * <p>실제 최근 3문제는 P3·P4·P5 이고 그 안에서는 1회다. 그런데 탐지 기록만 보면
     * P1 과 P5 가 나란히 보여 2회가 되고, 아주 오래된 실수가 현재 실수와 묶여 확정된다.
     */
    @Query("""
            select s.problemId from SubmissionRow s
            where s.userId = :userId
            group by s.problemId
            order by max(s.submittedAt) desc
            """)
    List<Long> recentProblemIds(@Param("userId") Long userId, Pageable page);
}
