package dev.codesprint.curriculum;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * 커리큘럼 데이터를 읽는다. 정본은 저장소의 {@code curriculum/*.yaml} 이며 빌드 시점에
 * jar 로 들어온다 - 근거: docs/adr/0012-curriculum-is-packaged-from-one-source.md
 *
 * <p>여기서 데이터를 <b>검증하지 않는다.</b> 순환 선수 관계, dangling 참조, weight 합 같은
 * 불변식은 {@code tools/check_curriculum.py} 가 CI 에서 막는다. 같은 검사를 두 언어로
 * 두면 둘이 갈라지고, 갈라졌을 때 어느 쪽이 맞는지 아무도 모른다.
 *
 * <p>다만 <b>읽지 못하는 것</b>은 즉시 실패시킨다. 커리큘럼이 없는 채로 뜨면
 * Decision Engine 이 모든 Skill 을 잠긴 것으로 보게 된다.
 */
@Component
public class CurriculumCatalog {

    private final Map<String, SkillDefinition> skills;
    private final List<Prerequisite> prerequisites;
    private final Map<String, MistakeDefinition> mistakes;

    public CurriculumCatalog() {
        this.skills = loadSkills();
        this.prerequisites = loadPrerequisites();
        this.mistakes = loadMistakes();
    }

    /** {@code prerequisites.yaml} 한 줄. */
    public record Prerequisite(String skillCode, String requires, double minimumMastery) {
    }

    public record SkillDefinition(String code, String name, String domain, String tier,
            String language) {
    }

    /**
     * @param autoDrill 확정되면 자동으로 Micro Drill 을 유발하는가(Addendum §42).
     *     슬라이스 1 에서는 두 개만 켜져 있다.
     * @param targetSkill 그 드릴이 겨냥하는 Skill. autoDrill 이 켜져 있으면 반드시 있다 -
     *     {@code tools/check_problems.py} 가 그 Skill 을 PRIMARY 로 갖는 MICRO_DRILL
     *     문제의 존재까지 확인한다.
     */
    public record MistakeDefinition(String code, boolean autoDrill, String targetSkill,
            String assignedBy) {
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> read(String file, String key) {
        try (InputStream in = CurriculumCatalog.class.getClassLoader()
                .getResourceAsStream("curriculum/" + file)) {
            if (in == null) {
                throw new IllegalStateException(
                        "커리큘럼 파일이 jar 에 없다: " + file
                                + ". build.gradle.kts 의 processResources 를 확인한다.");
            }
            Map<String, Object> doc = new Yaml().load(in);
            Object rows = doc.get(key);
            if (rows == null) {
                throw new IllegalStateException(file + " 에 " + key + " 키가 없다");
            }
            return (List<Map<String, Object>>) rows;
        } catch (IOException e) {
            throw new UncheckedIOException("커리큘럼을 읽지 못했다: " + file, e);
        }
    }

    private static Map<String, SkillDefinition> loadSkills() {
        Map<String, SkillDefinition> loaded = new LinkedHashMap<>();
        for (Map<String, Object> row : read("skills.yaml", "skills")) {
            String code = (String) row.get("code");
            loaded.put(code, new SkillDefinition(
                    code,
                    (String) row.get("name"),
                    (String) row.get("domain"),
                    (String) row.get("tier"),
                    (String) row.get("language")));
        }
        return Map.copyOf(loaded);
    }

    private static List<Prerequisite> loadPrerequisites() {
        List<Prerequisite> loaded = new ArrayList<>();
        for (Map<String, Object> row : read("prerequisites.yaml", "prerequisites")) {
            loaded.add(new Prerequisite(
                    (String) row.get("skill"),
                    (String) row.get("requires"),
                    ((Number) row.get("minimum_mastery")).doubleValue()));
        }
        return List.copyOf(loaded);
    }

    private static Map<String, MistakeDefinition> loadMistakes() {
        Map<String, MistakeDefinition> loaded = new LinkedHashMap<>();
        for (Map<String, Object> row : read("mistakes.yaml", "mistakes")) {
            String code = (String) row.get("code");
            loaded.put(code, new MistakeDefinition(
                    code,
                    Boolean.TRUE.equals(row.get("auto_drill")),
                    (String) row.get("target_skill"),
                    (String) row.get("assigned_by")));
        }
        return Map.copyOf(loaded);
    }

    public Set<String> skillCodes() {
        return skills.keySet();
    }

    public SkillDefinition skill(String code) {
        return skills.get(code);
    }

    /** 이 Skill 을 배우기 전에 필요한 것들. */
    public List<Prerequisite> prerequisitesOf(String skillCode) {
        return prerequisites.stream().filter(p -> p.skillCode().equals(skillCode)).toList();
    }

    public List<Prerequisite> allPrerequisites() {
        return prerequisites;
    }

    public MistakeDefinition mistake(String code) {
        return mistakes.get(code);
    }

    /**
     * 이 Mistake 가 자동 드릴을 유발하는가. 유발하면 겨냥할 Skill 을 돌려준다.
     *
     * @return 자동 드릴 대상 Skill, 또는 자동 드릴이 아니면 null
     */
    public String autoDrillTarget(String mistakeCode) {
        MistakeDefinition definition = mistakes.get(mistakeCode);
        if (definition == null || !definition.autoDrill()) {
            return null;
        }
        return definition.targetSkill();
    }
}
