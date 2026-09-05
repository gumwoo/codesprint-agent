package dev.codesprint.learning.service;

import dev.codesprint.judge.JudgePort;
import dev.codesprint.learning.domain.DecisionEngine;
import dev.codesprint.learning.domain.Evidence;
import dev.codesprint.learning.domain.EvidenceType;
import dev.codesprint.learning.domain.JudgeStatus;
import dev.codesprint.learning.domain.NextAction;
import dev.codesprint.learning.domain.SkillState;
import dev.codesprint.learning.domain.SubmissionEvidenceFactory;
import dev.codesprint.learning.domain.SubmissionEvidenceFactory.Submission;
import dev.codesprint.learning.persistence.ProblemRepository;
import dev.codesprint.learning.persistence.ProblemRow;
import dev.codesprint.learning.persistence.SubmissionRepository;
import dev.codesprint.learning.persistence.SubmissionRow;
import dev.codesprint.learning.persistence.UserRepository;
import dev.codesprint.learning.persistence.UserSkillRepository;
import dev.codesprint.learning.persistence.UserSkillRow;
import dev.codesprint.problem.ProblemCatalog;
import dev.codesprint.problem.ProblemCatalog.ProblemDefinition;
import dev.codesprint.problem.ProblemCatalog.SkillLink;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 제출 하나를 끝까지 처리한다. Milestone 1 데모의 흐름이다(Addendum §89).
 *
 * <pre>
 *   Judge -&gt; Evidence 저장 -&gt; mastery 재계산 -&gt; 다음 행동 결정
 * </pre>
 *
 * <p><b>이 순서에 함정이 하나 있다.</b> Decision Engine 이 "이 Skill 을 이미 배우고
 * 있었는가" 를 물을 때, 그 시점의 evidenceCount 에는 <b>방금 저장한 이번 제출이 이미
 * 포함되어 있다.</b> 그래서 제출을 반영하기 <b>전에</b> 개수를 읽어 따로 넘긴다.
 * 그러지 않으면 선수 조건을 못 채운 Skill 을 처음 틀린 사용자가 CHANGE_SKILL 이
 * 아니라 RETRY_VARIANT 를 받는다 - 준비되지 않은 문제를 계속 주게 된다.
 *
 * <p>Reviewer 는 아직 없다. 그래서 확정된 Mistake 도 없고 자동 드릴도 발동하지
 * 않는다 - 응답의 {@code review} 와 {@code promptVersion} 은 null 이다.
 */
@Service
public class SubmissionService {

    private final ProblemCatalog catalog;
    private final JudgePort judge;
    private final EvidenceStore evidenceStore;
    private final MasteryService mastery;
    private final DecisionEngine decisions;
    private final UserRepository users;
    private final ProblemRepository problems;
    private final SubmissionRepository submissions;
    private final UserSkillRepository userSkills;

    public SubmissionService(ProblemCatalog catalog, JudgePort judge,
            EvidenceStore evidenceStore, MasteryService mastery, DecisionEngine decisions,
            UserRepository users, ProblemRepository problems,
            SubmissionRepository submissions, UserSkillRepository userSkills) {
        this.catalog = catalog;
        this.judge = judge;
        this.evidenceStore = evidenceStore;
        this.mastery = mastery;
        this.decisions = decisions;
        this.users = users;
        this.problems = problems;
        this.submissions = submissions;
        this.userSkills = userSkills;
    }

    /**
     * @param before 이번 제출 <b>전</b>의 mastery. null 은 UNASSESSED 이며
     *     0.0(평가했고 못함)과 다르다.
     */
    public record SkillUpdate(String skillCode, Double before, Double after, double confidence,
            String status) {
    }

    public record Result(
            long submissionId,
            JudgePort.Result judge,
            List<SkillUpdate> skillUpdates,
            NextAction nextAction) {
    }

    public record Request(
            Long userId,
            String problemCode,
            String language,
            String sourceCode,
            int hintLevel,
            boolean solutionViewed,
            Integer solveSeconds) {
    }

