package dev.codesprint.reviewer;

import java.util.List;

/**
 * Reviewer 가 낸 분석. 계약: contracts/reviewer-output.llm.schema.json.
 *
 * <p><b>여기에 점수도 액션도 없다.</b> 요청 스키마에 그 필드가 존재하지 않으므로
 * 모델이 만들어낼 수 없다(ADR-0001). 확정 여부조차 없다 - 그건 시스템이 붙인다.
 *
 * @param failedCaseRefs 이 분석의 근거가 된 실패 Test Case id. 비어 있을 수 없다
 *     (ADR-0004). Judge 가 실제로 실패시킨 case 를 포함하는지 시스템이 확인한다.
 */
public record ReviewerOutput(
        String algorithmSelection,
        String primaryMistake,
        List<String> secondaryMistakes,
        double confidence,
        List<String> affectedSkills,
        List<Integer> failedCaseRefs,
        boolean conceptIssue,
        boolean implementationIssue,
        String explanation) {

    public ReviewerOutput {
        secondaryMistakes = secondaryMistakes == null ? List.of() : List.copyOf(secondaryMistakes);
        affectedSkills = affectedSkills == null ? List.of() : List.copyOf(affectedSkills);
        failedCaseRefs = failedCaseRefs == null ? List.of() : List.copyOf(failedCaseRefs);
    }
}
