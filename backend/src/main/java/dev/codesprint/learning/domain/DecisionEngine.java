package dev.codesprint.learning.domain;

import dev.codesprint.curriculum.CurriculumCatalog;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 다음 학습 행동을 정한다. 정본: Addendum §43, PRD §75~76.
 *
 * <p><b>LLM 에게 묻지 않는다</b>(ADR-0002). Reviewer 는 오답 원인 후보와 confidence 까지만
 * 내고, 그 Mistake 를 확정할지조차 시스템이 판단한다(Addendum §21). 여기 있는 규칙이
 * 유일한 결정 주체다.
 *
 * <p>순수 함수다. 같은 입력에는 같은 출력이 나오고, 상태도 시간도 보지 않는다.
 * 규칙 하나하나가 단위 테스트 대상이다(Addendum §86).
 */
@Component
public class DecisionEngine {

    private final CurriculumCatalog catalog;
    private final PrerequisiteEvaluator prerequisites;

    public DecisionEngine(CurriculumCatalog catalog, PrerequisiteEvaluator prerequisites) {
        this.catalog = catalog;
        this.prerequisites = prerequisites;
    }

    /**
     * 결정에 필요한 것 전부.
     *
     * @param judgeStatus Judge 판정. AI 가 관여하지 않은 결정론적 값이다.
     *     아직 끝나지 않은 판정({@code QUEUED} / {@code RUNNING})은 받지 않는다.
     * @param confirmedMistake <b>확정된</b> Mistake code. Reviewer 가 낸 후보가 아니라
     *     Addendum §21 의 조건을 통과한 것만 들어온다. 없으면 null.
     * @param state <b>이번 제출을 반영한 뒤</b>의 상태. 서비스 계층은 Evidence 를 저장하고
     *     mastery 를 재계산한 결과를 넘긴다.
     * @param priorEvidenceCount <b>이번 제출 전</b>에 이 Skill 에 쌓여 있던 Evidence 개수.
     *     "이 Skill 을 이미 배우고 있었는가" 는 {@code state.evidenceCount()} 로 알 수 없다 -
     *     실제 흐름이 Judge -> Evidence 저장 -> 재계산 -> Decision 이라서, Decision 이 보는
     *     시점에는 이번 실패가 이미 1개로 세어져 있다. 그 값으로 판단하면 선수 조건을
     *     못 채운 Skill 이 첫 제출에서 바로 선수 검사를 통과해버린다.
     * @param sameProblemAttempts 이 문제를 몇 번째 시도하는가 (이번 제출 포함).
     * @param reviewCompleted 이 Skill 에 복습 성공 기록이 있는가.
     * @param masteries 다른 Skill 들의 mastery. 선수 조건 판정에 쓴다.
     */
    public record Context(
            String skillCode,
            SkillState state,
            int priorEvidenceCount,
            JudgeStatus judgeStatus,
            String confirmedMistake,
            int sameProblemAttempts,
            boolean reviewCompleted,
            Map<String, Double> masteries) {

        public Context {
            if (priorEvidenceCount < 0) {
                throw new IllegalArgumentException(
                        "priorEvidenceCount 는 음수일 수 없다: " + priorEvidenceCount);
            }
            // 제출 전 개수가 반영 후보다 많을 수는 없다. 그런 값이 들어왔다면 서비스
            // 계층이 순서를 잘못 엮은 것이고, 조용히 넘기면 선수 검사가 사라진다.
            if (state != null && priorEvidenceCount > state.evidenceCount()) {
                throw new IllegalArgumentException(
                        "priorEvidenceCount(" + priorEvidenceCount + ") 가 반영 후 개수("
                                + state.evidenceCount() + ") 보다 많다");
            }
        }

        /** 이번 제출 전에 이미 이 Skill 을 배우고 있었는가. */
        public boolean startedBeforeAttempt() {
            return priorEvidenceCount > 0;
        }
    }