    /** 문제나 사용자가 없을 때. 채점 실패(SYSTEM_ERROR)와 구분한다 - 그쪽은 우리 잘못이다. */
    public static class NotFound extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public NotFound(String message) {
            super(message);
        }
    }

    @Transactional
    public Result submit(Request request) {
        ProblemDefinition problem = catalog.find(request.problemCode());
        if (problem == null) {
            throw new NotFound("그런 문제가 없다: " + request.problemCode());
        }
        if (!users.existsById(request.userId())) {
            throw new NotFound("그런 사용자가 없다: " + request.userId());
        }
        ProblemRow problemRow = problems.findByCode(problem.code())
                .orElseGet(() -> problems.save(new ProblemRow(problem.code(), problem.source())));

        // PRIMARY Skill 이 이 문제가 주로 겨냥하는 것이다. 다음 행동은 그것을 두고 정한다.
        String primarySkill = primarySkillOf(problem);

        // ── 제출을 반영하기 전에 읽는다 ──────────────────────────────────
        // 아래에서 Evidence 를 저장하고 나면 이 값은 이미 이번 제출을 포함한다.
        int priorEvidenceCount = evidenceStore.countFor(request.userId(), primarySkill);
        Map<String, Double> beforeMastery = masterySnapshot(request.userId(), problem);

        SubmissionRow submission = submissions.save(new SubmissionRow(
                request.userId(), problemRow.id(), request.language(),
                JudgeStatus.QUEUED.name(), request.hintLevel(), request.solutionViewed(),
                request.solveSeconds()));

        JudgePort.Result judged = judge.judge(problem.code(), request.sourceCode());
        submission.applyJudgement(judged.status().name(), judged.passed(), judged.total(),
                judged.executionMs(), judged.memoryKb(), judged.failedCaseId());
        submissions.save(submission);

        // 한 제출이 여러 Skill 에 Evidence 를 남긴다. sourceEventId 는 같고 skillCode 가
        // 다르므로, 재시도로 같은 제출이 두 번 처리되어도 중복이 걸러진다.
        String sourceEventId = "submission:" + submission.id();
        Instant occurred = submission.submittedAt() == null
                ? Instant.now() : submission.submittedAt();
        String occurredAt = occurred.truncatedTo(ChronoUnit.SECONDS).toString();

        for (SkillLink link : problem.skills()) {
            Evidence evidence = SubmissionEvidenceFactory.fromSubmission(new Submission(
                    sourceEventId,
                    link.skillCode(),
                    link.weight(),
                    judged.status(),
                    request.hintLevel(),
                    request.solutionViewed(),
                    occurredAt,
                    evidenceTypeOf(problem),
                    request.solveSeconds() == null ? null : request.solveSeconds().doubleValue(),
                    problem.expectedSolveSeconds(),
                    null,     // 유형 선택은 아직 받지 않는다 - recognition 을 관측하지 않는다
                    "NORMAL",
                    problem.code(),
                    null));   // TLE 원인 분류는 Reviewer 의 몫이다
            // null 이면 이 Skill 에 아무것도 기록하지 않는다(COMPILE_ERROR / SYSTEM_ERROR).
            if (evidence != null) {
                evidenceStore.saveIfAbsent(request.userId(), evidence);
            }
        }

        // 재계산. 선수 조건 판정에 쓸 mastery 는 매번 다시 읽는다 - 앞 Skill 의
        // 재계산 결과가 뒤 Skill 의 LOCKED / READY 판정에 영향을 준다.
        List<SkillUpdate> updates = new ArrayList<>();
        SkillState primaryState = null;
        for (SkillLink link : problem.skills()) {
            SkillState state = mastery.recomputeAndStore(
                    request.userId(), link.skillCode(), mastery.masteriesOf(request.userId()));
            updates.add(new SkillUpdate(link.skillCode(), beforeMastery.get(link.skillCode()),
                    state.mastery(), state.confidence(), state.status().name()));
            if (link.skillCode().equals(primarySkill)) {
                primaryState = state;
            }
        }

        NextAction action = decisions.decide(new DecisionEngine.Context(
                primarySkill,
                primaryState,
                priorEvidenceCount,
                judged.status(),
                null,   // 확정된 Mistake 는 Reviewer 가 붙어야 나온다
                (int) submissions.countByUserIdAndProblemId(request.userId(), problemRow.id()),
                false,  // 복습 성공 기록은 복습 일정이 붙어야 생긴다
                mastery.masteriesOf(request.userId())));

        return new Result(submission.id(), judged, updates, action);
    }

    /** 드릴의 관측은 좁다 - alpha 와 confidence 가중치가 모두 작다(Addendum §9, §18). */
    private static EvidenceType evidenceTypeOf(ProblemDefinition problem) {
        return "MICRO_DRILL".equals(problem.kind())
                ? EvidenceType.MICRO_DRILL_RESULT
                : EvidenceType.PROBLEM_SUBMISSION;
    }

    private static String primarySkillOf(ProblemDefinition problem) {
        return problem.skills().stream()
                .filter(link -> "PRIMARY".equals(link.role()))
                .map(SkillLink::skillCode)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "PRIMARY Skill 이 없는 문제다: " + problem.code()
                                + ". tools/check_problems.py 가 막았어야 한다."));
    }

    private Map<String, Double> masterySnapshot(Long userId, ProblemDefinition problem) {
        Map<String, Double> before = new LinkedHashMap<>();
        for (SkillLink link : problem.skills()) {
            UserSkillRow row = userSkills
                    .findByUserIdAndSkillCode(userId, link.skillCode())
                    .orElse(null);
            before.put(link.skillCode(),
                    row == null || row.masteryScore() == null
                            ? null : row.masteryScore().doubleValue());
        }
        return before;
    }
}
