package dev.codesprint.learning.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * problems 한 행. <b>본문은 두지 않는다.</b>
 *
 * <p>문제 본문과 Test Case 는 파일에 있고(ADR-0008), 여기에는 제출이 참조할 code 와
 * 출처만 둔다. 공개 저장소의 문제를 그대로 옮겨오면 CURATED 와 섞인다.
 */
@Entity
@Table(name = "problems")
public class ProblemRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String code;

    @Column(nullable = false, length = 20)
    private String source;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected ProblemRow() {
    }

    public ProblemRow(String code, String source) {
        this.code = code;
        this.source = source;
    }

    public Long id() {
        return id;
    }

    public String code() {
        return code;
    }

    public String source() {
        return source;
    }
}
