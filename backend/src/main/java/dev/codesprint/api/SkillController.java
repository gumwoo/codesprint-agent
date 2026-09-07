package dev.codesprint.api;

import dev.codesprint.curriculum.CurriculumCatalog;
import dev.codesprint.learning.domain.Dimension;
import dev.codesprint.learning.domain.SkillState;
import dev.codesprint.learning.persistence.UserRepository;
import dev.codesprint.learning.service.MasteryService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Skill 정의와 한 사용자의 Skill 상태.
 * 계약: contracts/skill-catalog.schema.json, contracts/skill-map.schema.json.
 *
 * <p>이 제품의 한 문장은 "무엇을 모르는지 찾아낸다" 인데, 그 답을 <b>볼 방법이
 * 없었다.</b> {@code user_skills} 는 제출마다 쌓이고 계약도 있었지만 내보내는 곳이
 * 없어서, 사용자는 제출 하나하나의 판정만 보고 자기 상태는 알 수 없었다.
 *
 * <h2>정의와 상태를 나눈 이유</h2>
 *
 * <p>Skill 의 이름 · 도메인 · 선수 관계는 누구에게나 같다. 상태에 섞어 내보내면
 * 커리큘럼이 사용자별 데이터처럼 보이고, 사용자마다 같은 것을 다시 실어 보낸다.
 *
 * <p>그리고 이 응답은 <b>계산하지 않는다.</b> mastery 도 status 도 서비스가 정한
 * 것을 옮길 뿐이다(ADR-0001). 화면이 두 값을 다시 조합하기 시작하면 서버가 정한 것과
 * 갈린다.
 */
@RestController
@RequestMapping("/api")
public class SkillController {

    private final CurriculumCatalog catalog;
    private final MasteryService mastery;
    private final UserRepository users;

    public SkillController(CurriculumCatalog catalog, MasteryService mastery,
            UserRepository users) {
        this.catalog = catalog;
        this.mastery = mastery;
        this.users = users;
    }

    public record RequirementView(String skillCode, double minimumMastery) {
    }

    public record SkillDefinitionView(String code, String name, String domain, String tier,
            List<RequirementView> requires) {
    }

    public record SkillCatalogResponse(List<SkillDefinitionView> skills) {
    }

    /**
     * 한 Skill 의 상태. 계약: contracts/user-skill.schema.json.
     *
     * @param mastery 아무 차원도 평가되지 않았으면 null 이다. <b>0.0 과 다르다</b> -
     *     "아직 안 봤다" 와 "보았고 못한다" 를 화면이 구분할 수 있어야 한다.
     */
    public record SkillStateView(String skillCode, Double concept, Double recognition,
            Double implementation, Double independent, Double retention, Double speed,
            Double mastery, double confidence, int evidenceCount, String status) {
    }

    public record SkillMapResponse(long userId, List<SkillStateView> skills) {
    }

    /** 활성 Skill 의 정의. 사용자와 무관하다. */
    @GetMapping("/skills")
    public SkillCatalogResponse skills() {
        return new SkillCatalogResponse(catalog.skillCodes().stream()
                .sorted()
                .map(catalog::skill)
                .map(skill -> new SkillDefinitionView(
                        skill.code(), skill.name(), skill.domain(), skill.tier(),
                        catalog.prerequisitesOf(skill.code()).stream()
                                .map(prerequisite -> new RequirementView(
                                        prerequisite.requires(),
                                        prerequisite.minimumMastery()))
                                .toList()))
                .toList());
    }

    /**
     * 이 사용자의 Skill 상태. <b>활성 Skill 을 하나도 빠뜨리지 않는다.</b>
     *
     * <p>없는 사용자는 404 다. 빈 목록으로 답하면 "아무것도 안 한 사람" 과
     * "없는 사람" 이 같아진다.
     */
    @GetMapping("/users/{userId}/skills")
    public ResponseEntity<SkillMapResponse> skillsOf(@PathVariable long userId) {
        if (!users.existsById(userId)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new SkillMapResponse(userId,
                mastery.statesOf(userId).stream().map(SkillController::toView).toList()));
    }

    private static SkillStateView toView(SkillState state) {
        return new SkillStateView(
                state.skillCode(),
                state.score(Dimension.CONCEPT),
                state.score(Dimension.RECOGNITION),
                state.score(Dimension.IMPLEMENTATION),
                state.score(Dimension.INDEPENDENT),
                state.score(Dimension.RETENTION),
                state.score(Dimension.SPEED),
                state.mastery(),
                state.confidence(),
                state.evidenceCount(),
                state.status().name());
    }
}
