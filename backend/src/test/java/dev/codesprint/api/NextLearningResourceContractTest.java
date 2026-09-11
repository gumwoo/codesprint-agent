package dev.codesprint.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import dev.codesprint.curriculum.CurriculumCatalog.ConceptDefinition;
import dev.codesprint.learning.service.NextProblemService;
import dev.codesprint.problem.ProblemCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/** PostgreSQL 없이 next-problem의 개념 자료 응답과 공개 계약을 직접 대조한다. */
class NextLearningResourceContractTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("REVIEW_CONCEPT 응답은 개념 자료 계약을 지킨다")
    void conceptResponseMatchesContract() throws Exception {
        NextProblemService service = mock(NextProblemService.class);
        ConceptDefinition concept = new ConceptDefinition(
                "BFS_GRID_TRAVERSAL", "격자 BFS", "좌표를 정점으로 보는 BFS다.",
                List.of("경계를 먼저 확인한다", "큐에 넣을 때 방문 처리한다"),
                "queue.append((row, col))", "정점과 간선이 무엇인지 말해 보세요.");
        when(service.resolve(42L)).thenReturn(Optional.of(new NextProblemService.Resolution(
                42L, "REVIEW_CONCEPT", concept.skillCode(), null, concept,
                "반복 실패한 Skill의 개념을 다시 확인한다")));

        ProblemController controller = new ProblemController(mock(ProblemCatalog.class), service);
        ResponseEntity<ProblemController.NextProblemResponse> response = controller.next(42L);
        JsonNode body = MAPPER.valueToTree(response.getBody());

        assertThat(body.get("problem").isNull()).isTrue();
        assertThat(body.get("concept").get("skillCode").asText())
                .isEqualTo(body.get("targetSkill").asText());
        assertThat(schema().validate(body)).as("next-problem 계약 위반").isEmpty();
    }

    @Test
    @DisplayName("계약은 REVIEW_CONCEPT의 누락된 concept를 거부한다")
    void reviewConceptRequiresConceptMaterial() throws Exception {
        JsonNode body = MAPPER.readTree("""
                {"submissionId":42,"action":"REVIEW_CONCEPT","targetSkill":"BFS_BASIC",
                 "problem":null,"concept":null,"reason":"개념 복습"}
                """);
        assertThat(schema().validate(body)).isNotEmpty();
    }

    @Test
    @DisplayName("계약은 다른 행동에 끼어든 concept를 거부한다")
    void otherActionsRejectConceptMaterial() throws Exception {
        JsonNode body = MAPPER.readTree("""
                {"submissionId":42,"action":"CONTINUE","targetSkill":null,"problem":null,
                 "concept":{"skillCode":"BFS_BASIC","title":"t","summary":"s",
                 "keyPoints":["p"],"example":"e","selfCheck":"c"},"reason":"계속"}
                """);
        assertThat(schema().validate(body)).isNotEmpty();
    }

    private static JsonSchema schema() throws Exception {
        Path contracts = Path.of(System.getProperty("codesprint.repoRoot")).resolve("contracts");
        return JsonSchemaFactory
                .builder(JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012))
                .schemaMappers(mappers -> mappers.mapPrefix(
                        "https://codesprint.dev/contracts/", contracts.toUri().toString()))
                .build()
                .getSchema(Files.readString(contracts.resolve("next-problem.schema.json")));
    }
}
