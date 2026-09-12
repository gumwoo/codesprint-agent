package dev.codesprint.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.codesprint.curriculum.CurriculumCatalog;
import dev.codesprint.learning.persistence.ProblemRepository;
import dev.codesprint.learning.persistence.SubmissionRepository;
import dev.codesprint.learning.persistence.SubmissionRow;
import dev.codesprint.learning.service.NextProblemService;
import dev.codesprint.problem.ProblemCatalog;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** DB 없이 저장된 행동을 학습 자료로 옮기는 경계를 검사한다. */
class NextProblemConceptTest {
    private final SubmissionRepository submissions = mock(SubmissionRepository.class);
    private final NextProblemService service = new NextProblemService(
            mock(ProblemCatalog.class), submissions, mock(ProblemRepository.class),
            new CurriculumCatalog());

    @Test
    @DisplayName("REVIEW_CONCEPT만 대상 Skill의 개념 자료를 받는다")
    void reviewConceptResolvesToTargetMaterial() {
        SubmissionRow row = submission(42L, "REVIEW_CONCEPT", "BFS_GRID_TRAVERSAL");
        when(submissions.findById(42L)).thenReturn(Optional.of(row));
        NextProblemService.Resolution resolution = service.resolve(42L).orElseThrow();
        assertThat(resolution.problem()).isNull();
        assertThat(resolution.concept()).isNotNull();
        assertThat(resolution.concept().skillCode()).isEqualTo(resolution.targetSkill());
        assertThat(resolution.concept().keyPoints()).isNotEmpty();
    }

    @Test
    @DisplayName("다른 행동에는 개념 자료를 끼워 넣지 않는다")
    void anotherActionDoesNotGainConceptMaterial() {
        SubmissionRow row = submission(43L, "CONTINUE", null);
        when(submissions.findById(43L)).thenReturn(Optional.of(row));
        assertThat(service.resolve(43L).orElseThrow().concept()).isNull();
    }

    @Test
    @DisplayName("REVIEW_CONCEPT 대상 자료가 없으면 계약 위반 응답을 만들지 않는다")
    void missingConceptMaterialFailsFast() {
        CurriculumCatalog curriculum = mock(CurriculumCatalog.class);
        NextProblemService guardedService = new NextProblemService(
                mock(ProblemCatalog.class), submissions, mock(ProblemRepository.class),
                curriculum);
        SubmissionRow row = submission(44L, "REVIEW_CONCEPT", "UNKNOWN_SKILL");
        when(submissions.findById(44L)).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> guardedService.resolve(44L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UNKNOWN_SKILL");
    }

    private static SubmissionRow submission(long id, String action, String target) {
        SubmissionRow row = mock(SubmissionRow.class);
        when(row.id()).thenReturn(id);
        when(row.nextActionType()).thenReturn(action);
        when(row.nextActionTarget()).thenReturn(target);
        when(row.nextProblemReason()).thenReturn("테스트 이유");
        return row;
    }
}
