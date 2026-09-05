package dev.codesprint.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codesprint.learning.domain.Dimension;
import dev.codesprint.learning.domain.Evidence;
import dev.codesprint.learning.domain.EvidenceType;
import dev.codesprint.learning.domain.JudgeStatus;
import dev.codesprint.learning.domain.SubmissionEvidenceFactory;
import dev.codesprint.learning.domain.SubmissionEvidenceFactory.Submission;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 제출 -> Evidence 매핑이 Python oracle 과 같은 값을 내는지 대조한다.
 *
 * <p>{@link MasteryGoldenTest} 는 <b>이미 만들어진 Evidence 로부터의 계산</b>을 고정한다.
 * 그 앞단인 매핑(Addendum 11~16)은 서비스 계층이 붙기 전까지 Java 에 없어서 고정할
 * 것이 없었다. 이제 두 곳에 있으므로 여기서 대조한다(ADR-0010).
 *
 * <p>이 매핑이 갈리면 mastery 계산이 아무리 정확해도 다른 값이 나온다 - 입력이 다르기
 * 때문이다.
 */
class SubmissionEvidenceGoldenTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Path goldenDir() {
        String root = System.getProperty("codesprint.repoRoot");
        if (root == null) {
            throw new IllegalStateException(
                    "codesprint.repoRoot 시스템 프로퍼티가 없다. build.gradle.kts 를 확인한다.");
        }
        return Path.of(root, "tests", "golden", "evidence");
    }

    static Stream<Path> goldenCases() throws IOException {
        try (var files = Files.list(goldenDir())) {
            List<Path> cases = files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
            if (cases.isEmpty()) {
                throw new IllegalStateException("golden fixture 가 하나도 없다: " + goldenDir());
            }
            return cases.stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("goldenCases")
    @DisplayName("Java 매핑이 Python oracle 과 같은 Evidence 를 만든다")
    void matchesPythonOracle(Path goldenFile) throws IOException {
        JsonNode golden = MAPPER.readTree(Files.readString(goldenFile));
        String hint = goldenFile.getFileName() + " — " + golden.get("description").asText();

        Evidence actual = SubmissionEvidenceFactory.fromSubmission(readInput(golden.get("input")));
        JsonNode expected = golden.get("expected");

        // null 은 "이 Skill 에 아무것도 기록하지 않는다" 다. 관측값이 전부 null 인
        // Evidence 와 다르다 - 그것은 confidence 만 올린다.
        if (expected.isNull()) {
            assertThat(actual).as(hint + " (Evidence 를 만들지 않아야 한다)").isNull();
            return;
        }
        assertThat(actual).as(hint + " (Evidence 를 만들어야 한다)").isNotNull();

        assertThat(SubmissionEvidenceFactory.evidenceId(
                actual.sourceEventId(), actual.skillCode()))
                .as(hint + " / evidenceId")
                .isEqualTo(expected.get("evidenceId").asText());
        assertThat(actual.sourceEventId()).as(hint + " / sourceEventId")
                .isEqualTo(expected.get("sourceEventId").asText());
        assertThat(actual.skillCode()).as(hint + " / skillCode")
                .isEqualTo(expected.get("skillCode").asText());
        assertThat(actual.evidenceType()).as(hint + " / evidenceType")
                .isEqualTo(EvidenceType.valueOf(expected.get("evidenceType").asText()));
        assertThat(actual.occurredAt()).as(hint + " / occurredAt")
                .isEqualTo(Evidence.parseInstant(expected.get("occurredAt").asText()));
        assertThat(actual.weight()).as(hint + " / weight")
                .isCloseTo(expected.get("weight").asDouble(), within(1e-9));

        JsonNode sourceConfidence = expected.get("sourceConfidence");
        if (sourceConfidence.isNull()) {
            assertThat(actual.sourceConfidence()).as(hint + " / sourceConfidence").isNull();
        } else {
            assertThat(actual.sourceConfidence()).as(hint + " / sourceConfidence")
                    .isCloseTo(sourceConfidence.asDouble(), within(1e-9));
        }

        JsonNode observed = expected.get("observed");
        for (Dimension dimension : Dimension.values()) {
            JsonNode value = observed.get(dimension.jsonName());
            String label = hint + " / observed." + dimension.jsonName();
            if (value.isNull()) {
                // 관측하지 않은 차원을 0 으로 채우면 "못한다" 가 된다(Addendum 4).
                assertThat(actual.observed(dimension)).as(label + " (null 이어야 한다)").isNull();
            } else {
                assertThat(actual.observed(dimension)).as(label + " (null 이면 안 된다)")
                        .isNotNull();
                assertThat(actual.observed(dimension)).as(label)
                        .isCloseTo(value.asDouble(), within(1e-9));
            }
        }

        JsonNode context = expected.get("context");
        assertThat(actual.context().problemCode()).as(hint + " / context.problemCode")
                .isEqualTo(text(context, "problemCode"));
        assertThat(actual.context().judgeStatus()).as(hint + " / context.judgeStatus")
                .isEqualTo(text(context, "judgeStatus"));
        assertThat(actual.context().hintLevel()).as(hint + " / context.hintLevel")
                .isEqualTo(context.get("hintLevel").asInt());
        assertThat(actual.context().solutionViewed()).as(hint + " / context.solutionViewed")
                .isEqualTo(context.get("solutionViewed").asBoolean());
        // 이 값이 틀리면 MASTERED 인 사용자가 힌트를 다 보고 틀렸을 때 WEAKENED 로
        // 떨어진다 - 독립 풀이를 시도한 적이 없는데도.
        assertThat(actual.context().independentAttempt())
                .as(hint + " / context.independentAttempt")
                .isEqualTo(context.get("independentAttempt").asBoolean());
    }

    @Test
    @DisplayName("golden 이 비어 있으면 이 테스트는 아무것도 검증하지 못한다")
    void goldenSetIsNotEmpty() throws IOException {
        try (var files = Files.list(goldenDir())) {
            assertThat(files.filter(p -> p.toString().endsWith(".json")).count())
                    .as("evidence golden fixture 개수")
                    .isGreaterThanOrEqualTo(15);
        }
    }

    @Test
    @DisplayName("판정이 끝나지 않은 제출로는 Evidence 를 만들지 않는다")
    void rejectsNonTerminalStatus() {
        // 그대로 통과시키면 관측값 없는 Evidence 가 되어, 측정한 게 없는데
        // confidence 와 evidenceCount 만 오른다.
        assertThatThrownBy(() -> SubmissionEvidenceFactory.fromSubmission(
                submission(JudgeStatus.QUEUED, "2026-09-05T10:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("QUEUED");
    }

    @Test
    @DisplayName("시간대 없는 occurredAt 은 거부한다")
    void rejectsNaiveTimestamp() {
        // 저장 뒤에 알면 늦다. Evidence 는 append-only 다.
        assertThatThrownBy(() -> SubmissionEvidenceFactory.fromSubmission(
                submission(JudgeStatus.ACCEPTED, "2026-09-05T10:00:00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("시간대");
    }

    private static Submission submission(JudgeStatus status, String occurredAt) {
        return new Submission("submission:1", "BFS_GRID_TRAVERSAL", 1.0, status, 0, false,
                occurredAt, EvidenceType.PROBLEM_SUBMISSION, null, null, null, "NORMAL",
                "P02_GRID_TRAVERSAL", null);
    }

    private static Submission readInput(JsonNode input) {
        return new Submission(
                input.get("sourceEventId").asText(),
                input.get("skillCode").asText(),
                input.get("skillWeight").asDouble(),
                JudgeStatus.valueOf(input.get("judgeStatus").asText()),
                input.get("hintLevel").asInt(),
                input.get("solutionViewed").asBoolean(),
                input.get("occurredAt").asText(),
                EvidenceType.valueOf(input.get("evidenceType").asText()),
                input.get("solveSeconds").isNull() ? null : input.get("solveSeconds").asDouble(),
                input.get("expectedSolveSeconds").isNull()
                        ? null : input.get("expectedSolveSeconds").asInt(),
                text(input, "algorithmSelection"),
                input.get("mode").asText(),
                text(input, "problemCode"),
                text(input, "tleCause"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
