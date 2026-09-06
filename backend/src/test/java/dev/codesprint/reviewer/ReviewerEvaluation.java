package dev.codesprint.reviewer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codesprint.curriculum.CurriculumCatalog;
import dev.codesprint.problem.ProblemCatalog;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * <b>Reviewer 가 실제로 얼마나 맞히는가.</b> 정본: ADR-0016.
 *
 * <p>다른 테스트가 확인하는 것은 "그 분석을 어디까지 믿고 무엇을 하는가" 다. 그건
 * 가짜 Reviewer 로 확인할 수 있고, 그래야 CI 가 모델 없이 돈다. <b>이 파일만 다르다</b>
 * - 여기서는 진짜 모델을 부르고, 라벨과 대조한다.
 *
 * <pre>
 *   CODESPRINT_REVIEWER_EVAL=true gradle test --tests '*ReviewerEvaluationTest*'
 * </pre>
 *
 * <p><b>기본은 꺼져 있다.</b> 모델 호출은 느리고 비결정적이라 CI 에 넣지 않는다 -
 * 넣으면 모델이 그날 다르게 답했다는 이유로 관계없는 PR 이 빨개진다(ADR-0016).
 *
 * <h2>단언은 하나뿐이다</h2>
 *
 * <p>정확도에는 임의의 기준선을 두지 않는다. "70% 이상" 같은 숫자는 근거가 없고,
 * 근거 없는 기준을 통과했다고 안심하는 편이 재지 않는 것보다 나쁘다.
 *
 * <p>대신 <b>오확정 0건</b>만 단언한다. §21-A 가 서 있는 전제이기 때문이다 - 라벨과
 * 다른 Mistake 가 CONFIRMED 까지 가면 그 사용자는 엉뚱한 드릴을 받는다. 나머지는
 * 전부 보고만 한다.
 */
public final class ReviewerEvaluation {

    private ReviewerEvaluation() {
    }


    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 한 케이스의 평가 결과. 집계를 테스트에서 확인하려고 열어 뒀다. */
    record Outcome(
            String problemCode,
            String label,
            String predicted,
            double confidence,
            MistakeStatus status,
            String note) {

        /**
         * 모델이 라벨과 같은 것을 답했는가. <b>그 분석을 쓸 수 있는지는 묻지 않는다</b>
         * - 검증에서 떨어진 분석도 문자열은 남아 있다.
         */
        boolean isCorrect() {
            return label.equals(predicted);
        }

        /** 검증을 통과해 실제로 학습 경로에 닿을 수 있는 분석인가. */
        boolean isUsable() {
            return status != null;
        }

        /** 라벨과 다른 것이 확정됐다. <b>이것이 일어나면 안 된다.</b> */
        boolean isFalseConfirmation() {
            return status != null && status.isConfirmed() && !isCorrect();
        }
    }

    public static void main(String[] args) {
        // 인자를 주면 파일 이름에 그것이 들어간 케이스만 돈다. 한 건을 다시 보려고
        // 20건을 다시 부르는 것은 시간도 비용도 낭비다.
        List<JsonNode> cases = loadCases(args.length > 0 ? args[0] : null);
        if (cases.isEmpty()) {
            System.err.println("평가 케이스가 없다 - "
                    + "tools/gen_reviewer_eval_cases.py --write 를 먼저 돌린다");
            System.exit(2);
        }

        ReviewerPort reviewer = new PromptReviewer(
                new ClaudeCliLlmClient(command(), Duration.ofSeconds(180)),
                PromptTemplate.load(promptVersion()));
        ReviewerOutputValidator validator = new ReviewerOutputValidator(new CurriculumCatalog());

        // 실패한 case 의 입력은 **실제 경로와 같은 곳에서** 읽는다. 평가 케이스에
        // 복사해 두면 정답표가 tests/ 에 한 벌 더 생기고, 더 나쁘게는 모델이 받는
        // 것이 production 과 달라져 여기서 잰 정확도가 실제와 무관해진다.
        ProblemCatalog problems = new ProblemCatalog(
                repoRoot().resolve("problems").toString());

        List<Outcome> outcomes = new ArrayList<>();
        for (JsonNode one : cases) {
            outcomes.add(evaluateOne(one, reviewer, validator, problems));
        }

        report(outcomes, promptVersion());

        List<Outcome> falseConfirmations = outcomes.stream()
                .filter(Outcome::isFalseConfirmation)
                .toList();
        if (!falseConfirmations.isEmpty()) {
            System.out.println();
            System.out.println("[FAIL] 라벨과 다른 Mistake 가 확정됐다 "
                    + falseConfirmations.size() + "건 - 그 사용자는 엉뚱한 드릴을 받는다.");
            System.out.println("       §21-A 의 뒷받침 조건이나 그 문제의 probes 태그를 다시 본다.");
            for (Outcome o : falseConfirmations) {
                System.out.println("       " + o.problemCode() + ": 심어둔 것 " + o.label()
                        + ", 확정된 것 " + o.predicted());
            }
            System.exit(1);
        }
        System.out.println();
        System.out.println("[OK] 오확정 0건");
    }

