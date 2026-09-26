package dev.codesprint.learning.service;

import dev.codesprint.learning.domain.SkillState;
import dev.codesprint.learning.domain.SkillStatus;
import dev.codesprint.learning.domain.SubmissionEvidenceFactory;
import dev.codesprint.learning.persistence.SubmissionRepository;
import dev.codesprint.mocktest.MockTestService;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 학습 분석 화면의 숫자. 정본: PRD §99(핵심 화면 Analytics) · §160(Learning Analytics), ADR-0049.
 *
 * <p><b>저장된 관측만 센다.</b> 제출 기록 · Skill 상태 · 끝난 모의 시험이 전부다. 추세를 예측하거나 점수를
 * 매기지 않고, LLM 을 부르지 않는다(ADR-0001). 화면은 이 숫자를 그대로 보여 준다 - 화면이 세기 시작하면
 * 사용자가 보는 값과 서버가 가진 값이 갈린다.
 */
@Service
public class AnalyticsService {

    /** 주별 추이를 몇 주 보여 주는가. 이번 주를 포함한다. */
    public static final int WEEKS = 8;

    /** 끝난 모의 시험을 몇 개까지 보여 주는가. 최근 것부터. */
    public static final int MOCK_TESTS = 10;

    /** 판정 칸의 순서. submissions 테이블의 CHECK 와 같은 값들이다. 채점 중(QUEUED · RUNNING)은 따로 센다. */
    static final List<String> VERDICTS = List.of("ACCEPTED", "WRONG_ANSWER", "TIME_LIMIT",
            "MEMORY_LIMIT", "RUNTIME_ERROR", "COMPILE_ERROR", "OUTPUT_LIMIT", "SYSTEM_ERROR");

    private final SubmissionRepository submissions;
    private final MasteryService mastery;
    private final MockTestService mockTests;
    private final Clock clock;

    public AnalyticsService(SubmissionRepository submissions, MasteryService mastery,
            MockTestService mockTests, Clock clock) {
        this.submissions = submissions;
        this.mastery = mastery;
        this.mockTests = mockTests;
        this.clock = clock;
    }

    public record VerdictCount(String status, int count) {
    }

    /**
     * @param total 제출 수(채점 중 포함)
     * @param judging 아직 판정이 없는 제출(QUEUED · RUNNING)
     * @param verdicts 판정마다의 수. 0 인 판정도 넣는다 - 칸이 사라지면 "0 번" 과 "모른다" 가 구별되지 않는다
     */
    public record Submissions(int total, int judging, List<VerdictCount> verdicts) {
    }

    /**
     * @param problems 한 번이라도 ACCEPTED 를 받은 문제 수
     * @param independent 그중 힌트를 {@code INDEPENDENT_HINT_CEILING} 단계보다 적게 보고 전체 풀이 없이 푼
     *     문제 수. Evidence 가 독립 풀이로 치는 기준과 같다({@link SubmissionEvidenceFactory})
     */
    public record Solved(int problems, int independent) {
    }

    /** @param weekStart 그 주의 월요일(UTC) */
    public record Week(String weekStart, int submissions, int accepted) {
    }

    public record StatusCount(String status, int count) {
    }

    public record MockTest(long mockTestId, Instant startedAt, int solved, int total) {
    }

    public record Analytics(long userId, Submissions submissions, Solved solved, List<Week> weeks,
            List<StatusCount> skills, List<MockTest> mockTests) {
    }

    @Transactional(readOnly = true)
    public Analytics of(long userId) {
        List<Object[]> rows = submissions.findStatusTimesAndProblems(userId);

        Map<String, Integer> byVerdict = new LinkedHashMap<>();
        VERDICTS.forEach(v -> byVerdict.put(v, 0));
        int judging = 0;
        Set<Long> solvedProblems = new HashSet<>();

        LocalDate thisWeek = LocalDate.now(clock.withZone(ZoneOffset.UTC))
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate firstWeek = thisWeek.minusWeeks(WEEKS - 1);
        Map<LocalDate, int[]> weeks = new LinkedHashMap<>();
        for (int i = 0; i < WEEKS; i++) {
            weeks.put(firstWeek.plusWeeks(i), new int[2]);
        }

        for (Object[] row : rows) {
            String status = (String) row[0];
            Instant at = (Instant) row[1];
            Long problemId = (Long) row[2];
            if (byVerdict.containsKey(status)) {
                byVerdict.merge(status, 1, Integer::sum);
            } else {
                judging++;
            }
            if ("ACCEPTED".equals(status)) {
                solvedProblems.add(problemId);
            }
            LocalDate week = at.atZone(ZoneOffset.UTC).toLocalDate()
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            int[] counts = weeks.get(week);
            if (counts != null) {
                counts[0]++;
                if ("ACCEPTED".equals(status)) {
                    counts[1]++;
                }
            }
        }

        List<VerdictCount> verdicts = new ArrayList<>();
        byVerdict.forEach((status, count) -> verdicts.add(new VerdictCount(status, count)));
        int independent = submissions.findIndependentlySolvedProblemIds(userId,
                SubmissionEvidenceFactory.INDEPENDENT_HINT_CEILING).size();

        List<Week> weekList = new ArrayList<>();
        weeks.forEach((start, c) -> weekList.add(new Week(start.toString(), c[0], c[1])));

        Map<SkillStatus, Integer> byStatus = new EnumMap<>(SkillStatus.class);
        for (SkillStatus status : SkillStatus.values()) {
            byStatus.put(status, 0);
        }
        for (SkillState state : mastery.statesOf(userId)) {
            byStatus.merge(state.status(), 1, Integer::sum);
        }
        List<StatusCount> skills = new ArrayList<>();
        byStatus.forEach((status, count) -> skills.add(new StatusCount(status.name(), count)));

        List<MockTest> tests = mockTests.history(userId, MOCK_TESTS).stream()
                .map(t -> new MockTest(t.mockTestId(), t.startedAt(), t.solved(), t.total()))
                .toList();

        return new Analytics(userId, new Submissions(rows.size(), judging, verdicts),
                new Solved(solvedProblems.size(), independent), weekList, skills, tests);
    }
}
