package dev.codesprint.learning.domain;

import dev.codesprint.curriculum.CurriculumCatalog;
import dev.codesprint.curriculum.CurriculumCatalog.Prerequisite;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 초기 진단 — <b>무엇을 물을지</b> 고른다. 정본: PRD §57 · §88, ADR-0018.
 *
 * <p><b>여기서 점수를 만들지 않는다.</b> 진단이 하는 일은 문제를 고르는 순서를 정하는
 * 것뿐이고, 푼 결과는 평소 제출과 같은 길(Judge → Evidence → {@link MasteryCalculator})
 * 을 지난다. 진단 전용 산식을 두면 한 Skill 의 mastery 를 정하는 길이 둘이 되고,
 * 둘은 반드시 갈린다(ADR-0018 §1).
 *
 * <p>그래서 이 클래스에는 <b>상태가 없다</b>. 진단 세션 테이블도 없다. 어디까지
 * 왔는지는 Evidence 에서 그대로 나온다 - mastery 가 파생값인 것과 같은 이유다
 * (ADR-0009). 저장하면 Evidence 와 어긋날 수 있고, 어긋나면 어느 쪽이 정본인지
 * 물어야 한다.
 *
 * <h2>분기</h2>
 *
 * <p>§57 의 분기형 진단에서 남는 것은 <b>전부 묻지 않는다</b> 는 발상이다. 슬라이스 1 은
 * 영역이 하나뿐이라 영역별 난이도 조절을 옮길 수 없지만, 선수 그래프가 같은 일을 한다.
 *
 * <pre>
 *   통과했다  →  그 선수들은 묻지 않는다   (함의)
 *   실패했다  →  선수로 내려간다           (원인)
 * </pre>
 */
@Component
public class DiagnosticPlanner {

    private final CurriculumCatalog catalog;

