package dev.codesprint.learning.service;

import dev.codesprint.learning.persistence.HintUsageRepository;
import dev.codesprint.learning.persistence.HintUsageRow;
import dev.codesprint.learning.persistence.ProblemRepository;
import dev.codesprint.learning.persistence.ProblemRow;
import dev.codesprint.learning.persistence.UserRepository;
import dev.codesprint.problem.HintCatalog;
import dev.codesprint.problem.ProblemCatalog;
import dev.codesprint.problem.ProblemCatalog.ProblemDefinition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 힌트를 한 단계 내주고 <b>그 사실을 기록한다</b>. 정본: ADR-0026, PRD §73.
 *
 * <p>이 서비스가 존재하는 이유는 내용이 아니라 <b>기록</b>이다. 힌트 문구는
 * {@code hints.yaml} 에 이미 있고 아무나 읽을 수 있다. 측정되어야 하는 것은 "누가
 * 몇 단계까지 봤는가" 이고, 그것은 <b>내주는 쪽만 알 수 있다.</b>
 *
 * <p>예전에는 제출이 그 값을 실어 왔다. 힌트 기능이 없는데도 받았고, 아무도 확인할 수
 * 없는 그 숫자로 mastery 가 깎였다(PR #19 에서 입력을 막았다). 이제 신고를 받지 않는다.
 */
@Service
public class HintService {

    private final ProblemCatalog problems;
    private final HintCatalog hints;
    private final UserRepository users;
    private final ProblemRepository problemRows;
    private final HintUsageRepository usage;
    private final ReviewScheduleService clock;

    public HintService(ProblemCatalog problems, HintCatalog hints, UserRepository users,
            ProblemRepository problemRows, HintUsageRepository usage,
            ReviewScheduleService clock) {
        this.problems = problems;
        this.hints = hints;
        this.users = users;
        this.problemRows = problemRows;
        this.usage = usage;
        this.clock = clock;
    }

    /** 문제나 사용자가 없을 때. */
    public static class NotFound extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public NotFound(String message) {
            super(message);
        }
    }

    /**
     * 사다리를 건너뛰려 했을 때. 400 이다.
     *
     * <p><b>왜 막는가.</b> 힌트 의존도는 "몇 단계를 봤는가" 로 mastery 를 가른다
     * (Addendum §22). 건너뛰기를 허용하면 H1 만 보고 바로 H5 를 열 수 있고, 그때
     * 기록되는 값은 같은 5 다 - 실제로 받은 도움의 양이 다른데 같은 벌점을 받는다.
     * 사다리는 위로 갈수록 더 주기로 되어 있고(ADR-0026), 그 순서가 지켜져야
     * 숫자 하나가 도움의 양을 대신할 수 있다.
     */
    public static class SkippedLadder extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public SkippedLadder(String message) {
            super(message);
        }
    }

    /**
     * 내준 힌트 하나.
     *
     * @param highestLevel 이 요청까지 포함해 <b>이 문제에서 본 최고 단계.</b> 화면이
     *     "다음은 몇 단계인가" 를 스스로 계산하지 않게 함께 준다(ADR-0001).
     * @param topLevel 사다리의 끝. {@code highestLevel == topLevel} 이면 더 없다.
     */
    public record Hint(String problemCode, int level, String text,
            int highestLevel, int topLevel) {
    }

    /**
     * {@code level} 단계를 내주고 기록한다. 이미 본 단계를 다시 요청하면 새로 기록하지
     * 않고 내용만 돌려준다 - 다시 열어 봤다고 더 많이 본 것이 아니다.
     */
    @Transactional
    public Hint reveal(long userId, String problemCode, int level) {
        ProblemDefinition problem = problems.find(problemCode);
        if (problem == null) {
            throw new NotFound("그런 문제가 없다: " + problemCode);
        }
        if (!users.existsById(userId)) {
            throw new NotFound("그런 사용자가 없다: " + userId);
        }
        String text = hints.textOf(problemCode, level);
        if (text == null) {
            throw new NotFound("그런 힌트 단계가 없다: " + problemCode + " H" + level);
        }

        ProblemRow problemRow = problemRows.findByCode(problem.code())
                .orElseGet(() -> problemRows.save(new ProblemRow(problem.code(), problem.source())));

        int seen = usage.highestLevel(userId, problemRow.id()).orElse(0);
        if (level > seen + 1) {
            throw new SkippedLadder(
                    "힌트는 한 단계씩 연다. 지금까지 본 것은 H" + seen
                            + " 이므로 다음은 H" + (seen + 1) + " 다 (요청: H" + level + ")");
        }
        if (level > seen) {
            usage.save(new HintUsageRow(userId, problemRow.id(), level, clock.now()));
        }

        return new Hint(problem.code(), level, text,
                Math.max(seen, level), hints.topLevel(problemCode));
    }
}
