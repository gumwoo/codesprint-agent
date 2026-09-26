package dev.codesprint.explain;

import java.util.List;
import java.util.Optional;

/**
 * 사용자의 설명을 읽는 쪽. 정본: PRD §148(Explain Back), ADR-0050.
 *
 * <p><b>분석만 한다.</b> 점수도 숙련도도 다음 행동도 만들지 않는다 - 응답 계약에 그 자리가 없다(ADR-0001).
 * 설명을 잘했다는 것은 풀 수 있다는 관측이 아니므로 Evidence 도 만들지 않는다.
 */
public interface ExplainPort {

    /**
     * @param question 시스템이 정한 질문. 모델이 질문을 바꾸지 않게 그대로 준다
     * @param conceptSummary 저장소의 개념 자료. 모델이 이것과 어긋나게 분석하지 않게 함께 준다
     */
    record Request(String problemTitle, String skillCode, String skillName, String conceptSummary,
            List<String> conceptPoints, String question, String explanation) {
    }

    /**
     * @param coveredPoints 설명이 짚은 요점
     * @param missingPoints 빠진 요점
     * @param misconception 잘못 이해한 것으로 보이는 한 가지. 없으면 null
     * @param followUpQuestion 스스로 확인할 질문 하나. 없으면 null
     */
    record Analysis(List<String> coveredPoints, List<String> missingPoints, String misconception,
            String followUpQuestion) {
    }

    /** 꺼져 있으면 false. 그때 {@link #analyze} 를 부르지 않는다. */
    boolean enabled();

    /** 모델 답이 계약을 어기면 비어 있다. 고쳐 쓰지 않는다. */
    Optional<Analysis> analyze(Request request);

    /** 켜져 있지 않으면 null. */
    String promptVersion();
}
