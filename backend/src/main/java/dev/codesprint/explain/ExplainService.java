package dev.codesprint.explain;

import dev.codesprint.curriculum.CurriculumCatalog;
import dev.codesprint.learning.persistence.ProblemRepository;
import dev.codesprint.learning.persistence.SubmissionRepository;
import dev.codesprint.learning.persistence.UserRepository;
import dev.codesprint.mocktest.MockTestService;
import dev.codesprint.problem.ProblemCatalog;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Explain Back. 정본: PRD §148, ADR-0050.
 *
 * <p>푼 문제에 대해 "왜 이 풀이가 통하는가" 를 사용자가 두 문장으로 설명하고, 모델이 짚은 점 · 빠진 점 · 잘못
 * 이해한 점을 돌려준다. PRD §148 은 "설명을 Evaluator 가 분석한다" 고 하지만 Evaluator 는 시스템이다
 * (ADR-0049) - 분석은 LLM 이 하는 일이고 결과는 <b>주장</b>이다. 그래서 <b>무엇도 기록하지 않는다</b>:
 * Evidence · mastery · 다음 행동 어디에도 들어가지 않는다. 설명을 잘했다는 것은 풀 수 있다는 관측이 아니다.
 *
 * <p>질문은 시스템이 정한다 - 그 문제의 PRIMARY Skill 이름으로 만든 한 문장이다. 모델이 질문을 고르면 같은
 * 문제에서 다른 것을 묻게 되고, 사용자는 무엇을 설명해야 하는지 모른다.
 */
@Service
public class ExplainService {

    /** 설명 길이의 상한. PRD 의 "두 문장" 보다 넉넉하다 - 길이로 자르지 않고 두 문장을 청할 뿐이다. */
    public static final int MAX_EXPLANATION = 1000;

    private final ExplainPort explainer;
    private final UserRepository users;
    private final ProblemCatalog problems;
    private final ProblemRepository problemRows;
    private final SubmissionRepository submissions;
    private final CurriculumCatalog curriculum;
    private final MockTestService mockTests;

    public ExplainService(ExplainPort explainer, UserRepository users, ProblemCatalog problems,
            ProblemRepository problemRows, SubmissionRepository submissions,
            CurriculumCatalog curriculum, MockTestService mockTests) {
        this.explainer = explainer;
        this.users = users;
        this.problems = problems;
        this.problemRows = problemRows;
        this.submissions = submissions;
        this.curriculum = curriculum;
        this.mockTests = mockTests;
    }

    public static class NotFound extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public NotFound(String message) {
            super(message);
        }
    }

    /** 요청이 틀렸다. 400. */
    public static class BadExplanation extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public BadExplanation(String message) {
            super(message);
        }
    }

    /** 지금 받을 수 없다 - 시험 중이거나 아직 풀지 않았다. 409. */
    public static class Withheld extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public Withheld(String message) {
            super(message);
        }
    }

    /** Explain Back 이 꺼져 있다. 503. */
    public static class Disabled extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public Disabled(String message) {
            super(message);
        }
    }

    /** 모델을 부르지 못했거나 답이 계약을 어겼다. 502. */
    public static class Unusable extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public Unusable(String message) {
            super(message);
        }
    }

    public record Result(String problemCode, String skillCode, String question,
            List<String> coveredPoints, List<String> missingPoints, String misconception,
            String followUpQuestion, String promptVersion) {
    }

    /** 시스템이 정한 질문(PRD §148 의 예를 따른다). */
    static String question(String skillName) {
        return "왜 이 문제에서 " + skillName + " 이(가) 통하는지 두 문장으로 설명해 보세요.";
    }

    public Result explain(long userId, String problemCode, String explanation) {
        users.findById(userId).orElseThrow(() -> new NotFound("그런 사용자가 없다: " + userId));
        var problem = problems.find(problemCode);
        if (problem == null) {
            throw new NotFound("그런 문제가 없다: " + problemCode);
        }
        if (explanation == null || explanation.isBlank() || explanation.length() > MAX_EXPLANATION) {
            throw new BadExplanation("설명은 1~" + MAX_EXPLANATION + " 자다");
        }
        // 시험 중에는 어떤 분석도 주지 않는다(PRD §84). 시험 밖 문제의 설명도 막는다 - 분석이 시험 문제와 같은
        // 유형의 요점을 알려 줄 수 있다.
        if (mockTests.inProgress(userId)) {
            throw new Withheld("시험 중에는 설명을 분석하지 않는다 - 시험을 끝내면 연다");
        }
        // 푼 문제만 설명한다. 풀기 전에 설명을 분석해 주면 빠진 요점이 곧 힌트가 되어, 힌트 사다리(ADR-0026)
        // 밖에서 도움을 받는 길이 된다.
        boolean solved = problemRows.findByCode(problemCode)
                .map(row -> submissions.existsByUserIdAndProblemIdAndStatus(userId, row.id(), "ACCEPTED"))
                .orElse(false);
        if (!solved) {
            throw new Withheld("푼 문제를 설명한다 - 먼저 ACCEPTED 를 받는다");
        }
        if (!explainer.enabled()) {
            throw new Disabled("Explain Back 이 꺼져 있다 - CODESPRINT_EXPLAIN_ENABLED=true 로 켠다");
        }
        var skill = curriculum.skill(problem.primarySkill());
        var concept = curriculum.concept(problem.primarySkill());
        String question = question(skill.name());
        ExplainPort.Request request = new ExplainPort.Request(problem.title(), skill.code(),
                skill.name(),
                concept == null ? "(개념 자료 없음)" : concept.title() + " - " + concept.summary(),
                concept == null ? List.of() : concept.keyPoints(), question, explanation.strip());
        return explainer.analyze(request)
                .map(a -> new Result(problemCode, skill.code(), question, a.coveredPoints(),
                        a.missingPoints(), a.misconception(), a.followUpQuestion(),
                        explainer.promptVersion()))
                .orElseThrow(() -> new Unusable("설명 분석을 쓸 수 없었다 - 다시 보낸다"));
    }
}
