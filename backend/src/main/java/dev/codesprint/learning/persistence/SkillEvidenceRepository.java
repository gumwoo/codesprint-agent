package dev.codesprint.learning.persistence;

import java.util.List;
import org.springframework.data.repository.Repository;

/**
 * Evidence 저장소. 정본을 읽고 쓴다.
 *
 * <p><b>JpaRepository 를 상속하지 않는다.</b> 그러면 {@code delete} / {@code deleteAll} /
 * {@code deleteById} 가 애플리케이션 코드에 전부 열린다. Evidence 는 append-only 이며
 * (ADR-0009) 하나가 사라지면 과거를 다시 접었을 때 다른 mastery 가 나온다.
 *
 * <p>엔티티에 setter 를 두지 않은 것만으로는 부족했다 - 그건 평범한 코드에서 필드를
 * 바꾸기 어렵게 할 뿐 UPDATE 나 DELETE 를 막지 못한다. 여기서 API 를 좁히고, DB
 * 트리거({@code skill_evidence_append_only})가 마지막으로 막는다.
 *
 * <p>재계산은 (사용자, Skill) 의 Evidence 를 <b>시간순으로</b> 훑는다. EMA 는 순서
 * 계산이므로 정렬이 정확해야 하고, 같은 시각이면 id 로 순서를 고정한다.
 */
public interface SkillEvidenceRepository extends Repository<SkillEvidenceRow, Long> {

    SkillEvidenceRow save(SkillEvidenceRow row);

    /** 테스트가 제약 위반을 즉시 관측하기 위해 필요하다. 저장 자체는 save 와 같다. */
    SkillEvidenceRow saveAndFlush(SkillEvidenceRow row);

    List<SkillEvidenceRow> findByUserIdAndSkillCodeOrderByOccurredAtAscIdAsc(
            Long userId, String skillCode);

    boolean existsByUserIdAndSourceEventIdAndSkillCode(
            Long userId, String sourceEventId, String skillCode);
}
