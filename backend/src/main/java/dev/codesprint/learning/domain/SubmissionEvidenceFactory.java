package dev.codesprint.learning.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

/**
 * 제출 하나를 Evidence 로 옮긴다. 정본: Addendum PART I 의 11~16.
 *
 * <p>여기 있는 표는 전부 <b>정본 문서에서 온 상수</b>다. 임의로 고르지 않았고, 고칠
 * 때는 Addendum 과 Python oracle({@code learning/evidence.py})을 함께 고친 뒤
 * {@code python tools/gen_evidence_golden.py --write} 로 golden 을 갱신한다.
 * 한쪽만 고치면 CI 가 막는다(ADR-0010).
 *
 * <p>LLM 을 부르지 않는다. Judge 결과와 학습 이벤트만 본다(ADR-0001).
 */
public final class SubmissionEvidenceFactory {

    private SubmissionEvidenceFactory() {
    }

    /**
     * Addendum §11. 힌트 단계별 AC 의 (implementation, independent).
     *
     * <p>같은 AC 라도 힌트를 몇 단계까지 썼는지에 따라 독립 풀이 점수가 크게 갈린다.
     * 6 은 "전체 풀이를 본 뒤 AC" 이며 Mastered 판정 근거로 쓸 수 없다(§11.7).
     */
    private static final double[][] AC_BY_HINT = {
            {0.90, 0.95},
            {0.88, 0.78},
            {0.84, 0.68},
            {0.80, 0.55},
            {0.74, 0.42},
            {0.65, 0.25},
            {0.55, 0.10},
    };

    /** Addendum §22. 이만큼 이상 힌트를 쓴 제출은 "최근 독립 풀이" 로 세지 않는다. */
    public static final int INDEPENDENT_HINT_CEILING = 4;

    /** Addendum §13. 풀이 시간 비율 -> speed. 정답일 때만 관측한다. */
    private static final double[][] SPEED_BY_RATIO = {
            {0.70, 1.00}, {1.00, 0.90}, {1.25, 0.75}, {1.50, 0.60}, {2.00, 0.40},
    };

    private static final double SPEED_FLOOR = 0.20;

    /** Addendum §15. 유형을 미리 알려주지 않은 모드에서만 recognition 을 관측한다. */
    private static final Map<String, Double> RECOGNITION_BY_VERDICT = Map.of(
            "CORRECT", 0.90, "PARTIAL", 0.55, "WRONG", 0.20);

    private static final Set<String> RECOGNITION_MODES = Set.of("EXAM", "MIXED", "DIAGNOSTIC");

    /**
     * 제출을 Evidence 로 옮길 때 필요한 것 전부.
     *
     * @param skillWeight 이 문제 안에서 그 Skill 이 차지하는 비중. SECONDARY Skill 은
     *     작고, 그만큼 confidence 도 덜 오른다.
     * @param mode 유형을 알려주는 모드인가. recognition 관측 여부를 가른다.
     * @param tleCause TLE 의 원인. 복잡도가 틀렸으면 알고리즘 선택도 흔들린 것이다.
     */
    public record Submission(
            String sourceEventId,
            String skillCode,
            double skillWeight,
            JudgeStatus judgeStatus,
            int hintLevel,
            boolean solutionViewed,
            String occurredAt,
            EvidenceType evidenceType,
            Double solveSeconds,
            Integer expectedSolveSeconds,
            String algorithmSelection,
            String mode,
            String problemCode,
            String tleCause) {
    }

