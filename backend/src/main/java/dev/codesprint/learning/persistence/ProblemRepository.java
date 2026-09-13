package dev.codesprint.learning.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 문제의 DB 쪽 기록. 본문과 Test Case 는 파일에 있고(ADR-0008) 여기에는 code 와
 * 출처만 있다. 제출이 참조할 대상이 필요해서 둔다.
 *
 * <p>행은 <b>처음 그 문제를 건드릴 때</b> 생긴다. 마이그레이션이 미리 넣지 않는 이유는
 * 문제은행이 파일 쪽에 있어서, 문제를 추가할 때마다 마이그레이션을 쓰게 되기 때문이다.
 */
public interface ProblemRepository extends JpaRepository<ProblemRow, Long> {

    Optional<ProblemRow> findByCode(String code);

    /**
     * 그 문제의 행이 <b>있게 만든다.</b> 이미 있으면 아무 일도 하지 않는다.
     *
     * <p>{@code findByCode(...).orElseGet(save)} 로 쓰면 같은 문제를 처음 건드리는
     * 요청 둘이 나란히 "없다" 를 보고 둘 다 넣는다. {@code code} 가 UNIQUE 라 한쪽이
     * 터진다 - 실제로 재현했다. 깨끗한 DB 에 여덟을 동시에 보내 <b>일곱이
     * {@code problems_code_key} 위반</b>이었다.
     *
     * <p>힌트와 제출이 <b>같은 길을 쓴다.</b> 따로 적으면 한쪽만 고쳐진다.
     */
    @Modifying
    @Query(value = """
            insert into problems (code, source)
            values (:code, :source)
            on conflict (code) do nothing
            """, nativeQuery = true)
    void insertIfAbsent(@Param("code") String code, @Param("source") String source);

    /** 행을 보장하고 돌려준다. */
    default ProblemRow ensure(String code, String source) {
        insertIfAbsent(code, source);
        return findByCode(code).orElseThrow(() ->
                new IllegalStateException("problems 행을 만들지도 찾지도 못했다: " + code));
    }
}
