package dev.codesprint.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codesprint.learning.domain.Dimension;
import dev.codesprint.learning.domain.Evidence;
import dev.codesprint.learning.domain.EvidenceContext;
import dev.codesprint.learning.domain.EvidenceType;
import dev.codesprint.learning.domain.MasteryCalculator;
import dev.codesprint.learning.domain.SkillState;
import dev.codesprint.learning.domain.SkillStatus;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Java 구현이 Python oracle 과 같은 값을 내는지 대조한다.
 *
 * <p>두 구현이 <b>같은 파일</b>(tests/golden/)을 읽는다. 한쪽만 고치면 여기가 깨진다 -
 * 근거: docs/adr/0010-java-implementation-is-checked-against-the-python-oracle.md
 *
 * <p>Addendum PART I 을 사람이 읽고 Java 로 옮기면 "옮긴 게 맞나" 를 확인할 방법이
 * 없다. 실행 가능한 명세가 있으면 대조할 수 있다.
 */
class MasteryGoldenTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Path goldenDir() {
        String root = System.getProperty("codesprint.repoRoot");
        if (root == null) {
            throw new IllegalStateException(
                    "codesprint.repoRoot 시스템 프로퍼티가 없다. build.gradle.kts 를 확인한다.");
        }
        return Path.of(root, "tests", "golden");
    }

    static Stream<Path> goldenCases() throws IOException {
        Path dir = goldenDir();
        try (var files = Files.list(dir)) {
            List<Path> cases = files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
            if (cases.isEmpty()) {
                throw new IllegalStateException("golden fixture 가 하나도 없다: " + dir);
            }
            return cases.stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("goldenCases")
    @DisplayName("Java 구현이 Python oracle 과 같은 값을 낸다")
    void matchesPythonOracle(Path goldenFile) throws IOException {
        JsonNode golden = MAPPER.readTree(Files.readString(goldenFile));
        String skillCode = golden.get("skillCode").asText();
        String description = golden.get("description").asText();

        List<Evidence> evidences = new ArrayList<>();
        for (JsonNode node : golden.get("evidences")) {
            evidences.add(readEvidence(node));
        }

        SkillState actual = MasteryCalculator.recompute(evidences, skillCode);
        JsonNode expected = golden.get("expected");

        // 실패했을 때 어느 케이스가 무엇을 고정하던 것인지 보여야 한다.
        String hint = goldenFile.getFileName() + " — " + description;

        assertThat(actual.skillCode()).as(hint).isEqualTo(expected.get("skillCode").asText());
        assertThat(actual.evidenceCount()).as(hint + " / evidenceCount")
                .isEqualTo(expected.get("evidenceCount").asInt());
        assertThat(actual.status()).as(hint + " / status")
                .isEqualTo(SkillStatus.valueOf(expected.get("status").asText()));

        assertScore(hint + " / mastery", actual.mastery(), expected.get("mastery"));
        assertThat(actual.confidence()).as(hint + " / confidence")
                .isCloseTo(expected.get("confidence").asDouble(), within(1e-9));

        for (Dimension dimension : Dimension.values()) {
            assertScore(hint + " / " + dimension.jsonName(),
                    actual.score(dimension), expected.get(dimension.jsonName()));
        }
    }

    /** null 과 0.0 은 다르다. 그 구분이 무너지면 재정규화 전체가 무의미해진다. */
    private static void assertScore(String hint, Double actual, JsonNode expected) {
        if (expected == null || expected.isNull()) {
            assertThat(actual).as(hint + " (null 이어야 한다)").isNull();
            return;
        }
        assertThat(actual).as(hint + " (null 이면 안 된다)").isNotNull();
        assertThat(actual).as(hint).isCloseTo(expected.asDouble(), within(1e-9));
    }

    @Test
    @DisplayName("golden 이 비어 있으면 이 테스트는 아무것도 검증하지 못한다")
    void goldenSetIsNotEmpty() throws IOException {
        try (var files = Files.list(goldenDir())) {
            assertThat(files.filter(p -> p.toString().endsWith(".json")).count())
                    .as("golden fixture 개수")
                    .isGreaterThanOrEqualTo(10);
        }
    }

    private static Evidence readEvidence(JsonNode node) {
        Map<Dimension, Double> observed = new EnumMap<>(Dimension.class);
        JsonNode observedNode = node.get("observed");
        for (Dimension dimension : Dimension.values()) {
            JsonNode value = observedNode.get(dimension.jsonName());
            if (value != null && !value.isNull()) {
                observed.put(dimension, value.asDouble());
            }
        }

        JsonNode contextNode = node.get("context");
        EvidenceContext context = new EvidenceContext(
                text(contextNode, "problemCode"),
                text(contextNode, "judgeStatus"),
                integer(contextNode, "hintLevel"),
                bool(contextNode, "solutionViewed"),
                bool(contextNode, "independentAttempt"),
                bool(contextNode, "reviewSucceeded"),
                integer(contextNode, "daysSinceLast"),
                text(contextNode, "verdict"));

        JsonNode confidence = node.get("sourceConfidence");
        return new Evidence(
                node.get("sourceEventId").asText(),
                node.get("skillCode").asText(),
                EvidenceType.valueOf(node.get("evidenceType").asText()),
                Evidence.parseInstant(node.get("occurredAt").asText()),
                node.get("weight").asDouble(),
                observed,
                confidence == null || confidence.isNull() ? null : confidence.asDouble(),
                context);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asInt();
    }

    private static Boolean bool(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asBoolean();
    }

    @SuppressWarnings("unused")
    private static UncheckedIOException unused(IOException e) {
        return new UncheckedIOException(e);
    }
}
