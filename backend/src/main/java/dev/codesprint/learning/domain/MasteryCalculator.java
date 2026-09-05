package dev.codesprint.learning.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * mastery / confidence / status 를 계산한다. 정본: Addendum PART I.
 *
 * <p><b>LLM 은 여기 관여하지 않는다</b>(ADR-0001). 입력은 Evidence 목록뿐이고,
 * 같은 입력에는 같은 출력이 나온다.
 *
 * <p>이 클래스는 Python oracle(learning/mastery.py)의 이식본이다. 두 구현이 같은
 * golden fixture(tests/golden/)를 읽고 같은 값을 내야 하며, CI 가 그것을 대조한다 -
 * 근거: docs/adr/0010-java-implementation-is-checked-against-the-python-oracle.md
 *
 * <p>상태를 갖지 않는다. Evidence 가 정본이므로 계산은 언제든 처음부터 다시 접을 수
 * 있어야 한다.
 */
public final class MasteryCalculator {

    /** Addendum §18. MVP 는 단순식을 쓴다. */
    public static final double CONFIDENCE_DIVISOR = 8.0;

    /** Addendum §22. MASTERED 판정 문턱. */
    public static final double MASTERY_THRESHOLD = 0.80;
    public static final double CONFIDENCE_THRESHOLD = 0.60;
    public static final int RECENT_INDEPENDENT_WINDOW = 3;
    public static final int RECENT_INDEPENDENT_REQUIRED = 2;

    /**
     * 상태 구간. Addendum 이 MASTERED 와 WEAKENED 만 정의하므로 그 사이는 여기서 정한다.
     * 근거가 약한 값이며 실제 사용자 분포를 보고 조정한다.
     */
    public static final double PRACTICING_THRESHOLD = 0.50;

    /**
     * Python 은 round(x, 4) 를 쓴다. Java 의 기본 반올림과 붙으면 마지막 자리가
     * 갈릴 수 있으므로 HALF_EVEN 으로 맞춘다 - Python 의 round 도 banker's rounding 이다.
     */
    private static final int SCALE = 4;

    private MasteryCalculator() {
    }

    /**
     * Evidence 목록 전체로부터 상태를 다시 계산한다.
     *
     * @throws IllegalArgumentException 다른 Skill 의 Evidence 가 섞였거나, 같은 원천에서
     *     내용이 다른 Evidence 가 왔을 때
     */
    public static SkillState recompute(List<Evidence> evidences, String skillCode) {
        List<Evidence> ordered = dedupeAndOrder(evidences, skillCode);

        Map<Dimension, Double> scores = new EnumMap<>(Dimension.class);
        double totalWeight = 0.0;
        List<Boolean> independentLog = new ArrayList<>();
        boolean reviewSucceeded = false;
        SkillStatus status = SkillStatus.UNASSESSED;

        for (Evidence evidence : ordered) {
            scores = apply(scores, evidence);
            totalWeight += evidence.weight();

            EvidenceContext context = evidence.context();
            if (context.isIndependentAttempt()) {
                independentLog.add(context.isAccepted() || context.isReviewSuccess());
            }
            if (evidence.evidenceType() == EvidenceType.REVIEW_RESULT) {
                reviewSucceeded = context.isReviewSuccess();
            }

            // 상태는 매 Evidence 마다 전이한다. WEAKENED 판정이 "직전 상태" 를 보기
            // 때문에 마지막에 한 번만 계산하면 경로를 잃는다.
            List<Boolean> recent = new ArrayList<>(independentLog);
            java.util.Collections.reverse(recent);
            status = determineStatus(
                    calculateMastery(scores),
                    calculateConfidence(totalWeight),
                    recent,
                    reviewSucceeded,
                    status);
        }

        return new SkillState(
                skillCode,
                scores,
                calculateMastery(scores),
                calculateConfidence(totalWeight),
                ordered.size(),
                status);
    }

    /**
     * 같은 원천 이벤트에서 온 Evidence 는 한 번만 센다.
     *
     * <p>Judge Worker 가 재시도로 같은 제출을 두 번 처리하면 같은 Evidence 가 두 번
     * 들어온다. 그대로 접으면 EMA 가 두 번 적용되고 confidence 도 두 번 오른다.
     *
     * <p><b>다만 내용이 다르면 조용히 버리지 않는다.</b> 같은 원천에서 서로 다른
     * 관측이 나왔다면 재시도가 아니라 데이터가 깨진 것이다. 하나를 골라 버리면 어느
     * 것이 남는지가 입력 순서에 달리고, "같은 Evidence 집합이면 같은 mastery" 가
     * 무너진다.
     */
    private static List<Evidence> dedupeAndOrder(List<Evidence> evidences, String skillCode) {
        List<Evidence> sorted = new ArrayList<>(evidences);
        // 문자열이 아니라 실제 시각으로 정렬한다. 같은 시각이면 dedupeKey 로 고정한다.
        sorted.sort(Comparator.comparing(Evidence::occurredAt).thenComparing(Evidence::dedupeKey));

        Map<String, Evidence> seen = new LinkedHashMap<>();
        List<Evidence> ordered = new ArrayList<>();
        for (Evidence evidence : sorted) {
            if (!evidence.skillCode().equals(skillCode)) {
                throw new IllegalArgumentException(
                        "다른 Skill 의 Evidence 가 섞였다: " + evidence.skillCode());
            }
            Evidence previous = seen.get(evidence.dedupeKey());
            if (previous != null) {
                if (!previous.sameContentAs(evidence)) {
                    throw new IllegalArgumentException(
                            "같은 원천(" + evidence.sourceEventId() + ", " + evidence.skillCode()
                                    + ")에서 서로 다른 Evidence 가 왔다. "
                                    + "재시도가 아니라 데이터가 깨진 것이다.");
                }
                continue;
            }
            seen.put(evidence.dedupeKey(), evidence);
            ordered.add(evidence);
        }
        return ordered;
    }

