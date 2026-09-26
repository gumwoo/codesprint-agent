package dev.codesprint.learning.domain;

/**
 * 제출 언어. 정본: PRD §67 · §164, ADR-0045.
 *
 * <p>언어는 채점 이미지를 고른다 - 하네스는 그 이미지가 정한 언어로만 돈다. 그래서 여기 없는 값은 받지 않는다
 * (400). 받아서 아무 이미지로 돌리면 language 와 실제 판정이 어긋난 기록이 append-only 정본에 남는다.
 *
 * <p>Skill Graph 는 언어에 매이지 않는다(§67). 다만 {@code skills.yaml} 에서 {@code language} 가 정해진 Skill
 * (예: PYTHON_LIST_BASIC)은 그 언어의 제출만 잴 수 있다 - Java 로 푼 것은 Python list 를 다룬 증거가 아니다.
 */
public enum SubmissionLanguage {
    PYTHON,
    JAVA,
    CPP;

    /** 대소문자를 가리지 않는다. 모르는 값이면 null - 호출자가 400 으로 돌려준다. */
    public static SubmissionLanguage parse(String value) {
        if (value == null) {
            return null;
        }
        for (SubmissionLanguage language : values()) {
            if (language.name().equalsIgnoreCase(value.strip())) {
                return language;
            }
        }
        return null;
    }

    /**
     * 이 언어의 제출이 그 Skill 의 증거가 될 수 있는가.
     *
     * @param skillLanguage {@code skills.yaml} 의 language. null 이면 언어와 무관한 Skill 이다.
     */
    public boolean measures(String skillLanguage) {
        return skillLanguage == null || name().equals(skillLanguage);
    }
}
