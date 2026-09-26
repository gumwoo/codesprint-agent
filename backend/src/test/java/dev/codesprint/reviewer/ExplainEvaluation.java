package dev.codesprint.reviewer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codesprint.curriculum.CurriculumCatalog;
import dev.codesprint.explain.ExplainPort;
import dev.codesprint.explain.ExplainService;
import dev.codesprint.explain.PromptExplainer;
import dev.codesprint.problem.ProblemCatalog;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * <b>Explain Back 이 설명을 실제로 어떻게 읽는가.</b> 정본: ADR-0052.
 *
 * <p>Reviewer 평가(ADR-0016)와 같은 자리에 있고 같은 설정 파일을 읽는다. 다른 점은 정답지다 - Reviewer 는
 * CI 가 실제로 채점해 본 오답이 정답지지만, 설명에는 채점이 없다. 그래서 <b>무엇을 심었는지 적어 둔 설명</b>을
 * 쓴다({@code tests/eval/explain/}).
 *
 * <pre>
 *   SOUND          틀린 말이 없고, 이 문제에서 그 기법이 통하는 핵심 이유를 말한다
 *   MISSING        틀린 말은 없지만 개념 자료의 요점 하나(omittedPoint)를 일부러 뺐다
 *   MISCONCEPTION  틀린 주장 하나(plantedMisconception)를 심었다
 *   VAGUE          맞지도 틀리지도 않은, 구체성이 없는 설명 - 기대하는 답이 없다. 보고만 한다
 * </pre>
 *
 * <h2>무엇을 실패로 볼 것인가</h2>
 *
 * <p>정확도에 기준선을 두지 않는다(ADR-0016 과 같은 이유). 두 가지만 본다.
 *
 * <pre>
 *   오지적 0건         SOUND · MISSING 설명에 "잘못 이해한 점" 을 붙이면 안 된다     exit 1
 *   평가가 성립했는가   쓸 수 있는 분석이 0건이면 아무것도 재지 않은 것이다            exit 2
 * </pre>
 *
 * <p>앞쪽이 Reviewer 의 "오확정 0건" 자리다. 맞게 이해한 사람에게 틀렸다고 말하는 것이 이 기능이 낼 수 있는
 * 가장 나쁜 답이다 - 기록은 남지 않아도 사람은 그 말을 믿는다. 빠진 요점을 짚었는지, 심은 오개념을 찾았는지는
 * 모델의 문장이 개념 자료와 다른 말로 나오므로 기계로 맞다고 할 수 없다. 개수와 원문을 보고만 한다.
 *
 * <pre>
 *   gradle evalExplain                  # 전부
 *   gradle evalExplain --args P02_      # 파일 이름에 그것이 들어간 것만
 * </pre>
 */
public final class ExplainEvaluation {

