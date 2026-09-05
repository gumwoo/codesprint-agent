package dev.codesprint.learning.persistence;

import java.io.Serializable;
import java.util.Objects;

/** user_skills 의 복합 키. */
public class UserSkillId implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long userId;
    private String skillCode;

    protected UserSkillId() {
    }

    public UserSkillId(Long userId, String skillCode) {
        this.userId = userId;
        this.skillCode = skillCode;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof UserSkillId that)) {
            return false;
        }
        return Objects.equals(userId, that.userId) && Objects.equals(skillCode, that.skillCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, skillCode);
    }
}
