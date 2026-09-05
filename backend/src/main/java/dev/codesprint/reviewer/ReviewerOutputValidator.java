package dev.codesprint.reviewer;

import dev.codesprint.curriculum.CurriculumCatalog;
import dev.codesprint.curriculum.CurriculumCatalog.MistakeDefinition;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Reviewer 출력이 쓸 수 있는 것인지 확인한다. 정본: ADR-0014, Addendum §20.
 *
 * <p>스키마만으로는 부족하다. 스키마는 "enum 안의 값인가" 까지만 보고, <b>그 값이
 * 이 저장소의 커리큘럼에 실재하는지</b>나 <b>Judge 가 실제로 실패시킨 case 를
 * 근거로 들었는지</b>는 보지 못한다.
 *
 * <p><b>어긋나면 그 분석은 통째로 버린다.</b> 고쳐서 쓰지 않는다 - 부분적으로 맞는
 * 분석을 살리려다 잘못된 절반이 학습 경로에 들어간다.
 */
@Component
public class ReviewerOutputValidator {

    private final CurriculumCatalog catalog;

    public ReviewerOutputValidator(CurriculumCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * @param problems 비어 있으면 쓸 수 있는 분석이다.
     */
    public record Result(List<String> problems) {

        public boolean isUsable() {
            return problems.isEmpty();
        }

        public String describe() {
            return String.join(" / ", problems);
        }
    }

    /** Judge 근거와의 대조는 {@link #citesJudgeFailure} 가 따로 본다. */
    public Result validate(ReviewerOutput output) {
        List<String> problems = new ArrayList<>();

        // 1. Reviewer 가 부여할 수 있는 code 인가.
        //
        // 스키마 enum 이 이미 막지만, 스키마 검증을 거치지 않는 경로(다른 모델
        // 어댑터, 재처리 배치)가 생길 수 있다. 그리고 이 검사는 enum 이 아니라
        // 커리큘럼 데이터를 본다 - mistakes.yaml 에서 assigned_by 를 바꾸면
        // 여기가 따라온다.
        checkAssignable(output.primaryMistake(), "primaryMistake", problems);
        for (String code : output.secondaryMistakes()) {
            checkAssignable(code, "secondaryMistakes", problems);
            // 같은 원인을 두 번 세면 재발 계수가 부풀고, 그 값이 확정을 좌우한다.
            if (code.equals(output.primaryMistake())) {
                problems.add("secondaryMistakes 에 primaryMistake 가 중복됐다: " + code);
            }
        }

        // 2. 실재하는 Skill 인가. 없는 Skill 에 penalty 가 걸리면 사용자는
        //    화면에 없는 항목 때문에 진도가 막힌다.
        if (output.affectedSkills().isEmpty()) {
            problems.add("affectedSkills 가 비어 있다");
        }
        for (String skill : output.affectedSkills()) {
            if (catalog.skill(skill) == null) {
                problems.add("커리큘럼에 없는 Skill 이다: " + skill);
            }
        }

        // 3. 근거가 있는가. 없는 분석은 확정은커녕 기록할 값도 아니다(ADR-0004).
        if (output.failedCaseRefs().isEmpty()) {
            problems.add("failedCaseRefs 가 비어 있다 - 근거 없는 분석이다");
        }

        // 4. confidence 범위. 스키마 밖에서 들어온 값이 그대로 확정 구간에 걸리면
        //    안 된다.
        if (output.confidence() < 0 || output.confidence() > 1) {
            problems.add("confidence 가 0~1 이 아니다: " + output.confidence());
        }

        return new Result(List.copyOf(problems));
    }

    /**
     * Reviewer 가 <b>Judge 가 실제로 실패시킨 case</b> 를 근거로 들었는가.
     *
     * <p>Addendum §21-A 의 "실패 Test Case 특성이 해당 Mistake 와 일치" 를 지금
     * 구현할 수 있는 형태다. case 에 성격 태그(경계 / 최소 / 최대…)가 붙으면 더
     * 강하게 볼 수 있지만, <b>지금은 이것이 전부다</b> - 더 강한 척하지 않는다
     * (ADR-0014).
     *
     * <p>Judge 가 case 를 특정하지 못한 판정에서는 확인할 것이 없으므로 false 다.
     * 그런 분석은 §21-A 로 확정되지 않고 재발(§21-B)을 기다린다.
     */
    public boolean citesJudgeFailure(ReviewerOutput output, Integer judgeFailedCaseId) {
        return judgeFailedCaseId != null && output.failedCaseRefs().contains(judgeFailedCaseId);
    }

    private void checkAssignable(String code, String field, List<String> problems) {
        MistakeDefinition definition = catalog.mistake(code);
        if (definition == null) {
            problems.add(field + " 가 커리큘럼에 없는 Mistake 다: " + code);
            return;
        }
        // SYSTEM 이 부여하는 code 를 LLM 이 주장할 수 없다. SYNTAX_ERROR 가 그렇다 -
        // 그 판정에서는 Reviewer 를 부르지도 않는다(ADR-0004).
        if (!"REVIEWER".equals(definition.assignedBy())) {
            problems.add(field + " 는 Reviewer 가 부여할 수 없는 Mistake 다: " + code
                    + " (assigned_by=" + definition.assignedBy() + ")");
        }
    }
}
