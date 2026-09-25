package dev.codesprint.learning.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 오늘의 학습 계획. 정본: PRD §80(Daily Planner) · §81(우선순위) · §82(시험 직전 모드), ADR-0038.
 *
 * <p><b>LLM 을 부르지 않는다.</b> 무엇을 얼마나 할지는 다음 행동과 같은 종류의 결정이라
 * 시스템이 정한다(ADR-0001, ADR-0002). 순수 함수다 - 같은 입력에 같은 계획이 나와야 사용자가
 * 왜 이 계획을 받았는지 설명할 수 있다.
 *
 * <h2>우선순위 (§81)</h2>
 *
 * <pre>
 *   Priority = GoalImportance × Weakness × Learnability × ExamFrequency × RetentionNeed
 *              ÷ EstimatedStudyCost
 * </pre>
 *
 * <p>여섯 항 중 <b>잴 수 있는 것만 쓴다</b>(CLAUDE.md "측정할 수 없는 것은 측정하지 않는다").
 *
 * <ul>
 *   <li>GoalImportance - 연습 · 새로 배우기는 트랙 안 Skill 만 본다(입력 states 가 트랙 범위다).
 *       만기 복습은 트랙과 상관없이 넣는다 - 이미 배운 것의 유지다(ADR-0035 남는 위험)
 *   <li>Weakness - {@code 1 - mastery}. 재 본 적 없으면 계획의 "새로 배우기" 쪽으로 간다
 *   <li>Learnability - 선수를 채웠는가. LOCKED 면 넣지 않는다
 *   <li>ExamFrequency - <b>쓰지 않는다.</b> 이 저장소에는 출제 빈도 데이터가 없다. 어림한 값을
 *       넣으면 근거 없는 숫자가 순서를 정한다
 *   <li>RetentionNeed - 만기 복습은 맨 앞에 둔다(ADR-0021)
 *   <li>EstimatedStudyCost - 그 Skill 문제의 {@code expectedSolveSeconds}
 * </ul>
 */
@Component
public class DailyPlanner {

    /** 이 날짜 이하로 남으면 시험 모드다(PRD §82 "D-7 이하"). */
    public static final int EXAM_MODE_DAYS = 7;

    /** 남은 시간이 이것보다 짧으면 혼합 블록을 만들지 않는다. */
    public static final int MIN_MIXED_MINUTES = 10;

    /**
     * 혼합 블록의 최대 길이. PRD §80 · §121 의 예가 10~20 분이다. 줄 문제가 없는 칸으로 하루
     * 대부분을 채우면 할 일이 없는 계획이 된다 - 새 사용자에게 720 분을 주면 691 분이 혼합이었다
     * (검증 에이전트). 남는 시간은 배정하지 않고 그렇다고 말한다.
     */
    public static final int MAX_MIXED_MINUTES = 20;

    /** 하루 공부 시간을 정하지 않았을 때 보여 줄 블록 수. 시간을 어림하지 않는다. */
    public static final int UNBUDGETED_BLOCKS = 3;

    public enum BlockType { DIAGNOSE, REVIEW, PRACTICE, LEARN, MIXED }

    public enum Mode { NORMAL, EXAM }

    /**
     * @param skillCode MIXED 이면 null 이다
     * @param minutes 이 블록에 쓸 시간. 그 Skill 문제의 예상 풀이 시간이다
     * @param reason 왜 이 블록인가. 화면이 그대로 보여 준다
     */
    public record Block(BlockType type, String skillCode, int minutes, String reason) {
    }

    /**
     * @param totalMinutes 하루 공부 시간. 정하지 않았으면 null - 그때는 시간을 채우지 않고
     *     앞의 몇 블록만 순서대로 준다
     * @param examInDays 시험까지 남은 날. 시험일이 없거나 지났으면 null
     */
    public record Plan(Integer totalMinutes, Integer examInDays, Mode mode, List<Block> blocks,
            String reason) {
    }

