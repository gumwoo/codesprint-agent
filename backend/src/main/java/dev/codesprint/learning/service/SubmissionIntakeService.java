package dev.codesprint.learning.service;

import dev.codesprint.judge.JudgeJobRepository;
import dev.codesprint.judge.JudgeJobRow;
import dev.codesprint.learning.domain.JudgeStatus;
import dev.codesprint.learning.persistence.HintUsageRepository;
import dev.codesprint.learning.persistence.ProblemRepository;
import dev.codesprint.learning.persistence.ProblemRow;
import dev.codesprint.learning.persistence.SubmissionRepository;
import dev.codesprint.learning.persistence.SubmissionRow;
import dev.codesprint.learning.persistence.UserRepository;
import dev.codesprint.problem.HintCatalog;
import dev.codesprint.problem.ProblemCatalog;
import dev.codesprint.problem.ProblemCatalog.ProblemDefinition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 제출을 받아 큐에 넣는다. <b>여기서 채점하지 않는다</b>(ADR-0013).
 *
 * <p>이 트랜잭션은 행 두 개를 쓰고 끝난다 - 제출 하나와 job 하나. 채점 시간과
 * 무관하며, 그것이 이 클래스가 존재하는 이유다. 예전에는 같은 트랜잭션 안에서
 * 컨테이너를 띄워 최대 120초를 기다렸고, 그동안 DB 커넥션을 잡고 있었다.
 *
 * <p>제출과 job 이 <b>같은 트랜잭션</b>에 들어간다. 별도 메시지 브로커였다면
 * "제출은 저장됐는데 큐에 못 넣었다" 를 따로 다뤄야 한다.
 */
@Service
public class SubmissionIntakeService {

    /**
     * 지원하는 언어. <b>슬라이스 1 은 Python 뿐이다</b>(Addendum PART III).
     *
     * <p>다른 값을 받아 넘기면 안 된다. Worker 는 무엇을 받든 {@code solution.py} 로
     * 써서 Python 으로 돌리므로, {@code language = JAVA} 로 저장해 놓고 실제로는 Java
     * 코드를 Python 으로 실행한 판정이 나온다. 그 판정으로 만든 Evidence 는
     * append-only 정본에 그대로 남는다.
     */
    private static final String SUPPORTED_LANGUAGE = "PYTHON";

    private final ProblemCatalog catalog;
    private final UserRepository users;
    private final ProblemRepository problems;
    private final SubmissionRepository submissions;
    private final JudgeJobRepository jobs;
    private final ReviewScheduleService reviewSchedules;
    private final HintUsageRepository hintUsage;

    public SubmissionIntakeService(ProblemCatalog catalog, UserRepository users,
            ProblemRepository problems, SubmissionRepository submissions,
            JudgeJobRepository jobs, ReviewScheduleService reviewSchedules,
            HintUsageRepository hintUsage) {
        this.catalog = catalog;
        this.users = users;
        this.problems = problems;
        this.submissions = submissions;
        this.jobs = jobs;
        this.reviewSchedules = reviewSchedules;
        this.hintUsage = hintUsage;
    }

    /**
     * <b>힌트 사용량이 없다.</b> 클라이언트가 신고하지 않고 서버가 기록에서 읽는다
     * (ADR-0026). 이 record 에 필드를 다시 넣는 것은 그 결정을 되돌리는 것이다.
     */
    public record Request(
            Long userId,
            String problemCode,
            String language,
            String sourceCode,
            Integer solveSeconds) {
    }

