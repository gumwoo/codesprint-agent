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

    @Test
    @DisplayName("진단은 하루 시간보다 길어도 빠지지 않는다 - 다른 Skill 이 그 자리를 채우지 않는다")
    void diagnosisIsNeverSkipped() {
        // 검증 에이전트가 재현했다. 하루 10 분에 진단 문제가 20 분이면 진단이 빠지고 짧은 새 Skill
        // 이 들어가, 계획과 결과 패널이 다른 곳을 가리켰다.
        Map<String, Integer> cost = new java.util.HashMap<>(COST);
        cost.put("PROBED", 20);
        Plan plan = planner.plan(STATES, List.of(), "PROBED", cost, 10, null);
        assertThat(plan.blocks()).isNotEmpty();
        assertThat(plan.blocks().get(0).type()).isEqualTo(BlockType.DIAGNOSE);
        assertThat(skills(plan)).as("넘친 뒤에는 아무것도 더 넣지 않는다").containsExactly("PROBED");
        assertThat(plan.reason()).contains("넘는다").startsWith("진단");
    }

    @Test
    @DisplayName("만기 복습도 예산에 밀려 빠지지 않는다")
    void reviewIsNeverSkipped() {
        Map<String, Integer> cost = new java.util.HashMap<>(COST);
        cost.put("REVIEWED", 40);
        Plan plan = planner.plan(STATES, List.of("REVIEWED"), null, cost, 30, null);
        assertThat(plan.blocks().get(0).type()).isEqualTo(BlockType.REVIEW);
    }

    @Test
    @DisplayName("혼합 블록은 20 분을 넘지 않고, 남는 시간은 비워 둔다고 말한다")
    void mixedIsCapped() {
        Plan plan = planner.plan(STATES, List.of(), null, COST, 600, null);
        Block last = plan.blocks().get(plan.blocks().size() - 1);
        assertThat(last.type()).isEqualTo(BlockType.MIXED);
        assertThat(last.minutes()).isLessThanOrEqualTo(DailyPlanner.MAX_MIXED_MINUTES);
        assertThat(plan.reason()).contains("비워 둔다");
    }

    @Test
    @DisplayName("지난 시험은 없는 시험이다")
    void pastExamIsNoExam() {
        Plan plan = planner.plan(STATES, List.of(), null, COST, 60, -3);
        assertThat(plan.mode()).isEqualTo(Mode.NORMAL);
        assertThat(plan.examInDays()).isNull();
    }
    @Test
    @DisplayName("시험 모드에서 모의 시험을 볼 때면 복습 다음, 연습 앞에 모의 시험을 둔다 - 평소에는 두지 않는다")
    void examModeSchedulesAMockTest() {
        Plan plan = planner.plan(STATES, List.of("REVIEWED"), null, COST, 200, 3, 60);
        assertThat(plan.blocks()).extracting(Block::type)
                .startsWith(BlockType.REVIEW, BlockType.MOCK_TEST, BlockType.PRACTICE);
        Block mock = plan.blocks().get(1);
        assertThat(mock.skillCode()).as("모의 시험은 특정 Skill 이 아니다").isNull();
        assertThat(mock.minutes()).isEqualTo(60);
        assertThat(plan.reason()).contains("모의 시험");

        // 대조: 같은 입력에 시험이 없으면(평소) 모의 시험을 두지 않는다
        Plan normal = planner.plan(STATES, List.of("REVIEWED"), null, COST, 200, null, 60);
        assertThat(normal.blocks()).extracting(Block::type).doesNotContain(BlockType.MOCK_TEST);
        // 대조: 볼 때가 아니면(null) 시험 모드여도 두지 않는다
        Plan notDue = planner.plan(STATES, List.of("REVIEWED"), null, COST, 200, 3, null);
        assertThat(notDue.blocks()).extracting(Block::type).doesNotContain(BlockType.MOCK_TEST);
    }

    @Test
    @DisplayName("모의 시험이 하루 시간에 들어가지 않으면 넣지 않고, 이유가 넣은 것처럼 말하지 않는다")
    void mockTestThatDoesNotFitIsLeftOutHonestly() {
        Plan plan = planner.plan(STATES, List.of("REVIEWED"), null, COST, 40, 3, 60);
        assertThat(plan.blocks()).extracting(Block::type).doesNotContain(BlockType.MOCK_TEST);
        assertThat(plan.reason()).contains("모의 시험(60분)이 하루 시간에 들어가지 않아 넣지 못했다")
                .doesNotContain("모의 시험 ·").doesNotContain("· 모의 시험");

        // 하루 시간을 정하지 않았으면 시간을 나누지 않으므로 앞 블록으로 보여 준다
        Plan unbudgeted = planner.plan(STATES, List.of("REVIEWED"), null, COST, null, 3, 60);
        assertThat(unbudgeted.blocks()).extracting(Block::type)
                .startsWith(BlockType.REVIEW, BlockType.MOCK_TEST);
    }
}
