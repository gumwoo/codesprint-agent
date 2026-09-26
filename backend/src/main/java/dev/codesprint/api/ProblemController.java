package dev.codesprint.api;

import dev.codesprint.curriculum.CurriculumCatalog.ConceptDefinition;
import dev.codesprint.learning.service.NextProblemService;
import dev.codesprint.problem.ProblemCatalog;
import dev.codesprint.problem.ProblemCatalog.ProblemDefinition;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 문제 조회와 다음 학습 자료.
 * 계약: contracts/problem-view.schema.json, contracts/concept-view.schema.json,
 * contracts/next-problem.schema.json.
 *
 * <p><b>hidden Test Case 를 내보내지 않는다.</b> 이 저장소의 문제는 파일을 열면 다
 * 보이지만(ADR-0008), 그것은 여기 있는 것이 fixture 이기 때문이지 API 가 그래도
 * 된다는 뜻이 아니다. 실서비스 문제은행이 붙으면 이 응답이 그대로 유출 경로가 된다.
 *
 * <p>다음 문제를 <b>여기서 정하지 않는다.</b> 무엇을 할지는 Decision Engine 이 이미
 * 정했고(ADR-0002) 제출 행에 남아 있다. 이 API 는 그 행동을 문제로 옮길 뿐이다.
 */
@RestController
@RequestMapping("/api")
public class ProblemController {

    private final ProblemCatalog catalog;
    private final NextProblemService nextProblem;
    private final dev.codesprint.mocktest.MockTestService mockTests;
    private final dev.codesprint.learning.persistence.UserRepository users;
    private final dev.codesprint.learning.persistence.SubmissionRepository submissions;
    private final dev.codesprint.curriculum.CurriculumCatalog curriculum;

    public ProblemController(ProblemCatalog catalog, NextProblemService nextProblem,
            dev.codesprint.mocktest.MockTestService mockTests,
            dev.codesprint.learning.persistence.UserRepository users,
            dev.codesprint.learning.persistence.SubmissionRepository submissions,
            dev.codesprint.curriculum.CurriculumCatalog curriculum) {
        this.catalog = catalog;
        this.nextProblem = nextProblem;
        this.mockTests = mockTests;
        this.users = users;
        this.submissions = submissions;
        this.curriculum = curriculum;
    }

    /** weight 를 내보내지 않는다. 채점 가중치는 내부 값이다. */
    public record SkillView(String skillCode, String role) {
    }

    public record SampleView(String input, String expectedOutput) {
    }

    /**
     * @param skills 학습 모드가 EXAM 이면 비어 있다 - 유형을 숨긴다(ADR-0043).
     * @param concept 학습 모드가 GUIDED 면 PRIMARY Skill 의 개념 자료, 아니면 null 이다.
     */
    public record ProblemView(String code, String title, String kind, String statement,
            Integer timeLimitMs, Integer memoryLimitMb, Integer expectedSolveSeconds,
            List<SkillView> skills, List<SampleView> samples, ConceptView concept) {
    }

    public record ConceptView(String skillCode, String title, String summary,
            List<String> keyPoints, String example, String selfCheck) {
    }

    /**
     * @param problem 다음에 풀 문제. 문제를 요구하지 않는 행동이면 null.
     * @param concept REVIEW_CONCEPT가 가리키는 자료. 다른 행동이면 null.
     *     둘 다 없을 수 있으며 왜 없는지는 reason에 적힌다. 빈 응답으로 두면
     *     "아직 안 끝났다"와 "다음 자료가 없다"를 구분할 수 없다.
     */
    public record NextProblemResponse(long submissionId, String action, String targetSkill,
            ProblemView problem, ConceptView concept, String reason) {
    }

    /** 목록에 담는 것. 본문과 예시는 없다 - 고르는 화면이지 푸는 화면이 아니다. */
    public record ProblemSummary(String code, String title, String kind, String primarySkill) {
    }

    public record ProblemListResponse(List<ProblemSummary> problems) {
    }