    /**
     * 힌트 사용량을 <b>스스로 신고한</b> 제출. 400 이다.
     *
     * <p>이제 서버가 기록에서 읽는다(ADR-0026). 그런데 요청이 여전히 값을 실어 보내면
     * <b>보낸 쪽은 그 값이 적용됐다고 믿는다.</b> 조용히 무시하는 것이 가장 나쁘다 -
     * 화면은 "해설을 봤다" 고 보냈는데 기록에는 없어서 독립 풀이로 남는다.
     *
     * <p>그래서 필드를 지우는 대신 <b>있으면 거절한다.</b> 지우기만 하면 Jackson 이
     * 모르는 필드를 조용히 버리므로 같은 일이 벌어진다.
     */
    public static class SelfReportedHintUsage extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public SelfReportedHintUsage(String message) {
            super(message);
        }
    }

    /** 아직 지원하지 않는 언어. 사용자 입력 문제이므로 400 이다. */
    public static class UnsupportedLanguage extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public UnsupportedLanguage(String message) {
            super(message);
        }
    }

    /** 문제나 사용자가 없을 때. 채점 실패(SYSTEM_ERROR)와 구분한다 - 그쪽은 우리 잘못이다. */
    public static class NotFound extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public NotFound(String message) {
            super(message);
        }
    }

    /**
     * @return 접수된 제출의 id. 결과는 {@code GET /api/submissions/{id}} 로 확인한다.
     */
    @Transactional
    public long accept(Request request) {
        // 큐에 넣기 전에 막는다. job 을 만든 뒤에 알면 language 와 실제 판정이
        // 어긋난 기록이 남는다.
        if (!SUPPORTED_LANGUAGE.equalsIgnoreCase(request.language())) {
            throw new UnsupportedLanguage(
                    "아직 " + SUPPORTED_LANGUAGE + " 만 채점한다: " + request.language());
        }
        ProblemDefinition problem = catalog.find(request.problemCode());
        if (problem == null) {
            throw new NotFound("그런 문제가 없다: " + request.problemCode());
        }
        if (!users.existsById(request.userId())) {
            throw new NotFound("그런 사용자가 없다: " + request.userId());
        }
        // 같은 문제의 첫 제출 둘이 동시에 오면 둘 다 "행이 없다" 를 보고 둘 다 넣는다.
        // 힌트와 같은 길을 쓴다 - 따로 적으면 한쪽만 고쳐진다.
        ProblemRow problemRow = problems.ensure(problem.code(), problem.source());

        // **힌트 단계를 여기서 읽는다.** 신고받지 않고 기록에서 가져온다(ADR-0026).
        //
        // 지금까지의 최댓값을 쓰는 이유는 한 번 본 힌트를 되돌릴 수 없기 때문이다.
        // H3 를 보고 실패한 뒤 힌트 없이 다시 내도 그 사람은 여전히 H3 를 알고 있다 -
        // 두 번째를 "힌트 없는 풀이" 로 세면 독립 풀이를 실제보다 높게 기록한다.
        //
        // 값은 제출 행에 **얼려 둔다.** 나중에 H5 를 더 봐도 이미 낸 제출의 Evidence 가
        // 따라 바뀌면 안 된다 - Evidence 는 append-only 정본이다(ADR-0009).
        int seenLevel = hintUsage.highestLevel(request.userId(), problemRow.id()).orElse(0);
        boolean solutionViewed = seenLevel >= HintCatalog.SOLUTION_LEVEL;
        int hintLevel = solutionViewed ? 0 : seenLevel;

        SubmissionRow submission = submissions.save(new SubmissionRow(
                request.userId(), problemRow.id(), SUPPORTED_LANGUAGE,
                JudgeStatus.QUEUED.name(), hintLevel, solutionViewed,
                request.solveSeconds(),
                // 제출 시각을 여기서 박는다. 복습 만기를 이 값으로 판정하므로
                // (ADR-0021) 시계가 하나여야 한다.
                reviewSchedules.now()));

        // **만기된 복습을 여기서 가져간다**(ADR-0021). 반영 시점이 아니라 제출 시점이다 -
        // 뒤에 낸 제출이 먼저 채점됐다고 복습을 가로채면, 같은 순서로 낸 사용자의
        // mastery 가 Worker 사정에 따라 달라진다.
        reviewSchedules.claim(request.userId(), problem.primarySkill(), submission.id(),
                submission.submittedAt());

        jobs.save(new JudgeJobRow(submission.id(), problem.code(), SUPPORTED_LANGUAGE,
                request.sourceCode()));

        return submission.id();
    }
}
