package dev.codesprint.learning;

import static org.assertj.core.api.Assertions.assertThat;

import dev.codesprint.curriculum.CurriculumCatalog;
import dev.codesprint.learning.domain.ActionType;
import dev.codesprint.learning.domain.DecisionEngine;
import dev.codesprint.learning.domain.Dimension;
import dev.codesprint.learning.domain.NextAction;
import dev.codesprint.learning.domain.PrerequisiteEvaluator;
import dev.codesprint.learning.domain.SkillState;
import dev.codesprint.learning.domain.SkillStatus;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Decision Engine 의 규칙을 하나씩 검증한다. Addendum §86 이 요구하는 목록이다.
 *
 * <p>Spring 을 띄우지 않는다. 순수 함수라 컨테이너도 DB 도 필요 없다 - 규칙이 틀렸을 때
 * 원인 범위가 규칙 안으로 좁혀진다.
 */
class DecisionEngineTest {

    private static final String SKILL = "BFS_GRID_TRAVERSAL";

    private final CurriculumCatalog catalog = new CurriculumCatalog();
    private final PrerequisiteEvaluator prerequisites = new PrerequisiteEvaluator(catalog);
    private final DecisionEngine engine = new DecisionEngine(catalog, prerequisites);

    /** 선수 조건을 전부 채운 상태. 선수 관계 분기를 타지 않게 한다. */
    private static Map<String, Double> allPrerequisitesMet() {
        return Map.of(
                "PYTHON_LIST_BASIC", 0.9,
                "PYTHON_DEQUE_BASIC", 0.9,
                "GRID_COORDINATE", 0.9,
                "GRID_BOUNDARY_CHECK", 0.9,
                "BFS_BASIC", 0.9,
                "BFS_VISITED_MANAGEMENT", 0.9,
                "BFS_GRID_TRAVERSAL", 0.9);
    }

    private static SkillState state(Double mastery, double confidence, SkillStatus status) {
        Map<Dimension, Double> scores = new EnumMap<>(Dimension.class);
        if (mastery != null) {
            scores.put(Dimension.IMPLEMENTATION, mastery);
            scores.put(Dimension.INDEPENDENT, mastery);
        }
        return new SkillState(SKILL, scores, mastery, confidence, mastery == null ? 0 : 5, status);
    }

    private NextAction decide(String judgeStatus, SkillState state, String mistake,
            int attempts, boolean reviewCompleted) {
        return engine.decide(new DecisionEngine.Context(
                SKILL, state, judgeStatus, mistake, attempts, reviewCompleted,
                allPrerequisitesMet()));
    }

    @Nested
    @DisplayName("성공했을 때 (Addendum 43)")
    class OnAccepted {

        @Test
        @DisplayName("문턱 아래면 CONTINUE")
        void belowThreshold() {
            NextAction action = decide("ACCEPTED",
                    state(0.60, 0.80, SkillStatus.PRACTICING), null, 1, true);
            assertThat(action.type()).isEqualTo(ActionType.CONTINUE);
        }

        @Test
        @DisplayName("confidence 가 낮으면 mastery 가 높아도 CONTINUE")
        void masteryHighButConfidenceLow() {
            // 잘할 가능성은 높아 보이지만 증거가 적다(Addendum 17).
            NextAction action = decide("ACCEPTED",
                    state(0.95, 0.20, SkillStatus.PRACTICING), null, 1, true);
            assertThat(action.type()).isEqualTo(ActionType.CONTINUE);
        }

        @Test
        @DisplayName("문턱을 넘었지만 복습 기록이 없으면 SCHEDULE_REVIEW")
        void withoutReview() {
            // 복습 없는 Mastery 를 인정하지 않는다(PRD 143-5).
            NextAction action = decide("ACCEPTED",
                    state(0.90, 0.80, SkillStatus.PRACTICING), null, 1, false);
            assertThat(action.type()).isEqualTo(ActionType.SCHEDULE_REVIEW);
        }

