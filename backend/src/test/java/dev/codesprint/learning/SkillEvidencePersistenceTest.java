package dev.codesprint.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.codesprint.learning.persistence.SkillEvidenceRepository;
import dev.codesprint.learning.persistence.SkillEvidenceRow;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Evidence 영속성을 실물 PostgreSQL 로 검증한다.
 *
 * <p>인메모리 DB 로 바꾸지 않는다. 여기서 확인하려는 것 대부분이 <b>PostgreSQL 과
 * Flyway 마이그레이션의 동작</b>이기 때문이다 - UNIQUE 제약, CHECK 제약, TIMESTAMPTZ 의
 * 시간대 처리, jsonb 컬럼. H2 로 바꾸면 이 중 어느 것도 진짜로 검증되지 않는다.
 */
@SpringBootTest
@Transactional
class SkillEvidencePersistenceTest {

    /**
     * DB 를 어디서 얻을지.
     *
     * <p>기본은 Testcontainers 다. 다만 {@code TEST_DB_URL} 이 있으면 그것을 쓴다 -
     * Docker-in-Docker 가 막히는 환경(Windows 의 Docker Desktop 안에서 Gradle 컨테이너를
     * 띄우는 경우)에서도 같은 테스트를 돌리기 위해서다.
     *
     * <p><b>둘 다 없으면 건너뛰지 않고 실패한다.</b> 조용히 skip 하면 DB 검증이 사라진
     * 줄 아무도 모른 채 CI 가 초록불을 낸다.
     */
    private static final String EXTERNAL_URL = System.getenv("TEST_DB_URL");

    private static PostgreSQLContainer<?> container;

