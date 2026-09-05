package dev.codesprint.learning.domain;

/**
 * Skill 을 재는 여섯 축과 가중치. 정본: Addendum §6.
 *
 * <p>independent 와 implementation 이 가장 큰 이유는 제품의 목표가 "설명할 수 있다" 가
 * 아니라 "혼자 풀 수 있다" 이기 때문이다(PRD §163).
 *
 * <p>값을 임의로 고치지 않는다. 고칠 때는 Addendum 과 Python oracle
 * (learning/mastery.py)을 함께 고치고, golden fixture 를 다시 생성한다.
 */
public enum Dimension {
    CONCEPT("concept", 0.15),
    RECOGNITION("recognition", 0.20),
    IMPLEMENTATION("implementation", 0.25),
    INDEPENDENT("independent", 0.25),
    RETENTION("retention", 0.10),
    SPEED("speed", 0.05);

    private final String jsonName;
    private final double weight;

    Dimension(String jsonName, double weight) {
        this.jsonName = jsonName;
        this.weight = weight;
    }

    public String jsonName() {
        return jsonName;
    }

    public double weight() {
        return weight;
    }
}
