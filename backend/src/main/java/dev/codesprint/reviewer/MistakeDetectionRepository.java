package dev.codesprint.reviewer;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MistakeDetectionRepository extends JpaRepository<MistakeDetectionRow, Long> {

    /**
     * 이 사용자의 최근 PRIMARY 탐지들을 <b>최근 순</b>으로. 각 항목은
     * {@code [mistakeCode, problemId]} 다.
     *
     * <p>Addendum §21-B 의 "최근 3<b>문제</b>에서 2회 이상" 을 세는 데 쓴다. 문제
     * 단위로 세야 하므로 problemId 가 함께 필요하다 - 같은 문제를 세 번 틀린 것은
     * "세 문제에서 반복됐다" 가 아니다.
     *
     * <p>SECONDARY 는 세지 않는다. 곁다리로 언급된 것이 확정을 만들면 안 된다.
     */
    @Query("""
            select d.mistakeCode, s.problemId
            from MistakeDetectionRow d, SubmissionRow s
            where s.id = d.submissionId
              and d.userId = :userId
              and d.role = 'PRIMARY'
            order by d.detectedAt desc, d.id desc
            """)
    List<Object[]> recentPrimaryDetections(@Param("userId") Long userId, Pageable page);

    List<MistakeDetectionRow> findBySubmissionId(Long submissionId);
}