    private static Outcome evaluateOne(JsonNode one, ReviewerPort reviewer,
            ReviewerOutputValidator validator, ProblemCatalog problems) {

        JsonNode judge = one.get("judge");
        String label = one.get("label").asText();
        String problemCode = one.get("problemCode").asText();
        int failedCaseId = judge.get("failedCaseId").asInt();
        ProblemCatalog.SampleCase failedCase = problems.caseById(problemCode, failedCaseId);

        ReviewerPort.Request request = new ReviewerPort.Request(
                problemCode,
                one.get("problemTitle").asText(),
                one.get("problemSource").asText(),
                judge.get("status").asText(),
                failedCaseId,
                failedCase == null ? null : failedCase.input(),
                failedCase == null ? null : failedCase.expectedOutput(),
                judge.hasNonNull("stderr") ? judge.get("stderr").asText() : null,
                one.get("sourceCode").asText(),
                names(one.get("skillCodes")));

        Optional<ReviewerOutput> analysed;
        try {
            analysed = reviewer.review(request);
        } catch (RuntimeException e) {
            return new Outcome(problemCode, label, null, 0, null,
                    "호출 실패: " + e.getClass().getSimpleName());
        }
        if (analysed.isEmpty()) {
            return new Outcome(problemCode, label, null, 0, null, "분석 없음");
        }

        ReviewerOutput output = analysed.get();
        ReviewerOutputValidator.Result validation = validator.validate(output);
        if (!validation.isUsable()) {
            // 버려진 분석은 학습 경로에 닿지 않는다. 맞았는지 틀렸는지도 따지지
            // 않는다 - 실제 경로에서 통째로 버리기 때문이다(ADR-0014).
            return new Outcome(problemCode, label, output.primaryMistake(),
                    output.confidence(), null, "검증 탈락: " + validation.describe());
        }

        CaseCorroboration corroboration = new CaseCorroboration(
                ints(judge.get("passedCaseIds")), ints(judge.get("failedCaseIds")),
                probes(one.get("probes")));

        // 재발(§21-B)은 사용자 이력이라 여기서 잴 수 없다. 이 하네스가 재는 것은
        // **한 번의 분석만으로 확정까지 가는 경로**, 즉 §21-A 다.
        MistakeConfirmation.Verdict verdict = MistakeConfirmation.decide(
                output.confidence(), corroboration.supports(output.primaryMistake()), 1);

        return new Outcome(problemCode, label, output.primaryMistake(),
                output.confidence(), verdict.status(), verdict.reason());
    }

