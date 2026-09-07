package dev.codesprint.api;

import dev.codesprint.learning.persistence.UserRepository;
import dev.codesprint.learning.service.DiagnosticService;
import dev.codesprint.learning.service.DiagnosticService.Step;
import dev.codesprint.problem.ProblemCatalog.ProblemDefinition;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 초기 진단. 계약: contracts/diagnostic-step.schema.json. 정본: PRD §114, ADR-0018.
 *
 * <p>신규 사용자에게는 시작점이 없다. {@code 내 Skill} 을 열면 전부 {@code –} 이고
 * 뿌리 둘만 {@code READY} 인데, 그 둘 중 무엇부터인지 아무도 말해 주지 않는다.
 *
 * <h2>세션을 만들지 않는다</h2>
 *
 * <p>PRD §114 는 {@code POST /api/diagnostics/start} 로 세션을 열고 id 로 이어 가는
 * 모양이다. 여기서는 <b>조회 하나</b>다 - 진행 상태가 Evidence 에서 그대로 나오기
 * 때문에 따로 적어 둘 것이 없다(ADR-0018 §1). 적어 두면 Evidence 와 어긋날 수 있고,
 * 어긋나면 어느 쪽이 정본인지 물어야 한다.
 *
 * <p>그래서 <b>시작도 끝도 명령이 아니다.</b> 사용자는 이 응답이 주는 문제를 평소처럼
 * {@code POST /api/problems/{code}/submit} 으로 낸다 - 진단 전용 제출 경로가 없다.
 * 진단 중이라고 다른 산식을 타면 같은 Skill 의 mastery 를 정하는 길이 둘이 된다.
 */
@RestController
@RequestMapping("/api")
public class DiagnosticController {

    private final DiagnosticService diagnostic;
    private final UserRepository users;

    public DiagnosticController(DiagnosticService diagnostic, UserRepository users) {
        this.diagnostic = diagnostic;
        this.users = users;
    }

    public record ProblemView(String code, String title, String statement,
            Integer timeLimitMs, Integer memoryLimitMb) {
    }

    /**
     * @param problem 지금 풀 문제. 끝났거나 물어볼 문제가 없으면 null 이다 -
     *     생략하지 않는다. 생략은 "모른다" 이고 null 은 "확인했고 없다" 다.
     * @param done 진단이 끝났는가. 모든 Skill 이 평가됐거나 함의됐을 때다.
     */
    public record StepResponse(long userId, boolean done, String targetSkill,
            ProblemView problem, int assessed, int total, String reason) {
    }

    /**
     * 지금 물어야 할 것. <b>같은 상태면 같은 답이 온다</b> - 여러 번 불러도 진행이
     * 앞서 나가지 않는다. 진도는 제출이 옮긴다.
     */
    @GetMapping("/users/{userId}/diagnostic")
    public ResponseEntity<StepResponse> next(@PathVariable long userId) {
        if (!users.existsById(userId)) {
            return ResponseEntity.notFound().build();
        }
        Step step = diagnostic.nextStep(userId);
        return ResponseEntity.ok(new StepResponse(userId, step.done(), step.targetSkill(),
                toView(step.problem()), step.assessed(), step.total(), step.reason()));
    }

    private static ProblemView toView(ProblemDefinition problem) {
        return problem == null ? null : new ProblemView(problem.code(), problem.title(),
                problem.statement(), problem.timeLimitMs(), problem.memoryLimitMb());
    }
}
