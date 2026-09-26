package dev.codesprint.reviewer;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.codesprint.curriculum.CurriculumCatalog;
import dev.codesprint.explain.ExplainPort;
import dev.codesprint.problem.ProblemCatalog;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Explain Back 평가의 정답지와 판정. 정본: ADR-0052.
 *
 * <p>모델을 부르지 않는다 - {@link ExplainEvaluation} 본체는 실제 모델이 필요해 CI 에서 돌지 않는다. <b>모델
 * 없이 확인할 수 있는 것은 여기서 본다.</b> 정답지가 틀렸거나 판정이 아무것도 거르지 못하면, 평가를 돌려도
 * 잰 것이 없다.
 */
class ExplainEvalCasesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final ProblemCatalog PROBLEMS = new ProblemCatalog(
            Path.of(System.getProperty("codesprint.repoRoot")).resolve("problems").toString());
    private static final CurriculumCatalog CURRICULUM = new CurriculumCatalog();

    @Test
    @DisplayName("저장소의 평가 설명은 전부 규칙을 지키고, 라벨마다 하나 이상 있다")
    void everyCaseIsUsable() {
        List<Path> files = ExplainEvaluation.caseFiles(null);
        assertThat(files).as("tests/eval/explain/").isNotEmpty();
        Set<ExplainEvaluation.Label> seen = EnumSet.noneOf(ExplainEvaluation.Label.class);
        for (Path path : files) {
            String name = path.getFileName().toString();
            var node = ExplainEvaluation.read(path);
            assertThat(ExplainEvaluation.issues(name, node, PROBLEMS, CURRICULUM)).as(name).isEmpty();
            seen.add(ExplainEvaluation.parse(name, node).label());
        }
        assertThat(seen).as("라벨마다 케이스가 있다").isEqualTo(ExplainEvaluation.labels());
    }

    /** 규칙마다 그것 하나만 어긴 케이스를 만들어, 검사가 실제로 잡는지 본다. */
    private static List<String> broken(String label, Consumer<ObjectNode> breakIt) {
        ObjectNode node = MAPPER.createObjectNode()
                .put("problemCode", "P02_GRID_TRAVERSAL")
                .put("label", label)
                .put("explanation", "설명");
        node.putNull("omittedPoint");
        node.putNull("plantedMisconception");
        if (label.equals("MISSING")) {
            node.put("omittedPoint", 0);
        }
        if (label.equals("MISCONCEPTION")) {
            node.put("plantedMisconception", "틀린 주장");
        }
        String before = "P02_GRID_TRAVERSAL__" + label + ".json";
        assertThat(ExplainEvaluation.issues(before, node, PROBLEMS, CURRICULUM))
                .as("망가뜨리기 전 " + label).isEmpty();
        breakIt.accept(node);
        return ExplainEvaluation.issues(before, node, PROBLEMS, CURRICULUM);
    }

    @Test
    @DisplayName("정답지 검사가 규칙마다 실제로 잡는다")
    void theCaseCheckCatchesEachRule() {
        assertThat(broken("SOUND", n -> n.remove("omittedPoint"))).as("null 필드 생략").isNotEmpty();
        assertThat(broken("SOUND", n -> n.put("label", "GOOD"))).as("모르는 라벨").isNotEmpty();
        assertThat(broken("SOUND", n -> n.put("label", "VAGUE"))).as("파일 이름과 라벨이 다르다").isNotEmpty();
        assertThat(broken("SOUND", n -> n.put("problemCode", "P999_NONE"))).as("없는 문제").isNotEmpty();
        assertThat(broken("SOUND", n -> n.put("explanation", " "))).as("빈 설명").isNotEmpty();
        assertThat(broken("MISSING", n -> n.putNull("omittedPoint"))).as("MISSING 인데 뺀 요점이 없다")
                .isNotEmpty();
        assertThat(broken("MISSING", n -> n.put("omittedPoint", 99))).as("없는 요점 번호").isNotEmpty();
        assertThat(broken("SOUND", n -> n.put("omittedPoint", 0))).as("SOUND 에 뺀 요점").isNotEmpty();
        assertThat(broken("MISCONCEPTION", n -> n.putNull("plantedMisconception")))
                .as("MISCONCEPTION 인데 심은 것이 없다").isNotEmpty();
        assertThat(broken("VAGUE", n -> n.put("plantedMisconception", "x"))).as("VAGUE 에 심은 오개념")
                .isNotEmpty();
    }

    private static ExplainEvaluation.Outcome outcome(String label, String misconception) {
        var c = new ExplainEvaluation.Case("P02_GRID_TRAVERSAL__" + label + ".json",
                "P02_GRID_TRAVERSAL", ExplainEvaluation.Label.valueOf(label), "설명", null, null);
        return new ExplainEvaluation.Outcome(c,
                new ExplainPort.Analysis(List.of(), List.of(), misconception, null), null);
    }

    private static ExplainEvaluation.Outcome none(String label) {
        var c = new ExplainEvaluation.Case("P02_GRID_TRAVERSAL__" + label + ".json",
                "P02_GRID_TRAVERSAL", ExplainEvaluation.Label.valueOf(label), "설명", null, null);
        return new ExplainEvaluation.Outcome(c, null, "호출 실패");
    }

    @Test
    @DisplayName("맞게 이해한 설명에 잘못 이해한 점을 붙이면 실패하고, 심은 오개념을 찾은 것은 실패가 아니다")
    void falseMisconceptionFails() {
        assertThat(ExplainEvaluation.verdict(List.of(outcome("SOUND", "틀렸다")))).isEqualTo(1);
        assertThat(ExplainEvaluation.verdict(List.of(outcome("MISSING", "틀렸다")))).isEqualTo(1);
        assertThat(ExplainEvaluation.verdict(List.of(outcome("MISCONCEPTION", "틀렸다")))).isZero();
        assertThat(ExplainEvaluation.verdict(List.of(outcome("VAGUE", "틀렸다")))).isZero();
        assertThat(ExplainEvaluation.verdict(List.of(outcome("SOUND", null)))).isZero();
        // 빈 문자열은 붙이지 않은 것이다 - 화면에 아무것도 나오지 않는다.
        assertThat(ExplainEvaluation.verdict(List.of(outcome("SOUND", " ")))).isZero();
    }

    @Test
    @DisplayName("쓸 수 있는 분석이 0건이면 오지적 0건으로 통과하지 않는다")
    void nothingMeasuredIsNotAPass() {
        assertThat(ExplainEvaluation.verdict(List.of(none("SOUND"), none("MISSING")))).isEqualTo(2);
    }
}
