package dev.codesprint.api;

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
 * 문제 조회와 "다음에 풀 문제".
 * 계약: contracts/problem-view.schema.json, contracts/next-problem.schema.json.
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

    public ProblemController(ProblemCatalog catalog, NextProblemService nextProblem) {
        this.catalog = catalog;
        this.nextProblem = nextProblem;
    }

    /** weight 를 내보내지 않는다. 채점 가중치는 내부 값이다. */
    public record SkillView(String skillCode, String role) {
    }

    public record SampleView(String input, String expectedOutput) {
    }

    public record ProblemView(String code, String title, String kind, String statement,
            Integer timeLimitMs, Integer memoryLimitMb, Integer expectedSolveSeconds,
            List<SkillView> skills, List<SampleView> samples) {
    }

    /**
     * @param problem 고르지 못했으면 null. 왜 없는지는 reason 에 적힌다 - 빈 응답으로
     *     두면 "아직 안 끝났다" 와 "줄 문제가 없다" 를 구분할 수 없다.
     */
    public record NextProblemResponse(long submissionId, String action, String targetSkill,
            ProblemView problem, String reason) {
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
                .sorted()
                .map(catalog::find)
                .map(problem -> new ProblemSummary(
                        problem.code(), problem.title(), problem.kind(),
                        problem.primarySkill()))
                .toList());
    }

    @GetMapping("/problems/{problemCode}")
    public ResponseEntity<ProblemView> find(@PathVariable String problemCode) {
        ProblemDefinition problem = catalog.find(problemCode);
        return problem == null
                ? ResponseEntity.notFound().build()
                : ResponseEntity.ok(toView(problem));
    }

    /**
     * 이 제출의 결과가 가리키는 다음 문제.
     *
     * <p>결과가 아직 반영되지 않았으면 404 다. 그때는 고를 근거 자체가 없다 -
     * {@code GET /api/submissions/{id}} 가 {@code PENDING} 을 돌려주는 구간이다.
     */
    @GetMapping("/submissions/{submissionId}/next-problem")
    public ResponseEntity<NextProblemResponse> next(@PathVariable long submissionId) {
        return nextProblem.resolve(submissionId)
                .map(resolution -> new NextProblemResponse(
                        resolution.submissionId(),
                        resolution.action(),
                        resolution.targetSkill(),
                        resolution.problem() == null ? null : toView(resolution.problem()),
                        resolution.reason()))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private ProblemView toView(ProblemDefinition problem) {
        return new ProblemView(
                problem.code(),
                problem.title(),
                problem.kind(),
                problem.statement(),
                problem.timeLimitMs(),
                problem.memoryLimitMb(),
                problem.expectedSolveSeconds(),
                problem.skills().stream()
                        .map(link -> new SkillView(link.skillCode(), link.role()))
                        .toList(),
                // 필터는 카탈로그가 한다. 여기서 다시 거르면 두 곳이 되고,
                // 한쪽을 잊으면 그대로 유출이다.
                catalog.samplesOf(problem.code()).stream()
                        .map(sample -> new SampleView(sample.input(), sample.expectedOutput()))
                        .toList());
    }
}
