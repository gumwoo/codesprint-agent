package dev.codesprint.mocktest;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 끝난 시험을 관측한 사실로 정리한다. 정본: PRD §84~86, ADR-0043.
 *
 * <p><b>순수 함수다.</b> 관측한 순간(열기 · 실행 · 제출)과 제출의 판정만 받는다. 읽기 시간 ·
 * 첫 코드 작성 시간 · 알고리즘 인식은 여기 없다 - 서버가 볼 수 없는 것이고, 어림해 채우면
 * 사용자는 그것을 관측으로 읽는다.
 */
public final class MockTestAnalysis {

    private MockTestAnalysis() {
    }

    public enum Outcome {
        /** 처음 ACCEPTED 를 낸 제출이 있다. */
        SOLVED,
        /**
         * 낸 제출 가운데 아직 채점되지 않은 것이 있다. 못 풀었다고 단정하지 않는다 - 끝나기 직전에 낸
         * 제출은 시험이 끝난 뒤에 채점되고, 그때 SOLVED 로 바뀔 수 있다(검증 에이전트가 재현했다).
         */
        JUDGING,
        /** 냈지만 풀지 못했다 - 버린 문제다. 낸 제출이 전부 채점됐다. */
        ATTEMPTED,
        /** 열었지만 내지 않았다. */
        OPENED,
        UNOPENED
    }

    /** 아직 판정이 없는 제출의 상태. */
    private static final java.util.Set<String> PENDING = java.util.Set.of("QUEUED", "RUNNING");

    public record ProblemInput(String label, int expectedSolveSeconds) {
    }

    public record Event(String label, MockTestEventRow.Kind kind, Long submissionId, Instant at) {
    }

    /**
     * 시각은 모두 <b>시작 후 초</b>다. 없으면 null - 그 일이 일어나지 않았다.
     *
     * @param timeSpentSeconds 연 뒤 푼 때까지, 못 풀었으면 시험이 끝날 때까지. 안 열었으면 null.
     * @param overExpected 그 시간이 기대 풀이 시간을 넘었는가. 안 열었으면 null.
     * @param lateGiveUp 포기 기준(연 뒤 기대 풀이 시간)을 넘긴 뒤에도 그 문제를 붙잡고 있었는가(PRD §95 "문제 포기
     *     전략"). 서버가 보는 것은 실행 · 제출 시각뿐이다 - 기준을 넘긴 뒤에 실행이나 제출이 있었고 끝내 풀지
     *     못했으면 true, 풀었거나 기준을 넘긴 뒤 손대지 않았으면 false, 채점 중이거나 안 열었으면 null(모른다).
     *     "연 때부터 끝날 때까지" 의 경과로 판정하지 않는다 - 열어 보고 바로 넘어간 사람도 그 값은 크다
     */
    public record ProblemResult(String label, Outcome outcome, Long openedAtSeconds,
            Long firstRunAtSeconds, Long firstSubmitAtSeconds, Long solvedAtSeconds,
            int submissions, Long timeSpentSeconds, Boolean overExpected, Boolean lateGiveUp) {
    }

    /**
     * @param openOrder 처음 연 순서(라벨).
     * @param easiestFirst 처음 연 문제가 기대 풀이 시간이 가장 짧은 문제인가. 아무것도 안
     *     열었으면 null. 가장 짧은 문제가 여럿이면 그중 하나를 열었으면 참이다.
     */
    public record Result(List<ProblemResult> problems, int solved, List<String> openOrder,
            Boolean easiestFirst) {
    }

    /**
     * @param statusOf 제출 id -> 판정. 아직 채점 중이면 없거나 QUEUED · RUNNING 이다.
     */
    public static Result analyze(Instant start, Instant closedAt, List<ProblemInput> problems,
            List<Event> events, Map<Long, String> statusOf) {
        List<Event> ordered = new ArrayList<>(events);
        ordered.sort(Comparator.comparing(Event::at));

        Map<String, Instant> opened = new LinkedHashMap<>();
        Map<String, Instant> firstRun = new LinkedHashMap<>();
        Map<String, Instant> firstSubmit = new LinkedHashMap<>();
        Map<String, Instant> solved = new LinkedHashMap<>();
        Map<String, Integer> submissions = new LinkedHashMap<>();
        Map<String, Boolean> judging = new LinkedHashMap<>();
        // 문제마다 마지막으로 손댄(실행 · 제출) 시각. 포기 기준을 넘긴 뒤에도 붙잡고 있었는지 이것으로 본다.
        Map<String, Instant> lastWork = new LinkedHashMap<>();
        for (Event event : ordered) {
            if (event.at().isAfter(closedAt)) {
                continue; // 끝난 뒤의 기록은 시험이 아니다
            }
            switch (event.kind()) {
                case OPENED -> opened.putIfAbsent(event.label(), event.at());
                case RUN -> {
                    firstRun.putIfAbsent(event.label(), event.at());
                    lastWork.put(event.label(), event.at());
                }
                case SUBMITTED -> {
                    firstSubmit.putIfAbsent(event.label(), event.at());
                    lastWork.put(event.label(), event.at());
                    submissions.merge(event.label(), 1, Integer::sum);
                    String status = statusOf.get(event.submissionId());
                    if ("ACCEPTED".equals(status)) {
                        solved.putIfAbsent(event.label(), event.at());
                    } else if (status == null || PENDING.contains(status)) {
                        judging.put(event.label(), true);
                    }
                }
                default -> throw new IllegalStateException(event.kind().name());
            }
        }

        List<ProblemResult> results = new ArrayList<>();
        for (ProblemInput problem : problems) {
            String label = problem.label();
            Instant open = opened.get(label);
            Instant done = solved.get(label);
            Outcome outcome = done != null ? Outcome.SOLVED
                    : judging.containsKey(label) ? Outcome.JUDGING
                    : submissions.containsKey(label) ? Outcome.ATTEMPTED
                    : open != null ? Outcome.OPENED : Outcome.UNOPENED;
            Long spent = open == null ? null
                    : Duration.between(open, done != null ? done : closedAt).toSeconds();
            results.add(new ProblemResult(label, outcome,
                    offset(start, open), offset(start, firstRun.get(label)),
                    offset(start, firstSubmit.get(label)), offset(start, done),
                    submissions.getOrDefault(label, 0), spent,
                    spent == null ? null : spent > problem.expectedSolveSeconds(),
                    lateGiveUp(outcome, open, lastWork.get(label), problem.expectedSolveSeconds())));
        }

        List<String> openOrder = new ArrayList<>(opened.keySet());
        Boolean easiestFirst = null;
        if (!openOrder.isEmpty()) {
            int shortest = problems.stream().mapToInt(ProblemInput::expectedSolveSeconds).min()
                    .orElseThrow();
            easiestFirst = problems.stream()
                    .filter(problem -> problem.label().equals(openOrder.get(0)))
                    .anyMatch(problem -> problem.expectedSolveSeconds() == shortest);
        }
        return new Result(List.copyOf(results), solved.size(), List.copyOf(openOrder),
                easiestFirst);
    }

    private static Boolean lateGiveUp(Outcome outcome, Instant opened, Instant lastWork,
            int expectedSeconds) {
        if (opened == null || outcome == Outcome.JUDGING) {
            return null;
        }
        if (outcome == Outcome.SOLVED) {
            return false;
        }
        return lastWork != null && lastWork.isAfter(opened.plusSeconds(expectedSeconds));
    }

    private static Long offset(Instant start, Instant at) {
        return at == null ? null : Duration.between(start, at).toSeconds();
    }
}
