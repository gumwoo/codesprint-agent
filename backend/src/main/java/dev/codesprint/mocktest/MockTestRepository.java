package dev.codesprint.mocktest;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 모의 시험. 정본: ADR-0043. */
public interface MockTestRepository extends JpaRepository<MockTestRow, Long> {

    /** 닫히지 않은 시험. 시간이 지났어도 아직 닫지 않았으면 여기 나온다. */
    Optional<MockTestRow> findByUserIdAndFinishedAtIsNull(Long userId);

    List<MockTestRow> findByUserIdOrderByStartedAtDesc(Long userId);
}
