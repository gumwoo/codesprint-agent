package dev.codesprint.mocktest;

import dev.codesprint.curriculum.CurriculumCatalog;
import dev.codesprint.learning.domain.SkillState;
import dev.codesprint.learning.domain.SkillStatus;
import dev.codesprint.learning.persistence.SubmissionRepository;
import dev.codesprint.learning.persistence.SubmissionRow;
import dev.codesprint.learning.persistence.UserRepository;
import dev.codesprint.learning.service.MasteryService;
import dev.codesprint.learning.service.RunService;
import dev.codesprint.learning.service.SubmissionIntakeService;
import dev.codesprint.learning.service.SubmissionQueryService;
import dev.codesprint.problem.ProblemCatalog;
import dev.codesprint.problem.ProblemCatalog.ProblemDefinition;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 모의 시험. 정본: PRD §84~86, ADR-0043.
 *
 * <p>문제는 {@link MockTestComposer} 가 고르고, 시험 중의 순간은 이 서비스가 주입된 시계로
 * {@code mock_test_events} 에 남긴다. <b>시험 중에는 도움을 주지 않는다</b> - 유형 · 힌트 ·
 * Reviewer 분석 · 다음 행동을 모두 끝난 뒤로 미룬다.
 */
@Service
public class MockTestService {

    private final MockTestRepository tests;
    private final MockTestProblemRepository problems;
    private final MockTestEventRepository events;
    private final UserRepository users;
    private final SubmissionRepository submissions;
    private final ProblemCatalog catalog;
    private final CurriculumCatalog curriculum;
    private final MasteryService mastery;
    private final SubmissionIntakeService intake;
    private final SubmissionQueryService queries;
    private final RunService runs;
    private final Clock clock;

    public MockTestService(MockTestRepository tests, MockTestProblemRepository problems,
            MockTestEventRepository events, UserRepository users,
            SubmissionRepository submissions, ProblemCatalog catalog,
            CurriculumCatalog curriculum, MasteryService mastery,
            SubmissionIntakeService intake, SubmissionQueryService queries, RunService runs,
            Clock clock) {
        this.tests = tests;
        this.problems = problems;
        this.events = events;
        this.users = users;
        this.submissions = submissions;
        this.catalog = catalog;
        this.curriculum = curriculum;
        this.mastery = mastery;
        this.intake = intake;
        this.queries = queries;
        this.runs = runs;
        this.clock = clock;
    }