    /**
     * (원천 이벤트, Skill) 에서 결정론적으로 만든다.
     *
     * <p>무작위 id 를 쓰면 같은 Evidence 를 두 번 만들 때 서로 다른 id 가 붙어 재계산
     * 결과가 입력에 따라 달라진다(ADR-0009 의 결정론).
     */
    public static String evidenceId(String sourceEventId, String skillCode) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((sourceEventId + "|" + skillCode).getBytes(StandardCharsets.UTF_8));
            return "ev_" + HexFormat.of().formatHex(digest).substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 이 없다", e);
        }
    }

    /** Addendum §13. 느릴수록 낮다. */
    public static double speedScore(double actualSeconds, double expectedSeconds) {
        if (expectedSeconds <= 0) {
            return SPEED_FLOOR;
        }
        double ratio = actualSeconds / expectedSeconds;
        for (double[] row : SPEED_BY_RATIO) {
            if (ratio <= row[0]) {
                return row[1];
            }
        }
        return SPEED_FLOOR;
    }

    /**
     * 이 제출을 "최근 독립 풀이" 로 셀 것인가 (Addendum §22).
     *
     * <p><b>성공과 실패에 같은 기준을 적용한다.</b> 힌트를 5단계까지 보고 틀린 것은
     * 독립 풀이에 실패한 것이 아니라 애초에 독립 풀이가 아니다.
     */
    public static boolean isIndependentAttempt(JudgeStatus status, int hintLevel,
            boolean solutionViewed) {
        if (!status.producesEvidence()) {
            return false;
        }
        return !solutionViewed && hintLevel < INDEPENDENT_HINT_CEILING;
    }

    /**
     * 제출 하나를 Evidence 로 옮긴다. 관측하지 못한 차원은 비워 둔다 - 0 으로 채우지
     * 않는다(Addendum §4).
     *
     * @return {@code null} 이면 <b>이 Skill 에는 아무것도 기록하지 않는다.</b>
     *     COMPILE_ERROR 가 그렇다 - 문법 오류를 알고리즘 Skill 의 penalty 로 쓰면
     *     안 된다(Addendum §12). SYSTEM_ERROR 는 우리 잘못이다.
     *     "관측값이 전부 null 인 Evidence" 와 다르다. 그런 Evidence 는 mastery 를
     *     바꾸지 않으면서 confidence 만 올린다.
     */
    public static Evidence fromSubmission(Submission submission) {
        // 판정이 끝나지 않은 제출로는 Evidence 를 만들 수 없다. QUEUED 를 그대로
        // 넘기면 관측값 없는 Evidence 가 되어 confidence 만 오른다.
        if (!submission.judgeStatus().isTerminal()) {
            throw new IllegalArgumentException(
                    "판정이 끝나지 않은 제출로 Evidence 를 만들 수 없다: "
                            + submission.judgeStatus());
        }

        // 형식과 시간대를 여기서 확인한다. 저장 뒤에 알면 늦다 - append-only 다.
        Evidence.parseInstant(submission.occurredAt());

        if (!submission.judgeStatus().producesEvidence()) {
            return null;
        }

        Map<Dimension, Double> observed = new EnumMap<>(Dimension.class);
        switch (submission.judgeStatus()) {
            case ACCEPTED -> {
                int level = submission.solutionViewed()
                        ? 6
                        : Math.min(Math.max(submission.hintLevel(), 0), 5);
                observed.put(Dimension.IMPLEMENTATION, AC_BY_HINT[level][0]);
                observed.put(Dimension.INDEPENDENT, AC_BY_HINT[level][1]);
                // 정답일 때만 speed 를 갱신한다(Addendum §13). 기대 시간을 모르면
                // 관측하지 않는다 - 0 으로 채우면 "느렸다" 가 된다.
                Integer expected = submission.expectedSolveSeconds();
                if (submission.solveSeconds() != null && expected != null && expected != 0) {
                    observed.put(Dimension.SPEED,
                            speedScore(submission.solveSeconds(), expected));
                }
            }
            case TIME_LIMIT -> {
                observed.put(Dimension.IMPLEMENTATION, 0.45);
                // Addendum §12. 복잡도 자체가 틀렸으면 알고리즘 선택도 흔들린 것이고,
                // 상수 최적화 문제면 구현만 본다.
                if ("COMPLEXITY".equals(submission.tleCause())) {
                    observed.put(Dimension.RECOGNITION, 0.35);
                }
            }
            case WRONG_ANSWER, OUTPUT_LIMIT -> {
                observed.put(Dimension.IMPLEMENTATION, 0.30);
                observed.put(Dimension.INDEPENDENT, 0.25);
            }
            // Addendum §12. 개념 이해도를 바로 낮추지 않는다. 구현 쪽만 본다.
            case RUNTIME_ERROR, MEMORY_LIMIT -> observed.put(Dimension.IMPLEMENTATION, 0.30);
            default -> throw new IllegalStateException(
                    "Evidence 를 만드는 판정인데 분기가 없다: " + submission.judgeStatus());
        }

        // Recognition 은 유형을 미리 알려주지 않은 모드에서만 관측한다(Addendum §15).
        if (submission.algorithmSelection() != null
                && RECOGNITION_MODES.contains(submission.mode())) {
            Double value = RECOGNITION_BY_VERDICT.get(submission.algorithmSelection());
            if (value != null) {
                observed.put(Dimension.RECOGNITION, value);
            }
        }

        return new Evidence(
                submission.sourceEventId(),
                submission.skillCode(),
                submission.evidenceType(),
                Evidence.parseInstant(submission.occurredAt()),
                // 문제 안에서의 Skill 비중이 곱해진다.
                submission.evidenceType().confidenceWeight() * submission.skillWeight(),
                observed,
                1.0,  // Judge 결과는 결정론적이다
                new EvidenceContext(
                        submission.problemCode(),
                        submission.judgeStatus().name(),
                        submission.hintLevel(),
                        submission.solutionViewed(),
                        isIndependentAttempt(submission.judgeStatus(), submission.hintLevel(),
                                submission.solutionViewed()),
                        null,
                        null,
                        null));
    }
}
