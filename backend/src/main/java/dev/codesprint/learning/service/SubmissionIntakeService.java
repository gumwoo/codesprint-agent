package dev.codesprint.learning.service;

import dev.codesprint.judge.JudgeJobRepository;
import dev.codesprint.judge.JudgeJobRow;
import dev.codesprint.learning.domain.JudgeStatus;
import dev.codesprint.learning.persistence.ProblemRepository;
import dev.codesprint.learning.persistence.ProblemRow;
import dev.codesprint.learning.persistence.SubmissionRepository;
import dev.codesprint.learning.persistence.SubmissionRow;
import dev.codesprint.learning.persistence.UserRepository;
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

    public SubmissionIntakeService(ProblemCatalog catalog, UserRepository users,
            ProblemRepository problems, SubmissionRepository submissions,
            JudgeJobRepository jobs) {
        this.catalog = catalog;
        this.users = users;
        this.problems = problems;
        this.submissions = submissions;
        this.jobs = jobs;
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
        ProblemRow problemRow = problems.findByCode(problem.code())
                .orElseGet(() -> problems.save(new ProblemRow(problem.code(), problem.source())));

        SubmissionRow submission = submissions.save(new SubmissionRow(
                request.userId(), problemRow.id(), SUPPORTED_LANGUAGE,
                JudgeStatus.QUEUED.name(), request.hintLevel(), request.solutionViewed(),
                request.solveSeconds()));

        jobs.save(new JudgeJobRow(submission.id(), problem.code(), SUPPORTED_LANGUAGE,
                request.sourceCode()));

        return submission.id();
    }
}
