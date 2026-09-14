package dev.codesprint.learning.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.codesprint.judge.JudgeJobRepository;
import dev.codesprint.judge.JudgeJobRow;
import dev.codesprint.judge.JudgeResult;
import dev.codesprint.learning.domain.ActionType;
import dev.codesprint.learning.domain.AttemptStreak;
import dev.codesprint.learning.domain.DecisionEngine;
import dev.codesprint.learning.domain.Evidence;
import dev.codesprint.learning.domain.EvidenceType;
import dev.codesprint.learning.domain.JudgeStatus;
import dev.codesprint.learning.domain.NextAction;
import dev.codesprint.learning.domain.NextSkillSelector;
import dev.codesprint.learning.domain.SkillState;
import dev.codesprint.learning.domain.SubmissionEvidenceFactory;
import dev.codesprint.learning.domain.SubmissionEvidenceFactory.Submission;
import dev.codesprint.learning.persistence.ReviewScheduleRow;
import dev.codesprint.learning.persistence.SubmissionRepository;
import dev.codesprint.learning.persistence.SubmissionRow;
import dev.codesprint.learning.persistence.UserSkillRepository;
import dev.codesprint.learning.persistence.UserSkillRow;
import dev.codesprint.problem.ProblemCatalog;
import dev.codesprint.reviewer.CaseCorroboration;
import dev.codesprint.reviewer.ReviewService;
import dev.codesprint.reviewer.ReviewerPort;
import dev.codesprint.problem.ProblemCatalog.ProblemDefinition;
import dev.codesprint.problem.ProblemCatalog.SkillLink;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 채점 결과를 학습 상태에 반영한다. 정본 흐름: Addendum §89.
 *
 * <pre>
 *   Evidence 저장 -&gt; mastery 재계산 -&gt; 다음 행동 결정
 * </pre>
 *
 * <p>PR #7 에서는 이 일이 API 요청 스레드 안에 있었다. 지금은 채점이 큐를 통해
 * 요청 밖으로 나갔으므로(ADR-0013) 여기가 별도 트랜잭션으로 돈다.
 *
 * <p><b>순서에 함정이 하나 있다.</b> Decision Engine 이 "이 Skill 을 이미 배우고
 * 있었는가" 를 물을 때, 그 시점의 evidenceCount 에는 방금 저장한 이번 제출이 이미
 * 포함되어 있다. 그래서 저장하기 <b>전에</b> 개수를 읽어 따로 넘긴다.
 *
 * <p>결정과 갱신 내역을 제출 행에 <b>남긴다.</b> 사용자가 나중에 결과를 다시 물어볼
 * 때 새로 계산하면, 그 사이 다른 제출이 바꿔 놓은 상태를 보게 되어 같은 제출을 두 번
 * 조회했을 때 다른 답이 나온다.
 */
@Service
public class JudgeResultApplier {