    public DiagnosticPlanner(CurriculumCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * 진단의 현재 상태.
     *
     * @param nextSkill 다음에 물을 Skill. 진단이 끝났으면 null 이다.
     * @param resolved 평가됐거나 함의된 Skill
     * @param remaining 아직 모르는 Skill
     * @param reason 왜 그것을 묻는가 / 왜 끝났는가. 화면과 감사 로그가 같이 읽는다.
     */
    public record Plan(String nextSkill, List<String> resolved, List<String> remaining,
            String reason) {

        public boolean done() {
            return nextSkill == null;
        }
    }

    /**
     * @param states {@code MasteryService.statesOf} 가 낸 전체 Skill 상태. 손대지 않은
     *     Skill 도 들어 있어야 한다 - 빠지면 그것을 "모른다" 로 셀 수 없다.
     */
    public Plan plan(List<SkillState> states) {
        Set<String> assessed = assessedSkills(states);
        Set<String> resolved = new LinkedHashSet<>(assessed);
        resolved.addAll(impliedSkills(states));
        List<String> remaining = states.stream()
                .map(SkillState::skillCode)
                .filter(code -> !resolved.contains(code))
                .sorted()
                .toList();

        if (remaining.isEmpty()) {
            // **끝났다는 말이 무엇을 뜻하는지 함께 말한다.** 함의된 Skill 은 Evidence 가
            // 없어서 Skill 지도에 여전히 mastery 없음으로 남는다 - 두 화면이 어긋나
            // 보이는데, 어긋난 것이 아니라 "묻지 않기로 했다" 와 "재 봤다" 가 다른
            // 것이다. 그 차이를 화면이 지어내지 않도록 여기서 적어 준다.
            int implied = resolved.size() - assessed.size();
            return new Plan(null, sorted(resolved), remaining,
                    implied == 0
                            ? assessed.size() + "개를 직접 확인했다"
                            : assessed.size() + "개를 직접 확인했고, " + implied
                                    + "개는 그 아래에 있어 함께 확인된 것으로 본다");
        }

        // 가장 많이 밝혀 주는 것을 먼저 묻는다. 통과하면 그 아래 선수들이 함께
        // 함의되므로, 아직 모르는 선수를 많이 거느린 Skill 일수록 한 문제가 답하는
        // 양이 크다. §57 이 "45개를 하나씩 주지 않는다" 고 한 것이 이 뜻이다.
        //
        // 동점이면 code 순. 진단이 사용자마다 달라지면 안 되는 것은 아니지만,
        // **같은 상태에서 같은 답** 이 나와야 재현하고 검사할 수 있다.
        String next = remaining.stream()
                .max(Comparator
                        .<String>comparingInt(code -> countUnresolvedBelow(code, resolved))
                        .thenComparing(Comparator.reverseOrder()))
                .orElseThrow();

        int informs = countUnresolvedBelow(next, resolved);
        return new Plan(next, sorted(resolved), remaining,
                informs > 1
                        ? "통과하면 선수 " + (informs - 1) + "개까지 함께 확인된다"
                        : "남은 것 중 이것부터 확인한다");
    }

    /**
     * 실제로 재 본 Skill. Evidence 가 있다는 뜻이다 - 통과했든 아니든 물어본 것은
     * 답을 얻었다.
     */
    private static Set<String> assessedSkills(List<SkillState> states) {
        Set<String> assessed = new LinkedHashSet<>();
        for (SkillState state : states) {
            if (state.evidenceCount() > 0) {
                assessed.add(state.skillCode());
            }
        }
        return assessed;
    }

    /**
     * 재 보지는 않았지만 묻지 않기로 한 Skill.
     *
     * <p>함의는 <b>충분히 잘했을 때만</b> 이다. 못 푼 Skill 은 그 아래 어디서 막혔는지
     * 모르므로 선수를 안다고 칠 수 없다 - 그게 "실패하면 내려간다" 다.
     *
     * <p><b>이것은 점수가 아니다.</b> 함의된 Skill 에는 Evidence 가 없고, 따라서
     * mastery 도 생기지 않는다(ADR-0018 §1). 진단이 정하는 것은 무엇을 묻지 않을지지
     * 얼마나 하는지가 아니다.
     */
    private Set<String> impliedSkills(List<SkillState> states) {
        Set<String> implied = new LinkedHashSet<>();
        for (SkillState state : states) {
            if (state.mastery() != null) {
                implied.addAll(impliedBy(state.skillCode(), state.mastery()));
            }
        }
        return implied;
    }

    /**
     * 이 Skill 을 이만큼 해냈다면 함께 확인된 것으로 볼 선수들.
     *
     * <p>문턱은 <b>간선이 이미 갖고 있는 값</b>을 쓴다. {@code X requires P (min 0.65)}
     * 는 "X 를 하려면 P 가 0.65 는 되어야 한다" 는 뜻이므로, X 를 0.65 이상으로 해냈다면
     * P 는 그 바에 도달해 있다고 본다.
     *
     * <p><b>진단 전용 상수를 만들지 않는다.</b> 만들면 "이 정도면 됐다" 는 값이 저장소에
     * 둘이 되고, {@code prerequisites.yaml} 을 고쳐도 진단만 옛 기준으로 남는다.
     *
     * <p>문턱을 넘지 못한 간선에서 <b>멈춘다</b>. 그 아래로 계속 내려가면 확인되지 않은
     * 것을 근거로 더 깊은 것을 확인하게 된다.
     */
    private Set<String> impliedBy(String skillCode, double mastery) {
        Set<String> implied = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(List.of(skillCode));
        while (!queue.isEmpty()) {
            for (Prerequisite edge : catalog.prerequisitesOf(queue.poll())) {
                if (mastery >= edge.minimumMastery() && implied.add(edge.requires())) {
                    queue.add(edge.requires());
                }
            }
        }
        return implied;
    }

    /** 이 Skill 아래의 선수 전부(이행적). 자기 자신은 넣지 않는다. */
    private Set<String> prerequisitesBelow(String skillCode) {
        Set<String> below = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(List.of(skillCode));
        while (!queue.isEmpty()) {
            for (Prerequisite prerequisite : catalog.prerequisitesOf(queue.poll())) {
                if (below.add(prerequisite.requires())) {
                    queue.add(prerequisite.requires());
                }
            }
        }
        return below;
    }

    /** 이것을 물어서 새로 알게 되는 Skill 수 - 자기 자신 + 아직 모르는 선수들. */
    private int countUnresolvedBelow(String skillCode, Set<String> resolved) {
        int count = 1;
        for (String below : prerequisitesBelow(skillCode)) {
            if (!resolved.contains(below)) {
                count++;
            }
        }
        return count;
    }

    private static List<String> sorted(Set<String> codes) {
        List<String> list = new ArrayList<>(codes);
        list.sort(Comparator.naturalOrder());
        return list;
    }

}