    /**
     * @param states 사용자의 트랙 범위 Skill 상태({@code MasteryService.statesOf})
     * @param dueReviews 지금 만기인 복습 Skill. 만기가 먼저 온 순서다
     * @param diagnosticSkill 진단이 아직 묻고 있는 Skill. 끝났으면 null
     * @param costMinutes Skill → 그 Skill 첫 NORMAL 문제의 예상 풀이 시간(분). 문제가 없는
     *     Skill 은 들어 있지 않다 - 줄 문제가 없으면 계획에도 넣지 않는다
     * @param dailyMinutes 하루 공부 시간. 모르면 null
     * @param examInDays 시험까지 남은 날. 모르면 null
     */
    public Plan plan(List<SkillState> states, List<String> dueReviews, String diagnosticSkill,
            Map<String, Integer> costMinutes, Integer dailyMinutes, Integer examInDays) {

        if (examInDays != null && examInDays < 0) {
            // 지난 시험은 없는 시험이다. 서비스가 이미 걸러 주지만 이 함수만 봐도 맞아야 한다.
            examInDays = null;
        }
        Mode mode = examInDays != null && examInDays <= EXAM_MODE_DAYS ? Mode.EXAM : Mode.NORMAL;

        List<Block> candidates = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();

        // 진단이 끝나지 않았으면 다음 행동을 진단이 가진다(ADR-0019). 계획도 같은 곳을 먼저 가리킨다 -
        // 두 화면이 다른 곳을 가리키면 사용자는 어느 쪽을 따를지 모른다.
        if (diagnosticSkill != null && costMinutes.containsKey(diagnosticSkill)) {
            candidates.add(new Block(BlockType.DIAGNOSE, diagnosticSkill,
                    costMinutes.get(diagnosticSkill), "진단이 아직 이 Skill 을 확인하지 못했다"));
            used.add(diagnosticSkill);
        }

        for (String skill : dueReviews) {
            if (costMinutes.containsKey(skill) && used.add(skill)) {
                candidates.add(new Block(BlockType.REVIEW, skill, costMinutes.get(skill),
                        "복습 만기가 됐다"));
            }
        }

        List<SkillState> practice = new ArrayList<>();
        List<SkillState> learn = new ArrayList<>();
        for (SkillState state : states) {
            if (used.contains(state.skillCode()) || !costMinutes.containsKey(state.skillCode())
                    || state.status() == SkillStatus.LOCKED
                    || state.status() == SkillStatus.MASTERED) {
                continue;
            }
            if (state.evidenceCount() > 0) {
                practice.add(state);
            } else {
                learn.add(state);
            }
        }

        // 연습: 약한 것부터, 같은 약함이면 짧은 것부터. 시험 모드에서는 반대로 **이미 가까운 것**
        // 부터다 - §82 "점수 상승 기대값 최대화", §83 ROI(얻는 것 ÷ 드는 시간).
        Comparator<SkillState> byNeed = mode == Mode.NORMAL
                ? Comparator.<SkillState>comparingDouble(
                        s -> -weakness(s) / costMinutes.get(s.skillCode()))
                : Comparator.<SkillState>comparingDouble(
                        s -> -mastery(s) / costMinutes.get(s.skillCode()));
        practice.sort(byNeed.thenComparing(SkillState::skillCode));
        learn.sort(Comparator.<SkillState>comparingInt(s -> costMinutes.get(s.skillCode()))
                .thenComparing(SkillState::skillCode));

        // 연습과 새로 배우기를 번갈아 둔다. 새 Skill 만 쌓으면 배운 것이 굳지 않고, 연습만
        // 하면 범위가 넓어지지 않는다. 시험 모드에서는 새로 배우지 않는다(§82).
        int p = 0;
        int l = 0;
        while (p < practice.size() || (mode == Mode.NORMAL && l < learn.size())) {
            if (p < practice.size()) {
                SkillState s = practice.get(p++);
                candidates.add(new Block(BlockType.PRACTICE, s.skillCode(),
                        costMinutes.get(s.skillCode()), mode == Mode.NORMAL
                                ? "mastery " + format(s.mastery()) + " - 아직 숙달 전이다"
                                : "mastery " + format(s.mastery()) + " - 시험 전에 굳힐 수 있다"));
            }
            if (mode == Mode.NORMAL && l < learn.size()) {
                SkillState s = learn.get(l++);
                candidates.add(new Block(BlockType.LEARN, s.skillCode(),
                        costMinutes.get(s.skillCode()), "선수를 채웠고 아직 시작하지 않았다"));
            }
        }

        String how = (diagnosticSkill != null && !candidates.isEmpty()
                        && candidates.get(0).type() == BlockType.DIAGNOSE
                ? "진단을 먼저 하고, " : "")
                + (mode == Mode.EXAM ? "시험이 " + examInDays + "일 남아 새로 배우지 않고 굳힌다"
                        : "복습 · 연습 · 새로 배우기 순서로 채웠다");

        if (dailyMinutes == null) {
            return new Plan(null, examInDays, mode,
                    List.copyOf(candidates.subList(0, Math.min(UNBUDGETED_BLOCKS, candidates.size()))),
                    how + " - 하루 공부 시간을 정하지 않아 시간을 나누지 않았다");
        }

        // 진단과 만기 복습은 **예산과 상관없이** 맨 앞에 둔다. 들어가지 않는다고 건너뛰면 그
        // 자리를 우선순위가 낮은 블록이 채우고, 계획이 결과 패널과 다른 곳을 가리킨다 - 하루
        // 10 분인 새 사용자에게 진단(20 분) 대신 다른 Skill 이 나왔다(검증 에이전트).
        List<Block> blocks = new ArrayList<>();
        int left = dailyMinutes;
        for (Block block : candidates) {
            boolean mandatory = block.type() == BlockType.DIAGNOSE || block.type() == BlockType.REVIEW;
            if (mandatory || block.minutes() <= left) {
                blocks.add(block);
                left -= block.minutes();
            }
        }
        if (left < 0) {
            how += " - 진단 · 복습만으로 하루 시간을 넘는다";
        }
        if (left >= MIN_MIXED_MINUTES) {
            int mixed = Math.min(left, MAX_MIXED_MINUTES);
            blocks.add(new Block(BlockType.MIXED, null, mixed,
                    "유형을 모르는 문제로 고르는 연습을 한다"));
            if (left > mixed) {
                how += " - 남은 " + (left - mixed) + "분은 지금 줄 문제가 없어 비워 둔다";
            }
        }
        return new Plan(dailyMinutes, examInDays, mode, List.copyOf(blocks), how);
    }

    private static double weakness(SkillState state) {
        return 1.0 - mastery(state);
    }

    private static double mastery(SkillState state) {
        return state.mastery() == null ? 0.0 : state.mastery();
    }

    private static String format(Double mastery) {
        return mastery == null ? "–" : String.format("%.2f", mastery);
    }
}