    private static final Logger log = LoggerFactory.getLogger(JudgeResultApplier.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProblemCatalog catalog;
    private final EvidenceStore evidenceStore;
    private final MasteryService mastery;
    private final DecisionEngine decisions;
    private final SubmissionRepository submissions;
    private final UserSkillRepository userSkills;
    private final JudgeJobRepository jobs;
    private final ReviewService reviews;
    private final NextProblemService nextProblem;
    private final DiagnosticService diagnostic;
    private final ReviewScheduleService reviewSchedules;
    private final NextSkillSelector nextSkills;

    public JudgeResultApplier(ProblemCatalog catalog, EvidenceStore evidenceStore,
            MasteryService mastery, DecisionEngine decisions, SubmissionRepository submissions,
            UserSkillRepository userSkills, JudgeJobRepository jobs, ReviewService reviews,
            NextProblemService nextProblem, DiagnosticService diagnostic,
            ReviewScheduleService reviewSchedules, NextSkillSelector nextSkills) {
        this.catalog = catalog;
        this.evidenceStore = evidenceStore;
        this.mastery = mastery;
        this.decisions = decisions;
        this.submissions = submissions;
        this.userSkills = userSkills;
        this.jobs = jobs;
        this.reviewSchedules = reviewSchedules;
        this.nextProblem = nextProblem;
        this.diagnostic = diagnostic;
        this.reviews = reviews;
        this.nextSkills = nextSkills;
    }

    /**
     * job 하나의 결과를 반영한다. 이미 반영했으면 아무것도 하지 않는다.
     *
     * <p>멱등해야 한다. 반영 도중 프로세스가 죽으면 같은 job 을 다시 집게 되는데,
     * Evidence 가 두 번 쌓이면 EMA 가 두 번 적용되고 confidence 도 두 번 오른다.
     * {@code (sourceEventId, skillCode)} 유일성이 그것을 막는다(ADR-0009).
     */
    @Transactional
    public void apply(Long jobId) {
        // **먼저 자리를 잡는다.** 읽고 확인한 뒤 쓰면 두 인스턴스가 나란히
        // "아직 반영 안 됐다" 를 보고 둘 다 반영한다(JudgeJobRepository.claimForApply).
        if (jobs.claimForApply(jobId, Instant.now()) == 0) {
            return;
        }
        JudgeJobRow job = jobs.findById(jobId).orElse(null);
        if (job == null) {
            // 자리를 잡았는데 행이 없다. 있을 수 없지만, 조용히 넘어가면
            // 원인을 찾을 단서가 없다.
            log.error("job {} 의 자리를 잡았는데 행이 없다", jobId);
            return;
        }

        SubmissionRow submission = submissions.findById(job.submissionId()).orElse(null);
        if (submission == null) {
            // 제출이 없는 job 은 반영할 곳이 없다. 다시 집어도 같으므로 끝낸 것으로 둔다.
            log.error("job {} 의 제출({})이 없다", jobId, job.submissionId());
            return;
        }

        ProblemDefinition problem = catalog.find(job.problemCode());
        if (problem == null) {
            // 문제가 카탈로그에서 사라졌다. 사용자 잘못이 아니므로 점수를 건드리지 않는다.
            log.error("job {} 의 문제({})가 카탈로그에 없다", jobId, job.problemCode());
            finish(job, submission, JudgeResult.systemError(), problem);
            return;
        }

        finish(job, submission, readResult(job), problem);
    }

    /**
     * 큐에 적힌 결과를 읽는다.
     *
     * <p><b>읽지 못하면 SYSTEM_ERROR 다.</b> 알 수 없는 status 를 그대로 넘기면
     * Evidence 매핑이 관측값 없는 Evidence 를 만들어, 측정한 것 없이 confidence 만
     * 올린다. 그리고 그건 우리 잘못이지 사용자 잘못이 아니다.
     */
    private static JudgeResult readResult(JudgeJobRow job) {
        if (!"DONE".equals(job.status()) || job.result() == null) {
            return JudgeResult.systemError();
        }
        try {
            JsonNode node = MAPPER.readTree(job.result());
            java.util.Set<Integer> passed = new java.util.LinkedHashSet<>();
            java.util.Set<Integer> failed = new java.util.LinkedHashSet<>();
            for (JsonNode one : node.path("cases")) {
                // 실행된 case 만 여기 있다. 없는 case 는 실패가 아니라 미실행이다.
                (("ACCEPTED".equals(one.path("status").asText())) ? passed : failed)
                        .add(one.get("id").asInt());
            }
            return new JudgeResult(
                    JudgeStatus.valueOf(node.get("status").asText()),
                    node.get("passed").asInt(),
                    node.get("total").asInt(),
                    integer(node, "executionMs"),
                    integer(node, "memoryKb"),
                    integer(node, "failedCaseId"),
                    text(node, "stderr"),
                    passed,
                    failed);
        } catch (JsonProcessingException | IllegalArgumentException | NullPointerException e) {
            log.error("job {} 의 결과를 읽지 못했다: {}", job.id(), job.result(), e);
            return JudgeResult.systemError();
        }
    }

    private void finish(JudgeJobRow job, SubmissionRow submission, JudgeResult judged,
            ProblemDefinition problem) {

        submission.applyJudgement(judged.status().name(), judged.passed(), judged.total(),
                judged.executionMs(), judged.memoryKb(), judged.failedCaseId(),
                judged.stderr());

        // 문제를 모르면 Skill 도 모른다. 그래도 **끝내야 한다.**
        //
        // 처음에는 nextAction 을 null 로 두고 반영만 표시했는데, 조회 쪽은 완료 여부를
        // nextActionType 으로 판단한다. 그래서 job 은 처리됐는데 GET 은 영원히
        // PENDING 을 돌려줬다 - 사용자는 끝나지 않는 채점을 기다린다.
        //
        // CONTINUE 로 끝낸다. 우리 잘못이므로 학습 경로를 바꾸지 않는다(ADR-0013).
        if (problem == null) {
            submission.applyOutcome(ActionType.CONTINUE.name(), null,
                    "채점 결과를 반영할 문제 정보를 찾지 못했다", "[]");
            submission.applyNextProblem(null, "문제 정보를 찾지 못해 다음 문제를 고를 수 없다");
            submissions.save(submission);
            return;
        }

        Long userId = submission.userId();
        String primarySkill = primarySkillOf(problem);

        // ── Evidence 를 저장하기 전에 읽는다 ────────────────────────────
        int priorEvidenceCount = evidenceStore.countFor(userId, primarySkill);
        Map<String, Double> beforeMastery = masterySnapshot(userId, problem);

        // 한 제출이 여러 Skill 에 Evidence 를 남긴다. sourceEventId 는 같고 skillCode 가
        // 다르므로, 같은 job 을 두 번 처리해도 중복이 걸러진다.
        String sourceEventId = "submission:" + submission.id();
        Instant occurred = submission.submittedAt() == null
                ? Instant.now() : submission.submittedAt();
        String occurredAt = occurred.truncatedTo(ChronoUnit.SECONDS).toString();

        // **이 제출이 예약된 복습인가.** 문제 종류가 아니라 일정이 정한다(ADR-0021).
        // 만기된 일정이 PRIMARY Skill 에 걸려 있을 때만 복습이다.
        //
        // 기준 시각은 **제출 시각**이다. 처리 시각으로 보면 채점이 밀리는 동안 만기가
        // 지났을 때 만기 전에 낸 제출이 복습이 된다.
        //
        // 그리고 Evidence 를 만들지 않는 판정에서는 복습도 건드리지 않는다.
        // SYSTEM_ERROR 는 우리 잘못이고 COMPILE_ERROR 는 알고리즘 Skill 의 실패가
        // 아니다 - 그대로 두면 **우리 하네스가 죽었다는 이유로 복습이 실패로 기록되고
        // 간격이 줄어든다.** 일반 제출 경로는 producesEvidence() 로 이미 그것을 막고
        // 있었는데, 복습 경로만 그 검사를 우회하고 있었다.
        // **이 제출이 복습을 가져갔는가.** 그 자리는 제출 시점에 이미 정해졌다 -
        // 여기서 다시 고르면 채점 완료 순서가 학습 결과를 바꾼다.
        ReviewScheduleRow claimed = reviewSchedules.claimedBy(submission.id()).orElse(null);

        // Evidence 를 만들지 않는 판정에서는 복습을 소비하지 않고 자리를 되돌린다.
        // SYSTEM_ERROR 는 우리 잘못이고 COMPILE_ERROR 는 알고리즘 Skill 의 실패가
        // 아니다 - 되돌리지 않으면 그 사용자는 다시는 이 Skill 을 복습할 수 없다.
        if (claimed != null && !judged.status().producesEvidence()) {
            reviewSchedules.release(claimed);
            claimed = null;
        }
        ReviewScheduleRow dueReview = claimed;

        for (SkillLink link : problem.skills()) {
            if (dueReview != null && link.skillCode().equals(primarySkill)) {
                // PRIMARY 만 복습으로 센다. 곁다리로 걸린 Skill 은 이 복습이 겨냥한
                // 것이 아니고, 그 Skill 의 일정은 따로 돈다.
                //
                // **둘 다 만들지 않는다.** evidenceId 가 (제출, Skill) 로 정해지므로
                // 하나만 남는데, 어느 쪽이 남는지가 순서에 달리면 안 된다.
                applyReview(userId, dueReview, sourceEventId, occurredAt, occurred,
                        judged.status(), submission);
                continue;
            }
            Evidence evidence = SubmissionEvidenceFactory.fromSubmission(new Submission(
                    sourceEventId,
                    link.skillCode(),
                    link.weight(),
                    judged.status(),
                    submission.hintLevel(),
                    submission.solutionViewed(),
                    occurredAt,
                    evidenceTypeOf(problem),
                    submission.solveSeconds() == null
                            ? null : submission.solveSeconds().doubleValue(),
                    problem.expectedSolveSeconds(),
                    null,     // 유형 선택은 아직 받지 않는다 - recognition 을 관측하지 않는다
                    "NORMAL",
                    problem.code(),
                    null));   // TLE 원인 분류는 Reviewer 의 몫이다
            // null 이면 이 Skill 에 아무것도 기록하지 않는다(COMPILE_ERROR / SYSTEM_ERROR).
            if (evidence != null) {
                evidenceStore.saveIfAbsent(userId, evidence);
            }
        }

        // ── Reviewer ────────────────────────────────────────────────────
        //
        // 실패 Test Case 가 있을 때만 부른다(ADR-0004). ACCEPTED 에는 설명할 실패가
        // 없고, COMPILE_ERROR 는 근거로 들 case 가 없으며, SYSTEM_ERROR 는 우리 잘못이다.
        //
        // **확정된 것만 Decision Engine 으로 넘어간다.** Reviewer 가 낸 후보는
        // 그대로 쓰이지 않는다(ADR-0001, ADR-0014).
        String confirmedMistake = null;
        if (reviews.shouldReview(judged.status())) {
            var review = reviews.review(
                    userId, submission.id(), submission.problemId(), submission.submittedAt(),
                    reviewRequest(problem, judged, job),
                    // Reviewer 밖의 근거. **Reviewer 출력을 넣지 않는다**(ADR-0014).
                    new CaseCorroboration(judged.passedCaseIds(), judged.failedCaseIds(),
                            catalog.probesOf(problem.code())),
                    // 이 문제에서 일어날 수 있다고 선언된 실수들(ADR-0029).
                    problem.commonMistakes());

            if (review.isPresent()) {
                ReviewService.Review value = review.get();
                confirmedMistake = value.confirmedMistake();
                submission.applyReview(
                        value.primaryMistake(),
                        BigDecimal.valueOf(value.confidence()),
                        value.status().name(),
                        value.explanation(),
                        writeSecondary(value.secondaryMistakes()),
                        value.promptVersion());
            }
        }

        // 재계산. 선수 조건 판정에 쓸 mastery 는 매번 다시 읽는다 - 앞 Skill 의
        // 재계산 결과가 뒤 Skill 의 LOCKED / READY 판정에 영향을 준다.
        ArrayNode updates = MAPPER.createArrayNode();
        SkillState primaryState = null;
        for (SkillLink link : problem.skills()) {
            SkillState state = mastery.recomputeAndStore(
                    userId, link.skillCode(), mastery.masteriesOf(userId));
            updates.add(toNode(link.skillCode(), beforeMastery.get(link.skillCode()), state));
            if (link.skillCode().equals(primarySkill)) {
                primaryState = state;
            }
        }

        // **상태를 한 번만 계산한다.** 진단과 다음 Skill 선택이 각각 다시 계산하면
        // 조회가 두 배가 되고, 더 나쁘게는 한 결정 안에서 서로 다른 시점을 보게 된다.
        //
        // 진단은 **이번 제출을 반영한 뒤** 본다. 앞서 보면 방금 낸 Skill 이 아직
        // 안 재 본 것으로 남아, 같은 Skill 을 다시 물으러 보낸다.
        List<SkillState> states = mastery.statesOf(userId);

        // **선수 조건 판정도 같은 snapshot 을 본다.** masteriesOf() 는 user_skills
        // 캐시를 읽는데, 그 행은 정본이 아니다(ADR-0009). 방금 재계산한 states 와
        // 다른 값을 줄 수 있고, 그러면 한 결정 안에서 NextSkillSelector 는 "열렸다",
        // Decision Engine 은 "아직 막혔다" 를 볼 수 있다.
        //
        // Collectors.toMap 을 쓰지 않는다 - mastery 는 null 일 수 있고(아직 평가 전),
        // 그 구현은 null 값에서 NPE 를 낸다. 여기서 null 은 정상 데이터다.
        Map<String, Double> masteries = new HashMap<>();
        for (SkillState state : states) {
            masteries.put(state.skillCode(), state.mastery());
        }
        NextAction action = decisions.decide(new DecisionEngine.Context(
                primarySkill,
                primaryState,
                priorEvidenceCount,
                judged.status(),
                confirmedMistake,
                consecutiveFailures(userId, submission.problemId()),
                // 같은 자료를 두 번 주지 않는다(ADR-0030).
                submissions.conceptAlreadyShown(userId, submission.problemId()),
                reviewSchedules.isScheduled(userId, primarySkill),
                masteries,
                pendingDiagnosticSkill(states),
                // 방금 이 제출로 복습을 끝냈다면 그 일정은 더 이상 만기가 아니다 -
                // 위에서 다음 간격으로 밀어 두었으므로 여기서 다시 걸리지 않는다.
                reviewSchedules.nextDue(userId)
                        .map(ReviewScheduleRow::skillCode).orElse(null),
                nextSkills.after(primarySkill, states).orElse(null)));

        // 결정이 "복습을 예약하라" 면 여기서 잡는다. **이미 있으면 그대로 둔다** -
        // 제출마다 새로 잡으면 30일까지 올라간 간격이 매번 1일로 되돌아간다.
        if (action.type() == ActionType.SCHEDULE_REVIEW) {
            reviewSchedules.scheduleIfAbsent(userId, primarySkill, occurred);
        }

        // 다음에 풀 문제도 지금 고정한다. 조회할 때 고르면 그 사이 다른 제출이
        // 바꿔 놓은 상태를 보게 되어 같은 제출이 다른 문제를 가리킨다.
        //
        // **결정을 쓰기 전에 고른다.** 이 호출은 제출을 조회하므로 auto-flush 가
        // 일어나는데, 결정만 쓰인 상태로 행이 나가면 "결정이 있으면 이유도 있다"
        // 제약에 걸린다. 실제로 그렇게 깨졌다 - 제약이 순서 문제를 잡아냈다.
        NextProblemService.Selection selection = nextProblem.select(
                userId, action.type(), action.targetSkill(), submission.problemId());

        submission.applyOutcome(action.type().name(), action.targetSkill(), action.reason(),
                updates.toString());
        submission.applyNextProblem(selection.problemCode(), selection.reason());

        submissions.save(submission);
    }

    /**
     * 진단이 아직 확인하지 못한 Skill. 진단이 끝났으면 null 이다.
     *
     * <p><b>줄 문제가 있을 때만 돌려준다.</b> 물어볼 NORMAL 문제가 없는 Skill 을
     * 가리키면 Decision Engine 이 갈 곳 없는 행동을 내고, 사용자는 다음 문제를
     * 못 받는다.
     */
    private String pendingDiagnosticSkill(List<SkillState> states) {
        DiagnosticService.Step step = diagnostic.nextStep(states);
        return step.done() || step.problem() == null ? null : step.targetSkill();
    }

    /**
     * Reviewer 에게 넘길 것. <b>Judge 근거를 함께 준다.</b>
     *
     * <p>실패한 case 의 입력이 없으면 모델은 코드만 훑고 "있을 법한 실수" 를
     * 나열하게 된다. 그 분석은 실제로 일어난 실패를 설명하지 못한다(Addendum 20).
     */
    private ReviewerPort.Request reviewRequest(ProblemDefinition problem, JudgeResult judged,
            JudgeJobRow job) {

        var failedCase = catalog.caseById(problem.code(), judged.failedCaseId());
        return new ReviewerPort.Request(
                problem.code(),
                problem.title(),
                problem.source(),
                judged.status().name(),
                judged.failedCaseId(),
                failedCase == null ? null : failedCase.input(),
                failedCase == null ? null : failedCase.expectedOutput(),
                judged.stderr(),
                job.sourceCode(),
                problem.skills().stream().map(SkillLink::skillCode).toList());
    }

    /** secondary 는 목록이라 jsonb 로 남긴다. 비어 있어도 [] 를 쓴다 - null 과 다르다. */
    private static String writeSecondary(List<String> codes) {
        ArrayNode node = MAPPER.createArrayNode();
        codes.forEach(node::add);
        return node.toString();
    }

    private static ObjectNode toNode(String skillCode, Double before, SkillState state) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("skillCode", skillCode);
        // null 은 UNASSESSED 다. 0.0(평가했고 못함)과 구분한다.
        node.put("before", before);
        node.put("after", state.mastery());
        node.put("confidence", state.confidence());
        node.put("status", state.status().name());
        return node;
    }

