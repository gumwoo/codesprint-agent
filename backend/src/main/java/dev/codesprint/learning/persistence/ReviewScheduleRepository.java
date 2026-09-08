package dev.codesprint.learning.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 복습 일정. 정본: ADR-0021. */
public interface ReviewScheduleRepository extends JpaRepository<ReviewScheduleRow, Long> {

    Optional<ReviewScheduleRow> findByUserIdAndSkillCode(Long userId, String skillCode);

    /**
     * 지금 만기이고 <b>아직 아무도 가져가지 않은</b> 일정들. 가장 오래 밀린 것부터
     * 준다 — 여러 개가 밀리면 오래된 쪽이 잊혔을 가능성이 크다.
     *
     * <p>가져간 것을 빼지 않으면, 채점을 기다리는 동안 <b>같은 복습을 또 하라는
     * 액션이 나간다.</b> 그 안내를 따라 낸 제출은 claim 에 실패해 평범한 제출이 되므로,
     * 화면이 말한 것과 실제로 기록되는 것이 어긋난다.
     */
    @Query("""
            select s from ReviewScheduleRow s
            where s.userId = :userId and s.dueAt <= :now
              and s.claimedSubmissionId is null
            order by s.dueAt asc
            """)
    List<ReviewScheduleRow> dueFor(@Param("userId") Long userId, @Param("now") Instant now);

    Optional<ReviewScheduleRow> findByClaimedSubmissionId(Long submissionId);

    List<ReviewScheduleRow> findByUserIdOrderByDueAtAsc(Long userId);

    /**
     * 만기된 복습을 <b>이 제출이 가져간다.</b> 가져간 쪽만 1 을 받는다.
     *
     * <p>읽고 확인한 뒤 쓰면 두 제출이 나란히 "아직 아무도 안 가져갔다" 를 보고
     * 둘 다 가져간다. 조건부 UPDATE 한 번이어야 한다 -
     * {@code JudgeJobRepository.claimForApply} 와 같은 이유다.
     *
     * <p>만기는 <b>제출 시각</b>으로 본다(ADR-0021).
     */
    @Modifying
    @Query("""
            update ReviewScheduleRow s set s.claimedSubmissionId = :submissionId
            where s.userId = :userId and s.skillCode = :skillCode
              and s.dueAt <= :submittedAt and s.claimedSubmissionId is null
            """)
    int claimForReview(@Param("userId") Long userId, @Param("skillCode") String skillCode,
            @Param("submissionId") Long submissionId,
            @Param("submittedAt") Instant submittedAt);
}
