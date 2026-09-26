package dev.codesprint.reviewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import dev.codesprint.generator.DraftPrompt;
import dev.codesprint.generator.GeneratorSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 생성기가 Reviewer 와 같은 경계 위에 있는가. 정본: ADR-0032.
 *
 * <p>Reviewer 설정 하네스({@link ReviewerSettings})와 같은 패키지에 둔다 - 두 설정을
 * 나란히 비교해야 하기 때문이다.
 */
class GeneratorSharesReviewerSettingsTest {

    @Test
    @DisplayName("생성기는 Reviewer 와 같은 명령을 쓴다 - 도구 권한이 한쪽만 열리지 않는다")
    void theGeneratorUsesTheSameCommand() {
        assertThat(GeneratorSettings.load().command())
                .isEqualTo(ReviewerSettings.load().command())
                .contains("--tools", "");
    }

    @Test
    @DisplayName("프롬프트 파일의 자리를 코드가 전부 채운다")
    void everyPlaceholderIsFilled() {
        // 채우지 못한 자리가 남으면 PromptTemplate 이 거부한다. 파일과 코드가 갈리면
        // 모델을 부르기도 전에 여기서 드러난다.
        Map<String, String> values = new HashMap<>();
        for (String name : DraftPrompt.PLACEHOLDERS) {
            values.put(name, "x");
        }
        String version = GeneratorSettings.load().promptVersion();
        assertThat(version).isEqualTo("problem-v3");
        assertThatCode(() -> PromptTemplate.load(version).render(values))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("실제 커리큘럼으로 프롬프트 값을 만들 수 있다")
    void valuesAreBuiltFromTheRealCurriculum() {
        Map<String, String> values = DraftPrompt.values(
                Path.of(System.getProperty("codesprint.repoRoot")), "PYTHON_LIST_BASIC");
        assertThat(values.keySet()).containsExactlyInAnyOrderElementsOf(DraftPrompt.PLACEHOLDERS);
        assertThat(values.get("existingProblems")).contains("P11_LIST_BASIC");
        // SYSTEM 이 부여하는 실수는 초안에 쓸 수 없다고 알려 준다.
        assertThat(values.get("allowedMistakes")).doesNotContain("SYNTAX_ERROR");
        assertThat(values.get("skillControlRule")).contains("null 로 둔다");
        // 정답만으로 잴 수 없는 Skill 은 보조 후보에 없다 - 채택 검사가 막는 것을 권하지 않는다.
        assertThat(values.get("secondaryCandidates")).doesNotContain("PYTHON_DEQUE_BASIC");
    }

    @Test
    @DisplayName("정답만으로 잴 수 없는 Skill 이면 대조 풀이를 요구한다 - 커리큘럼 데이터에서 읽는다")
    void theSkillControlRuleFollowsTheCurriculum() {
        // ADR-0033. deque 없이 인덱스로 풀리는 문제가 채택돼 PYTHON_DEQUE_BASIC 을 재지 못했다.
        Map<String, String> values = DraftPrompt.values(
                Path.of(System.getProperty("codesprint.repoRoot")), "PYTHON_DEQUE_BASIC");
        assertThat(values.get("skillControlRule")).contains("skillControl 을 반드시 채운다");
    }

    @Test
    @DisplayName("Python 채택기가 쓰는 고정 초안이 같은 계약을 통과한다")
    void theSharedFixtureSatisfiesTheContract() throws Exception {
        // 생성(Java)과 채택(Python)이 같은 계약을 읽는다. 여기서 통과하는 초안이
        // tools/meta_test_adoption.py 의 출발점이다.
        Path root = Path.of(System.getProperty("codesprint.repoRoot"));
        var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(Files.readString(root.resolve("contracts/problem-draft.llm.schema.json")));
        for (String fixture : new String[] {"good-draft.json", "good-queue-draft.json"}) {
            var draft = new ObjectMapper().readTree(
                    Files.readString(root.resolve("tests/generation/" + fixture)));
            assertThat(schema.validate(draft)).as(fixture).isEmpty();
        }
    }
}