        @Test
        @DisplayName("네 조건을 모두 채우면 UNLOCK_NEXT")
        void allConditionsMet() {
            NextAction action = decide("ACCEPTED",
                    state(0.90, 0.80, SkillStatus.MASTERED), null, 1, true);
            assertThat(action.type()).isEqualTo(ActionType.UNLOCK_NEXT);
        }
    }

    @Nested
    @DisplayName("실패했을 때 (Addendum 43)")
    class OnFailure {

        @Test
        @DisplayName("BOUNDARY_CHECK 확정 → GRID_BOUNDARY_CHECK 드릴")
        void boundaryCheckDrill() {
            NextAction action = decide("WRONG_ANSWER",
                    state(0.60, 0.50, SkillStatus.PRACTICING), "BOUNDARY_CHECK", 1, false);

            assertThat(action.type()).isEqualTo(ActionType.MICRO_DRILL);
            // 대상 Skill 은 코드가 아니라 curriculum/mistakes.yaml 에서 온다.
            assertThat(action.targetSkill()).isEqualTo("GRID_BOUNDARY_CHECK");
        }

        @Test
        @DisplayName("VISITED_TIMING 확정 → BFS_VISITED_MANAGEMENT 드릴")
        void visitedTimingDrill() {
            NextAction action = decide("WRONG_ANSWER",
                    state(0.60, 0.50, SkillStatus.PRACTICING), "VISITED_TIMING", 1, false);

            assertThat(action.type()).isEqualTo(ActionType.MICRO_DRILL);
            assertThat(action.targetSkill()).isEqualTo("BFS_VISITED_MANAGEMENT");
        }

        @Test
        @DisplayName("자동 드릴이 아닌 Mistake 는 드릴로 보내지 않는다")
        void nonAutoDrillMistake() {
            // 슬라이스 1 은 자동 액션을 2종으로 묶어둔다(Addendum 42).
            // Reviewer 정확도가 측정되기 전에 넓히면 오분류가 곧 학습 경로 오염이 된다.
            NextAction action = decide("WRONG_ANSWER",
                    state(0.60, 0.50, SkillStatus.PRACTICING), "INDEX_ERROR", 1, false);

            assertThat(action.type()).isEqualTo(ActionType.RETRY_VARIANT);
        }

        @Test
        @DisplayName("같은 문제 3회 실패 → REVIEW_CONCEPT")
        void repeatedFailureOnSameProblem() {
            NextAction action = decide("WRONG_ANSWER",
                    state(0.40, 0.50, SkillStatus.LEARNING), null, 3, false);

            assertThat(action.type()).isEqualTo(ActionType.REVIEW_CONCEPT);
        }

        @Test
        @DisplayName("확정된 실수가 드릴 대상이면 시도 횟수보다 먼저 본다")
        void drillWinsOverAttemptCount() {
            // 같은 실수를 반복하는 것이 원인이면, 문제를 바꾸는 것보다 그 실수를
            // 교정하는 것이 먼저다.
            NextAction action = decide("WRONG_ANSWER",
                    state(0.40, 0.50, SkillStatus.LEARNING), "BOUNDARY_CHECK", 5, false);

            assertThat(action.type()).isEqualTo(ActionType.MICRO_DRILL);
        }

        @Test
        @DisplayName("그 밖에는 RETRY_VARIANT")
        void otherwise() {
            NextAction action = decide("WRONG_ANSWER",
                    state(0.50, 0.50, SkillStatus.PRACTICING), null, 1, false);

            assertThat(action.type()).isEqualTo(ActionType.RETRY_VARIANT);
            assertThat(action.targetSkill()).isEqualTo(SKILL);
        }

        @Test
        @DisplayName("RUNTIME_ERROR / TIME_LIMIT 도 같은 실패 분기를 탄다")
        void otherFailureStatuses() {
            for (String status : java.util.List.of(
                    "RUNTIME_ERROR", "TIME_LIMIT", "MEMORY_LIMIT", "OUTPUT_LIMIT")) {
                NextAction action = decide(status,
                        state(0.50, 0.50, SkillStatus.PRACTICING), "BOUNDARY_CHECK", 1, false);
                assertThat(action.type()).as(status).isEqualTo(ActionType.MICRO_DRILL);
            }
        }
    }