    /**
     * 지금 이 문제에서 연속으로 몇 번 막혀 있는가.
     *
     * <p>제출 행을 그냥 세지 않는다. 그러면 우리 장애(SYSTEM_ERROR)와 문법 오류가
     * 나중 제출의 경로를 바꾼다 - {@link AttemptStreak} 에 이유를 적어 뒀다.
     */
    private int consecutiveFailures(Long userId, Long problemId) {
        List<JudgeStatus> statuses = submissions
                .findStatusesNewestFirst(userId, problemId).stream()
                .map(JudgeStatus::valueOf)
                .toList();
        return AttemptStreak.consecutiveFailures(statuses);
    }

    /** 드릴의 관측은 좁다 - alpha 와 confidence 가중치가 모두 작다(Addendum §9, §18). */
    /**
     * 복습 제출을 Evidence 로 옮기고 다음 간격을 잡는다.
     *
     * <p>성공은 <b>독립적으로</b> 통과한 것만이다. 힌트를 보고 맞힌 것을 복습 성공으로
     * 세면 "시간이 지나도 혼자 되는가" 를 재지 못한다 - 지금은 힌트 기능이 없어
     * 판정만 보면 되지만, 생기면 여기가 그 조건을 지켜야 한다.
     */
    private void applyReview(Long userId, ReviewScheduleRow schedule, String sourceEventId,
            String occurredAt, Instant occurred, JudgeStatus status, SubmissionRow submission) {

        boolean succeeded = status == JudgeStatus.ACCEPTED
                && SubmissionEvidenceFactory.isIndependentAttempt(
                        status, submission.hintLevel(), submission.solutionViewed());

        int daysSince = reviewSchedules.complete(schedule, succeeded, occurred);
        evidenceStore.saveIfAbsent(userId, SubmissionEvidenceFactory.fromReview(
                new SubmissionEvidenceFactory.ReviewSubmission(
                        sourceEventId, schedule.skillCode(), daysSince, succeeded, occurredAt)));
    }

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

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asInt();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
