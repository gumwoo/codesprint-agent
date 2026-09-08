package dev.codesprint.learning.domain;

import dev.codesprint.curriculum.CurriculumCatalog;
import dev.codesprint.curriculum.CurriculumCatalog.Prerequisite;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 한 Skill 을 숙달한 뒤 어디로 갈 것인가. 정본: ADR-0022.
 *
 * <p>PRD §75 는 {@code UNLOCK_NEXT} 라는 행동만 정하고 <b>무엇을 고를지는 정하지
 * 않는다.</b> 그래서 규칙을 여기서 세운다.
 *
 * <p>순수 함수다. 상태도 시간도 보지 않는다 — {@link DecisionEngine} 과 같은 이유로,
 * 규칙 하나하나가 단위 테스트 대상이어야 한다.
 */
@Component
public class NextSkillSelector {

    private final CurriculumCatalog catalog;
    private final PrerequisiteEvaluator prerequisites;

    public NextSkillSelector(CurriculumCatalog catalog, PrerequisiteEvaluator prerequisites) {
        this.catalog = catalog;
        this.prerequisites = prerequisites;
    }

    /**
     * 방금 {@code masteredSkill} 을 숙달했다. 다음은 어디인가.
     *
     * <h2>고르는 순서</h2>
     *
     * <ol>
     *   <li><b>이번 숙달이 실제로 연 Skill.</b> 그 Skill 을 선수로 요구하면서, 이제
     *       모든 선수를 채운 것. 이것이 {@code UNLOCK_NEXT} 라는 이름 그대로의 뜻이다
     *   <li>없으면 <b>지금 할 수 있는 것 중 가장 뒤처진 것.</b> 그래프의 끝을 숙달했거나,
     *       열린 것이 이미 다 숙달됐을 때다
     *   <li>그것도 없으면 비어 있다 — 커리큘럼에 남은 것이 없다
     * </ol>
     *
     * <p>1번에서 여럿이 열리면 code 순으로 고른다. <b>여기서 정보 이득 같은 것을
     * 따지지 않는다</b> — 진단은 모르는 것을 채우는 일이라 그 기준이 맞지만, 여기서는
     * 어느 쪽이든 배워야 하는 것이라 순서에 의미를 부여하면 없는 근거를 만드는 셈이다.
     *
     * @param states 전체 Skill 상태. 손대지 않은 Skill 도 들어 있어야 한다.
     * @return 다음 Skill. 남은 것이 없으면 비어 있다.
     */
    public Optional<String> after(String masteredSkill, List<SkillState> states) {
        Map<String, SkillState> byCode = new HashMap<>();
        Map<String, Double> masteries = new HashMap<>();
        for (SkillState state : states) {
            byCode.put(state.skillCode(), state);
            masteries.put(state.skillCode(), state.mastery());
        }

        Optional<String> opened = catalog.allPrerequisites().stream()
                .filter(edge -> edge.requires().equals(masteredSkill))
                .map(Prerequisite::skillCode)
                .distinct()
                .filter(code -> notMastered(byCode.get(code)))
                .filter(code -> prerequisites.unmet(code, masteries).isEmpty())
                .min(Comparator.naturalOrder());
        if (opened.isPresent()) {
            return opened;
        }

        // 그래프의 끝이거나, 열린 것이 이미 다 숙달됐다. 그래도 보낼 곳은 있다 -
        // 선수를 채웠는데 아직 숙달하지 못한 Skill 이 남아 있으면 그쪽이다.
        //
        // **가장 뒤처진 것부터** 고른다. PrerequisiteEvaluator 가 막힌 선수를 고를 때
        // 쓰는 기준과 같다 - 잘하는 것을 더 잘하게 만드는 것보다 못하는 것을 끌어올리는
        // 편이 전체 잠금 해제까지의 거리를 줄인다.
        return states.stream()
                .filter(state -> !state.skillCode().equals(masteredSkill))
                .filter(state -> notMastered(state))
                .filter(state -> prerequisites.unmet(state.skillCode(), masteries).isEmpty())
                .min(Comparator
                        .<SkillState>comparingDouble(
                                state -> state.mastery() == null ? 0.0 : state.mastery())
                        .thenComparing(SkillState::skillCode))
                .map(SkillState::skillCode);
    }

    /**
     * 아직 숙달하지 않았는가.
     *
     * <p>목록에 없는 Skill 은 숙달하지 않은 것으로 본다 — 그런 값이 오면 커리큘럼과
     * 상태가 어긋난 것이고, 그때 "숙달했다" 로 치면 사용자를 조용히 건너뛰게 한다.
     */
    private static boolean notMastered(SkillState state) {
        return state == null || state.status() != SkillStatus.MASTERED;
    }
}