    /**
     * Evidence 하나를 EMA 로 반영한다. 정본: Addendum §9~10.
     *
     * <p>첫 관측은 EMA 를 거치지 않고 그대로 들어간다. 이전 값이 없으므로 섞을 것이
     * 없다. 대신 confidence 가 낮게 시작해 "값은 있지만 아직 못 믿는다" 를 표현한다.
     */
    static Map<Dimension, Double> apply(Map<Dimension, Double> scores, Evidence evidence) {
        double alpha = evidence.evidenceType().alpha();
        Map<Dimension, Double> updated = new EnumMap<>(scores);
        for (Dimension dimension : Dimension.values()) {
            Double observed = evidence.observed(dimension);
            if (observed == null) {
                continue; // 관측하지 못한 차원은 건드리지 않는다
            }
            Double current = updated.get(dimension);
            double next = (current == null)
                    ? observed
                    : current * (1 - alpha) + observed * alpha;
            updated.put(dimension, round(next));
        }
        return updated;
    }

    /**
     * 평가된 차원만으로 계산하고 가중치를 재정규화한다. 정본: Addendum §6.
     *
     * <p>평가되지 않은 차원을 0 으로 두면 안 된다. 그러면 "아직 안 봤다" 가 "못한다" 로
     * 둔갑하고, 초반에 mastery 가 부당하게 낮게 나온다. AC 하나만 있을 때 0.4625 가
     * 아니라 0.925 다.
     *
     * @return 아무 차원도 평가되지 않았으면 null - 0.0 과 다르다(Addendum §4)
     */
    public static Double calculateMastery(Map<Dimension, Double> scores) {
        double weighted = 0.0;
        double totalWeight = 0.0;
        for (Map.Entry<Dimension, Double> entry : scores.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            weighted += entry.getValue() * entry.getKey().weight();
            totalWeight += entry.getKey().weight();
        }
        if (totalWeight == 0.0) {
            return null;
        }
        return round(weighted / totalWeight);
    }

    /** 정본: Addendum §18. mastery 와 별개다. */
    public static double calculateConfidence(double totalWeight) {
        return round(Math.min(1.0, totalWeight / CONFIDENCE_DIVISOR));
    }

    /**
     * 상태를 정한다. 정본: Addendum §22~23.
     *
     * @param recentIndependent 최근 독립 풀이 결과를 최신순으로. 힌트를 4단계 이상 쓴
     *     제출과 정답을 본 제출은 성공이든 실패든 여기 들어오지 않는다.
     */
    public static SkillStatus determineStatus(
            Double mastery,
            double confidence,
            List<Boolean> recentIndependent,
            boolean reviewSucceeded,
            SkillStatus previousStatus) {

        if (mastery == null) {
            return SkillStatus.UNASSESSED;
        }

        List<Boolean> window = recentIndependent.subList(
                0, Math.min(RECENT_INDEPENDENT_WINDOW, recentIndependent.size()));
        long failures = window.stream().filter(ok -> !ok).count();
        long successes = window.stream().filter(ok -> ok).count();

        // Addendum §23. MASTERED 에서 나가는 길은 열거돼 있다.
        // 그 밖의 이유로 점수가 조금 내려갔다고 강등하지 않는다 - 그러지 않으면
        // 힌트를 보며 푼 실패들이 EMA 로 점수를 끌어내려 조용히 PRACTICING 으로
        // 떨어지고, 독립 풀이를 시도한 적이 없는데도 복습 우선순위를 잃는다.
        //
        // 세 번째 조건(동일 핵심 Mistake 2회 반복)은 Reviewer 가 붙어야 판단할 수 있다.
        if (previousStatus == SkillStatus.MASTERED) {
            if (!reviewSucceeded || failures >= RECENT_INDEPENDENT_REQUIRED) {
                return SkillStatus.WEAKENED;
            }
            return SkillStatus.MASTERED;
        }

        // Addendum §22. 네 조건을 모두 만족해야 한다.
        if (mastery >= MASTERY_THRESHOLD
                && confidence >= CONFIDENCE_THRESHOLD
                && window.size() >= RECENT_INDEPENDENT_WINDOW
                && successes >= RECENT_INDEPENDENT_REQUIRED
                && reviewSucceeded) {
            return SkillStatus.MASTERED;
        }

        if (previousStatus == SkillStatus.WEAKENED && mastery < MASTERY_THRESHOLD) {
            // 아직 회복되지 않았다. PRACTICING 으로 되돌리면 복습 우선순위를 잃는다.
            return SkillStatus.WEAKENED;
        }

        return mastery >= PRACTICING_THRESHOLD ? SkillStatus.PRACTICING : SkillStatus.LEARNING;
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(SCALE, RoundingMode.HALF_EVEN).doubleValue();
    }
}
