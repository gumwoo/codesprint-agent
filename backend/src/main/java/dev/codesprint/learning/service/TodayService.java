package dev.codesprint.learning.service;

import dev.codesprint.learning.domain.ActionType;
import dev.codesprint.learning.domain.DailyPlanner;
import dev.codesprint.learning.domain.DailyPlanner.BlockType;
import dev.codesprint.learning.domain.SkillState;
import dev.codesprint.learning.domain.SkillStatus;
import dev.codesprint.learning.persistence.ReviewScheduleRow;
import dev.codesprint.learning.persistence.UserRow;
import dev.codesprint.problem.ProblemCatalog;
import dev.codesprint.problem.ProblemCatalog.ProblemDefinition;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 오늘의 학습 계획과 대시보드 숫자. 정본: PRD §80 · §115 · §121, ADR-0038.
 *
 * <p>{@link DailyPlanner} 가 <b>무엇을</b> 할지 정하고, 여기서 그것을 <b>어느 문제로</b> 할지
 * 옮긴다. 문제는 다음 행동과 같은 규칙({@link NextProblemService})으로 고른다 - 계획이 주는
 * 문제와 결과 패널이 주는 문제가 다른 규칙으로 고르면 같은 Skill 에 다른 문제가 나온다.
 */
@Service
public class TodayService {

    private final MasteryService mastery;
    private final DailyPlanner planner;
    private final DiagnosticService diagnostic;
    private final ReviewScheduleService reviews;
    private final NextProblemService nextProblem;
    private final ProblemCatalog problems;
    private final Clock clock;

    public TodayService(MasteryService mastery, DailyPlanner planner, DiagnosticService diagnostic,
            ReviewScheduleService reviews, NextProblemService nextProblem, ProblemCatalog problems,
            Clock clock) {
        this.mastery = mastery;
        this.planner = planner;
        this.diagnostic = diagnostic;
        this.reviews = reviews;
        this.nextProblem = nextProblem;
        this.problems = problems;
        this.clock = clock;
    }

    /** 계획의 한 칸. problem 이 null 이면 MIXED 다. */
    public record Block(String type, String skillCode, ProblemDefinition problem, int minutes,
            String reason) {
    }

    /**
     * @param mastered 트랙 안에서 MASTERED 인 Skill 수(PRD §121 "Mastered Skills 37 / 126")
     * @param total 트랙 안 Skill 수
     */
    public record Today(String date, Integer examInDays, Integer totalMinutes, String mode,
            List<Block> blocks, String reason, int mastered, int total) {
    }

    @Transactional(readOnly = true)
    public Today today(UserRow user) {
        LocalDate date = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        Integer examInDays = user.examDate() == null ? null
                : (int) ChronoUnit.DAYS.between(date, user.examDate());
        if (examInDays != null && examInDays < 0) {
            // 지난 시험은 없는 시험이다. 음수를 넘기면 "시험 모드" 가 영원히 켜진다.
            examInDays = null;
        }

        Long userId = user.id();
        List<SkillState> states = mastery.statesOf(userId);
        DiagnosticService.Step step = diagnostic.nextStep(userId);
        String diagnosticSkill = step.done() || step.problem() == null ? null : step.targetSkill();
        List<String> due = reviews.due(userId).stream().map(ReviewScheduleRow::skillCode).toList();

        // 문제를 먼저 고른다. 줄 문제가 없는 Skill 은 계획에 넣지 않는다 - 갈 곳 없는 칸이 된다.
        Map<String, ProblemDefinition> picked = new LinkedHashMap<>();
        Map<String, Integer> cost = new LinkedHashMap<>();
        if (diagnosticSkill != null) {
            choose(userId, diagnosticSkill, ActionType.DIAGNOSTIC_PROBE, picked, cost);
        }
        for (String skill : due) {
            choose(userId, skill, ActionType.REVIEW_DUE, picked, cost);
        }
        for (SkillState state : states) {
            if (state.status() == SkillStatus.LOCKED || state.status() == SkillStatus.MASTERED) {
                continue;
            }
            choose(userId, state.skillCode(), state.evidenceCount() > 0
                    ? ActionType.RETRY_VARIANT : ActionType.UNLOCK_NEXT, picked, cost);
        }

        DailyPlanner.Plan plan = planner.plan(states, due, diagnosticSkill, cost,
                user.dailyMinutes(), examInDays);
        List<Block> blocks = new ArrayList<>();
        for (DailyPlanner.Block block : plan.blocks()) {
            blocks.add(new Block(block.type().name(), block.skillCode(),
                    block.type() == BlockType.MIXED ? null : picked.get(block.skillCode()),
                    block.minutes(), block.reason()));
        }
        int mastered = (int) states.stream()
                .filter(s -> s.status() == SkillStatus.MASTERED).count();
        return new Today(date.toString(), examInDays, plan.totalMinutes(), plan.mode().name(),
                blocks, plan.reason(), mastered, states.size());
    }

    private void choose(Long userId, String skill, ActionType action,
            Map<String, ProblemDefinition> picked, Map<String, Integer> cost) {
        if (picked.containsKey(skill)) {
            return;
        }
        String code = nextProblem.select(userId, action, skill, null).problemCode();
        ProblemDefinition problem = code == null ? null : problems.find(code);
        if (problem == null) {
            return;
        }
        picked.put(skill, problem);
        // 예상 풀이 시간을 분으로 올림한다. 1 분보다 짧게 잡지 않는다.
        int seconds = problem.expectedSolveSeconds() == null ? 600 : problem.expectedSolveSeconds();
        cost.put(skill, Math.max(1, (seconds + 59) / 60));
    }
}
