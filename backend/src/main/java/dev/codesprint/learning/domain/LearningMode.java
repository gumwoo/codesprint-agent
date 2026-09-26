package dev.codesprint.learning.domain;

/**
 * 학습 모드. 정본: PRD §151, ADR-0043.
 *
 * <p><b>모드는 "무엇을 내주는가" 만 바꾼다.</b> 다음 행동과 mastery 는 모드로 바뀌지 않는다 -
 * 바뀌기 시작하면 같은 풀이 기록에서 모드마다 다른 학습 경로가 나와 규칙을 검증할 수 없다
 * (ADR-0002).
 *
 * @param highestHint 내줄 수 있는 가장 높은 힌트 단계. 0 이면 힌트가 없다. 6 은 전체 풀이다.
 * @param showsSkills 문제 화면에 Skill(유형)을 보여 주는가.
 * @param attachesConcept 문제 화면에 PRIMARY Skill 의 개념 자료를 함께 주는가.
 */
public enum LearningMode {
    GUIDED(6, true, true),
    NORMAL(6, true, false),
    STRICT(2, true, false),
    EXAM(0, false, false),
    FREE(6, true, false);

    private final int highestHint;
    private final boolean showsSkills;
    private final boolean attachesConcept;

    LearningMode(int highestHint, boolean showsSkills, boolean attachesConcept) {
        this.highestHint = highestHint;
        this.showsSkills = showsSkills;
        this.attachesConcept = attachesConcept;
    }

    public int highestHint() {
        return highestHint;
    }

    public boolean showsSkills() {
        return showsSkills;
    }

    public boolean attachesConcept() {
        return attachesConcept;
    }

    /** 모르는 값이면 null. 호출자가 400 으로 돌려준다 - 조용히 NORMAL 로 바꾸지 않는다. */
    public static LearningMode parse(String value) {
        if (value == null) {
            return null;
        }
        for (LearningMode mode : values()) {
            if (mode.name().equals(value)) {
                return mode;
            }
        }
        return null;
    }
}
