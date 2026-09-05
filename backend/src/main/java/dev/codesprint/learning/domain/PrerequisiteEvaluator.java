package dev.codesprint.learning.domain;

import dev.codesprint.curriculum.CurriculumCatalog;
import dev.codesprint.curriculum.CurriculumCatalog.Prerequisite;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 선수 관계로 {@code LOCKED} / {@code READY} 를 정한다. 정본: Addendum §33, §76.
 *
 * <p>이 판정은 {@link MasteryCalculator} 가 하지 못한다. Evidence 만 봐서는 "앞 Skill 을
 * 충분히 했는가" 를 알 수 없기 때문이다(ADR-0009). 그래서 두 계산을 나눠 뒀다.
 *
 * <pre>
 *   MasteryCalculator      Evidence  → UNASSESSED / LEARNING / PRACTICING / MASTERED / WEAKENED
 *   PrerequisiteEvaluator  선수 관계 → LOCKED / READY
 * </pre>
 *
 * <p>둘을 합치는 규칙은 {@link #resolve}에 있다. <b>이미 배우기 시작한 Skill 은 잠그지
 * 않는다</b> — 다른 문제의 SECONDARY Skill 로 Evidence 가 쌓여 선수 조건보다 먼저
 * 진도가 나간 경우가 있고, 그때 화면에서 "잠김" 으로 되돌리면 사용자는 자기가 푼 것이
 * 사라진 것으로 본다.
 */
@Component
public class PrerequisiteEvaluator {

    private final CurriculumCatalog catalog;

    public PrerequisiteEvaluator(CurriculumCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * 아직 채우지 못한 선수 조건들. 비어 있으면 이 Skill 을 시작할 수 있다.
     *
     * @param masteries Skill code → mastery. 값이 없거나 null 이면 아직 평가되지 않은 것이며
     *     선수 조건을 채우지 못한 것으로 본다.
     */
    public List<Prerequisite> unmet(String skillCode, Map<String, Double> masteries) {
        return catalog.prerequisitesOf(skillCode).stream()
                .filter(p -> {
                    Double mastery = masteries.get(p.requires());
                    return mastery == null || mastery < p.minimumMastery();
                })
                .toList();
    }

    /**
     * 다음으로 채워야 할 선수 Skill 하나. Decision Engine 이 {@code CHANGE_SKILL} 의
     * 대상으로 쓴다.
     *
     * <p>여러 개가 남아 있으면 <b>가장 뒤처진 것</b>을 고른다. 진도가 덜 나간 쪽을 먼저
     * 하는 편이 전체 잠금 해제까지의 거리를 줄인다.
     */
    public Optional<String> nextPrerequisite(String skillCode, Map<String, Double> masteries) {
        return unmet(skillCode, masteries).stream()
                .min((a, b) -> Double.compare(
                        masteries.getOrDefault(a.requires(), 0.0),
                        masteries.getOrDefault(b.requires(), 0.0)))
                .map(Prerequisite::requires);
    }

    /**
     * Evidence 기반 상태와 선수 관계를 합쳐 최종 status 를 정한다.
     *
     * @param evidenceStatus {@link MasteryCalculator#recompute} 가 낸 상태
     */
    public SkillStatus resolve(String skillCode, SkillStatus evidenceStatus,
            Map<String, Double> masteries) {

        boolean blocked = !unmet(skillCode, masteries).isEmpty();

        // 이미 배우기 시작했으면 선수 관계로 되돌리지 않는다.
        // Evidence 가 있다는 것은 사용자가 실제로 그 Skill 을 다뤘다는 뜻이고,
        // 그것을 "잠김" 으로 표시하면 푼 것이 사라진 것처럼 보인다.
        if (evidenceStatus != SkillStatus.UNASSESSED) {
            return evidenceStatus;
        }

        return blocked ? SkillStatus.LOCKED : SkillStatus.READY;
    }
}