    @Nested
    @DisplayName("Reviewer 를 부르지 않는 판정 (ADR-0004)")
    class WithoutReviewer {

        @Test
        @DisplayName("COMPILE_ERROR 는 같은 문제를 고쳐 다시 낸다")
        void compileError() {
            NextAction action = decide("COMPILE_ERROR",
                    state(0.60, 0.50, SkillStatus.PRACTICING), null, 1, false);

            assertThat(action.type()).isEqualTo(ActionType.CONTINUE);
            assertThat(action.reason()).contains("문법");
        }

        @Test
        @DisplayName("SYSTEM_ERROR 는 학습 경로를 바꾸지 않는다")
        void systemError() {
            // 우리 잘못이다. 사용자를 드릴로 보내면 안 된다.
            NextAction action = decide("SYSTEM_ERROR",
                    state(0.60, 0.50, SkillStatus.PRACTICING), null, 3, false);

            assertThat(action.type()).isEqualTo(ActionType.CONTINUE);
        }
    }

    @Nested
    @DisplayName("선수 관계")
    class Prerequisites {

        @Test
        @DisplayName("잠긴 Skill 이면 선수 Skill 로 보낸다")
        void lockedSkillRedirects() {
            // 준비되지 않은 문제에서 반복 실패하면 그 실패가 Evidence 로 쌓여
            // mastery 를 끌어내린다.
            NextAction action = engine.decide(new DecisionEngine.Context(
                    "BFS_SHORTEST_PATH",
                    new SkillState("BFS_SHORTEST_PATH", Map.of(), null, 0.0, 0,
                            SkillStatus.UNASSESSED),
                    "WRONG_ANSWER", null, 1, false,
                    Map.of()));

            assertThat(action.type()).isEqualTo(ActionType.CHANGE_SKILL);
            assertThat(action.targetSkill()).isEqualTo("BFS_GRID_TRAVERSAL");
        }

        @Test
        @DisplayName("이미 배우기 시작한 Skill 은 선수 관계로 되돌리지 않는다")
        void startedSkillIsNotLockedBack() {
            // 다른 문제의 SECONDARY Skill 로 Evidence 가 쌓여 선수 조건보다 먼저 진도가
            // 나가는 경우가 있다. 그때 "잠김" 으로 되돌리면 사용자는 자기가 푼 것이
            // 사라진 것으로 본다.
            NextAction action = engine.decide(new DecisionEngine.Context(
                    "BFS_SHORTEST_PATH",
                    state(0.55, 0.40, SkillStatus.PRACTICING),
                    "WRONG_ANSWER", null, 1, false,
                    Map.of()));

            assertThat(action.type()).isEqualTo(ActionType.RETRY_VARIANT);
        }

        @Test
        @DisplayName("선수 조건을 채웠으면 그대로 진행한다")
        void metPrerequisitesProceed() {
            NextAction action = engine.decide(new DecisionEngine.Context(
                    "BFS_SHORTEST_PATH",
                    new SkillState("BFS_SHORTEST_PATH", Map.of(), null, 0.0, 0,
                            SkillStatus.UNASSESSED),
                    "WRONG_ANSWER", null, 1, false,
                    Map.of("BFS_GRID_TRAVERSAL", 0.95)));

            assertThat(action.type()).isEqualTo(ActionType.RETRY_VARIANT);
        }
    }

    @Nested
    @DisplayName("결정에는 근거가 남는다")
    class Auditability {

        @Test
        @DisplayName("모든 액션이 reason 을 갖는다")
        void everyActionHasReason() {
            for (String status : java.util.List.of(
                    "ACCEPTED", "WRONG_ANSWER", "COMPILE_ERROR", "SYSTEM_ERROR",
                    "RUNTIME_ERROR", "TIME_LIMIT")) {
                NextAction action = decide(status,
                        state(0.60, 0.50, SkillStatus.PRACTICING), null, 1, false);
                assertThat(action.reason()).as(status).isNotBlank();
            }
        }
    }
}
