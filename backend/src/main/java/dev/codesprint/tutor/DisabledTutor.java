package dev.codesprint.tutor;

import java.util.Optional;

/**
 * 꺼진 Tutor. <b>기본이다</b> - 로컬 Claude CLI 가 있고 로그인돼 있을 때만 켠다(Reviewer 와 같다).
 * 꺼져 있어도 나머지는 그대로 돈다. Tutor 는 설명일 뿐 학습 경로에 들어가지 않는다.
 */
public class DisabledTutor implements TutorPort {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public Optional<Answer> answer(Request request) {
        return Optional.empty();
    }

    @Override
    public String promptVersion() {
        return null;
    }
}
