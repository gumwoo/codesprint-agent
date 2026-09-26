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
import java.util.function.Function;
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

    /** 저장소의 개념 자료. 규칙 하나를 시험할 때 이것만 갈아 끼운다. */
    private static final Function<String, List<String>> REAL_POINTS = skill -> {
        var concept = CURRICULUM.concept(skill);
        return concept == null ? null : concept.keyPoints();
    };

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

    private static ObjectNode valid(String label) {
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
        return node;
    }

    /**
     * 규칙 하나만 어긴 케이스의 검사 결과. <b>파일 이름은 망가뜨린 뒤의 문제 · 라벨로 다시 짓는다</b> - 그대로 두면
     * 문제 코드를 바꾼 케이스가 파일 이름 규칙에 먼저 걸려, 없는 문제 검사를 지워도 통과했다(검증 에이전트).
     */
    private static List<String> broken(String label, Consumer<ObjectNode> breakIt,
            Function<String, List<String>> keyPoints) {
        ObjectNode node = valid(label);
        assertThat(ExplainEvaluation.issues(name(node), node, PROBLEMS, REAL_POINTS))
                .as("망가뜨리기 전 " + label).isEmpty();
        breakIt.accept(node);
        return ExplainEvaluation.issues(name(node), node, PROBLEMS, keyPoints);
    }

    private static List<String> broken(String label, Consumer<ObjectNode> breakIt) {
        return broken(label, breakIt, REAL_POINTS);
    }

    private static String name(ObjectNode node) {
        return node.path("problemCode").asText() + "__" + node.path("label").asText() + ".json";
    }

    @Test
    @DisplayName("정답지 검사가 규칙마다 그 규칙의 이유로 잡는다")
    void theCaseCheckCatchesEachRule() {
        assertThat(broken("SOUND", n -> n.remove("omittedPoint"))).as("null 필드 생략")
                .containsExactly("omittedPoint 가 없다");
        assertThat(broken("SOUND", n -> n.put("label", "GOOD"))).as("모르는 라벨")
                .containsExactly("모르는 라벨 GOOD");
        assertThat(broken("SOUND", n -> n.put("problemCode", "P999_NONE"))).as("없는 문제")
                .containsExactly("없는 문제 P999_NONE");
        assertThat(broken("SOUND", n -> { }, skill -> null)).as("개념 자료가 없는 Skill")
                .singleElement().asString().contains("개념 자료가 없다");
        assertThat(broken("SOUND", n -> n.put("explanation", " "))).as("빈 설명")
                .singleElement().asString().startsWith("설명은 1~");
        assertThat(broken("SOUND", n -> n.put("explanation", "가".repeat(1001)))).as("1000 자 초과")
                .singleElement().asString().startsWith("설명은 1~");
        assertThat(broken("MISSING", n -> n.putNull("omittedPoint"))).as("MISSING 인데 뺀 요점이 없다")
                .singleElement().asString().startsWith("MISSING 은 개념 자료 요점의 번호");
        assertThat(broken("MISSING", n -> n.put("omittedPoint", 99))).as("없는 요점 번호")
                .singleElement().asString().startsWith("MISSING 은 개념 자료 요점의 번호");
        assertThat(broken("MISSING", n -> n.put("omittedPoint", -1))).as("음수 요점 번호")
                .singleElement().asString().startsWith("MISSING 은 개념 자료 요점의 번호");
        assertThat(broken("SOUND", n -> n.put("omittedPoint", 0))).as("SOUND 에 뺀 요점")
                .containsExactly("SOUND 은 omittedPoint 가 null 이다");
        assertThat(broken("MISCONCEPTION", n -> n.putNull("plantedMisconception")))
                .as("MISCONCEPTION 인데 심은 것이 없다")
                .singleElement().asString().startsWith("MISCONCEPTION 은 심은 틀린 주장을");
        assertThat(broken("MISCONCEPTION", n -> n.put("plantedMisconception", "  ")))
                .as("MISCONCEPTION 인데 심은 것이 공백")
                .singleElement().asString().startsWith("MISCONCEPTION 은 심은 틀린 주장을");
        assertThat(broken("VAGUE", n -> n.put("plantedMisconception", "x"))).as("VAGUE 에 심은 오개념")
                .containsExactly("VAGUE 은 plantedMisconception 이 null 이다");
    }

    @Test
    @DisplayName("파일 이름이 문제 · 라벨과 다르면 잡는다")
    void theFileNameMustMatch() {
        ObjectNode node = valid("SOUND");
        assertThat(ExplainEvaluation.issues("P02_GRID_TRAVERSAL__VAGUE.json", node, PROBLEMS, REAL_POINTS))
                .containsExactly("파일 이름이 <문제>__<라벨>.json 이 아니다");
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
        // 화면과 같은 기준이다(app.js 의 if (analysis.misconception)). 빈 문자열은 그리지 않으므로 붙이지 않은 것이고,
        // 공백 한 칸은 "잘못 이해한 점: " 줄을 그리므로 붙인 것이다.
        assertThat(ExplainEvaluation.verdict(List.of(outcome("SOUND", "")))).isZero();
        assertThat(ExplainEvaluation.verdict(List.of(outcome("SOUND", " ")))).isEqualTo(1);
    }

    @Test
    @DisplayName("쓸 수 있는 분석이 0건이면 오지적 0건으로 통과하지 않는다")
    void nothingMeasuredIsNotAPass() {
        assertThat(ExplainEvaluation.verdict(List.of(none("SOUND"), none("MISSING")))).isEqualTo(2);
    }
}
