package dev.codesprint.learning.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 문제의 DB 쪽 기록. 본문과 Test Case 는 파일에 있고(ADR-0008) 여기에는 code 와
 * 출처만 있다. 제출이 참조할 대상이 필요해서 둔다.
 */
public interface ProblemRepository extends JpaRepository<ProblemRow, Long> {

    Optional<ProblemRow> findByCode(String code);
}
