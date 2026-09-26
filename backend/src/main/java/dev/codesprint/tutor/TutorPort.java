package dev.codesprint.tutor;

import java.util.List;
import java.util.Optional;

/**
 * 자유 질문에 답하는 쪽. 정본: PRD §90, ADR-0044.
 *
 * <p><b>설명만 한다.</b> 점수도 다음 행동도 만들지 않는다 - 응답 계약에 그 자리가 없다(ADR-0001).
 */
public interface TutorPort {

    /**
     * @param conceptSummary 저장소의 개념 자료 요약. 튜터가 이것과 어긋나게 말하지 않게 함께 준다.
     */
    record Request(String skillCode, String skillName, String conceptSummary,
            List<String> conceptPoints, String question) {
    }

    record Answer(String answer, String followUpQuestion) {
    }

    /** 꺼져 있으면 false. 그때 {@link #answer} 를 부르지 않는다. */
    boolean enabled();

    /** 모델 답이 계약을 어기면 비어 있다. 고쳐 쓰지 않는다. */
    Optional<Answer> answer(Request request);

    /** 켜져 있지 않으면 null. */
    String promptVersion();
}
