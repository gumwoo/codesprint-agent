package dev.codesprint.learning.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 복습 일정. 정본: ADR-0021. */
public interface ReviewScheduleRepository extends JpaRepository<ReviewScheduleRow, Long> {

    Optional<ReviewScheduleRow> findByUserIdAndSkillCode(Long userId, String skillCode);

    /**
     * 지금 만기인 일정들. <b>가장 오래 밀린 것부터</b> 준다 — 여러 개가 밀리면 오래된
     * 쪽이 잊혔을 가능성이 크다.
     */
    @Query("""
            select s from ReviewScheduleRow s
            where s.userId = :userId and s.dueAt <= :now
            order by s.dueAt asc
            """)
    List<ReviewScheduleRow> dueFor(@Param("userId") Long userId, @Param("now") Instant now);
}
