package dev.codesprint.learning.service;

import dev.codesprint.learning.domain.Dimension;
import dev.codesprint.learning.domain.Evidence;
import dev.codesprint.learning.domain.MasteryCalculator;
import dev.codesprint.learning.domain.PrerequisiteEvaluator;
import dev.codesprint.learning.domain.SkillState;
import dev.codesprint.learning.domain.SkillStatus;
import dev.codesprint.learning.persistence.UserSkillRepository;
import dev.codesprint.learning.persistence.UserSkillRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Evidence 로부터 Skill 상태를 다시 계산하고 캐시에 반영한다.
 *
 * <p><b>저장된 mastery 를 이어서 갱신하지 않는다.</b> 매번 Evidence 전체를 처음부터
 * 다시 접는다(ADR-0009). EMA 는 되돌릴 수 없으므로, 저장된 값을 정본으로 삼으면
 * 산식을 고쳐도 과거에 적용할 수 없다.
 *
 * <p>느려 보이지만 한 Skill 의 Evidence 는 사용자당 수십~수백 건이다. 그 비용으로
 * "산식을 고치면 과거가 따라온다" 를 산다.
 */
@Service
public class MasteryService {

    private final EvidenceStore evidence;
    private final UserSkillRepository userSkills;
    private final PrerequisiteEvaluator prerequisites;
    private final dev.codesprint.curriculum.CurriculumCatalog catalog;

    public MasteryService(EvidenceStore evidence, UserSkillRepository userSkills,
            PrerequisiteEvaluator prerequisites,
            dev.codesprint.curriculum.CurriculumCatalog catalog) {
        this.evidence = evidence;
        this.userSkills = userSkills;
        this.prerequisites = prerequisites;
        this.catalog = catalog;
    }

    /**
     * 이 사용자의 <b>활성 Skill 전체</b> 상태. 계약: contracts/skill-map.schema.json.
     *
     * <p><b>손대지 않은 Skill 도 담는다.</b> 아직 Evidence 가 없는 Skill 이 목록에서
     * 빠지면 "무엇을 모르는가" 가 보이지 않는다 - 이 제품이 답해야 하는 질문이 그것이다.
     *
     * <p><b>캐시를 읽지 않고 Evidence 에서 다시 계산한다.</b> 저장된 user_skills 행은
     * 캐시이고 정본이 아니다(ADR-0009). 읽기 경로가 캐시를 그대로 내보내면, 캐시가
     * 어긋났을 때 화면이 그 어긋난 값을 사실로 보여준다.
     *
     * <p>선수 관계 판정에 쓰는 mastery 도 방금 계산한 것들로 채운다. 캐시에서 가져오면
     * 한 응답 안에 두 시점이 섞인다.
     */
    @Transactional(readOnly = true)
    public List<SkillState> statesOf(Long userId) {
        List<String> codes = catalog.skillCodes().stream().sorted().toList();

        Map<String, SkillState> fromEvidence = new java.util.LinkedHashMap<>();
        Map<String, Double> masteries = new HashMap<>();
        for (String code : codes) {
            SkillState state = MasteryCalculator.recompute(evidence.load(userId, code), code);
            fromEvidence.put(code, state);
            masteries.put(code, state.mastery());
        }

        List<SkillState> states = new java.util.ArrayList<>();
        for (String code : codes) {
            SkillState state = fromEvidence.get(code);
            // Evidence 만으로는 LOCKED / READY 를 알 수 없다. 그 둘은 선수 관계에서 나온다.
            states.add(new SkillState(code, state.scores(), state.mastery(),
                    state.confidence(), state.evidenceCount(),
                    prerequisites.resolve(code, state.status(), masteries)));
        }
        return states;
    }

    /**
     * 한 Skill 을 다시 계산해 캐시에 쓴다.
     *
     * @param masteries 선수 조건 판정에 쓸 다른 Skill 들의 mastery
     * @return 저장된 상태. status 는 Evidence 기반 상태에 선수 관계를 합친 값이다.
     */
    @Transactional
    public SkillState recomputeAndStore(Long userId, String skillCode,
            Map<String, Double> masteries) {

        List<Evidence> all = evidence.load(userId, skillCode);
        SkillState state = MasteryCalculator.recompute(all, skillCode);

        // Evidence 만으로는 LOCKED / READY 를 알 수 없다. 그 둘은 선수 관계에서 나온다.
        SkillStatus resolved = prerequisites.resolve(skillCode, state.status(), masteries);

        Instant lastStudiedAt = all.isEmpty()
                ? null : all.get(all.size() - 1).occurredAt();

        UserSkillRow row = userSkills.findByUserIdAndSkillCode(userId, skillCode)
                .orElseGet(() -> new UserSkillRow(userId, skillCode));
        row.replaceWith(
                decimal(state.score(Dimension.CONCEPT)),
                decimal(state.score(Dimension.RECOGNITION)),
                decimal(state.score(Dimension.IMPLEMENTATION)),
                decimal(state.score(Dimension.INDEPENDENT)),
                decimal(state.score(Dimension.RETENTION)),
                decimal(state.score(Dimension.SPEED)),
                decimal(state.mastery()),
                BigDecimal.valueOf(state.confidence()),
                state.evidenceCount(),
                resolved.name(),
                lastStudiedAt,
                Instant.now());
        userSkills.save(row);

        return new SkillState(skillCode, state.scores(), state.mastery(), state.confidence(),
                state.evidenceCount(), resolved);
    }

    /**
     * 이 사용자의 Skill -> mastery. 선수 조건 판정의 입력이다.
     *
     * <p><b>값이 null 인 항목이 정상으로 들어온다.</b> user_skills 는 UNASSESSED /
     * LOCKED / READY 에서 mastery_score 가 NULL 인 행을 허용한다. 그것을 걸러내지
     * 않는 이유는 "행이 없다" 와 "행은 있는데 아직 평가되지 않았다" 를 구분해서
     * 넘기기 위해서다 - 판정 쪽에서 둘 다 0.0 으로 본다.
     */
    @Transactional(readOnly = true)
    public Map<String, Double> masteriesOf(Long userId) {
        Map<String, Double> masteries = new HashMap<>();
        for (UserSkillRow row : userSkills.findByUserId(userId)) {
            masteries.put(row.skillCode(),
                    row.masteryScore() == null ? null : row.masteryScore().doubleValue());
        }
        return masteries;
    }

    private static BigDecimal decimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }
}
