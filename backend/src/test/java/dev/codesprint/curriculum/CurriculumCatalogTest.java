package dev.codesprint.curriculum;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 커리큘럼이 빌드로 실제로 들어왔는지 확인한다.
 *
 * <p>이 경로가 조용히 끊기면 catalog 가 비고, Decision Engine 이 모든 Skill 을 잠긴 것으로
 * 보거나 자동 드릴을 하나도 내지 않는다. 로더가 예외를 던지긴 하지만, 그 예외가 뜨는
 * 시점이 런타임이라 여기서 먼저 잡는다.
 *
 * <p>데이터 자체의 불변식(순환 선수 관계, dangling 참조 등)은 검사하지 않는다.
 * {@code tools/check_curriculum.py} 가 CI 에서 막으며, 같은 검사를 두 언어로 두면
 * 둘이 갈라졌을 때 어느 쪽이 맞는지 알 수 없다.
 */
class CurriculumCatalogTest {

    private final CurriculumCatalog catalog = new CurriculumCatalog();

    private static Path repoRoot() {
        return Path.of(System.getProperty("codesprint.repoRoot"));
    }

    @Test
    @DisplayName("Skill 이 저장소의 skills.yaml 개수와 같다")
    void loadsAllSkills() throws Exception {
        long declared = Files.readAllLines(repoRoot().resolve("curriculum/skills.yaml")).stream()
                .filter(line -> line.startsWith("  - code:"))
                .count();

        assertThat(catalog.skillCodes())
                .as("빌드가 복사한 skills.yaml 과 저장소의 것이 같아야 한다")
                .hasSize((int) declared);
        assertThat(catalog.skill("BFS_GRID_TRAVERSAL")).isNotNull();
    }

    @Test
    @DisplayName("선수 관계가 실린다")
    void loadsPrerequisites() {
        assertThat(catalog.allPrerequisites()).isNotEmpty();
        assertThat(catalog.prerequisitesOf("BFS_SHORTEST_PATH"))
                .extracting(CurriculumCatalog.Prerequisite::requires)
                .contains("BFS_GRID_TRAVERSAL");
    }

    @Test
    @DisplayName("자동 드릴 대상이 코드가 아니라 데이터에서 온다")
    void autoDrillTargetsComeFromData() {
        // 여기 값이 바뀌면 curriculum/mistakes.yaml 이 바뀐 것이다.
        // Decision Engine 이 이 값을 하드코딩하지 않는다는 뜻이기도 하다.
        assertThat(catalog.autoDrillTarget("BOUNDARY_CHECK")).isEqualTo("GRID_BOUNDARY_CHECK");
        assertThat(catalog.autoDrillTarget("VISITED_TIMING"))
                .isEqualTo("BFS_VISITED_MANAGEMENT");
    }

    @Test
    @DisplayName("자동 드릴이 꺼진 Mistake 는 대상을 내지 않는다")
    void nonAutoDrillMistakesHaveNoTarget() {
        // 슬라이스 1 은 자동 액션을 2종으로 묶어둔다(Addendum 42).
        for (String code : List.of("INDEX_ERROR", "INPUT_PARSE", "OUTPUT_FORMAT",
                "SYNTAX_ERROR", "IMPLEMENTATION_MISC", "NO_VISITED")) {
            assertThat(catalog.autoDrillTarget(code)).as(code).isNull();
        }
    }

    @Test
    @DisplayName("SYSTEM 이 부여하는 Mistake 는 Reviewer 몫이 아니다")
    void systemAssignedMistakes() {
        // ADR-0004. LLM enum 에 없는 code 이며, Judge 결과만으로 결정론적으로 붙는다.
        assertThat(catalog.mistake("SYNTAX_ERROR").assignedBy()).isEqualTo("SYSTEM");
        assertThat(catalog.mistake("BOUNDARY_CHECK").assignedBy()).isEqualTo("REVIEWER");
    }
}
