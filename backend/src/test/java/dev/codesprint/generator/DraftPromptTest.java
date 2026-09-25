package dev.codesprint.generator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 한 실행에서 앞서 만든 초안을 다음 초안에게 "이미 있는 문제" 로 보여 주는가.
 *
 * <p>같은 프롬프트로 둘을 만들면 같은 문제가 나왔다 - CORE-1 · CORE-2 에서 채택된 문제 중
 * 여덟이 앞 초안과 같은 문제라 철회됐다(ADR-0036).
 */
class DraftPromptTest {

    @Test
    @DisplayName("앞서 만든 초안이 기존 문제 목록 뒤에 붙는다")
    void earlierDraftsAreAppended() {
        Map<String, String> values = Map.of("existingProblems", "- P11 리스트: 본문");
        String made = DraftPrompt.summary("X-1", "구간 합", "정수 N 개의 합을 구한다");

        Map<String, String> next = DraftPrompt.withDrafts(values, made);

        assertThat(next.get("existingProblems"))
                .startsWith("- P11 리스트: 본문")
                .contains("X-1 구간 합: 정수 N 개의 합을 구한다");
        assertThat(values.get("existingProblems")).as("원래 값은 그대로다")
                .isEqualTo("- P11 리스트: 본문");
    }

    @Test
    @DisplayName("기존 문제가 없으면 '(없음)' 대신 초안만 보여 준다")
    void noExistingProblemsIsReplaced() {
        Map<String, String> next = DraftPrompt.withDrafts(
                Map.of("existingProblems", "(없음)"), DraftPrompt.summary("X-1", "t", "s"));
        assertThat(next.get("existingProblems")).isEqualTo("- X-1 t: s");
    }

    @Test
    @DisplayName("첫 초안에는 아무것도 붙지 않는다")
    void theFirstDraftSeesOnlyTheBank() {
        Map<String, String> values = Map.of("existingProblems", "(없음)");
        assertThat(DraftPrompt.withDrafts(values, "")).isSameAs(values);
    }
}
