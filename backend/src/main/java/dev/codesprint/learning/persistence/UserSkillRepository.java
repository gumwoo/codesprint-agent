package dev.codesprint.learning.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 재계산된 캐시를 읽고 쓴다. <b>정본이 아니다</b>(ADR-0009).
 *
 * <p>여기 있는 값이 Evidence 와 어긋나면 Evidence 가 이긴다. 그래서 삭제가 열려 있어도
 * 위험하지 않다 - 지워도 Evidence 로부터 다시 만들 수 있다.
 */
public interface UserSkillRepository extends JpaRepository<UserSkillRow, UserSkillId> {

    List<UserSkillRow> findByUserId(Long userId);

    Optional<UserSkillRow> findByUserIdAndSkillCode(Long userId, String skillCode);
}
