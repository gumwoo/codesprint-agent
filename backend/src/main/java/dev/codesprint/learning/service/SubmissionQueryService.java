package dev.codesprint.learning.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codesprint.learning.persistence.SubmissionRepository;
import dev.codesprint.learning.persistence.SubmissionRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 제출 하나의 현재 상태를 읽는다. 계약: contracts/submission-status.schema.json.
 *
 * <p><b>여기서 다시 계산하지 않는다.</b> 다음 행동과 갱신 내역은 결과를 반영하던
 * 그 순간에 정해져 제출 행에 남아 있다. 조회 때마다 새로 계산하면 그 사이 다른
 * 제출이 바꿔 놓은 상태를 보게 되어, 같은 제출을 두 번 조회했을 때 다른 답이 나온다.
 */
@Service
public class SubmissionQueryService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SubmissionRepository submissions;

    public SubmissionQueryService(SubmissionRepository submissions) {
        this.submissions = submissions;
    }

    public record SkillUpdate(String skillCode, Double before, Double after, double confidence,
            String status) {
    }

    public record Judgement(String status, int passed, int total, Integer executionMs,
            Integer memoryKb, Integer failedCaseId, String stderr) {
    }

    public record NextActionView(String type, String targetSkill, String reason) {
    }

    /**
     * Reviewer 분석과 시스템의 확정 판단. 부르지 않았으면 null 이다.
     *
     * @param status 시스템이 붙인다. LLM 이 스스로 CONFIRMED 를 선언할 수 없다.
     */
    public record ReviewView(String primaryMistake, List<String> secondaryMistakes,
            double confidence, String status, String explanation) {
    }

    /**
     * @param complete 채점이 끝났고 그 결과가 학습 상태에 반영됐는가. 둘을 구분해
     *     보여주지 않는 이유는 사용자가 할 일이 같기 때문이다 - 기다린다.
     */
    public record View(long submissionId, boolean complete, Judgement judge,
            List<SkillUpdate> skillUpdates, NextActionView nextAction, ReviewView review,
            String promptVersion) {
    }

    @Transactional(readOnly = true)
    public Optional<View> find(long submissionId) {
        return submissions.findById(submissionId).map(SubmissionQueryService::toView);
    }

    private static View toView(SubmissionRow row) {
        // nextActionType 이 채워지는 시점이 곧 "반영이 끝난" 시점이다. 판정 상태만으로는
        // 판단할 수 없다 - 채점은 끝났는데 아직 반영하지 않은 구간이 있다.
        boolean complete = row.nextActionType() != null;
        if (!complete) {
            return new View(row.id(), false, null, List.of(), null, null, null);
        }
        return new View(
                row.id(),
                true,
                new Judgement(row.status(), value(row.passed()), value(row.total()),
                        row.executionMs(), row.memoryKb(), row.failedCaseId(), null),
                readUpdates(row.skillUpdates()),
                new NextActionView(row.nextActionType(), row.nextActionTarget(),
                        row.nextActionReason()),
                readReview(row),
                row.promptVersion());
    }

    /**
     * Reviewer 를 부르지 않았거나 분석을 버렸으면 null 이다. 셋을 구분해 보여주지
     * 않는다 - 사용자가 할 수 있는 일이 같다.
     */
    private static ReviewView readReview(SubmissionRow row) {
        if (row.reviewPrimaryMistake() == null) {
            return null;
        }
        return new ReviewView(
                row.reviewPrimaryMistake(),
                readCodes(row.reviewSecondary()),
                row.reviewConfidence() == null ? 0.0 : row.reviewConfidence().doubleValue(),
                row.reviewStatus(),
                row.reviewExplanation());
    }

    private static List<String> readCodes(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            List<String> codes = new ArrayList<>();
            MAPPER.readTree(json).forEach(node -> codes.add(node.asText()));
            return codes;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("secondaryMistakes 를 읽지 못했다: " + json, e);
        }
    }

    private static int value(Integer number) {
        return number == null ? 0 : number;
    }

    private static List<SkillUpdate> readUpdates(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            List<SkillUpdate> updates = new ArrayList<>();
            for (JsonNode node : MAPPER.readTree(json)) {
                updates.add(new SkillUpdate(
                        node.get("skillCode").asText(),
                        node.get("before").isNull() ? null : node.get("before").asDouble(),
                        node.get("after").isNull() ? null : node.get("after").asDouble(),
                        node.get("confidence").asDouble(),
                        node.get("status").asText()));
            }
            return updates;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("제출의 갱신 내역을 읽지 못했다: " + json, e);
        }
    }
}
