package dev.codesprint.generator;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * 생성기 프롬프트의 자리를 채울 값을 모은다.
 *
 * <p><b>커리큘럼은 jar 에 구운 것을 읽는다</b>(ADR-0012). 쓸 수 있는 Skill · Mistake code 를
 * 프롬프트에 적는 것은 모델을 돕는 것이지 검사가 아니다 - 실제로 막는 것은 채택 검사다.
 */
public final class DraftPrompt {

    /** 프롬프트 파일이 요구하는 자리. 테스트가 이것으로 파일과 코드가 맞는지 본다. */
    public static final List<String> PLACEHOLDERS = List.of(
            "skillCode", "skillName", "skillDescription", "draftSchema",
            "secondaryCandidates", "allowedMistakes", "existingProblems");

    private DraftPrompt() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, String> values(Path repoRoot, String skillCode) {
        List<Map<String, Object>> skills =
                (List<Map<String, Object>>) loadYaml("curriculum/skills.yaml").get("skills");
        List<Map<String, Object>> mistakes =
                (List<Map<String, Object>>) loadYaml("curriculum/mistakes.yaml").get("mistakes");

        Map<String, Object> skill = skills.stream()
                .filter(s -> skillCode.equals(s.get("code")))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "skills.yaml 에 없는 Skill 이다: " + skillCode));

        List<String> secondary = new ArrayList<>();
        for (Map<String, Object> s : skills) {
            if (!skillCode.equals(s.get("code"))) {
                secondary.add("- " + s.get("code") + " — " + s.get("name"));
            }
        }
        List<String> allowed = new ArrayList<>();
        for (Map<String, Object> m : mistakes) {
            // SYSTEM 이 부여하는 실수는 Reviewer 가 주장하지 않는다. 초안에도 쓰지 않는다.
            if ("REVIEWER".equals(m.get("assigned_by"))) {
                allowed.add("- " + m.get("code") + " — " + oneLine(m.get("description")));
            }
        }

        Map<String, String> values = new LinkedHashMap<>();
        values.put("skillCode", skillCode);
        values.put("skillName", String.valueOf(skill.get("name")));
        values.put("skillDescription", oneLine(skill.get("description")));
        values.put("draftSchema", readResource("contracts/problem-draft.llm.schema.json"));
        values.put("secondaryCandidates", String.join("\n", secondary));
        values.put("allowedMistakes", String.join("\n", allowed));
        values.put("existingProblems", existingProblems(repoRoot, skillCode));
        return values;
    }

    /** 이 Skill 을 쓰는 기존 문제의 제목과 본문 앞부분. 겹치지 않게 하려고 보여준다. */
    @SuppressWarnings("unchecked")
    static String existingProblems(Path repoRoot, String skillCode) {
        Path problems = repoRoot.resolve("problems");
        List<String> lines = new ArrayList<>();
        try (var dirs = Files.list(problems)) {
            for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                Path file = dir.resolve("problem.yaml");
                if (!Files.exists(file)) {
                    continue;
                }
                Map<String, Object> doc;
                try (InputStream in = Files.newInputStream(file)) {
                    doc = new Yaml().load(in);
                }
                boolean uses = ((List<Map<String, Object>>) doc.getOrDefault("skills", List.of()))
                        .stream().anyMatch(s -> skillCode.equals(s.get("code")));
                if (uses) {
                    String statement = oneLine(doc.get("statement"));
                    lines.add("- " + doc.get("code") + " " + doc.get("title") + ": "
                            + statement.substring(0, Math.min(statement.length(), 160)));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("problems/ 를 읽지 못했다: " + problems, e);
        }
        return lines.isEmpty() ? "(없음)" : String.join("\n", lines);
    }

    private static Map<String, Object> loadYaml(String resource) {
        try (InputStream in = DraftPrompt.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " 가 jar 에 없다");
            }
            return new Yaml().load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(resource + " 를 읽지 못했다", e);
        }
    }

    static String readResource(String resource) {
        try (InputStream in = DraftPrompt.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " 가 jar 에 없다");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(resource + " 를 읽지 못했다", e);
        }
    }

    private static String oneLine(Object text) {
        return text == null ? "" : String.valueOf(text).strip().replaceAll("\\s+", " ");
    }
}
