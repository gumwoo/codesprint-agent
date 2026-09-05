package dev.codesprint.reviewer;

import dev.codesprint.learning.domain.JudgeStatus;
import dev.codesprint.learning.persistence.SubmissionRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * 오답 원인 분석을 호출하고, 검증하고, 어디까지 인정할지 정하고, 기록한다.
 * 정본: Addendum §19~21 / ADR-0004 / ADR-0014.
 *
 * <pre>
 *   실패 판정 -&gt; Reviewer 호출 -&gt; 검증 -&gt; 확정 판단 -&gt; 기록
 * </pre>
 *
 * <p><b>확정된 Mistake 만 Decision Engine 으로 넘어간다.</b> 그리고 확정됐다고 전부
 * 드릴로 가지도 않는다 - {@code auto_drill: true} 인 둘만 연결된다. Reviewer 정확도가
 * 실제 제출 데이터로 측정되기 전에 자동 액션을 넓히면 오분류가 곧바로 학습 경로
 * 오염이 된다.
 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    private final ReviewerPort reviewer;
    private final ReviewerOutputValidator validator;
    private final MistakeDetectionRepository detections;
    private final SubmissionRepository submissions;

    public ReviewService(ReviewerPort reviewer, ReviewerOutputValidator validator,
            MistakeDetectionRepository detections, SubmissionRepository submissions) {
        this.reviewer = reviewer;
        this.validator = validator;
        this.detections = detections;
        this.submissions = submissions;
    }

    /**
     * 분석 결과와 시스템의 판단.
     *
     * @param confirmedMistake 확정된 code, 또는 확정되지 않았으면 null.
     *     <b>Decision Engine 에 넘어가는 값이다.</b>
     */
    public record Review(
            String primaryMistake,
            List<String> secondaryMistakes,
            double confidence,
            MistakeStatus status,
            String explanation,
            String promptVersion,
            String confirmedMistake) {
    }

    /**
     * 이 판정에서 Reviewer 를 부르는가. 근거: ADR-0004.
     *
     * <p>{@code ACCEPTED} 에서는 설명할 실패가 없고, {@code COMPILE_ERROR} 는 Test Case 를
     * 하나도 실행하지 못해 근거로 들 case 가 없으며, {@code SYSTEM_ERROR} 는 우리 잘못이다.
     */
    public boolean shouldReview(JudgeStatus status) {
        return status.invokesReviewer();
    }

    /**
     * @return 비어 있으면 <b>기록할 분석이 없다</b>는 뜻이다 - Reviewer 가 없거나,
     *     분석에 실패했거나, 검증을 통과하지 못했다. 셋을 응답에서 구분하지 않는다.
     *     사용자가 할 수 있는 일이 같기 때문이다.
     */
    public Optional<Review> review(Long userId, Long submissionId, Long problemId,
            ReviewerPort.Request request) {

        Optional<ReviewerOutput> analysed;
        try {
            analysed = reviewer.review(request);
        } catch (RuntimeException e) {
            // 분석에 실패해도 제출 처리는 계속된다. Reviewer 는 판정도 점수도
            // 만들지 않으므로, 없으면 없는 대로 나머지가 돌아야 한다.
            log.error("Reviewer 호출이 실패했다: submission {}", submissionId, e);
            return Optional.empty();
        }
        if (analysed.isEmpty()) {
            return Optional.empty();
        }
        ReviewerOutput output = analysed.get();

        ReviewerOutputValidator.Result validation = validator.validate(output);
        if (!validation.isUsable()) {
            // 통째로 버린다. 부분적으로 맞는 분석을 살리려다 잘못된 절반이
            // 학습 경로에 들어간다(ADR-0014).
            log.warn("Reviewer 출력을 버린다: submission {} - {}",
                    submissionId, validation.describe());
            return Optional.empty();
        }

        int recent = recentDetectionCount(userId, problemId, output.primaryMistake());

        // §21-A 의 독립적인 근거는 아직 없다. Reviewer 가 실패 case 를 인용했다는
        // 사실은 근거가 아니다 - 그 번호를 요청으로 알려줬으므로 되돌려주기만 하면
        // 된다(ADR-0014). case 성격 태그나 결정론적 Rule 이 생기면 여기에 꽂는다.
        boolean corroborated = false;

        MistakeConfirmation.Verdict verdict = MistakeConfirmation.decide(
                output.confidence(), corroborated, recent);

        record(userId, submissionId, output, verdict);

        return Optional.of(new Review(
                output.primaryMistake(),
                output.secondaryMistakes(),
                output.confidence(),
                verdict.status(),
                output.explanation(),
                reviewer.promptVersion(),
                verdict.isConfirmed() ? output.primaryMistake() : null));
    }

    /** 이 제출에 남은 탐지들. 조회 API 가 쓴다. */
    public List<MistakeDetectionRow> detectionsOf(Long submissionId) {
        return detections.findBySubmissionId(submissionId);
    }

    /**
     * 같은 Mistake 가 <b>최근 {@value MistakeConfirmation#RECENT_WINDOW}문제</b>에서 몇 번
     * 나왔는가. 이번 것을 포함한다.
     *
     * <p><b>창을 먼저 정하고 그 안에서 센다.</b> 탐지 기록에서 창을 뽑으면 실수가
     * 없었던 문제가 창에 들어오지 않아, 아주 오래된 실수가 현재 실수와 묶인다 -
     * {@code SubmissionRepository.recentProblemIds} 에 그 예를 적어 뒀다.
     */
    private int recentDetectionCount(Long userId, Long problemId, String mistakeCode) {
        // 이번 제출의 문제는 아직 탐지가 저장되기 전이므로 따로 센다.
        List<Long> window = new ArrayList<>(submissions.recentProblemIds(
                userId, PageRequest.of(0, MistakeConfirmation.RECENT_WINDOW)));
        window.remove(problemId);

        // 이번 문제가 창의 한 자리를 차지한다.
        List<Long> others = window.subList(0,
                Math.min(window.size(), MistakeConfirmation.RECENT_WINDOW - 1));

        long previous = others.isEmpty()
                ? 0
                : detections.countProblemsWithPrimary(userId, mistakeCode, others);
        return (int) previous + 1;
    }

    private void record(Long userId, Long submissionId, ReviewerOutput output,
            MistakeConfirmation.Verdict verdict) {

        List<MistakeDetectionRow> rows = new ArrayList<>();
        rows.add(new MistakeDetectionRow(submissionId, userId, output.primaryMistake(),
                "PRIMARY", BigDecimal.valueOf(output.confidence()),
                verdict.status().name(), verdict.reason(), reviewer.promptVersion()));

        for (String code : output.secondaryMistakes()) {
            // secondary 는 확정 대상이 아니다. 기록은 남기되 상태는 참고 로그다 -
            // 곁다리로 언급된 것이 재발 계수에도, 자동 드릴에도 들어가지 않는다.
            rows.add(new MistakeDetectionRow(submissionId, userId, code, "SECONDARY",
                    BigDecimal.valueOf(output.confidence()), MistakeStatus.LOGGED_ONLY.name(),
                    "secondary 는 확정 대상이 아니다", reviewer.promptVersion()));
        }
        detections.saveAll(rows);
    }
}
