package dev.codesprint.learning.domain;

import java.util.EnumMap;
import java.util.Map;

/**
 * 한 사용자의 한 Skill 상태. 계약: contracts/user-skill.schema.json.
 *
 * <p>Evidence 로부터 재계산된 <b>파생값</b>이다. DB 의 user_skills 행은 캐시이며,
 * Evidence 와 어긋나면 Evidence 가 이긴다(ADR-0009).
 *
 * @param mastery 평가된 차원만으로 계산하고 가중치를 재정규화한 값.
 *     아무 차원도 평가되지 않았으면 null 이다 - 0.0(평가했고 못함)과 다르다.
 * @param confidence mastery 와 별개다. "이 판단을 얼마나 믿을 수 있는가".
 */
public record SkillState(
        String skillCode,
        Map<Dimension, Double> scores,
        Double mastery,
        double confidence,
        int evidenceCount,
        SkillStatus status) {

    public SkillState {
        Map<Dimension, Double> copy = new EnumMap<>(Dimension.class);
        if (scores != null) {
            scores.forEach((dimension, value) -> {
                if (value != null) {
                    copy.put(dimension, value);
                }
            });
        }
        scores = Map.copyOf(copy);
    }

    public static SkillState unassessed(String skillCode) {
        return new SkillState(skillCode, Map.of(), null, 0.0, 0, SkillStatus.UNASSESSED);
    }

    public Double score(Dimension dimension) {
        return scores.get(dimension);
    }
}