    static void report(List<Outcome> outcomes, String promptVersion) {
        long usable = outcomes.stream().filter(Outcome::isUsable).count();
        // **두 숫자를 나눠 적는다.** 검증에서 떨어진 분석도 primary 문자열은 남아
        // 있어서, 하나로 합치면 "쓸 수 있는 분석 10/20, primary 일치 20/20" 같은
        // 읽는 사람을 오해시키는 줄이 나온다. 실제로 학습 경로에 닿는 것은 앞의
        // 것뿐이므로 그쪽을 먼저 적는다.
        long usableCorrect = outcomes.stream()
                .filter(Outcome::isUsable).filter(Outcome::isCorrect).count();
        long rawCorrect = outcomes.stream().filter(Outcome::isCorrect).count();
        long confirmed = outcomes.stream()
                .filter(o -> o.isUsable() && o.status().isConfirmed()).count();

        System.out.println();
        System.out.println("== Reviewer 평가 (" + promptVersion + ") ==");
        System.out.printf("케이스 %d건 · 쓸 수 있는 분석 %d건%n", outcomes.size(), usable);
        System.out.printf("primary 일치  쓸 수 있는 분석 중 %d/%d · 버려진 것까지 %d/%d%n",
                usableCorrect, usable, rawCorrect, outcomes.size());
        System.out.printf("한 번의 분석만으로 확정(§21-A) %d건%n", confirmed);
        System.out.println();
        System.out.printf("%-26s %-20s %-20s %6s %-12s %s%n",
                "문제", "심어둔 실수", "모델의 답", "conf", "판정", "비고");
        for (Outcome o : outcomes) {
            System.out.printf("%-26s %-20s %-20s %6.2f %-12s %s%s%n",
                    o.problemCode(), o.label(),
                    o.predicted() == null ? "-" : o.predicted(),
                    o.confidence(),
                    o.status() == null ? "-" : o.status().name(),
                    o.isCorrect() ? "" : "[불일치] ",
                    o.note() == null ? "" : o.note());
        }

        // 무엇을 무엇으로 오해하는가. 개수보다 이쪽이 프롬프트를 고칠 단서가 된다.
        Map<String, Map<String, Integer>> confusion = new TreeMap<>();
        for (Outcome o : outcomes) {
            confusion.computeIfAbsent(o.label(), k -> new TreeMap<>())
                    .merge(o.predicted() == null ? "-" : o.predicted(), 1, Integer::sum);
        }
        System.out.println();
        System.out.println("-- 혼동 --");
        confusion.forEach((label, predictions) ->
                System.out.printf("%-20s -> %s%n", label, predictions));
    }

    private static List<JsonNode> loadCases(String filter) {
        Path dir = repoRoot().resolve("tests/eval/reviewer");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var files = Files.list(dir)) {
            List<JsonNode> cases = new ArrayList<>();
            for (Path path : files
                    .filter(p -> p.toString().endsWith(".json"))
                    .filter(p -> filter == null || p.getFileName().toString().contains(filter))
                    .sorted().toList()) {
                cases.add(MAPPER.readTree(Files.readString(path)));
            }
            return cases;
        } catch (IOException e) {
            throw new UncheckedIOException("평가 케이스를 읽지 못했다: " + dir, e);
        }
    }

    private static Path repoRoot() {
        String root = System.getProperty("codesprint.repoRoot");
        if (root == null) {
            throw new IllegalStateException("codesprint.repoRoot 시스템 프로퍼티가 없다.");
        }
        return Path.of(root);
    }

    /**
     * 실행에 쓸 CLI 명령. 기본값은 application.yml 과 같아야 한다.
     *
     * <p><b>{@code --bare} 를 넣지 않는다.</b> 그것을 붙이면 구독 로그인을 읽지 않고
     * API 키를 요구한다.
     */
    private static List<String> command() {
        String override = System.getenv("CODESPRINT_REVIEWER_COMMAND");
        if (override != null && !override.isBlank()) {
            return List.of(override.split(","));
        }
        return List.of("claude", "-p", "--tools", "", "--no-session-persistence",
                "--strict-mcp-config", "--setting-sources", "", "--output-format", "json");
    }

    private static String promptVersion() {
        String version = System.getenv("CODESPRINT_REVIEWER_PROMPT");
        return version == null || version.isBlank() ? "reviewer-v1" : version;
    }

    private static List<String> names(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    private static Set<Integer> ints(JsonNode array) {
        Set<Integer> values = new LinkedHashSet<>();
        array.forEach(node -> values.add(node.asInt()));
        return values;
    }

    private static Map<String, Set<Integer>> probes(JsonNode node) {
        Map<String, Set<Integer>> probes = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> probes.put(e.getKey(), ints(e.getValue())));
        return probes;
    }
}
