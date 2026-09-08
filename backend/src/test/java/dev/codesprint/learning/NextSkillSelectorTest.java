package dev.codesprint.learning;

import static org.assertj.core.api.Assertions.assertThat;

import dev.codesprint.curriculum.CurriculumCatalog;
import dev.codesprint.learning.domain.Dimension;
import dev.codesprint.learning.domain.NextSkillSelector;
import dev.codesprint.learning.domain.PrerequisiteEvaluator;
import dev.codesprint.learning.domain.SkillState;
import dev.codesprint.learning.domain.SkillStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 숙달한 뒤 어디로 가는가. 정본: ADR-0022.
 *
 * <p>Spring 을 띄우지 않는다. 순수 함수라 컨테이너도 DB 도 필요 없다.
 */
class NextSkillSelectorTest {

    private final CurriculumCatalog catalog = new CurriculumCatalog();
    private final NextSkillSelector selector =
            new NextSkillSelector(catalog, new PrerequisiteEvaluator(catalog));

    /** 전부 손대지 않은 상태에서 시작해, 준 것만 채운다. */
    private List<SkillState> states(Map<String, Double> masteries, String... mastered) {
        List<String> masteredList = List.of(mastered);
        List<SkillState> states = new ArrayList<>();
        for (String code : catalog.skillCodes().stream().sorted().toList()) {
            Double mastery = masteries.get(code);
            SkillStatus status = masteredList.contains(code)
                    ? SkillStatus.MASTERED
                    : mastery == null ? SkillStatus.UNASSESSED : SkillStatus.PRACTICING;
            states.add(new SkillState(code,
                    mastery == null ? Map.of() : Map.of(Dimension.IMPLEMENTATION, mastery),
                    mastery, mastery == null ? 0.0 : 0.7, mastery == null ? 0 : 5, status));
        }
        return states;
    }

    @Test
    @DisplayName("이번 숙달이 실제로 연 Skill 로 간다")
    void theSkillThisMasteryOpened() {
        // PYTHON_LIST_BASIC 을 숙달하면 GRID_COORDINATE 가 열린다.
        assertThat(selector.after("PYTHON_LIST_BASIC",
                states(Map.of("PYTHON_LIST_BASIC", 0.90), "PYTHON_LIST_BASIC")))
                .contains("GRID_COORDINATE");
    }

    @Test
    @DisplayName("선수가 아직 남았으면 그 Skill 은 열리지 않았다")
    void aSkillWithOtherUnmetPrerequisitesIsNotOpen() {
        // BFS_GRID_TRAVERSAL 은 선수가 셋이다. BFS_BASIC 하나를 숙달해도 열리지 않는다.
        assertThat(selector.after("BFS_BASIC",
                states(Map.of("PYTHON_DEQUE_BASIC", 0.90, "BFS_BASIC", 0.90),
                        "PYTHON_DEQUE_BASIC", "BFS_BASIC")))
                .isNotEqualTo(java.util.Optional.of("BFS_GRID_TRAVERSAL"));
    }

    @Test
    @DisplayName("그래프의 끝을 숙달하면 남은 것 중 가장 뒤처진 곳으로 간다")
    void theEndOfTheGraphFallsBackToTheWeakest() {
        // BFS_SHORTEST_PATH 를 요구하는 Skill 은 없다. 그래도 보낼 곳은 있다.
        Map<String, Double> masteries = new java.util.HashMap<>(Map.of(
                "PYTHON_LIST_BASIC", 0.90,
                "PYTHON_DEQUE_BASIC", 0.90,
                "GRID_COORDINATE", 0.90,
                "GRID_BOUNDARY_CHECK", 0.90,
                "BFS_BASIC", 0.90,
                "BFS_VISITED_MANAGEMENT", 0.40,     // 가장 뒤처졌다
                "BFS_GRID_TRAVERSAL", 0.90,
                "BFS_SHORTEST_PATH", 0.95));

        assertThat(selector.after("BFS_SHORTEST_PATH",
                states(masteries, "BFS_SHORTEST_PATH")))
                .as("잘하는 것을 더 잘하게 만드는 것보다 못하는 것을 끌어올린다")
                .contains("BFS_VISITED_MANAGEMENT");
    }

    @Test
    @DisplayName("전부 숙달했으면 갈 곳이 없다")
    void nothingLeft() {
        Map<String, Double> masteries = new java.util.HashMap<>();
        catalog.skillCodes().forEach(code -> masteries.put(code, 0.95));

        assertThat(selector.after("BFS_SHORTEST_PATH",
                states(masteries, catalog.skillCodes().toArray(String[]::new))))
                .as("커리큘럼에 남은 것이 없다").isEmpty();
    }

    @Test
    @DisplayName("이미 숙달한 Skill 로 되돌리지 않는다")
    void doesNotSendBackToAMasteredSkill() {
        // GRID_COORDINATE 가 이미 숙달돼 있으면, PYTHON_LIST_BASIC 을 숙달해도
        // 그리로 보내지 않는다.
        assertThat(selector.after("PYTHON_LIST_BASIC",
                states(Map.of("PYTHON_LIST_BASIC", 0.90, "GRID_COORDINATE", 0.90),
                        "PYTHON_LIST_BASIC", "GRID_COORDINATE")))
                .isNotEqualTo(java.util.Optional.of("GRID_COORDINATE"));
    }
}
