package dev.codesprint.learning.service;

import dev.codesprint.learning.domain.ActionType;
import dev.codesprint.learning.domain.SubmissionEvidenceFactory;
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
 *
 * <p><b>고르는 시점은 결과를 반영할 때 한 번이다.</b> 조회할 때마다 다시 고르면 그
 * 사이 다른 제출이 바꿔 놓은 상태를 보게 되어 같은 제출이 다른 문제를 가리킨다.
 * 게다가 nextAction 은 과거 시점에 고정돼 있으므로, 한 응답 안에서 기준 시점이
 * 둘이 된다.
 */
@Service
public class NextProblemService {

    private final ProblemCatalog catalog;
    private final SubmissionRepository submissions;
    private final ProblemRepository problems;

    public NextProblemService(ProblemCatalog catalog, SubmissionRepository submissions,
            ProblemRepository problems) {
        this.catalog = catalog;
        this.problems = problems;
        this.submissions = submissions;
    }

    /**
     * @param problemCode 고르지 못했으면 null. 그 행동에 맞는 문제가 없거나, 애초에
     *     문제를 요구하지 않는 행동이다.
     * @param reason 왜 그 문제인가, 또는 왜 없는가. 감사 로그다.
     */
    public record Selection(String problemCode, String reason) {
    }

    /** 조회 응답. 저장된 선택을 읽어 문제 본문을 붙인 것이다. */
    public record Resolution(long submissionId, String action, String targetSkill,
            ProblemDefinition problem, String reason) {
    }

    /**
     * 결과를 반영하는 그 시점에 고른다. {@link JudgeResultApplier} 가 부른다.
     *
     * @param justAttemptedProblemId 방금 낸 문제. 같은 문제를 다시 주지 않는다.
     */
    public Selection select(Long userId, ActionType action, String targetSkill,
            Long justAttemptedProblemId) {

        return switch (action) {
            case MICRO_DRILL -> pick(userId, targetSkill, "MICRO_DRILL",
                    justAttemptedProblemId, "확정된 실수를 겨냥한 드릴");
            // 다른 Skill 로 보낼 때는 일반 문제다. 드릴은 이미 배운 것을 좁게 다시
            // 다루는 것이라, 아직 시작도 안 한 Skill 에 주면 맥락이 없다.
            case CHANGE_SKILL -> pick(userId, targetSkill, "NORMAL",
                    justAttemptedProblemId, "선수 Skill 을 먼저 채운다");
            case RETRY_VARIANT -> pick(userId, targetSkill, "NORMAL",
                    justAttemptedProblemId, "같은 Skill 의 다른 문제로 연습한다");

            // 문제를 주지 않는 행동들. 각각 이유가 다르므로 뭉뚱그리지 않는다.
            case CONTINUE -> none("같은 문제를 이어서 푼다 - 새 문제를 고르지 않는다");
            case REVIEW_CONCEPT -> none("개념 자료가 아직 없다 - 문제로 대체하지 않는다");
            case SCHEDULE_REVIEW -> none("복습 일정이 아직 없다");
            case UNLOCK_NEXT -> none("다음 Skill 을 고르는 규칙이 아직 없다");
            default -> none("이 행동을 문제로 옮기는 규칙이 아직 없다: " + action);
        };
    }

    /**
     * 저장된 선택을 읽는다. <b>여기서 다시 고르지 않는다.</b>
     *
     * @return 결과가 아직 반영되지 않았으면 비어 있다 - 고를 근거 자체가 없던 구간이다.
     */
    @Transactional(readOnly = true)
    public Optional<Resolution> resolve(long submissionId) {
        SubmissionRow submission = submissions.findById(submissionId).orElse(null);
        if (submission == null || submission.nextActionType() == null) {
            return Optional.empty();
        }
        return Optional.of(new Resolution(
                submission.id(),
                submission.nextActionType(),
                submission.nextActionTarget(),
                submission.nextProblemCode() == null
                        ? null : catalog.find(submission.nextProblemCode()),
                submission.nextProblemReason()));
    }

    private Selection pick(Long userId, String skillCode, String kind,
            Long justAttemptedProblemId, String why) {

        if (skillCode == null) {
            return none("대상 Skill 이 없다");
        }
        List<ProblemDefinition> candidates = catalog.byPrimarySkill(skillCode, kind);
        if (candidates.isEmpty()) {
            // 자동 드릴 대상 Mistake 에는 드릴 문제가 반드시 있어야 한다 -
            // tools/check_problems.py 가 CI 에서 확인한다. 여기 걸리면 데이터가
            // 아니라 그 검사가 놓친 것이다.
            return none(kind + " 종류의 " + skillCode + " 문제가 없다");
        }

        // **스스로 풀어낸** 문제만 후보에서 뺀다. 정답을 보고 맞힌 문제는 아직
        // 혼자 풀지 못하는 문제다(Addendum 22).
        List<String> solved = independentlySolvedCodes(userId);
        String justAttempted = codeOf(justAttemptedProblemId);

        return candidates.stream()
                .filter(problem -> !solved.contains(problem.code()))
                .filter(problem -> !problem.code().equals(justAttempted))
                .findFirst()
                .map(problem -> new Selection(problem.code(), why))
                // 전부 스스로 풀었으면 다시 준다. 같은 문제를 또 주지는 않는다.
                .orElseGet(() -> candidates.stream()
                        .filter(problem -> !problem.code().equals(justAttempted))
                        .findFirst()
                        .map(problem -> new Selection(problem.code(),
                                why + " (전부 스스로 푼 적이 있어 다시 낸다)"))
                        .orElseGet(() -> none(
                                "방금 푼 문제 말고는 " + skillCode + " 문제가 없다")));
    }

    private static Selection none(String reason) {
        return new Selection(null, reason);
    }

    /**
     * 이 사용자가 스스로 풀어낸 문제들.
     *
     * <p>힌트를 많이 쓰거나 풀이를 보고 맞힌 것은 여기 들어오지 않는다. 문턱은
     * Evidence 쪽과 <b>같은 상수</b>를 쓴다 - 두 곳이 갈라지면 "숙달했다고 보는 기준"
     * 이 화면과 점수에서 달라진다.
     */
    private List<String> independentlySolvedCodes(Long userId) {
        return submissions.findIndependentlySolvedProblemIds(
                        userId, SubmissionEvidenceFactory.INDEPENDENT_HINT_CEILING).stream()
                .map(this::codeOf)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private String codeOf(Long problemId) {
        return problemId == null
                ? null
                : problems.findById(problemId).map(ProblemRow::code).orElse(null);
    }
}
