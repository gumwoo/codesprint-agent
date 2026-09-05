package dev.codesprint.reviewer;

/**
 * Reviewer 의 주장을 어디까지 인정할지 정한다. 정본: Addendum §19, §21 / ADR-0014.
 *
 * <p>순수 함수다. 같은 입력에는 같은 출력이 나오고, LLM 을 부르지 않는다.
 *
 * <p><b>Reviewer 는 자기 확신도를 스스로 매긴다.</b> 그래서 confidence 만으로 확정하면
 * 순환이다 - 확신에 찬 오분류를 막을 방법이 없다. Judge 가 실제로 실패시킨 case 를
 * 인용했는가(§21-A), 또는 같은 실수가 실제로 재발했는가(§21-B)를 함께 본다.
 */
public final class MistakeConfirmation {

    private MistakeConfirmation() {
    }

    /** Addendum §19 의 구간. 값을 임의로 고치지 않는다. */
    public static final double LOGGED_ONLY_CEILING = 0.60;
    public static final double POSSIBLE_CEILING = 0.80;
    public static final double PROBABLE_CEILING = 0.90;

    /** §21-B. 같은 Mistake 가 최근 이만큼의 문제 안에서 이만큼 나오면 재발로 본다. */
    public static final int RECENT_WINDOW = 3;
    public static final int RECENT_REQUIRED = 2;

    /**
     * @param status 시스템이 인정한 수준
     * @param reason 왜 그 수준인가. 사용자 문구가 아니라 감사 로그다 - 사용자가 왜
     *     이 드릴을 받았는지 나중에 추적해야 한다.
     */
    public record Verdict(MistakeStatus status, String reason) {

        public boolean isConfirmed() {
            return status.isConfirmed();
        }
    }

    /**
     * @param confidence Reviewer 가 낸 값. 시스템이 고치지 않는다.
     * @param citedJudgeFailure Reviewer 가 <b>Judge 가 실제로 실패시킨 case</b> 를
     *     근거로 들었는가. Judge 는 첫 실패에서 멈추므로(ADR-0005) 그 case 는 하나로
     *     특정된다. 인용하지 않았다면 그 분석은 실제로 일어난 실패를 설명하고 있지 않다.
     * @param recentDetections 같은 Mistake 가 <b>최근 {@value #RECENT_WINDOW}문제</b>에서
     *     몇 번 탐지됐는가. 이번 것을 포함한다.
     */
    public static Verdict decide(double confidence, boolean citedJudgeFailure,
            int recentDetections) {

        if (confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence 는 0~1 이어야 한다: " + confidence);
        }

        // §21-A. 확신이 높고, 실제로 실패한 case 를 설명하고 있다.
        if (confidence >= PROBABLE_CEILING && citedJudgeFailure) {
            return new Verdict(MistakeStatus.CONFIRMED,
                    "confidence " + confidence + " >= 0.90 이고 Judge 가 실패시킨 case 를 인용했다");
        }

        // §21-B. 확신이 조금 낮아도 같은 실수가 실제로 반복됐다.
        //
        // 여기에는 case 인용을 요구하지 않는다. 재발 자체가 다른 종류의 근거이며,
        // 한 번의 분석이 아니라 여러 제출에 걸친 관측이기 때문이다.
        if (confidence >= POSSIBLE_CEILING && recentDetections >= RECENT_REQUIRED) {
            return new Verdict(MistakeStatus.CONFIRMED,
                    "confidence " + confidence + " >= 0.80 이고 최근 " + RECENT_WINDOW
                            + "문제에서 " + recentDetections + "회 탐지됐다");
        }

        // 확신은 높은데 근거가 어긋난 경우다. **확정하지 않는다.**
        // Judge Evidence 가 LLM 보다 우선한다(Addendum §20).
        if (confidence >= PROBABLE_CEILING) {
            return new Verdict(MistakeStatus.PROBABLE,
                    "confidence 는 높지만 Judge 가 실패시킨 case 를 인용하지 않았다");
        }
        if (confidence >= POSSIBLE_CEILING) {
            return new Verdict(MistakeStatus.PROBABLE,
                    "confidence " + confidence + " 는 0.80~0.90 이고 재발은 "
                            + recentDetections + "회다");
        }
        if (confidence >= LOGGED_ONLY_CEILING) {
            return new Verdict(MistakeStatus.POSSIBLE,
                    "confidence " + confidence + " 는 0.60~0.80 이다 - 자동 드릴은 하지 않는다");
        }
        return new Verdict(MistakeStatus.LOGGED_ONLY,
                "confidence " + confidence + " < 0.60 - 참고 로그로만 남긴다");
    }
}
