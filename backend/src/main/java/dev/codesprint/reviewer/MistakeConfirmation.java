package dev.codesprint.reviewer;

/**
 * Reviewer 의 주장을 어디까지 인정할지 정한다. 정본: Addendum §19, §21 / ADR-0014.
 *
 * <p>순수 함수다. 같은 입력에는 같은 출력이 나오고, LLM 을 부르지 않는다.
 *
 * <p><b>Reviewer 는 자기 확신도를 스스로 매긴다.</b> 그래서 confidence 만으로 확정하면
 * 순환이다 - 확신에 찬 오분류를 막을 방법이 없다.
 *
 * <h2>왜 "실패한 case 를 인용했는가" 는 근거가 아닌가</h2>
 *
 * <p>처음에는 §21-A 를 "Reviewer 가 Judge 의 실패 case 를 인용했는가" 로 구현했다.
 * <b>그것은 근거가 아니다.</b> Reviewer 요청에 그 case 번호가 이미 들어 있으므로,
 * 모델은 받은 값을 그대로 돌려주기만 하면 된다.
 *
 * <pre>
 *   Judge      case 4 실패
 *   요청       failedCaseId = 4          &lt;- 모델에게 알려준다
 *   Reviewer   failedCaseRefs = [4]      &lt;- 그대로 되돌려준다
 *   시스템     "번호가 일치한다" -&gt; 확정  &lt;- 아무것도 확인하지 않았다
 * </pre>
 *
 * <p>case 4 가 실제로 그 Mistake 를 보여주는 case 인지는 <b>아무도 확인하지 않았다.</b>
 * 그래서 그 검사는 confidence 만 보는 것과 사실상 같았다.
 *
 * <p>지금은 그런 확인을 할 데이터가 없다 - {@code cases.json} 에 case 의 성격을
 * 나타내는, 기계가 읽을 수 있는 태그가 없다. 그때까지 <b>§21-A 로는 확정하지 않는다.</b>
 * 확정은 §21-B(실제 재발)로만 일어난다.
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
     * @param corroborated <b>Reviewer 와 독립적인</b> 근거가 그 Mistake 를 뒷받침하는가.
     *     결정론적 Rule 탐지(§21-C)나 실패 case 의 성격 태그 대조가 여기 들어간다.
     *     <b>Reviewer 자신의 출력에서 나온 값은 여기 들어올 수 없다</b> - 요청으로
     *     알려준 것을 되돌려받는 것은 확인이 아니다.
     *     <p>지금은 그런 신호가 하나도 없으므로 호출자가 언제나 false 를 넘긴다.
     *     인자를 남겨 두는 이유는 조건 A 를 지우지 않기 위해서다 - 데이터가 생기면
     *     여기에 꽂는다.
     * @param recentDetections 같은 Mistake 가 <b>최근 {@value #RECENT_WINDOW}문제</b>에서
     *     몇 번 탐지됐는가. 이번 것을 포함한다.
     */
    public static Verdict decide(double confidence, boolean corroborated,
            int recentDetections) {

        if (confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence 는 0~1 이어야 한다: " + confidence);
        }

        // §21-A. 확신이 높고, 독립적인 근거가 같은 것을 가리킨다.
        if (confidence >= PROBABLE_CEILING && corroborated) {
            return new Verdict(MistakeStatus.CONFIRMED,
                    "confidence " + confidence + " >= 0.90 이고 독립적인 근거가 뒷받침한다");
        }

        // §21-B. 확신이 조금 낮아도 같은 실수가 실제로 반복됐다.
        //
        // 재발은 Reviewer 의 주장이 아니라 여러 제출에 걸친 관측이다. 그래서 이쪽은
        // 지금도 쓸 수 있다 - 한 번의 분석이 스스로를 증명하는 구조가 아니다.
        if (confidence >= POSSIBLE_CEILING && recentDetections >= RECENT_REQUIRED) {
            return new Verdict(MistakeStatus.CONFIRMED,
                    "confidence " + confidence + " >= 0.80 이고 최근 " + RECENT_WINDOW
                            + "문제에서 " + recentDetections + "회 탐지됐다");
        }

        // 확신은 높지만 뒷받침이 없다. **확정하지 않는다.**
        // Judge Evidence 가 LLM 보다 우선한다(Addendum §20).
        if (confidence >= PROBABLE_CEILING) {
            return new Verdict(MistakeStatus.PROBABLE,
                    "confidence 는 높지만 Reviewer 밖의 근거가 없다 - 재발을 기다린다");
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
