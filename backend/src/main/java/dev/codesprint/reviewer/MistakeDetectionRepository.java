package dev.codesprint.reviewer;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MistakeDetectionRepository extends JpaRepository<MistakeDetectionRow, Long> {

    /**
     * 주어진 문제들 안에서 이 Mistake 가 PRIMARY 로 탐지된 <b>문제 개수</b>.
     *
     * <p>Addendum §21-B 를 센다. 창(어떤 문제들인가)은 이 쿼리가 정하지 않는다 -
     * {@code SubmissionRepository.recentProblemIds} 가 정해서 넘겨준다. 탐지 기록에서
     * 창을 뽑으면 실수가 없었던 문제가 빠져 오래된 실수가 현재와 묶인다.
     *
     * <p><b>문제 단위로 센다.</b> 같은 문제를 세 번 틀린 것은 "세 문제에서 반복됐다" 가
     * 아니다 - 그렇게 세면 한 문제에서 고전하는 사용자가 곧바로 확정을 받는다.
     *
     * <p>SECONDARY 는 세지 않는다. 곁다리로 언급된 것이 확정을 만들면 안 된다.
     *
     * <p>그 문제에서 <b>일어날 수 없다고 선언된</b> 탐지도 세지 않는다(ADR-0029).
     * 확정을 그 자리에서 막는 것만으로는 부족하다 - 행이 남아 있으면 나중에 다른
     * 문제에서 같은 실수가 나올 때 재발 횟수를 채워 준다.
     */
    @Query("""
            select count(distinct s.problemId)
            from MistakeDetectionRow d, SubmissionRow s
            where s.id = d.submissionId
              and d.userId = :userId
              and d.mistakeCode = :mistakeCode
              and d.role = 'PRIMARY'
              and d.declaredForProblem = true
              and s.problemId in :problemIds
            """)
    long countProblemsWithPrimary(@Param("userId") Long userId,
            @Param("mistakeCode") String mistakeCode,
            @Param("problemIds") Collection<Long> problemIds);

    List<MistakeDetectionRow> findBySubmissionId(Long submissionId);
}