    /**
     * 풀 수 있는 문제 목록. 계약: contracts/problem-list.schema.json.
     *
     * <p><b>이것은 학습 경로가 아니다.</b> 무엇을 다음에 풀지는 Decision Engine 이
     * 정하고(ADR-0002) {@code next-problem} 이 돌려준다. 이 목록은 <b>첫 제출 이전의
     * 진입점</b>이다 - 제출이 하나도 없으면 결정할 근거 자체가 없다.
     */
    @GetMapping("/problems")
    public ProblemListResponse list() {
        // code 순으로 고정한다. 카탈로그의 Map 은 순서를 보장하지 않아, 그대로
        // 내보내면 같은 데이터인데 실행할 때마다 목록 순서가 달라진다.
        return new ProblemListResponse(catalog.codes().stream()
                .sorted(ProblemCatalog.BY_NUMBER)
                .map(catalog::find)
                .map(problem -> new ProblemSummary(
                        problem.code(), problem.title(), problem.kind(),
                        problem.primarySkill()))
                .toList());
    }

    /**
     * @param userId 있으면 그 사용자의 학습 모드를 따른다(ADR-0043). 없으면 NORMAL 과 같다.
     */
    @GetMapping("/problems/{problemCode}")
    public ResponseEntity<ProblemView> find(@PathVariable String problemCode,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Long userId) {
        ProblemDefinition problem = catalog.find(problemCode);
        if (problem == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toView(problem, modeOf(userId)));
    }

    private dev.codesprint.learning.domain.LearningMode modeOf(Long userId) {
        return userId == null ? dev.codesprint.learning.domain.LearningMode.NORMAL
                : users.findById(userId)
                        .map(dev.codesprint.learning.persistence.UserRow::learningMode)
                        .orElse(dev.codesprint.learning.domain.LearningMode.NORMAL);
    }

    /**
     * 이 제출의 결과가 가리키는 다음 문제.
     *
     * <p>결과가 아직 반영되지 않았으면 404 다. 그때는 고를 근거 자체가 없다 -
     * {@code GET /api/submissions/{id}} 가 {@code PENDING} 을 돌려주는 구간이다.
     */
    @GetMapping("/submissions/{submissionId}/next-problem")
    public ResponseEntity<NextProblemResponse> next(@PathVariable long submissionId) {
        // 시험 중의 제출에는 다음 행동을 보여 주지 않는다(PRD §84).
        if (mockTests.hidesUntilEnd(submissionId)) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.CONFLICT).build();
        }
        var mode = modeOf(submissions.findById(submissionId)
                .map(dev.codesprint.learning.persistence.SubmissionRow::userId).orElse(null));
        return nextProblem.resolve(submissionId)
                .map(resolution -> new NextProblemResponse(
                        resolution.submissionId(),
                        resolution.action(),
                        resolution.targetSkill(),
                        resolution.problem() == null ? null : toView(resolution.problem(), mode),
                        resolution.concept() == null ? null : toView(resolution.concept()),
                        resolution.reason()))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private ProblemView toView(ProblemDefinition problem,
            dev.codesprint.learning.domain.LearningMode mode) {
        var concept = mode.attachesConcept() ? curriculum.concept(problem.primarySkill()) : null;
        return new ProblemView(
                problem.code(),
                problem.title(),
                problem.kind(),
                problem.statement(),
                problem.timeLimitMs(),
                problem.memoryLimitMb(),
                problem.expectedSolveSeconds(),
                mode.showsSkills()
                        ? problem.skills().stream()
                                .map(link -> new SkillView(link.skillCode(), link.role()))
                                .toList()
                        : List.of(),
                // 필터는 카탈로그가 한다. 여기서 다시 거르면 두 곳이 되고,
                // 한쪽을 잊으면 그대로 유출이다.
                catalog.samplesOf(problem.code()).stream()
                        .map(sample -> new SampleView(sample.input(), sample.expectedOutput()))
                        .toList(),
                concept == null ? null : toView(concept));
    }

    private static ConceptView toView(ConceptDefinition concept) {
        return new ConceptView(concept.skillCode(), concept.title(), concept.summary(),
                concept.keyPoints(), concept.example(), concept.selfCheck());
    }
}
