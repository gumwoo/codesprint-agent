package dev.codesprint.tutor;

import dev.codesprint.curriculum.CurriculumCatalog;
import dev.codesprint.learning.domain.LearningMode;
import dev.codesprint.learning.persistence.UserRepository;
import dev.codesprint.mocktest.MockTestService;
import org.springframework.stereotype.Service;

/**
 * 자유 질문. 정본: PRD §90 · §150 · §151, ADR-0044.
 *
 * <p><b>무엇도 기록하지 않는다.</b> 답은 Evidence 가 되지 않고 mastery 도 다음 행동도 바꾸지
 * 않는다 - 설명을 들은 것은 풀 수 있다는 관측이 아니다.
 *
 * <p>FREE 모드에서만 준다(PRD §151). 다른 모드는 "힌트 우선" 이라 사다리를 거쳐야 하고(§150),
 * 튜터가 사다리를 건너뛰는 길이 되면 힌트 기록(ADR-0027)이 받은 도움의 양을 말하지 못한다.
 */
@Service
public class TutorService {

    /** 질문 길이의 상한. 프롬프트에 그대로 들어간다. */
    public static final int MAX_QUESTION = 1000;

    private final TutorPort tutor;
    private final UserRepository users;
    private final CurriculumCatalog curriculum;
    private final MockTestService mockTests;

    public TutorService(TutorPort tutor, UserRepository users, CurriculumCatalog curriculum,
            MockTestService mockTests) {
        this.tutor = tutor;
        this.users = users;
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
    public static class BadQuestion extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public BadQuestion(String message) {
            super(message);
        }
    }

    /** 지금 줄 수 없다 - 모드 · 시험. 409. */
    public static class Withheld extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public Withheld(String message) {
            super(message);
        }
    }

    /** Tutor 가 꺼져 있다. 503. */
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

    public record Result(String skillCode, String answer, String followUpQuestion,
            String promptVersion) {
    }

    public Result ask(long userId, String skillCode, String question) {
        var user = users.findById(userId)
                .orElseThrow(() -> new NotFound("그런 사용자가 없다: " + userId));
        var skill = curriculum.skill(skillCode);
        if (skill == null) {
            throw new NotFound("그런 Skill 이 없다: " + skillCode);
        }
        if (question == null || question.isBlank() || question.length() > MAX_QUESTION) {
            throw new BadQuestion("질문은 1~" + MAX_QUESTION + " 자다");
        }
        if (user.learningMode() != LearningMode.FREE) {
            throw new Withheld("자유 질문은 학습 모드 FREE 에서 연다 (지금: " + user.learningMode() + ")");
        }
        // 시험 중에는 어떤 도움도 주지 않는다(PRD §84). 시험 문제의 Skill 을 몰라도 막는다 -
        // 시험 중인지는 서버가 알고, 무엇을 묻는지는 모른다.
        if (mockTests.latest(userId)
                .filter(test -> test.state() == MockTestService.State.IN_PROGRESS).isPresent()) {
            throw new Withheld("시험 중에는 질문에 답하지 않는다");
        }
        if (!tutor.enabled()) {
            throw new Disabled("Tutor 가 꺼져 있다 - CODESPRINT_TUTOR_ENABLED=true 로 켠다");
        }
        var concept = curriculum.concept(skillCode);
        TutorPort.Request request = new TutorPort.Request(skillCode, skill.name(),
                concept == null ? "(개념 자료 없음)" : concept.title() + " - " + concept.summary(),
                concept == null ? java.util.List.of() : concept.keyPoints(), question.strip());
        return tutor.answer(request)
                .map(answer -> new Result(skillCode, answer.answer(), answer.followUpQuestion(),
                        tutor.promptVersion()))
                .orElseThrow(() -> new Unusable("튜터의 답을 쓸 수 없었다 - 다시 묻는다"));
    }
}
