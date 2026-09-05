package dev.codesprint.learning.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Evidence 저장소. 정본을 읽고 쓴다.
 *
 * <p>재계산은 (사용자, Skill) 의 Evidence 를 <b>시간순으로</b> 훑는다. EMA 는 순서
 * 계산이므로 정렬이 정확해야 하고, 같은 시각이면 id 로 순서를 고정한다.
 */
public interface SkillEvidenceRepository extends JpaRepository<SkillEvidenceRow, Long> {

    List<SkillEvidenceRow> findByUserIdAndSkillCodeOrderByOccurredAtAscIdAsc(
            Long userId, String skillCode);

    boolean existsByUserIdAndSourceEventIdAndSkillCode(
            Long userId, String sourceEventId, String skillCode);
}
