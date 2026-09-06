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
     *
     * <h2>그 제출 시점까지만 본다</h2>
     *
     * <p>채점은 요청 밖에서 일어나므로(ADR-0013), 결과를 반영하는 시점에는 <b>그보다
     * 나중의 제출이 이미 있을 수 있다.</b> 그것까지 세면 아직 일어나지 않은 문제가
     * 창의 한 자리를 차지하고, 원래 창에 있어야 할 문제가 밀려난다.
     *
     * <pre>
     *   P2 탐지 · P5 깨끗 · P9 탐지(지금)   창 = P9·P5·P2 -&gt; 2회 -&gt; 확정
     *   그 뒤에 P3 을 하나 더 내면           창 = P3·P9·P5 -&gt; 1회 -&gt; 확정 안 됨
     * </pre>
     *
     * <p>제출 시각이 같을 수 있으므로 id 로 한 번 더 가른다. 그냥 {@code <=} 로 두면
     * 같은 순간의 <b>나중</b> 제출이 창에 들어온다.
     *
     * @param at 그 제출의 시각. 이 시점 이후는 보지 않는다.
     * @param submissionId 그 제출. 시각이 같을 때의 경계다.
     */
    @Query("""
            select s.problemId from SubmissionRow s
            where s.userId = :userId
              and (s.submittedAt < :at or (s.submittedAt = :at and s.id <= :submissionId))
            group by s.problemId
            order by max(s.submittedAt) desc, max(s.id) desc
            """)
    List<Long> recentProblemIds(@Param("userId") Long userId,
            @Param("at") java.time.Instant at,
            @Param("submissionId") Long submissionId,
            Pageable page);

    /**
     * 이 사용자가 <b>스스로</b> 풀어낸 문제들.
     *
     * <p><b>AC 를 전부 "풀었다" 로 보면 안 된다.</b> 힌트를 많이 쓰거나 풀이를 보고
     * 맞힌 것은 독립 풀이가 아니며(Addendum §22), Evidence 쪽은 이미 그렇게 센다 -
     * {@code SubmissionEvidenceFactory.isIndependentAttempt}. 문제 선택에서만 다시
     * "AC 한 번" 으로 단순화하면, 정답을 보고 맞힌 문제가 후보에서 영영 빠진다.
     * 그 사람은 그 문제를 아직 혼자 풀지 못한다.
     *
     * @param hintCeiling 이 값 이상 힌트를 쓴 AC 는 독립 풀이로 세지 않는다.
     *     상수는 {@code SubmissionEvidenceFactory.INDEPENDENT_HINT_CEILING} 하나뿐이며
     *     호출자가 넘긴다 - 여기 숫자를 박으면 정본이 둘이 된다.
     */
    @Query("""
            select distinct s.problemId from SubmissionRow s
            where s.userId = :userId
              and s.status = 'ACCEPTED'
              and s.solutionViewed = false
              and s.hintLevel < :hintCeiling
            """)
    List<Long> findIndependentlySolvedProblemIds(@Param("userId") Long userId,
            @Param("hintCeiling") int hintCeiling);
}