    private ExplainEvaluation() {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    enum Label { SOUND, MISSING, MISCONCEPTION, VAGUE }

    /** 한 설명. 파일 이름은 {@code <문제 code>__<라벨>.json} 이다. */
    record Case(String file, String problemCode, Label label, String explanation,
            Integer omittedPoint, String plantedMisconception) {
    }

    /**
     * 한 설명의 결과.
     *
     * @param analysis 쓸 수 있는 분석. 오지 않았거나 계약을 어겨 버려졌으면 null
     */
    record Outcome(Case evaluated, ExplainPort.Analysis analysis, String note) {

        boolean isUsable() {
            return analysis != null;
        }

        /**
         * 화면과 같은 기준으로 센다 - 화면은 {@code if (analysis.misconception)} 이라 빈 문자열만 숨기고 공백 한 칸은
         * "잘못 이해한 점: " 줄을 그린다. 여기서 공백을 "붙이지 않음" 으로 세면 사용자가 본 것과 평가가 갈린다.
         */
        boolean flagsMisconception() {
            return analysis != null && analysis.misconception() != null
                    && !analysis.misconception().isEmpty();
        }

        /** 맞게 이해한 설명에 "잘못 이해한 점" 을 붙였다. <b>이것이 일어나면 안 된다.</b> */
        boolean isFalseMisconception() {
            return flagsMisconception() && (evaluated.label() == Label.SOUND
                    || evaluated.label() == Label.MISSING);
        }
    }

    public static void main(String[] args) {
        String filter = args.length > 0 ? args[0] : null;
        ProblemCatalog problems = new ProblemCatalog(repoRoot().resolve("problems").toString());
        CurriculumCatalog curriculum = new CurriculumCatalog();

        List<Case> cases = new ArrayList<>();
        List<String> broken = new ArrayList<>();
        for (Path path : caseFiles(filter)) {
            JsonNode node = read(path);
            List<String> issues = issues(path.getFileName().toString(), node, problems, curriculum);
            if (issues.isEmpty()) {
                cases.add(parse(path.getFileName().toString(), node));
            } else {
                broken.add(path.getFileName() + ": " + String.join(" / ", issues));
            }
        }
        if (!broken.isEmpty()) {
            // 정답지가 틀렸으면 무엇을 재도 의미가 없다. 부르기 전에 멈춘다.
            broken.forEach(line -> System.err.println("[X] " + line));
            System.exit(2);
        }
        if (cases.isEmpty()) {
            System.err.println("평가 케이스가 없다 - tests/eval/explain/");
            System.exit(2);
        }

        // 설정은 애플리케이션과 같은 파일(codesprint.explain)에서 읽는다.
        ReviewerSettings.Values settings = ReviewerSettings.load("explain", System::getenv);
        ExplainPort explainer = new PromptExplainer(
                new ClaudeCliLlmClient(settings.command(),
                        Duration.ofSeconds(settings.timeoutSeconds())),
                PromptTemplate.load(settings.promptVersion()), settings.promptVersion());

        List<Outcome> outcomes = new ArrayList<>();
        for (Case one : cases) {
            outcomes.add(evaluateOne(one, explainer, problems, curriculum));
        }
        report(outcomes, settings.promptVersion(), curriculum, problems);
        System.exit(verdict(outcomes));
    }

    /** @return 0 통과 · 1 오지적 · 2 평가가 성립하지 않음 */
    static int verdict(List<Outcome> outcomes) {
        List<Outcome> wrong = outcomes.stream().filter(Outcome::isFalseMisconception).toList();
        if (!wrong.isEmpty()) {
            System.out.println();
            System.out.println("[FAIL] 맞게 이해한 설명에 '잘못 이해한 점' 을 붙였다 " + wrong.size()
                    + "건 - 그 사용자는 맞는 이해를 틀렸다고 듣는다.");
            for (Outcome o : wrong) {
                System.out.println("       " + o.evaluated().file() + ": " + o.analysis().misconception());
            }
            return 1;
        }
        // 오지적이 0건인 것과 아무것도 재지 않은 것을 구분한다 - 모델이 완전히 죽어도 오지적은 0건이다.
        if (outcomes.stream().noneMatch(Outcome::isUsable)) {
            System.out.println();
            System.out.println("[INVALID] 쓸 수 있는 분석이 0건이라 평가가 성립하지 않는다.");
            return 2;
        }
        System.out.println();
        System.out.println("[OK] 오지적 0건");
        return 0;
    }

    private static Outcome evaluateOne(Case one, ExplainPort explainer, ProblemCatalog problems,
            CurriculumCatalog curriculum) {
        ExplainPort.Request request = ExplainService.request(problems.find(one.problemCode()),
                curriculum, one.explanation());
        Optional<ExplainPort.Analysis> analysed;
        try {
            analysed = explainer.analyze(request);
        } catch (RuntimeException e) {
            return new Outcome(one, null, "호출 실패: " + e.getClass().getSimpleName());
        }
        return analysed.map(a -> new Outcome(one, a, null))
                .orElseGet(() -> new Outcome(one, null, "분석 없음 또는 계약 위반"));
    }

    static void report(List<Outcome> outcomes, String promptVersion, CurriculumCatalog curriculum,
            ProblemCatalog problems) {
        long usable = outcomes.stream().filter(Outcome::isUsable).count();
        System.out.println();
        System.out.println("== Explain Back 평가 (" + promptVersion + ") ==");
        System.out.printf("케이스 %d건 · 쓸 수 있는 분석 %d건 (오지 않았거나 버려짐 %d)%n",
                outcomes.size(), usable, outcomes.size() - usable);

        // 라벨마다 "잘못 이해한 점" 을 붙인 수와 "빠진 점" 을 하나 이상 낸 수. 쓸 수 있는 분석 안에서만 센다.
        Map<Label, long[]> byLabel = new TreeMap<>();
        for (Label label : EnumSet.allOf(Label.class)) {
            byLabel.put(label, new long[3]);
        }
        for (Outcome o : outcomes) {
            long[] counts = byLabel.get(o.evaluated().label());
            if (!o.isUsable()) {
                continue;
            }
            counts[0]++;
            if (o.flagsMisconception()) {
                counts[1]++;
            }
            if (!o.analysis().missingPoints().isEmpty()) {
                counts[2]++;
            }
        }
        System.out.println();
        System.out.printf("%-14s %6s %18s %16s%n", "라벨", "분석", "잘못 이해한 점 있음", "빠진 점 있음");
        byLabel.forEach((label, c) ->
                System.out.printf("%-14s %6d %18d %16d%n", label, c[0], c[1], c[2]));

        // 심은 것과 모델이 말한 것을 나란히 둔다. 같은 뜻인지는 사람이 읽는다 - 문장이 달라 기계로 셀 수 없다.
        System.out.println();
        System.out.println("-- 심은 것 / 모델의 답 --");
        for (Outcome o : outcomes) {
            Case c = o.evaluated();
            System.out.println(c.file());
            if (c.label() == Label.MISSING) {
                System.out.println("  뺀 요점   " + keyPoint(c, curriculum, problems));
            }
            if (c.label() == Label.MISCONCEPTION) {
                System.out.println("  심은 오개념 " + c.plantedMisconception());
            }
            if (!o.isUsable()) {
                System.out.println("  (분석 없음) " + o.note());
                continue;
            }
            System.out.println("  잘못 이해한 점 " + o.analysis().misconception());
            System.out.println("  빠진 점   " + o.analysis().missingPoints());
        }
    }

    private static String keyPoint(Case c, CurriculumCatalog curriculum, ProblemCatalog problems) {
        var concept = curriculum.concept(problems.find(c.problemCode()).primarySkill());
        return concept.keyPoints().get(c.omittedPoint());
    }

    /**
     * 정답지가 성립하는가. 모델을 부르지 않으므로 CI 가 본다({@code ExplainEvalCasesTest}).
     *
     * @return 어긴 규칙. 비어 있으면 쓸 수 있다
     */
    static List<String> issues(String file, JsonNode node, ProblemCatalog problems,
            CurriculumCatalog curriculum) {
        return issues(file, node, problems, skill -> {
            var concept = curriculum.concept(skill);
            return concept == null ? null : concept.keyPoints();
        });
    }

    /** @param keyPointsOf Skill 코드 → 개념 자료 요점. 자료가 없으면 null. 테스트가 갈아 끼운다 */
    static List<String> issues(String file, JsonNode node, ProblemCatalog problems,
            java.util.function.Function<String, List<String>> keyPointsOf) {
        List<String> issues = new ArrayList<>();
        for (String field : List.of("problemCode", "label", "explanation", "omittedPoint",
                "plantedMisconception")) {
            // null 을 허용하는 필드도 적어야 한다 - 생략은 "모른다", null 은 "없다" 다.
            if (!node.has(field)) {
                issues.add(field + " 가 없다");
            }
        }
        if (!issues.isEmpty()) {
            return issues;
        }
        String code = node.get("problemCode").asText();
        Label label;
        try {
            label = Label.valueOf(node.get("label").asText());
        } catch (IllegalArgumentException e) {
            issues.add("모르는 라벨 " + node.get("label").asText());
            return issues;
        }
        if (!file.equals(code + "__" + label + ".json")) {
            issues.add("파일 이름이 <문제>__<라벨>.json 이 아니다");
        }
        var problem = problems.find(code);
        if (problem == null) {
            issues.add("없는 문제 " + code);
            return issues;
        }
        List<String> keyPoints = keyPointsOf.apply(problem.primarySkill());
        if (keyPoints == null) {
            issues.add(problem.primarySkill() + " 의 개념 자료가 없다 - 무엇이 빠졌는지 말할 근거가 없다");
            return issues;
        }
        String explanation = node.get("explanation").asText("");
        if (explanation.isBlank() || explanation.length() > ExplainService.MAX_EXPLANATION) {
            issues.add("설명은 1~" + ExplainService.MAX_EXPLANATION + " 자다");
        }
        JsonNode omitted = node.get("omittedPoint");
        JsonNode planted = node.get("plantedMisconception");
        if (label == Label.MISSING) {
            if (!omitted.isInt() || omitted.asInt() < 0 || omitted.asInt() >= keyPoints.size()) {
                issues.add("MISSING 은 개념 자료 요점의 번호(0~" + (keyPoints.size() - 1)
                        + ")를 omittedPoint 에 적는다");
            }
        } else if (!omitted.isNull()) {
            issues.add(label + " 은 omittedPoint 가 null 이다");
        }
        if (label == Label.MISCONCEPTION) {
            if (!planted.isTextual() || planted.asText().isBlank()) {
                issues.add("MISCONCEPTION 은 심은 틀린 주장을 plantedMisconception 에 적는다");
            }
        } else if (!planted.isNull()) {
            issues.add(label + " 은 plantedMisconception 이 null 이다");
        }
        return issues;
    }

    static Case parse(String file, JsonNode node) {
        JsonNode omitted = node.get("omittedPoint");
        JsonNode planted = node.get("plantedMisconception");
        return new Case(file, node.get("problemCode").asText(),
                Label.valueOf(node.get("label").asText()), node.get("explanation").asText(),
                omitted.isNull() ? null : omitted.asInt(), planted.isNull() ? null : planted.asText());
    }

    static List<Path> caseFiles(String filter) {
        Path dir = repoRoot().resolve("tests/eval/explain");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var files = Files.list(dir)) {
            return files.filter(p -> p.toString().endsWith(".json"))
                    .filter(p -> filter == null || p.getFileName().toString().contains(filter))
                    .sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("평가 케이스를 읽지 못했다: " + dir, e);
        }
    }

    static JsonNode read(Path path) {
        try {
            return MAPPER.readTree(Files.readString(path));
        } catch (IOException e) {
            throw new UncheckedIOException("읽지 못했다: " + path, e);
        }
    }

    /** 이 평가가 다루는 라벨. CI 가 라벨마다 케이스가 있는지 본다. */
    static Set<Label> labels() {
        return EnumSet.allOf(Label.class);
    }

    private static Path repoRoot() {
        String root = System.getProperty("codesprint.repoRoot");
        if (root == null) {
            throw new IllegalStateException("codesprint.repoRoot 시스템 프로퍼티가 없다.");
        }
        return Path.of(root);
    }
}