    static {
        if (EXTERNAL_URL == null) {
            container = new PostgreSQLContainer<>("postgres:16-alpine");
            container.start();
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        if (EXTERNAL_URL != null) {
            registry.add("spring.datasource.url", () -> EXTERNAL_URL);
            registry.add("spring.datasource.username",
                    () -> System.getenv().getOrDefault("TEST_DB_USER", "codesprint"));
            registry.add("spring.datasource.password",
                    () -> System.getenv().getOrDefault("TEST_DB_PASSWORD", "codesprint"));
        } else {
            registry.add("spring.datasource.url", container::getJdbcUrl);
            registry.add("spring.datasource.username", container::getUsername);
            registry.add("spring.datasource.password", container::getPassword);
        }
    }

    @Autowired
    private SkillEvidenceRepository repository;

    @Autowired
    private EntityManager entityManager;

    private Long userId;

    @BeforeEach
    void createUser() {
        userId = (Long) entityManager
                .createNativeQuery(
                        "INSERT INTO users (email, nickname) VALUES (:email, 'tester') "
                                + "RETURNING id")
                .setParameter("email", "tester+" + System.nanoTime() + "@example.com")
                .getSingleResult();
    }

    private SkillEvidenceRow row(String sourceEventId, String skillCode, Instant at,
            BigDecimal implementation) {
        return new SkillEvidenceRow(
                sourceEventId,
                "ev_" + Integer.toHexString((sourceEventId + skillCode).hashCode()),
                userId,
                skillCode,
                "PROBLEM_SUBMISSION",
                at,
                new BigDecimal("1.0000"),
                new BigDecimal("1.0000"),
                null, null, implementation, null, null, null,
                "{\"judgeStatus\":\"ACCEPTED\"}");
    }

    @Test
    @DisplayName("Flyway 마이그레이션이 돌고 Evidence 를 저장할 수 있다")
    void savesEvidence() {
        repository.saveAndFlush(
                row("submission:1", "BFS_GRID_TRAVERSAL",
                        Instant.parse("2026-09-01T10:00:00Z"), new BigDecimal("0.9000")));

        List<SkillEvidenceRow> found = repository
                .findByUserIdAndSkillCodeOrderByOccurredAtAscIdAsc(userId, "BFS_GRID_TRAVERSAL");

        assertThat(found).hasSize(1);
        assertThat(found.get(0).sourceEventId()).isEqualTo("submission:1");
        assertThat(found.get(0).observedImplementation())
                .isEqualByComparingTo(new BigDecimal("0.9000"));
    }

    @Test
    @DisplayName("같은 원천 + 같은 Skill 은 두 번 저장되지 않는다 (Worker 재시도 방어)")
    void rejectsDuplicateSourceEvent() {
        repository.saveAndFlush(
                row("submission:1", "BFS_GRID_TRAVERSAL",
                        Instant.parse("2026-09-01T10:00:00Z"), new BigDecimal("0.9000")));

        // 재시도로 같은 제출이 다시 처리되면 EMA 가 두 번 적용되고 confidence 도
        // 두 번 오른다 - 재시도가 사용자 점수를 바꾼다(ADR-0009).
        assertThatThrownBy(() -> repository.saveAndFlush(
                row("submission:1", "BFS_GRID_TRAVERSAL",
                        Instant.parse("2026-09-01T10:00:00Z"), new BigDecimal("0.3000"))))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("같은 제출이라도 Skill 이 다르면 별개다")
    void sameSubmissionDifferentSkillsAreDistinct() {
        repository.saveAndFlush(
                row("submission:1", "BFS_GRID_TRAVERSAL",
                        Instant.parse("2026-09-01T10:00:00Z"), new BigDecimal("0.9000")));
        repository.saveAndFlush(
                row("submission:1", "GRID_BOUNDARY_CHECK",
                        Instant.parse("2026-09-01T10:00:00Z"), new BigDecimal("0.9000")));

        assertThat(repository
                .findByUserIdAndSkillCodeOrderByOccurredAtAscIdAsc(userId, "BFS_GRID_TRAVERSAL"))
                .hasSize(1);
        assertThat(repository
                .findByUserIdAndSkillCodeOrderByOccurredAtAscIdAsc(userId, "GRID_BOUNDARY_CHECK"))
                .hasSize(1);
    }

    @Test
    @DisplayName("관측값이 하나도 없는 Evidence 는 DB 가 거부한다")
    void rejectsEvidenceWithoutObservation() {
        // 그런 Evidence 는 mastery 를 바꾸지 않으면서 confidence 만 올린다.
        // 측정한 게 없는데 측정 신뢰도가 오르는 상태다.
        SkillEvidenceRow empty = new SkillEvidenceRow(
                "submission:empty", "ev_empty", userId, "BFS_GRID_TRAVERSAL",
                "PROBLEM_SUBMISSION", Instant.parse("2026-09-01T10:00:00Z"),
                new BigDecimal("1.0000"), new BigDecimal("1.0000"),
                null, null, null, null, null, null,
                "{\"judgeStatus\":\"ACCEPTED\"}");

        assertThatThrownBy(() -> repository.saveAndFlush(empty))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("시간대가 다른 값도 실제 시각 순으로 읽힌다")
    void ordersByActualInstantAcrossTimezones() {
        // A 는 UTC 01:00, B 는 UTC 01:30. 문자열로 비교하면 B 가 앞선다.
        repository.saveAndFlush(row("submission:B", "BFS_GRID_TRAVERSAL",
                Instant.parse("2026-09-05T01:30:00Z"), new BigDecimal("0.3000")));
        repository.saveAndFlush(row("submission:A", "BFS_GRID_TRAVERSAL",
                java.time.OffsetDateTime.parse("2026-09-05T10:00:00+09:00").toInstant(),
                new BigDecimal("0.9000")));

        List<SkillEvidenceRow> found = repository
                .findByUserIdAndSkillCodeOrderByOccurredAtAscIdAsc(userId, "BFS_GRID_TRAVERSAL");

        assertThat(found).extracting(SkillEvidenceRow::sourceEventId)
                .as("EMA 는 순서 계산이므로 실제 시각 순이어야 한다")
                .containsExactly("submission:A", "submission:B");
    }

    @Test
    @DisplayName("범위를 벗어난 관측값은 DB 가 거부한다")
    void rejectsOutOfRangeObservation() {
        // skill_evidence 는 append-only 정본이다. user_skills 는 캐시라 다시 계산하면
        // 되지만 이쪽은 잘못 저장되면 지울 수 없고, 그 값으로 접은 mastery 가 계속 나온다.
        SkillEvidenceRow tooBig = new SkillEvidenceRow(
                "submission:range", "ev_range", userId, "BFS_GRID_TRAVERSAL",
                "PROBLEM_SUBMISSION", Instant.parse("2026-09-01T10:00:00Z"),
                new BigDecimal("1.0000"), new BigDecimal("1.0000"),
                null, null, new BigDecimal("3.5000"), null, null, null,
                "{}");
        assertThatThrownBy(() -> repository.saveAndFlush(tooBig)).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("음수 weight 와 범위 밖 sourceConfidence 를 DB 가 거부한다")
    void rejectsOutOfRangeWeightAndConfidence() {
        SkillEvidenceRow negativeWeight = new SkillEvidenceRow(
                "submission:w", "ev_w", userId, "BFS_GRID_TRAVERSAL",
                "PROBLEM_SUBMISSION", Instant.parse("2026-09-01T10:00:00Z"),
                new BigDecimal("-5.0000"), new BigDecimal("1.0000"),
                null, null, new BigDecimal("0.9000"), null, null, null, "{}");
        assertThatThrownBy(() -> repository.saveAndFlush(negativeWeight))
                .isInstanceOf(Exception.class);

        SkillEvidenceRow badConfidence = new SkillEvidenceRow(
                "submission:c", "ev_c", userId, "BFS_GRID_TRAVERSAL",
                "PROBLEM_SUBMISSION", Instant.parse("2026-09-01T10:00:00Z"),
                new BigDecimal("1.0000"), new BigDecimal("-1.0000"),
                null, null, new BigDecimal("0.9000"), null, null, null, "{}");
        assertThatThrownBy(() -> repository.saveAndFlush(badConfidence))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("mastery 가 null 이어도 LOCKED / READY 는 저장된다")
    void allowsPrerequisiteDrivenStatusesWithoutMastery() {
        // LOCKED 와 READY 는 Evidence 가 아니라 선수 관계에서 나오는 상태다(ADR-0009).
        // 신규 사용자의 잠긴 Skill 은 mastery 가 null 인 채로 LOCKED 다.
        for (String status : List.of("UNASSESSED", "LOCKED", "READY")) {
            entityManager.createNativeQuery(
                    "INSERT INTO user_skills (user_id, skill_code, status) "
                            + "VALUES (:uid, :code, :status)")
                    .setParameter("uid", userId)
                    .setParameter("code", "SKILL_" + status)
                    .setParameter("status", status)
                    .executeUpdate();
        }
        entityManager.flush();

        Number stored = (Number) entityManager
                .createNativeQuery("SELECT count(*) FROM user_skills WHERE user_id = :uid")
                .setParameter("uid", userId)
                .getSingleResult();
        assertThat(stored.intValue()).isEqualTo(3);
    }

    @Test
    @DisplayName("mastery 가 null 인데 학습 중 상태이면 DB 가 거부한다")
    void rejectsNullMasteryWithLearningStatus() {
        assertThatThrownBy(() -> {
            entityManager.createNativeQuery(
                    "INSERT INTO user_skills (user_id, skill_code, status) "
                            + "VALUES (:uid, 'SKILL_X', 'PRACTICING')")
                    .setParameter("uid", userId)
                    .executeUpdate();
            entityManager.flush();
        }).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("알 수 없는 evidence_type 은 DB 가 거부한다")
    void rejectsUnknownEvidenceType() {
        SkillEvidenceRow bad = new SkillEvidenceRow(
                "submission:bad", "ev_bad", userId, "BFS_GRID_TRAVERSAL",
                "PROBLEM_SUBMISSIONS", Instant.parse("2026-09-01T10:00:00Z"),
                new BigDecimal("1.0000"), new BigDecimal("1.0000"),
                null, null, new BigDecimal("0.9000"), null, null, null,
                "{}");

        assertThatThrownBy(() -> repository.saveAndFlush(bad)).isInstanceOf(Exception.class);
    }
}
