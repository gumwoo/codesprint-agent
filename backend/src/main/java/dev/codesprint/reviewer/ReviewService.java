package dev.codesprint.reviewer;

import dev.codesprint.learning.domain.JudgeStatus;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Map;
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

    public ReviewService(ReviewerPort reviewer, ReviewerOutputValidator validator,
            MistakeDetectionRepository detections) {
        this.reviewer = reviewer;
        this.validator = validator;
        this.detections = detections;
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
     * @param failedCaseId Judge 가 실패시킨 case. 확정 조건 A 의 근거 대조에 쓴다.
     * @return 비어 있으면 <b>기록할 분석이 없다</b>는 뜻이다 - Reviewer 가 없거나,
     *     분석에 실패했거나, 검증을 통과하지 못했다. 셋을 응답에서 구분하지 않는다.
     *     사용자가 할 수 있는 일이 같기 때문이다.
     */
    public Optional<Review> review(Long userId, Long submissionId, Long problemId,
            ReviewerPort.Request request, Integer failedCaseId) {

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
        MistakeConfirmation.Verdict verdict = MistakeConfirmation.decide(
                output.confidence(),
                validator.citesJudgeFailure(output, failedCaseId),
                recent);

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
     * <p>문제 단위로 센다. 같은 문제를 세 번 틀린 것은 "세 문제에서 반복됐다" 가
     * 아니다 - 그렇게 세면 한 문제에서 고전하는 사용자가 곧바로 확정을 받는다.
     */
    private int recentDetectionCount(Long userId, Long problemId, String mistakeCode) {
        // 넉넉히 읽고 문제 단위로 접는다. 한 문제에 제출이 여러 번 있을 수 있으므로
        // 창(3문제)보다 많이 가져와야 한다.
        List<Object[]> rows = detections.recentPrimaryDetections(
                userId, PageRequest.of(0, MistakeConfirmation.RECENT_WINDOW * 10));

        // 문제별로 "이 Mistake 가 그 문제에서 나왔는가" 를 한 번만 센다.
        // 같은 문제의 제출 세 번을 3회로 세면, 한 문제에서 고전하는 사용자가
        // 곧바로 확정을 받는다.
        Map<Long, Boolean> byProblem = new LinkedHashMap<>();
        byProblem.put(problemId, true);   // 이번 제출의 문제, 이번 탐지

        for (Object[] row : rows) {
            String code = (String) row[0];
            Long seen = ((Number) row[1]).longValue();
            if (!byProblem.containsKey(seen)
                    && byProblem.size() >= MistakeConfirmation.RECENT_WINDOW) {
                break;   // 창을 넘겼다
            }
            byProblem.merge(seen, code.equals(mistakeCode), (a, b) -> a || b);
        }

        return (int) byProblem.values().stream().filter(Boolean::booleanValue).count();
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
