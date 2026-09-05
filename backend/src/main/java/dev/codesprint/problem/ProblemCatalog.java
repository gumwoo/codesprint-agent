package dev.codesprint.problem;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * 문제 메타데이터를 읽는다. 정본은 {@code problems/<CODE>/problem.yaml} 이다.
 *
 * <p><b>커리큘럼과 달리 jar 에 넣지 않는다.</b> 이 저장소의 문제는 전부
 * {@code DEV_FIXTURE} 이고(ADR-0008), 실서비스 문제은행은 여기 없다. 배포물에
 * 구워 넣으면 나중에 문제은행을 갈아끼울 때 재배포가 필요해진다.
 *
 * <p>여기서 데이터를 <b>검증하지 않는다.</b> Skill 참조 무결성, weight 합, 자동 드릴
 * 대상 존재 여부 같은 불변식은 {@code tools/check_problems.py} 가 CI 에서 막는다.
 * 같은 검사를 두 언어로 두면 둘이 갈라진다(ADR-0012 와 같은 이유).
 */
@Component
public class ProblemCatalog {

    private final Path root;
    private final Map<String, ProblemDefinition> problems;

    public ProblemCatalog(@Value("${codesprint.problems-dir}") String problemsDir) {
        this.root = Path.of(problemsDir);
        this.problems = load(this.root);
    }

    /**
     * 문제 하나.
     *
     * @param expectedSolveSeconds 기대 풀이 시간. speed 관측의 기준이다(Addendum §13).
     *     없으면 null 이며, 그때는 speed 를 <b>관측하지 않는다</b> - 0 으로 채우면
     *     "느렸다" 가 된다.
     * @param kind {@code NORMAL} / {@code MICRO_DRILL} 등. 드릴은 Evidence 종류가 다르다.
     */
    public record ProblemDefinition(
            String code,
            String title,
            String kind,
            String source,
            String statement,
            Integer timeLimitMs,
            Integer memoryLimitMb,
            Integer expectedSolveSeconds,
            List<SkillLink> skills) {

        /** 이 문제가 주로 겨냥하는 Skill. */
        public String primarySkill() {
            return skills.stream()
                    .filter(link -> "PRIMARY".equals(link.role()))
                    .map(SkillLink::skillCode)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "PRIMARY Skill 이 없는 문제다: " + code
                                    + ". tools/check_problems.py 가 막았어야 한다."));
        }
    }

    /**
     * 사용자에게 보여줄 수 있는 Test Case.
     *
     * <p><b>{@code hidden: true} 인 case 는 여기 들어오지 않는다.</b> 공개 저장소에서는
     * 파일을 열면 다 보이지만(ADR-0008), 그것은 이 저장소가 fixture 를 담고 있기
     * 때문이지 API 가 그래도 된다는 뜻이 아니다.
     */
    public record SampleCase(String input, String expectedOutput) {
    }

    /**
     * 문제와 Skill 의 연결.
     *
     * @param weight 이 문제가 그 Skill 을 얼마나 보는가. Evidence 의 weight 에 곱해져
     *     SECONDARY Skill 의 confidence 를 덜 올린다.
     */
    public record SkillLink(String skillCode, String role, double weight) {
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ProblemDefinition> load(Path root) {
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException(
                    "문제 디렉터리가 없다: " + root.toAbsolutePath()
                            + ". application.yml 의 codesprint.problems-dir 을 확인한다.");
        }
        Map<String, ProblemDefinition> loaded = new LinkedHashMap<>();
        try (var dirs = Files.list(root)) {
            for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                Path file = dir.resolve("problem.yaml");
                if (!Files.exists(file)) {
                    continue;
                }
                Map<String, Object> doc;
                try (var in = Files.newInputStream(file)) {
                    doc = new Yaml().load(in);
                }
                List<SkillLink> skills = new ArrayList<>();
                for (Map<String, Object> row :
                        (List<Map<String, Object>>) doc.getOrDefault("skills", List.of())) {
                    skills.add(new SkillLink(
                            (String) row.get("code"),
                            (String) row.get("role"),
                            ((Number) row.get("weight")).doubleValue()));
                }
                String code = (String) doc.get("code");
                loaded.put(code, new ProblemDefinition(
                        code,
                        (String) doc.get("title"),
                        (String) doc.get("kind"),
                        (String) doc.get("source"),
                        (String) doc.get("statement"),
                        integer(doc.get("timeLimitMs")),
                        integer(doc.get("memoryLimitMb")),
                        integer(doc.get("expectedSolveSeconds")),
                        List.copyOf(skills)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("문제를 읽지 못했다: " + root, e);
        }
        if (loaded.isEmpty()) {
            // 조용히 비어 있으면 모든 제출이 "문제 없음" 으로 실패한다.
            throw new IllegalStateException("문제를 하나도 읽지 못했다: " + root.toAbsolutePath());
        }
        return Map.copyOf(loaded);
    }

    private static Integer integer(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    /** @return 없으면 null */
    public ProblemDefinition find(String code) {
        return problems.get(code);
    }

    public List<String> codes() {
        return List.copyOf(problems.keySet());
    }

    /** Test Case 파일. Worker 가 읽는다 - 이 파일에는 정답이 들어 있다(ADR-0006). */
    public Path casesFile(String code) {
        return root.resolve(code).resolve("cases.json");
    }

    /**
     * 공개 가능한 case 만 읽는다.
     *
     * <p>필터를 호출자에게 맡기지 않는다. 한 곳에서라도 잊으면 그대로 유출이고,
     * 지금은 fixture 라 티가 나지 않는다.
     */
    @SuppressWarnings("unchecked")
    public List<SampleCase> samplesOf(String code) {
        Path file = casesFile(code);
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            Map<String, Object> doc = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(Files.readString(file), Map.class);
            List<SampleCase> samples = new ArrayList<>();
            for (Map<String, Object> row
                    : (List<Map<String, Object>>) doc.getOrDefault("cases", List.of())) {
                if (Boolean.TRUE.equals(row.get("hidden"))) {
                    continue;
                }
                samples.add(new SampleCase(
                        (String) row.get("input"), (String) row.get("expectedOutput")));
            }
            return List.copyOf(samples);
        } catch (IOException e) {
            throw new UncheckedIOException("Test Case 를 읽지 못했다: " + file, e);
        }
    }

    /**
     * 이 Skill 을 PRIMARY 로 갖는 문제들. code 순으로 고정한다 - 같은 상황에서 같은
     * 문제가 나와야 사용자가 왜 이 문제를 받았는지 설명할 수 있다.
     */
    public List<ProblemDefinition> byPrimarySkill(String skillCode, String kind) {
        return problems.values().stream()
                .filter(problem -> kind == null || kind.equals(problem.kind()))
                .filter(problem -> problem.skills().stream().anyMatch(link ->
                        "PRIMARY".equals(link.role()) && link.skillCode().equals(skillCode)))
                .sorted(java.util.Comparator.comparing(ProblemDefinition::code))
                .toList();
    }
}