    /** 시험 · 문제 · 사용자가 없거나 남의 시험일 때. 404. */
    public static class NotFound extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public NotFound(String message) {
            super(message);
        }
    }

    /** 지금 할 수 없는 일. 409 - 시험 중이거나, 끝났거나, 시험을 만들 수 없다. */
    public static class Conflict extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public Conflict(String message) {
            super(message);
        }
    }

    public enum State { IN_PROGRESS, FINISHED }

    /**
     * @param submissions 이 문제에 낸 제출 수. 판정은 따로 조회한다.
     */
    public record LabelProgress(String label, boolean opened, int submissions) {
    }

    /**
     * @param remainingSeconds 이 응답 시점의 남은 시간. 끝났으면 0 이다.
     */
    public record Overview(long mockTestId, State state, Instant startedAt, Instant endsAt,
            long remainingSeconds, List<LabelProgress> problems) {
    }

    public record Sample(String input, String expectedOutput) {
    }

    /** 시험 중에 보여 주는 문제. <b>code · 제목 · Skill 이 없다</b> - 셋 다 유형을 알려 준다. */
    public record ProblemSheet(long mockTestId, String label, String statement,
            Integer timeLimitMs, Integer memoryLimitMb, List<Sample> samples) {
    }

    /** 시험 중의 제출 결과. 판정만 준다 - 분석과 다음 행동은 끝난 뒤다. */
    public record Verdict(long submissionId, String label, String state, String status,
            Integer passed, Integer total) {
    }

    public record Mistake(long submissionId, String mistake, String status) {
    }

    /** 끝난 뒤 문제 하나. 이제 code · 제목 · Skill 을 보여 준다. */
    public record ProblemReport(String label, String problemCode, String title,
            String primarySkill, int expectedSolveSeconds, MockTestAnalysis.Outcome outcome,
            Long openedAtSeconds, Long firstRunAtSeconds, Long firstSubmitAtSeconds,
            Long solvedAtSeconds, int submissions, Long timeSpentSeconds,
            Boolean overExpected, List<Mistake> mistakes) {
    }

    public record Report(long mockTestId, Instant startedAt, Instant closedAt,
            int durationSeconds, int solved, int total, List<String> openOrder,
            Boolean easiestFirst, List<ProblemReport> problems) {
    }

    // ── 만들기 ─────────────────────────────────────────────────────────────

    @Transactional
    public Overview create(long userId) {
        var user = users.findById(userId)
                .orElseThrow(() -> new NotFound("그런 사용자가 없다: " + userId));
        Instant now = clock.instant();

        Optional<MockTestRow> open = tests.findByUserIdAndFinishedAtIsNull(userId);
        if (open.isPresent()) {
            if (!open.get().isOver(now)) {
                throw new Conflict("진행 중인 시험이 있다: " + open.get().id());
            }
            // 시간이 지난 시험은 여기서 닫는다 - 닫히지 않은 시험은 사용자당 하나다.
            open.get().close(now);
            tests.saveAndFlush(open.get());
        }

        MockTestComposer.Composition composition = MockTestComposer.compose(candidates(userId));
        if (composition.picks().isEmpty()) {
            throw new Conflict("시험을 만들 문제가 모자란다 - 트랙 " + user.track()
                    + " 에서 처음 보는, 잠기지 않은 문제가 도메인 둘 이상에 걸쳐 있어야 한다");
        }

        MockTestRow test;
        try {
            test = tests.saveAndFlush(new MockTestRow(userId, now,
                    now.plus(Duration.ofMinutes(composition.minutes()))));
        } catch (DataIntegrityViolationException e) {
            throw new Conflict("진행 중인 시험이 있다");
        }
        for (MockTestComposer.Pick pick : composition.picks()) {
            problems.save(new MockTestProblemRow(test.id(), pick.label(), pick.problem().code()));
        }
        problems.flush();
        return overview(test, now);
    }

    /**
     * 후보: 사용자의 트랙 안(ADR-0035), 한 번도 제출하지 않은, PRIMARY Skill 이 잠기지 않은
     * 일반 문제. 드릴 · 복습 문제는 시험 문제가 아니다.
     */
    private List<MockTestComposer.Candidate> candidates(long userId) {
        Map<String, SkillStatus> status = new HashMap<>();
        for (SkillState state : mastery.statesOf(userId)) {
            status.put(state.skillCode(), state.status());
        }
        Set<String> seen = new HashSet<>(submissions.submittedProblemCodes(userId));

        List<MockTestComposer.Candidate> candidates = new ArrayList<>();
        for (String code : catalog.codes()) {
            ProblemDefinition problem = catalog.find(code);
            SkillStatus skill = status.get(problem.primarySkill());
            if (!"NORMAL".equals(problem.kind()) || skill == null || skill == SkillStatus.LOCKED
                    || seen.contains(code) || problem.expectedSolveSeconds() == null) {
                continue;
            }
            candidates.add(new MockTestComposer.Candidate(code, number(code),
                    curriculum.skill(problem.primarySkill()).domain(),
                    problem.expectedSolveSeconds()));
        }
        return candidates;
    }

    private static int number(String code) {
        return Integer.parseInt(code.substring(1, code.indexOf('_')));
    }

    // ── 시험 중 ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Overview overview(long mockTestId, long userId) {
        return overview(owned(mockTestId, userId), clock.instant());
    }

    private Overview overview(MockTestRow test, Instant now) {
        List<MockTestEventRow> observed = events.findByMockTestIdOrderByOccurredAtAscIdAsc(test.id());
        List<LabelProgress> progress = problems.findByMockTestIdOrderByLabelAsc(test.id()).stream()
                .map(problem -> new LabelProgress(problem.label(),
                        observed.stream().anyMatch(e -> e.label().equals(problem.label())
                                && e.kind() == MockTestEventRow.Kind.OPENED),
                        (int) observed.stream().filter(e -> e.label().equals(problem.label())
                                && e.kind() == MockTestEventRow.Kind.SUBMITTED).count()))
                .toList();
        boolean over = test.isOver(now);
        long remaining = over ? 0 : Duration.between(now, test.endsAt()).toSeconds();
        return new Overview(test.id(), over ? State.FINISHED : State.IN_PROGRESS,
                test.startedAt(), test.endsAt(), remaining, progress);
    }

    /** 문제를 연다. <b>연 시각이 곧 기록이다</b> - 그래서 조회가 아니라 상태 변경이다. */
    @Transactional
    public ProblemSheet open(long mockTestId, String label, long userId) {
        MockTestRow test = inProgress(mockTestId, userId);
        ProblemDefinition problem = problemOf(test, label);
        events.save(new MockTestEventRow(test.id(), label, MockTestEventRow.Kind.OPENED, null,
                clock.instant()));
        return new ProblemSheet(test.id(), label, problem.statement(), problem.timeLimitMs(),
                problem.memoryLimitMb(), catalog.samplesOf(problem.code()).stream()
                        .map(sample -> new Sample(sample.input(), sample.expectedOutput()))
                        .toList());
    }

    @Transactional
    public long run(long mockTestId, String label, long userId, String language,
            String sourceCode) {
        MockTestRow test = inProgress(mockTestId, userId);
        ProblemDefinition problem = problemOf(test, label);
        long runId = runs.accept(new RunService.Request(userId, problem.code(), language,
                sourceCode));
        events.save(new MockTestEventRow(test.id(), label, MockTestEventRow.Kind.RUN, null,
                clock.instant()));
        return runId;
    }

    /**
     * 실행 결과. 일반 조회({@code GET /api/runs/{id}})는 문제 code 를 돌려주므로 시험 중에는
     * 이것을 쓴다.
     */
    @Transactional(readOnly = true)
    public RunService.Result runResult(long mockTestId, long runId, long userId) {
        MockTestRow test = owned(mockTestId, userId);
        return runs.find(runId, userId)
                .filter(result -> problems.findByMockTestIdOrderByLabelAsc(test.id()).stream()
                        .anyMatch(p -> p.problemCode().equals(result.problemCode())))
                .orElseThrow(() -> new NotFound("이 시험의 실행이 아니다: " + runId));
    }

    @Transactional
    public long submit(long mockTestId, String label, long userId, String language,
            String sourceCode) {
        MockTestRow test = inProgress(mockTestId, userId);
        ProblemDefinition problem = problemOf(test, label);
        long submissionId = intake.accept(new SubmissionIntakeService.Request(userId,
                problem.code(), language, sourceCode, null));
        events.save(new MockTestEventRow(test.id(), label, MockTestEventRow.Kind.SUBMITTED,
                submissionId, clock.instant()));
        return submissionId;
    }

    @Transactional(readOnly = true)
    public Verdict verdict(long mockTestId, long submissionId, long userId) {
        MockTestRow test = owned(mockTestId, userId);
        MockTestEventRow event = events.findFirstBySubmissionId(submissionId)
                .filter(e -> e.mockTestId().equals(test.id()))
                .orElseThrow(() -> new NotFound("이 시험의 제출이 아니다: " + submissionId));
        return queries.find(submissionId)
                .map(view -> view.complete()
                        ? new Verdict(submissionId, event.label(), "COMPLETE",
                                view.judge().status(), view.judge().passed(),
                                view.judge().total())
                        : new Verdict(submissionId, event.label(), "PENDING", null, null, null))
                .orElseThrow(() -> new NotFound("그런 제출이 없다: " + submissionId));
    }

    @Transactional
    public Overview finish(long mockTestId, long userId) {
        MockTestRow test = owned(mockTestId, userId);
        Instant now = clock.instant();
        test.close(now);
        tests.save(test);
        return overview(test, now);
    }

    // ── 끝난 뒤 ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Report report(long mockTestId, long userId) {
        MockTestRow test = owned(mockTestId, userId);
        if (!test.isOver(clock.instant())) {
            throw new Conflict("시험이 끝나야 보고서를 준다 - 시험 중에는 분석을 주지 않는다");
        }
        List<MockTestProblemRow> rows = problems.findByMockTestIdOrderByLabelAsc(test.id());
        List<MockTestEventRow> observed = events.findByMockTestIdOrderByOccurredAtAscIdAsc(test.id());

        List<Long> submissionIds = observed.stream()
                .filter(e -> e.kind() == MockTestEventRow.Kind.SUBMITTED)
                .map(MockTestEventRow::submissionId).toList();
        Map<Long, SubmissionRow> byId = new HashMap<>();
        submissions.findAllById(submissionIds).forEach(row -> byId.put(row.id(), row));
        Map<Long, String> statusOf = new HashMap<>();
        byId.forEach((id, row) -> statusOf.put(id, row.status()));

        MockTestAnalysis.Result analysis = MockTestAnalysis.analyze(test.startedAt(),
                test.closedAt(),
                rows.stream().map(row -> new MockTestAnalysis.ProblemInput(row.label(),
                        catalog.find(row.problemCode()).expectedSolveSeconds())).toList(),
                observed.stream().map(e -> new MockTestAnalysis.Event(e.label(), e.kind(),
                        e.submissionId(), e.occurredAt())).toList(),
                statusOf);

        List<ProblemReport> reports = new ArrayList<>();
        for (MockTestAnalysis.ProblemResult result : analysis.problems()) {
            MockTestProblemRow row = rows.stream()
                    .filter(r -> r.label().equals(result.label())).findFirst().orElseThrow();
            ProblemDefinition problem = catalog.find(row.problemCode());
            List<Mistake> mistakes = observed.stream()
                    .filter(e -> e.label().equals(result.label())
                            && e.kind() == MockTestEventRow.Kind.SUBMITTED
                            && !e.occurredAt().isAfter(test.closedAt()))
                    .map(e -> byId.get(e.submissionId()))
                    .filter(s -> s != null && s.reviewPrimaryMistake() != null)
                    .map(s -> new Mistake(s.id(), s.reviewPrimaryMistake(), s.reviewStatus()))
                    .toList();
            reports.add(new ProblemReport(result.label(), problem.code(), problem.title(),
                    problem.primarySkill(), problem.expectedSolveSeconds(), result.outcome(),
                    result.openedAtSeconds(), result.firstRunAtSeconds(),
                    result.firstSubmitAtSeconds(), result.solvedAtSeconds(),
                    result.submissions(), result.timeSpentSeconds(), result.overExpected(),
                    mistakes));
        }
        return new Report(test.id(), test.startedAt(), test.closedAt(),
                (int) Duration.between(test.startedAt(), test.endsAt()).toSeconds(),
                analysis.solved(), rows.size(), analysis.openOrder(), analysis.easiestFirst(),
                List.copyOf(reports));
    }

    // ── 다른 기능이 묻는 것 ────────────────────────────────────────────────

    /**
     * 이 사용자의 진행 중인 시험에 이 문제가 들어 있는가. 들어 있으면 힌트 · 일반 제출 ·
     * 일반 실행을 막는다 - 문제 code 를 알아내 직접 부르는 길도 닫는다.
     */
    @Transactional(readOnly = true)
    public boolean inProgressContains(long userId, String problemCode) {
        Instant now = clock.instant();
        return tests.findByUserIdAndFinishedAtIsNull(userId)
                .filter(test -> !test.isOver(now))
                .map(test -> problems.findByMockTestIdOrderByLabelAsc(test.id()).stream()
                        .anyMatch(p -> p.problemCode().equals(problemCode)))
                .orElse(false);
    }

    /**
     * 이 제출이 아직 진행 중인 시험의 것인가. 그렇다면 일반 조회(분석 · 다음 행동)를 막는다.
     */
    @Transactional(readOnly = true)
    public boolean hidesUntilEnd(long submissionId) {
        Instant now = clock.instant();
        return events.findFirstBySubmissionId(submissionId)
                .flatMap(event -> tests.findById(event.mockTestId()))
                .map(test -> !test.isOver(now))
                .orElse(false);
    }

    private MockTestRow owned(long mockTestId, long userId) {
        return tests.findById(mockTestId)
                .filter(test -> test.userId() == userId)
                .orElseThrow(() -> new NotFound("그런 시험이 없다: " + mockTestId));
    }

    private MockTestRow inProgress(long mockTestId, long userId) {
        MockTestRow test = owned(mockTestId, userId);
        if (test.isOver(clock.instant())) {
            throw new Conflict("시험이 끝났다: " + mockTestId);
        }
        return test;
    }

    private ProblemDefinition problemOf(MockTestRow test, String label) {
        return problems.findById(new MockTestProblemRow.Key(test.id(), label))
                .map(row -> catalog.find(row.problemCode()))
                .orElseThrow(() -> new NotFound("이 시험에 그런 문제가 없다: " + label));
    }
}
