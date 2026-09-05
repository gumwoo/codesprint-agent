package dev.codesprint.learning.domain;

/**
 * Decision Engine 의 결정. 계약: contracts/submit-response.schema.json 의 nextAction.
 *
 * @param targetSkill 대상이 있는 액션에서만 채운다. 없으면 null - 생략과 구분한다.
 * @param reason 어떤 규칙이 이 액션을 골랐는가. 사용자에게 보여주는 문장이 아니라
 *     <b>감사 로그</b>다. 학습 경로가 이상할 때 어느 분기를 탔는지 추적하는 데 쓴다.
 */
public record NextAction(ActionType type, String targetSkill, String reason) {

    public static NextAction of(ActionType type, String reason) {
        return new NextAction(type, null, reason);
    }

    public static NextAction targeting(ActionType type, String targetSkill, String reason) {
        return new NextAction(type, targetSkill, reason);
    }
}
