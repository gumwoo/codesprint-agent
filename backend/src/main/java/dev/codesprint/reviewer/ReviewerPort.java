package dev.codesprint.reviewer;

import java.util.List;
import java.util.Optional;

/**
 * 오답 원인을 분석한다. <b>구현은 LLM 이다.</b>
 *
 * <p>인터페이스로 두는 이유는 두 가지다.
 *
 * <ol>
 *   <li>오케스트레이션이 모델에 묶이지 않는다. 검증 · 확정 · 저장은 어떤 모델을 쓰든 같다.
 *   <li><b>테스트가 LLM 없이 돈다.</b> 여기서 검증해야 하는 것은 모델의 분석 품질이
 *       아니라 "그 분석을 어디까지 믿고 무엇을 하는가" 다. 품질은 별도 평가 하네스의
 *       몫이며, 그 라벨이 {@code mistake_detections} 에 쌓이고 있다.
 * </ol>
 *
 * <p>호출 조건은 시스템이 정한다 - 실패 Test Case 가 있을 때만이다(ADR-0004).
 * 이 인터페이스는 그 판단을 하지 않는다.
 */
public interface ReviewerPort {

    /**
     * @param failedCaseId Judge 가 실패시킨 case. Judge 는 첫 실패에서 멈추므로
     *     하나로 특정된다(ADR-0005).
     * @param skillCodes 이 문제가 겨냥하는 Skill 들. 분석 범위를 좁힌다.
     */
    record Request(
            String problemCode,
            String judgeStatus,
            Integer failedCaseId,
            String sourceCode,
            List<String> skillCodes) {
    }

    /**
     * @return 분석. 비어 있으면 <b>Reviewer 를 쓸 수 없었다</b>는 뜻이다 - 분석에
     *     실패한 것과 구분하지 않는다. 어느 쪽이든 Mistake 는 확정되지 않는다.
     */
    Optional<ReviewerOutput> review(Request request);

    /**
     * 이 분석을 만든 프롬프트 버전(PRD §135).
     *
     * <p>없으면 나중에 프롬프트를 바꿨을 때 이전 라벨과 이후 라벨을 섞어 정확도를
     * 재게 된다. 그때 가서 남기기 시작하면 이미 쌓인 것은 되살릴 수 없다.
     */
    String promptVersion();
}
