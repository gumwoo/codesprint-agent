package dev.codesprint.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.codesprint.curriculum.CurriculumCatalog;
import dev.codesprint.learning.domain.ActionType;
import dev.codesprint.learning.domain.DecisionEngine;
import dev.codesprint.learning.domain.Dimension;
import dev.codesprint.learning.domain.JudgeStatus;
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

    private NextAction decide(JudgeStatus judgeStatus, SkillState state, String mistake,
            int attempts, boolean reviewCompleted) {
        // 이번 제출 전 개수는 반영 후에서 이번 것 하나를 뺀 값이다.
        return engine.decide(new DecisionEngine.Context(
                SKILL, state, Math.max(0, state.evidenceCount() - 1),
                judgeStatus, mistake, attempts, reviewCompleted,
                allPrerequisitesMet()));
    }

    @Nested
    @DisplayName("성공했을 때 (Addendum 43)")
    class OnAccepted {

        @Test
        @DisplayName("문턱 아래면 CONTINUE")
        void belowThreshold() {
            NextAction action = decide(JudgeStatus.ACCEPTED,
                    state(0.60, 0.80, SkillStatus.PRACTICING), null, 1, true);
            assertThat(action.type()).isEqualTo(ActionType.CONTINUE);
        }

        @Test
        @DisplayName("confidence 가 낮으면 mastery 가 높아도 CONTINUE")
        void masteryHighButConfidenceLow() {
            // 잘할 가능성은 높아 보이지만 증거가 적다(Addendum 17).
            NextAction action = decide(JudgeStatus.ACCEPTED,
                    state(0.95, 0.20, SkillStatus.PRACTICING), null, 1, true);
            assertThat(action.type()).isEqualTo(ActionType.CONTINUE);
        }

        @Test
        @DisplayName("문턱을 넘었지만 복습 기록이 없으면 SCHEDULE_REVIEW")
        void withoutReview() {
            // 복습 없는 Mastery 를 인정하지 않는다(PRD 143-5).
            NextAction action = decide(JudgeStatus.ACCEPTED,
                    state(0.90, 0.80, SkillStatus.PRACTICING), null, 1, false);
            assertThat(action.type()).isEqualTo(ActionType.SCHEDULE_REVIEW);
        }

        @Test
        @DisplayName("MASTERED 여야만 UNLOCK_NEXT")
        void onlyMasteredUnlocks() {
            NextAction action = decide(JudgeStatus.ACCEPTED,
                    state(0.90, 0.80, SkillStatus.MASTERED), null, 1, true);
            assertThat(action.type()).isEqualTo(ActionType.UNLOCK_NEXT);
        }

        @Test
        @DisplayName("점수가 문턱을 넘고 복습까지 했어도 MASTERED 가 아니면 UNLOCK_NEXT 가 아니다")
        void highScoresWithoutMasteredStatus() {
            // "숙달했는가" 의 판정자는 MasteryCalculator 하나다. 여기서 문턱만 다시 보면
            // 나머지 조건(최근 독립 풀이 2/3 성공 등)을 빠뜨린 채 다음 Skill 로 넘긴다.
            NextAction action = decide(JudgeStatus.ACCEPTED,
                    state(0.90, 0.80, SkillStatus.PRACTICING), null, 1, true);

            assertThat(action.type())
                    .as("아직 숙달되지 않은 사용자를 다음 Skill 로 넘기면 안 된다")
                    .isEqualTo(ActionType.CONTINUE);
        }

        @Test
        @DisplayName("WEAKENED 는 점수가 높아도 절대 UNLOCK_NEXT 가 아니다")
        void weakenedNeverUnlocks() {
            // WEAKENED 는 "됐었는데 지금은 아니다" 다. 다음으로 넘길 상태가 아니다.
            NextAction action = decide(JudgeStatus.ACCEPTED,
                    state(0.82, 0.80, SkillStatus.WEAKENED), null, 1, true);

            assertThat(action.type()).isNotEqualTo(ActionType.UNLOCK_NEXT);
            assertThat(action.type()).isEqualTo(ActionType.CONTINUE);
        }

        @Test
        @DisplayName("MASTERED 외의 어떤 상태도 UNLOCK_NEXT 를 내지 않는다")
        void noOtherStatusUnlocks() {
            for (SkillStatus status : SkillStatus.values()) {
                if (status == SkillStatus.MASTERED || status == SkillStatus.UNASSESSED) {
                    continue;
                }
                NextAction action = decide(JudgeStatus.ACCEPTED,
                        state(0.95, 0.95, status), null, 1, true);
                assertThat(action.type()).as(status.name()).isNotEqualTo(ActionType.UNLOCK_NEXT);
            }
        }
    }

    @Nested
    @DisplayName("실패했을 때 (Addendum 43)")
    class OnFailure {

        @Test
        @DisplayName("BOUNDARY_CHECK 확정 → GRID_BOUNDARY_CHECK 드릴")
        void boundaryCheckDrill() {
            NextAction action = decide(JudgeStatus.WRONG_ANSWER,
                    state(0.60, 0.50, SkillStatus.PRACTICING), "BOUNDARY_CHECK", 1, false);

            assertThat(action.type()).isEqualTo(ActionType.MICRO_DRILL);
            // 대상 Skill 은 코드가 아니라 curriculum/mistakes.yaml 에서 온다.
            assertThat(action.targetSkill()).isEqualTo("GRID_BOUNDARY_CHECK");
        }

        @Test
        @DisplayName("VISITED_TIMING 확정 → BFS_VISITED_MANAGEMENT 드릴")
        void visitedTimingDrill() {
            NextAction action = decide(JudgeStatus.WRONG_ANSWER,
                    state(0.60, 0.50, SkillStatus.PRACTICING), "VISITED_TIMING", 1, false);

            assertThat(action.type()).isEqualTo(ActionType.MICRO_DRILL);
            assertThat(action.targetSkill()).isEqualTo("BFS_VISITED_MANAGEMENT");
        }

        @Test
        @DisplayName("자동 드릴이 아닌 Mistake 는 드릴로 보내지 않는다")
        void nonAutoDrillMistake() {
            // 슬라이스 1 은 자동 액션을 2종으로 묶어둔다(Addendum 42).
            // Reviewer 정확도가 측정되기 전에 넓히면 오분류가 곧 학습 경로 오염이 된다.
            NextAction action = decide(JudgeStatus.WRONG_ANSWER,
                    state(0.60, 0.50, SkillStatus.PRACTICING), "INDEX_ERROR", 1, false);

            assertThat(action.type()).isEqualTo(ActionType.RETRY_VARIANT);
        }

        @Test
        @DisplayName("같은 문제 3회 실패 → REVIEW_CONCEPT")
        void repeatedFailureOnSameProblem() {
            NextAction action = decide(JudgeStatus.WRONG_ANSWER,
                    state(0.40, 0.50, SkillStatus.LEARNING), null, 3, false);

            assertThat(action.type()).isEqualTo(ActionType.REVIEW_CONCEPT);
        }

        @Test
        @DisplayName("확정된 실수가 드릴 대상이면 시도 횟수보다 먼저 본다")
        void drillWinsOverAttemptCount() {
            // 같은 실수를 반복하는 것이 원인이면, 문제를 바꾸는 것보다 그 실수를
            // 교정하는 것이 먼저다.
            NextAction action = decide(JudgeStatus.WRONG_ANSWER,
                    state(0.40, 0.50, SkillStatus.LEARNING), "BOUNDARY_CHECK", 5, false);

            assertThat(action.type()).isEqualTo(ActionType.MICRO_DRILL);
        }

        @Test
        @DisplayName("그 밖에는 RETRY_VARIANT")
        void otherwise() {
            NextAction action = decide(JudgeStatus.WRONG_ANSWER,
                    state(0.50, 0.50, SkillStatus.PRACTICING), null, 1, false);

            assertThat(action.type()).isEqualTo(ActionType.RETRY_VARIANT);
            assertThat(action.targetSkill()).isEqualTo(SKILL);
        }

        @Test
        @DisplayName("RUNTIME_ERROR / TIME_LIMIT 도 같은 실패 분기를 탄다")
        void otherFailureStatuses() {
            for (JudgeStatus status : java.util.List.of(
                    JudgeStatus.RUNTIME_ERROR, JudgeStatus.TIME_LIMIT,
                    JudgeStatus.MEMORY_LIMIT, JudgeStatus.OUTPUT_LIMIT)) {
                NextAction action = decide(status,
                        state(0.50, 0.50, SkillStatus.PRACTICING), "BOUNDARY_CHECK", 1, false);
                assertThat(action.type()).as(status.name()).isEqualTo(ActionType.MICRO_DRILL);
            }
        }
    }

    @Nested
    @DisplayName("Reviewer 를 부르지 않는 판정 (ADR-0004)")
    class WithoutReviewer {

        @Test
        @DisplayName("COMPILE_ERROR 는 같은 문제를 고쳐 다시 낸다")
        void compileError() {
            NextAction action = decide(JudgeStatus.COMPILE_ERROR,
                    state(0.60, 0.50, SkillStatus.PRACTICING), null, 1, false);

            assertThat(action.type()).isEqualTo(ActionType.CONTINUE);
            assertThat(action.reason()).contains("문법");
        }

        @Test
        @DisplayName("SYSTEM_ERROR 는 학습 경로를 바꾸지 않는다")
        void systemError() {
            // 우리 잘못이다. 사용자를 드릴로 보내면 안 된다.
            NextAction action = decide(JudgeStatus.SYSTEM_ERROR,
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
                    0,
                    JudgeStatus.WRONG_ANSWER, null, 1, false,
                    Map.of()));

            assertThat(action.type()).isEqualTo(ActionType.CHANGE_SKILL);
            assertThat(action.targetSkill()).isEqualTo("BFS_GRID_TRAVERSAL");
        }

        @Test
        @DisplayName("LOCKED 상태도 선수 Skill 로 보낸다")
        void lockedStatusIsAlsoRedirected() {
            // PrerequisiteEvaluator.resolve() 가 UNASSESSED + 선수 미충족을 LOCKED 로
            // 바꾼다. 서비스 계층이 그 결과를 그대로 넘기면 Decision Engine 이 받는
            // status 는 UNASSESSED 가 아니라 LOCKED 다.
            //
            // status 로 분기하던 시절에는 이것이 그대로 통과해 RETRY_VARIANT 가 나왔다 -
            // 잠긴 Skill 의 문제를 계속 주는 것이다.
            NextAction action = engine.decide(new DecisionEngine.Context(
                    "BFS_SHORTEST_PATH",
                    new SkillState("BFS_SHORTEST_PATH", Map.of(), null, 0.0, 0,
                            SkillStatus.LOCKED),
                    0,
                    JudgeStatus.WRONG_ANSWER, null, 1, false, Map.of()));

            assertThat(action.type()).isEqualTo(ActionType.CHANGE_SKILL);
            assertThat(action.targetSkill()).isEqualTo("BFS_GRID_TRAVERSAL");
        }

        @Test
        @DisplayName("READY 인데 선수가 다시 미충족이 되어도 보낸다")
        void readyStatusIsAlsoChecked() {
            // 선수 Skill 이 WEAKENED 로 떨어지면 mastery 가 문턱 아래로 내려갈 수 있다.
            NextAction action = engine.decide(new DecisionEngine.Context(
                    "BFS_SHORTEST_PATH",
                    new SkillState("BFS_SHORTEST_PATH", Map.of(), null, 0.0, 0,
                            SkillStatus.READY),
                    0,
                    JudgeStatus.WRONG_ANSWER, null, 1, false,
                    Map.of("BFS_GRID_TRAVERSAL", 0.30)));

            assertThat(action.type()).isEqualTo(ActionType.CHANGE_SKILL);
        }

        @Test
        @DisplayName("이미 배우기 시작한 Skill 은 선수 관계로 되돌리지 않는다")
        void startedSkillIsNotLockedBack() {
            // 다른 문제의 SECONDARY Skill 로 Evidence 가 쌓여 선수 조건보다 먼저 진도가
            // 나가는 경우가 있다. 그때 "잠김" 으로 되돌리면 사용자는 자기가 푼 것이
            // 사라진 것으로 본다.
            // state() 는 evidenceCount 5 인 상태를 만든다 - 실제로 푼 적이 있다는 뜻이다.
            NextAction action = engine.decide(new DecisionEngine.Context(
                    "BFS_SHORTEST_PATH",
                    state(0.55, 0.40, SkillStatus.PRACTICING),
                    4,
                    JudgeStatus.WRONG_ANSWER, null, 1, false,
                    Map.of()));

            assertThat(action.type()).isEqualTo(ActionType.RETRY_VARIANT);
        }

        @Test
        @DisplayName("선수 mastery 가 null 이어도 터지지 않고 그것을 먼저 고른다")
        void nullPrerequisiteMastery() {
            // user_skills 는 UNASSESSED / LOCKED / READY 에서 mastery_score 가 NULL 인
            // 행을 허용한다(PR #5). DB 에서 그대로 map 을 만들면 null 값이 들어온다.
            // Map.getOrDefault 는 그런 경우 기본값을 주지 않고 null 을 돌려준다.
            Map<String, Double> masteries = new java.util.HashMap<>();
            masteries.put("BFS_BASIC", null);              // 아직 평가되지 않았다
            masteries.put("BFS_VISITED_MANAGEMENT", 0.4);
            masteries.put("GRID_BOUNDARY_CHECK", 0.5);

            NextAction action = engine.decide(new DecisionEngine.Context(
                    "BFS_GRID_TRAVERSAL",
                    new SkillState("BFS_GRID_TRAVERSAL", Map.of(), null, 0.0, 0,
                            SkillStatus.UNASSESSED),
                    0,
                    JudgeStatus.WRONG_ANSWER, null, 1, false, masteries));

            assertThat(action.type()).isEqualTo(ActionType.CHANGE_SKILL);
            assertThat(action.targetSkill())
                    .as("가장 뒤처진 것부터 - null 은 0.0 으로 본다")
                    .isEqualTo("BFS_BASIC");
        }

        @Test
        @DisplayName("이번 제출이 반영된 뒤에도 제출 전 기준으로 선수 조건을 본다")
        void firstAttemptOnLockedSkillIsStillRedirected() {
            // 실제 서비스 흐름은 Judge -> Evidence 저장 -> mastery 재계산 -> Decision 이다.
            // 그래서 Decision 이 받는 state 에는 이번 실패가 이미 세어져 있다.
            //
            // state.evidenceCount() 로 판단하던 시절에는 이 케이스가 통과해버렸다 -
            // 잠긴 Skill 을 처음 틀린 사용자가 CHANGE_SKILL 대신 RETRY_VARIANT 를 받는다.
            // 기존 테스트는 전부 evidenceCount 0 으로 호출해서 이것을 잡지 못했다.
            SkillState afterThisSubmission = new SkillState(
                    "BFS_SHORTEST_PATH", Map.of(Dimension.IMPLEMENTATION, 0.0),
                    0.0, 0.25, 1, SkillStatus.LEARNING);

            NextAction action = engine.decide(new DecisionEngine.Context(
                    "BFS_SHORTEST_PATH", afterThisSubmission,
                    0,                                   // 제출 전에는 하나도 없었다
                    JudgeStatus.WRONG_ANSWER, null, 1, false, Map.of()));

            assertThat(action.type()).isEqualTo(ActionType.CHANGE_SKILL);
            assertThat(action.targetSkill()).isEqualTo("BFS_GRID_TRAVERSAL");
        }

        @Test
        @DisplayName("제출 전 개수가 반영 후보다 많으면 거부한다")
        void priorCountCannotExceedCurrent() {
            // 서비스 계층이 순서를 잘못 엮은 것이다. 조용히 넘기면 선수 검사가 사라진다.
            assertThatThrownBy(() -> new DecisionEngine.Context(
                    "BFS_SHORTEST_PATH",
                    new SkillState("BFS_SHORTEST_PATH", Map.of(), null, 0.0, 1,
                            SkillStatus.LEARNING),
                    2,
                    JudgeStatus.WRONG_ANSWER, null, 1, false, Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("priorEvidenceCount");
        }

        @Test
        @DisplayName("선수 조건을 채웠으면 그대로 진행한다")
        void metPrerequisitesProceed() {
            NextAction action = engine.decide(new DecisionEngine.Context(
                    "BFS_SHORTEST_PATH",
                    new SkillState("BFS_SHORTEST_PATH", Map.of(), null, 0.0, 0,
                            SkillStatus.UNASSESSED),
                    0,
                    JudgeStatus.WRONG_ANSWER, null, 1, false,
                    Map.of("BFS_GRID_TRAVERSAL", 0.95)));

            assertThat(action.type()).isEqualTo(ActionType.RETRY_VARIANT);
        }
    }

    @Nested
    @DisplayName("판정이 끝나지 않은 제출")
    class NonTerminalStatus {

        @Test
        @DisplayName("QUEUED / RUNNING 으로는 다음 행동을 정할 수 없다")
        void rejectsNonTerminal() {
            // 문자열로 받던 시절에는 이런 값이 일반 실패처럼 RETRY_VARIANT 를 냈다 -
            // 채점이 끝나지도 않았는데 다른 문제로 보내는 것이다.
            for (JudgeStatus status : java.util.List.of(JudgeStatus.QUEUED, JudgeStatus.RUNNING)) {
                assertThatThrownBy(() -> decide(status,
                        state(0.60, 0.50, SkillStatus.PRACTICING), null, 1, false))
                        .as(status.name())
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        @DisplayName("판정 목록이 계약과 정확히 같다")
        void statusListMatchesContract() throws Exception {
            // 한 방향만 보면 안 된다. "enum 의 값이 스키마 문자열에 있는가" 만 확인하면
            // **스키마에 새 status 가 생기고 enum 이 안 따라가도 통과한다** -
            // 그러면 Judge 가 내는 값을 Decision Engine 이 못 알아보는 상태가 조용히 남는다.
            // 집합이 같은지 본다.
            com.fasterxml.jackson.databind.JsonNode schema =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                            java.nio.file.Files.readString(java.nio.file.Path.of(
                                    System.getProperty("codesprint.repoRoot"),
                                    "contracts/judge-result.schema.json")));

            java.util.Set<String> inContract = new java.util.TreeSet<>();
            schema.get("properties").get("status").get("enum")
                    .forEach(node -> inContract.add(node.asText()));

            java.util.Set<String> terminal = new java.util.TreeSet<>();
            for (JudgeStatus status : JudgeStatus.values()) {
                if (status.isTerminal()) {
                    terminal.add(status.name());
                }
            }

            assertThat(terminal)
                    .as("QUEUED / RUNNING 은 판정이 아니라 진행 상태이므로 계약에 없다")
                    .isEqualTo(inContract);
        }
    }

    @Nested
    @DisplayName("결정에는 근거가 남는다")
    class Auditability {

        @Test
        @DisplayName("모든 액션이 reason 을 갖는다")
        void everyActionHasReason() {
            for (JudgeStatus status : java.util.List.of(
                    JudgeStatus.ACCEPTED, JudgeStatus.WRONG_ANSWER, JudgeStatus.COMPILE_ERROR,
                    JudgeStatus.SYSTEM_ERROR, JudgeStatus.RUNTIME_ERROR, JudgeStatus.TIME_LIMIT)) {
                NextAction action = decide(status,
                        state(0.60, 0.50, SkillStatus.PRACTICING), null, 1, false);
                assertThat(action.reason()).as(status.name()).isNotBlank();
            }
        }
    }
}
