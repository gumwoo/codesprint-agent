package dev.codesprint.learning.service;

import dev.codesprint.learning.domain.ActionType;
import dev.codesprint.learning.persistence.ProblemRepository;
import dev.codesprint.learning.persistence.ProblemRow;
import dev.codesprint.learning.persistence.SubmissionRepository;
import dev.codesprint.learning.persistence.SubmissionRow;
import dev.codesprint.problem.ProblemCatalog;
import dev.codesprint.problem.ProblemCatalog.ProblemDefinition;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결정된 다음 행동을 <b>실제 문제</b>로 옮긴다.
 *
 * <p><b>여기서 결정하지 않는다.</b> 무엇을 할지는 Decision Engine 이 이미 정했고
 * (ADR-0002) 제출 행에 남아 있다. 이 클래스가 하는 일은 그 행동이 겨냥한 Skill 에
 * 맞는 문제를 고르는 것뿐이다.
 *
 * <p>추천 알고리즘이 아니다. 난이도 조절도, 학습 계획도 여기 없다 - 그것들은 각각
 * 다른 결정이고 다른 규칙을 갖는다. 이 클래스가 커지기 시작하면 Decision Engine 이
 * 두 곳으로 갈라진다.
 */
@Service
public class NextProblemService {

    private final ProblemCatalog catalog;
    private final SubmissionRepository submissions;
    private final ProblemRepository problems;

    public NextProblemService(ProblemCatalog catalog, SubmissionRepository submissions,
            ProblemRepository problems) {
        this.catalog = catalog;
        this.submissions = submissions;
        this.problems = problems;
    }

    /**
     * @param problem 고르지 못했으면 null. 그 행동에 맞는 문제가 없거나, 애초에
     *     문제를 요구하지 않는 행동이다.
     */
    public record Resolution(long submissionId, String action, String targetSkill,
            ProblemDefinition problem, String reason) {
    }

    @Transactional(readOnly = true)
    public Optional<Resolution> resolve(long submissionId) {
        SubmissionRow submission = submissions.findById(submissionId).orElse(null);
        if (submission == null) {
            return Optional.empty();
        }
        // 아직 결과가 반영되지 않았으면 고를 근거가 없다.
        if (submission.nextActionType() == null) {
            return Optional.empty();
        }

        ActionType action = ActionType.valueOf(submission.nextActionType());
        String target = submission.nextActionTarget();

        return Optional.of(switch (action) {
            case MICRO_DRILL -> pick(submission, action, target, "MICRO_DRILL",
                    "확정된 실수를 겨냥한 드릴");
            // 다른 Skill 로 보낼 때는 일반 문제다. 드릴은 이미 배운 것을 좁게 다시
            // 다루는 것이라, 아직 시작도 안 한 Skill 에 주면 맥락이 없다.
            case CHANGE_SKILL -> pick(submission, action, target, "NORMAL",
                    "선수 Skill 을 먼저 채운다");
            case RETRY_VARIANT -> pick(submission, action, target, "NORMAL",
                    "같은 Skill 의 다른 문제로 연습한다");

            // 문제를 주지 않는 행동들. 각각 이유가 다르므로 뭉뚱그리지 않는다.
            case CONTINUE -> none(submission, action, target,
                    "같은 문제를 이어서 푼다 - 새 문제를 고르지 않는다");
            case REVIEW_CONCEPT -> none(submission, action, target,
                    "개념 자료가 아직 없다 - 문제로 대체하지 않는다");
            case SCHEDULE_REVIEW -> none(submission, action, target,
                    "복습 일정이 아직 없다");
            case UNLOCK_NEXT -> none(submission, action, target,
                    "다음 Skill 을 고르는 규칙이 아직 없다");
            default -> none(submission, action, target,
                    "이 행동을 문제로 옮기는 규칙이 아직 없다: " + action);
        });
    }

    private Resolution pick(SubmissionRow submission, ActionType action, String skillCode,
            String kind, String why) {

        if (skillCode == null) {
            return none(submission, action, null, "대상 Skill 이 없다");
        }
        List<ProblemDefinition> candidates = catalog.byPrimarySkill(skillCode, kind);
        if (candidates.isEmpty()) {
            // 자동 드릴 대상 Mistake 에는 드릴 문제가 반드시 있어야 한다 -
            // tools/check_problems.py 가 CI 에서 확인한다. 여기 걸리면 데이터가
            // 아니라 그 검사가 놓친 것이다.
            return none(submission, action, skillCode,
                    kind + " 종류의 " + skillCode + " 문제가 없다");
        }

        // 아직 풀지 못한 것을 먼저 준다. 이미 맞힌 문제를 다시 주면 연습이 되지 않고,
        // 그 결과가 Evidence 로 쌓여 mastery 를 부풀린다.
        List<String> solved = solvedCodes(submission.userId());
        String justAttempted = codeOf(submission.problemId());

        return candidates.stream()
                .filter(problem -> !solved.contains(problem.code()))
                .filter(problem -> !problem.code().equals(justAttempted))
                .findFirst()
                .map(problem -> new Resolution(submission.id(), action.name(), skillCode,
                        problem, why))
                // 전부 풀었으면 다시 준다. 같은 문제를 또 주지는 않는다.
                .orElseGet(() -> candidates.stream()
                        .filter(problem -> !problem.code().equals(justAttempted))
                        .findFirst()
                        .map(problem -> new Resolution(submission.id(), action.name(), skillCode,
                                problem, why + " (전부 푼 적이 있어 다시 낸다)"))
                        .orElseGet(() -> none(submission, action, skillCode,
                                "방금 푼 문제 말고는 " + skillCode + " 문제가 없다")));
    }

    private Resolution none(SubmissionRow submission, ActionType action, String target,
            String reason) {
        return new Resolution(submission.id(), action.name(), target, null, reason);
    }

    /** 이 사용자가 한 번이라도 맞힌 문제들. */
    private List<String> solvedCodes(Long userId) {
        return submissions.findAcceptedProblemIds(userId).stream()
                .map(this::codeOf)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private String codeOf(Long problemId) {
        return problems.findById(problemId).map(ProblemRow::code).orElse(null);
    }
}
