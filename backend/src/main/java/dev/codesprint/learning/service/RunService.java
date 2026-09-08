package dev.codesprint.learning.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codesprint.judge.JudgeJobRepository;
import dev.codesprint.judge.JudgeJobRow;
import dev.codesprint.learning.persistence.UserRepository;
import dev.codesprint.problem.ProblemCatalog;
import dev.codesprint.problem.ProblemCatalog.ProblemDefinition;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 제출 전 실행. 정본: PRD §117, ADR-0020.
 *
 * <p>코드를 시험해 보려면 제출해야 했다. 그런데 제출은 Evidence 를 만들고 mastery 를
 * 움직인다 - <b>돌려 보는 것과 답을 내는 것이 같은 일이었다.</b> 초기 진단이 붙으면서
 * 더 아파졌다. 진단이 준 첫 문제를 시험 삼아 돌려볼 수 없다.
 *
 * <h2>제출과 같은 큐를 쓴다</h2>
 *
 * <p>큐의 정본은 {@code judge_jobs} 하나다(ADR-0013). 표를 하나 더 만들면 리스 ·
 * 재시도 · fencing 을 두 벌 유지하게 된다.
 *
 * <p>대신 <b>실행이 학습 상태에 닿을 수 없다는 것을 행 모양으로 보장한다.</b> RUN 행은
 * {@code submission_id} 를 가질 수 없고(V7 의 CHECK), 결과를 반영하는 경로는 그 값으로만
 * 이어진다. Poller 의 {@code kind = 'SUBMIT'} 조건은 그 위에 덧댄 것이지 그것 하나에
 * 기대지 않는다 - 코드는 지워질 수 있다.
 *
 * <h2>공개 case 만 돈다</h2>
 *
 * <p>Worker 가 {@code --samples-only} 로 하네스를 부른다. 숨은 case 를 돌리면 사용자는
 * 제출하지 않고도 채점 결과를 얻는다 - 그것은 실행이 아니라 제출이다.
 */
@Service
public class RunService {

    private static final String SUPPORTED_LANGUAGE = "PYTHON";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProblemCatalog catalog;
    private final UserRepository users;
    private final JudgeJobRepository jobs;

    public RunService(ProblemCatalog catalog, UserRepository users, JudgeJobRepository jobs) {
        this.catalog = catalog;
        this.users = users;
        this.jobs = jobs;
    }

    public record Request(Long userId, String problemCode, String language, String sourceCode) {
    }

    /** 결과. 아직 안 끝났으면 {@code judged} 가 null 이다. */
    public record Result(long runId, String problemCode, String status, JsonNode judged,
            String failureReason) {
    }

    /** 문제나 사용자가 없을 때. */
    public static class NotFound extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public NotFound(String message) {
            super(message);
        }
    }

    /** 아직 지원하지 않는 언어. */
    public static class UnsupportedLanguage extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public UnsupportedLanguage(String message) {
            super(message);
        }
    }

    /**
     * @return 접수된 실행의 id. 결과는 {@code GET /api/runs/{id}} 로 확인한다.
     */
    @Transactional
    public long accept(Request request) {
        if (!SUPPORTED_LANGUAGE.equalsIgnoreCase(request.language())) {
            throw new UnsupportedLanguage(
                    "아직 " + SUPPORTED_LANGUAGE + " 만 실행한다: " + request.language());
        }
        ProblemDefinition problem = catalog.find(request.problemCode());
        if (problem == null) {
            throw new NotFound("그런 문제가 없다: " + request.problemCode());
        }
        if (!users.existsById(request.userId())) {
            throw new NotFound("그런 사용자가 없다: " + request.userId());
        }

        // 문제 행을 만들지 않는다. 실행은 이 사용자가 그 문제를 **풀었다는 기록이
        // 아니므로**, 남길 것이 큐의 job 하나뿐이다.
        return jobs.save(JudgeJobRow.forRun(request.userId(), problem.code(),
                SUPPORTED_LANGUAGE, request.sourceCode())).id();
    }

    /**
     * 실행 결과를 읽는다.
     *
     * <p><b>낸 사람만 볼 수 있다.</b> 실행 결과에는 그 사람이 쓴 코드의 출력이 담긴다.
     */
    @Transactional(readOnly = true)
    public Optional<Result> find(long runId, long userId) {
        return jobs.findById(runId)
                .filter(job -> "RUN".equals(job.kind()))
                .filter(job -> userId == (job.userId() == null ? -1L : job.userId()))
                .map(job -> new Result(job.id(), job.problemCode(), job.status(),
                        parse(job.result()), job.failureReason()));
    }

    private static JsonNode parse(String json) {
        if (json == null) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // Worker 가 쓴 값이 계약을 벗어났다. 실행은 학습 상태를 건드리지 않으므로
            // 여기서 조용히 비워도 잃는 것이 없다 - 다만 운영 로그에는 남겨야 한다.
            throw new IllegalStateException("실행 결과를 읽지 못했다: run " + json, e);
        }
    }
}