    public NextAction decide(Context context) {
        // 판정이 끝나지 않은 제출로는 다음 행동을 정할 수 없다.
        // 문자열로 받던 시절에는 QUEUED 가 들어와도 일반 실패처럼 RETRY_VARIANT 를 냈다 -
        // 채점이 끝나지도 않았는데 다른 문제로 보내는 것이다.
        if (!context.judgeStatus().isTerminal()) {
            throw new IllegalArgumentException(
                    "판정이 끝나지 않은 제출로 다음 행동을 정할 수 없다: " + context.judgeStatus());
        }

        // 채점 실패는 우리 잘못이다. **아무 판단도 하지 않는다.**
        //
        // 이 분기가 아래 선수 조건 검사보다 먼저 와야 한다. 뒤에 두면 우리 하네스가
        // 죽었을 때 사용자의 화면이 다른 Skill 로 튄다 - 사용자는 아무것도 하지
        // 않았는데 학습 경로가 바뀐다. 선수 조건이 실제로 미충족이더라도, 그것을
        // 알리는 계기가 우리 장애여서는 안 된다.
        if (context.judgeStatus() == JudgeStatus.SYSTEM_ERROR) {
            return NextAction.of(ActionType.CONTINUE, "채점 실패 - 사용자 잘못이 아니다");
        }

        // 0. 아직 배우기 시작하지 않은 Skill 이면 선수 조건을 먼저 본다.
        //
        // Addendum §43 에는 없는 분기다. 그 pseudocode 는 "이미 이 Skill 을 하고 있다" 를
        // 전제하는데, 실제로는 선수 조건을 못 채운 Skill 의 문제가 주어질 수 있다.
        // 그대로 두면 사용자가 준비되지 않은 문제에서 반복 실패하고, 그 실패가
        // Evidence 로 쌓여 mastery 를 끌어내린다.
        //
        // 조건을 status 가 아니라 Evidence 유무로 본다. status 로 보면 어떤 값을
        // 나열해도 빠지는 것이 생긴다 - 처음에는 UNASSESSED 만 봤는데,
        // PrerequisiteEvaluator.resolve() 가 만들어내는 LOCKED / READY 가 그대로
        // 통과했다. LOCKED 인 Skill 이 RETRY_VARIANT 를 받는 상태였다.
        //
        // 그리고 **이번 제출을 반영하기 전** 개수를 본다. state.evidenceCount() 를
        // 쓰면 안 된다 - 실제 흐름이 Judge -> Evidence 저장 -> 재계산 -> Decision 이라서
        // 여기 도착했을 때는 이번 실패가 이미 1개로 세어져 있다. 잠긴 Skill 을 처음
        // 틀린 사용자가 CHANGE_SKILL 대신 RETRY_VARIANT 를 받게 된다.
        //
        // 반대로 제출 전에 이미 Evidence 가 있었으면 선수 관계로 되돌리지 않는다
        // (PrerequisiteEvaluator 의 같은 판단).
        if (!context.startedBeforeAttempt()) {
            Optional<String> blocker =
                    prerequisites.nextPrerequisite(context.skillCode(), context.masteries());
            if (blocker.isPresent()) {
                return NextAction.targeting(ActionType.CHANGE_SKILL, blocker.get(),
                        "선수 조건 미충족: " + blocker.get() + " 을(를) 먼저 채운다");
            }
        }

        return switch (context.judgeStatus()) {
            case ACCEPTED -> onAccepted(context);

            // COMPILE_ERROR 와 SYSTEM_ERROR 에서는 Reviewer 를 부르지 않는다(ADR-0004).
            // 확정된 Mistake 도 없으므로 드릴로 보낼 근거가 없다.
            //
            // 문법 오류는 고쳐 다시 내면 되는 상황이다. 다른 문제로 보내면 흐름이 끊긴다.
            case COMPILE_ERROR ->
                    NextAction.of(ActionType.CONTINUE, "문법 오류 - 같은 문제를 고쳐 다시 낸다");
            default -> onFailure(context);
        };
    }

    /**
     * Addendum §43 의 성공 분기.
     *
     * <p><b>"숙달했는가" 의 판정자는 {@link MasteryCalculator} 하나다.</b> 여기서
     * mastery / confidence 문턱을 다시 확인하지 않는다 - 그러면 Mastery 규칙의 일부만
     * 복제하게 되고, 나머지 조건(최근 독립 풀이 2/3 성공, 복습 성공, WEAKENED 이력)을
     * 빠뜨린 채 다음 Skill 로 넘기게 된다.
     *
     * <p>실제로 그랬다. 문턱만 보던 시절에는 {@code PRACTICING} 과 {@code WEAKENED} 에서도
     * {@code UNLOCK_NEXT} 가 나왔다 - 아직 숙달되지 않은 사용자를 다음 Skill 로 넘기는 것은
     * Decision Engine 이 가장 피해야 할 오류다.
     */
    private NextAction onAccepted(Context context) {
        SkillState state = context.state();

        if (state.status() == SkillStatus.MASTERED) {
            return NextAction.of(ActionType.UNLOCK_NEXT,
                    "MASTERED - Addendum 22 의 네 조건을 모두 채웠다");
        }

        // 점수는 충분한데 MASTERED 가 아닌 가장 흔한 이유가 "복습을 아직 안 했다" 다.
        // 복습 없는 Mastery 를 인정하지 않으므로(PRD §143-5) 그쪽으로 밀어준다.
        Double mastery = state.mastery();
        boolean strongScores = mastery != null
                && mastery >= MasteryCalculator.MASTERY_THRESHOLD
                && state.confidence() >= MasteryCalculator.CONFIDENCE_THRESHOLD;

        if (strongScores && !context.reviewCompleted()) {
            return NextAction.of(ActionType.SCHEDULE_REVIEW,
                    "점수는 문턱을 넘었으나 복습 성공 기록이 없다");
        }

        return NextAction.of(ActionType.CONTINUE,
                "정답이지만 아직 " + state.status() + " (mastery=" + mastery
                        + ", confidence=" + state.confidence() + ")");
    }

    /** Addendum §43 의 실패 분기. */
    private NextAction onFailure(Context context) {
        // 확정된 Mistake 가 자동 드릴 대상이면 거기로 보낸다.
        //
        // 대상 Skill 을 여기 하드코딩하지 않는다. curriculum/mistakes.yaml 의
        // auto_drill / target_skill 이 정본이고, 그 Skill 을 PRIMARY 로 갖는
        // MICRO_DRILL 문제가 실재하는지는 tools/check_problems.py 가 확인한다.
        // 하드코딩하면 데이터를 고쳐도 코드가 따라가지 않는다.
        String mistake = context.confirmedMistake();
        if (mistake != null) {
            String target = catalog.autoDrillTarget(mistake);
            if (target != null) {
                return NextAction.targeting(ActionType.MICRO_DRILL, target,
                        "확정된 실수 " + mistake + " → " + target + " 드릴");
            }
        }

        // 같은 문제를 세 번 넘게 틀리면 문제가 아니라 개념 쪽이다.
        if (context.sameProblemAttempts() >= 3) {
            return NextAction.targeting(ActionType.REVIEW_CONCEPT, context.skillCode(),
                    "같은 문제 " + context.sameProblemAttempts() + "회 실패 - 개념부터 다시 본다");
        }

        return NextAction.targeting(ActionType.RETRY_VARIANT, context.skillCode(),
                "구현 연습이 더 필요하다");
    }
}
