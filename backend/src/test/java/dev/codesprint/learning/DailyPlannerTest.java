package dev.codesprint.learning;

import static org.assertj.core.api.Assertions.assertThat;

import dev.codesprint.learning.domain.DailyPlanner;
import dev.codesprint.learning.domain.DailyPlanner.Block;
import dev.codesprint.learning.domain.DailyPlanner.BlockType;
import dev.codesprint.learning.domain.DailyPlanner.Mode;
import dev.codesprint.learning.domain.DailyPlanner.Plan;
import dev.codesprint.learning.domain.Dimension;
import dev.codesprint.learning.domain.SkillState;
import dev.codesprint.learning.domain.SkillStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 오늘의 계획. 정본: PRD §80 · §81 · §82, ADR-0038.
 *
 * <p>순수 함수라 Spring 도 DB 도 없다.
 */
class DailyPlannerTest {

    private final DailyPlanner planner = new DailyPlanner();

    private static SkillState state(String code, Double mastery, SkillStatus status) {
        return new SkillState(code,
                mastery == null ? Map.of() : Map.of(Dimension.IMPLEMENTATION, mastery),
                mastery, mastery == null ? 0.0 : 0.5, mastery == null ? 0 : 3, status);
    }

    private static final Map<String, Integer> COST = Map.of(
            "WEAK", 10, "STRONG", 10, "NEW_A", 5, "NEW_B", 15, "LOCKED_ONE", 10,
            "DONE", 10, "REVIEWED", 10, "PROBED", 10);

    private static final List<SkillState> STATES = List.of(
            state("WEAK", 0.30, SkillStatus.PRACTICING),
            state("STRONG", 0.80, SkillStatus.PRACTICING),
            state("NEW_A", null, SkillStatus.READY),
            state("NEW_B", null, SkillStatus.READY),
            state("LOCKED_ONE", null, SkillStatus.LOCKED),
            state("DONE", 0.95, SkillStatus.MASTERED),
            state("NO_PROBLEM", 0.20, SkillStatus.PRACTICING));

    private static List<String> skills(Plan plan) {
        return plan.blocks().stream().map(Block::skillCode).toList();
    }

    @Test
    @DisplayName("진단 · 복습 · 연습 · 새로 배우기 순서로 채우고 남은 시간은 혼합이다")
    void blocksFollowTheOrder() {
        Plan plan = planner.plan(STATES, List.of("REVIEWED"), "PROBED", COST, 55, null);

        assertThat(plan.blocks()).extracting(Block::type).containsExactly(
                BlockType.DIAGNOSE, BlockType.REVIEW, BlockType.PRACTICE, BlockType.LEARN,
                BlockType.PRACTICE, BlockType.MIXED);
        // 연습은 약한 것부터, 새로 배우기는 짧은 것부터.
        assertThat(skills(plan)).containsExactly("PROBED", "REVIEWED", "WEAK", "NEW_A", "STRONG",
                null);
        // NEW_B(15분)는 들어가지 않고, 남은 10분이 혼합이다.
        assertThat(plan.blocks().stream().mapToInt(Block::minutes).sum()).isEqualTo(55);
        assertThat(plan.mode()).isEqualTo(Mode.NORMAL);
    }

    @Test
    @DisplayName("잠긴 Skill · 숙달한 Skill · 줄 문제가 없는 Skill 은 넣지 않는다")
    void excluded() {
        Plan plan = planner.plan(STATES, List.of(), null, COST, 600, null);
        assertThat(skills(plan)).doesNotContain("LOCKED_ONE", "DONE", "NO_PROBLEM");
    }

    @Test
    @DisplayName("시간이 모자라면 들어가는 블록만 넣는다")
    void budgetIsRespected() {
        Plan plan = planner.plan(STATES, List.of(), null, COST, 20, null);
        assertThat(plan.blocks().stream().mapToInt(Block::minutes).sum()).isLessThanOrEqualTo(20);
        assertThat(skills(plan)).containsExactly("WEAK", "NEW_A");
    }

    @Test
    @DisplayName("하루 공부 시간을 모르면 시간을 어림하지 않고 앞의 몇 블록만 준다")
    void unknownDailyMinutes() {
        Plan plan = planner.plan(STATES, List.of(), null, COST, null, null);
        assertThat(plan.totalMinutes()).isNull();
        assertThat(plan.blocks()).hasSize(DailyPlanner.UNBUDGETED_BLOCKS)
                .extracting(Block::type).doesNotContain(BlockType.MIXED);
    }

    @Test
    @DisplayName("시험이 7일 이하로 남으면 새로 배우지 않고 가까운 것부터 굳힌다")
    void examModeConsolidates() {
        Plan plan = planner.plan(STATES, List.of(), null, COST, 60, 3);
        assertThat(plan.mode()).isEqualTo(Mode.EXAM);
        assertThat(plan.blocks()).extracting(Block::type).doesNotContain(BlockType.LEARN);
        // 대조군: 평소에는 약한 것(WEAK)이 먼저, 시험 직전에는 가까운 것(STRONG)이 먼저다.
        assertThat(skills(plan).get(0)).isEqualTo("STRONG");
        assertThat(skills(planner.plan(STATES, List.of(), null, COST, 60, 8)).get(0))
                .as("8일 남으면 평소 모드").isEqualTo("WEAK");
    }

    @Test
    @DisplayName("같은 Skill 을 두 번 넣지 않는다 - 복습이면 연습으로 다시 넣지 않는다")
    void noDuplicateSkills() {
        Plan plan = planner.plan(STATES, List.of("WEAK"), null, COST, 600, null);
        assertThat(skills(plan).stream().filter("WEAK"::equals).count()).isEqualTo(1);
        assertThat(plan.blocks().get(0).type()).isEqualTo(BlockType.REVIEW);
    }
}
