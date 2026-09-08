package dev.codesprint.learning.service;

import dev.codesprint.learning.domain.DiagnosticPlanner;
import dev.codesprint.learning.domain.SkillState;
import dev.codesprint.learning.domain.DiagnosticPlanner.Plan;
import dev.codesprint.problem.ProblemCatalog;
import dev.codesprint.problem.ProblemCatalog.ProblemDefinition;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 초기 진단을 문제로 옮긴다. 정본: ADR-0018.
 *
 * <p>{@link DiagnosticPlanner} 가 <b>어떤 Skill</b> 을 물을지 정하고, 여기서 그 Skill 의
 * 문제를 고른다. {@link NextProblemService} 와 같은 역할 분담이다 - 결정과 그 결정을
 * 문제로 옮기는 일을 섞지 않는다.
 *
 * <p><b>진단 세션을 저장하지 않는다.</b> 어디까지 왔는지는 Evidence 에서 나오므로,
 * 따로 적어 두면 Evidence 와 어긋날 수 있고 그때 어느 쪽이 정본인지 물어야 한다
 * (ADR-0009). 사용자가 중간에 창을 닫아도 다시 열면 같은 자리에서 이어진다.
 */
@Service
public class DiagnosticService {

    private final MasteryService mastery;
    private final DiagnosticPlanner planner;
    private final ProblemCatalog problems;

    public DiagnosticService(MasteryService mastery, DiagnosticPlanner planner,
            ProblemCatalog problems) {
        this.mastery = mastery;
        this.planner = planner;
        this.problems = problems;
    }

    /**
     * @param problem 지금 풀 문제. 진단이 끝났으면 null 이다.
     * @param assessed 이미 평가됐거나 함의된 Skill 수
     * @param total 활성 Skill 수. 둘을 함께 주어 화면이 "몇 개 남았는지" 를 계산하지
     *     않게 한다 - 화면은 서버가 정한 것을 보여주기만 한다(ADR-0001).
     */
    public record Step(String targetSkill, ProblemDefinition problem, boolean done,
            int assessed, int total, String reason) {
    }

    @Transactional(readOnly = true)
    public Step nextStep(Long userId) {
        return nextStep(mastery.statesOf(userId));
    }

    /**
     * 이미 계산한 상태로 묻는다.
     *
     * <p>상태 재계산은 활성 Skill 수만큼 조회를 낸다(ADR-0009). 한 번의 결과 반영에서
     * 진단과 다음 Skill 선택이 각각 다시 계산하면 그 비용이 두 배가 되고, 더 나쁘게는
     * <b>한 응답 안에서 서로 다른 시점의 상태를 보게 된다.</b>
     */
    public Step nextStep(List<SkillState> states) {
        Plan plan = planner.plan(states);
        int total = plan.resolved().size() + plan.remaining().size();

        if (plan.done()) {
            return new Step(null, null, true, plan.resolved().size(), total, plan.reason());
        }

        // NORMAL 만 고른다. MICRO_DRILL 은 이미 확정된 실수를 좁게 다시 다루는 것이라
        // 아직 아무것도 모르는 사용자의 수준을 재지 못한다.
        List<ProblemDefinition> candidates =
                problems.byPrimarySkill(plan.nextSkill(), "NORMAL");
        if (candidates.isEmpty()) {
            // 진단이 물을 수 없는 Skill 이다. 여기서 멈추면 사용자는 시작점을 못 받으므로
            // **끝난 것으로 치지 않고** 그 사실을 그대로 알린다.
            return new Step(plan.nextSkill(), null, false, plan.resolved().size(), total,
                    "이 Skill 을 물어볼 NORMAL 문제가 없다: " + plan.nextSkill());
        }
        return new Step(plan.nextSkill(), candidates.get(0), false,
                plan.resolved().size(), total, plan.reason());
    }
}
