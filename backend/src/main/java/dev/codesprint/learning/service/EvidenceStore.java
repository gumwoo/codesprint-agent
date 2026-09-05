package dev.codesprint.learning.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.codesprint.learning.domain.Dimension;
import dev.codesprint.learning.domain.Evidence;
import dev.codesprint.learning.domain.EvidenceContext;
import dev.codesprint.learning.domain.EvidenceType;
import dev.codesprint.learning.domain.SubmissionEvidenceFactory;
import dev.codesprint.learning.persistence.SkillEvidenceRepository;
import dev.codesprint.learning.persistence.SkillEvidenceRow;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 도메인 Evidence 와 {@code skill_evidence} 행 사이를 옮긴다.
 *
 * <p><b>이 방향이 중요하다.</b> mastery 는 저장된 값이 아니라 여기서 읽어온 Evidence 로
 * 매번 다시 계산되는 파생값이다(ADR-0009). 그래서 행 -> 도메인 변환이 손실 없이
 * 되어야 한다 - 하나라도 빠지면 재계산이 다른 값을 낸다.
 */
@Component
public class EvidenceStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SkillEvidenceRepository repository;

    public EvidenceStore(SkillEvidenceRepository repository) {
        this.repository = repository;
    }

    /**
     * 이미 있으면 저장하지 않는다.
     *
     * <p>Judge Worker 재시도로 같은 제출이 두 번 처리될 수 있다. 그대로 쌓으면 EMA 가
     * 두 번 적용되고 confidence 도 두 번 오른다 - 재시도가 사용자 점수를 바꾼다.
     * DB 의 UNIQUE 제약이 마지막으로 막지만, 여기서 먼저 걸러 예외를 흐름으로
     * 쓰지 않는다.
     *
     * @return 실제로 저장했으면 true
     */
    public boolean saveIfAbsent(Long userId, Evidence evidence) {
        if (repository.existsByUserIdAndSourceEventIdAndSkillCode(
                userId, evidence.sourceEventId(), evidence.skillCode())) {
            return false;
        }
        repository.save(toRow(userId, evidence));
        return true;
    }

    /** 재계산용. <b>시간순</b>으로 읽는다 - EMA 는 순서 계산이다. */
    public List<Evidence> load(Long userId, String skillCode) {
        List<Evidence> evidences = new ArrayList<>();
        for (SkillEvidenceRow row
                : repository.findByUserIdAndSkillCodeOrderByOccurredAtAscIdAsc(userId, skillCode)) {
            evidences.add(toDomain(row));
        }
        return evidences;
    }

    /**
     * 지금까지 쌓인 개수. <b>제출을 반영하기 전에</b> 읽어 Decision Engine 에 넘긴다 -
     * 반영 뒤의 개수로는 "이 Skill 을 이미 배우고 있었는가" 를 알 수 없다.
     */
    public int countFor(Long userId, String skillCode) {
        return (int) repository.countByUserIdAndSkillCode(userId, skillCode);
    }

    private static SkillEvidenceRow toRow(Long userId, Evidence evidence) {
        return new SkillEvidenceRow(
                evidence.sourceEventId(),
                SubmissionEvidenceFactory.evidenceId(
                        evidence.sourceEventId(), evidence.skillCode()),
                userId,
                evidence.skillCode(),
                evidence.evidenceType().name(),
                evidence.occurredAt(),
                BigDecimal.valueOf(evidence.weight()),
                evidence.sourceConfidence() == null
                        ? null : BigDecimal.valueOf(evidence.sourceConfidence()),
                decimal(evidence.observed(Dimension.CONCEPT)),
                decimal(evidence.observed(Dimension.RECOGNITION)),
                decimal(evidence.observed(Dimension.IMPLEMENTATION)),
                decimal(evidence.observed(Dimension.INDEPENDENT)),
                decimal(evidence.observed(Dimension.RETENTION)),
                decimal(evidence.observed(Dimension.SPEED)),
                writeContext(evidence.context()));
    }

    private static Evidence toDomain(SkillEvidenceRow row) {
        Map<Dimension, Double> observed = new EnumMap<>(Dimension.class);
        put(observed, Dimension.CONCEPT, row.observedConcept());
        put(observed, Dimension.RECOGNITION, row.observedRecognition());
        put(observed, Dimension.IMPLEMENTATION, row.observedImplementation());
        put(observed, Dimension.INDEPENDENT, row.observedIndependent());
        put(observed, Dimension.RETENTION, row.observedRetention());
        put(observed, Dimension.SPEED, row.observedSpeed());

        return new Evidence(
                row.sourceEventId(),
                row.skillCode(),
                EvidenceType.valueOf(row.evidenceType()),
                row.occurredAt(),
                row.weight().doubleValue(),
                observed,
                row.sourceConfidence() == null ? null : row.sourceConfidence().doubleValue(),
                readContext(row.context()));
    }

    private static void put(Map<Dimension, Double> target, Dimension dimension,
            BigDecimal value) {
        // null 은 "관측하지 않았다" 다. 0 으로 채우면 "못한다" 가 된다(Addendum 4).
        if (value != null) {
            target.put(dimension, value.doubleValue());
        }
    }

    private static BigDecimal decimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private static String writeContext(EvidenceContext context) {
        ObjectNode node = MAPPER.createObjectNode();
        // 모든 키를 쓴다. 생략은 "기록하지 않았다", null 은 "해당 없다" 로 다르다.
        node.put("problemCode", context.problemCode());
        node.put("judgeStatus", context.judgeStatus());
        node.put("hintLevel", context.hintLevel());
        node.put("solutionViewed", context.solutionViewed());
        node.put("independentAttempt", context.independentAttempt());
        node.put("reviewSucceeded", context.reviewSucceeded());
        node.put("daysSinceLast", context.daysSinceLast());
        node.put("verdict", context.verdict());
        return node.toString();
    }

    private static EvidenceContext readContext(String json) {
        try {
            var node = MAPPER.readTree(json);
            return new EvidenceContext(
                    node.path("problemCode").isNull() ? null : node.path("problemCode").asText(null),
                    node.path("judgeStatus").isNull() ? null : node.path("judgeStatus").asText(null),
                    node.path("hintLevel").isNull() || node.path("hintLevel").isMissingNode()
                            ? null : node.path("hintLevel").asInt(),
                    node.path("solutionViewed").isNull()
                            || node.path("solutionViewed").isMissingNode()
                            ? null : node.path("solutionViewed").asBoolean(),
                    node.path("independentAttempt").isNull()
                            || node.path("independentAttempt").isMissingNode()
                            ? null : node.path("independentAttempt").asBoolean(),
                    node.path("reviewSucceeded").isNull()
                            || node.path("reviewSucceeded").isMissingNode()
                            ? null : node.path("reviewSucceeded").asBoolean(),
                    node.path("daysSinceLast").isNull() || node.path("daysSinceLast").isMissingNode()
                            ? null : node.path("daysSinceLast").asInt(),
                    node.path("verdict").isNull() ? null : node.path("verdict").asText(null));
        } catch (JsonProcessingException e) {
            // 정본을 읽지 못하면 mastery 를 계산할 수 없다. 조용히 빈 context 로
            // 넘기면 independentAttempt 가 사라져 MASTERED 판정이 달라진다.
            throw new IllegalStateException("Evidence 의 context 를 읽지 못했다: " + json, e);
        }
    }
}
