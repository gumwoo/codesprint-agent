package dev.codesprint.mocktest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.codesprint.mocktest.MockTestAnalysis.Event;
import dev.codesprint.mocktest.MockTestAnalysis.Outcome;
import dev.codesprint.mocktest.MockTestAnalysis.ProblemInput;
import dev.codesprint.mocktest.MockTestAnalysis.ProblemResult;
import dev.codesprint.mocktest.MockTestAnalysis.Result;
import dev.codesprint.mocktest.MockTestEventRow.Kind;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 끝난 시험을 관측한 사실로 정리한다. 정본: ADR-0043. 순수 함수다. */
class MockTestAnalysisTest {

    private static final Instant START = Instant.parse("2026-09-26T09:00:00Z");
    private static final Instant END = START.plusSeconds(3600);

    private static Instant at(long seconds) {
        return START.plusSeconds(seconds);
    }

    private static final List<ProblemInput> PROBLEMS = List.of(
            new ProblemInput("A", 1200), new ProblemInput("B", 300),
            new ProblemInput("C", 900), new ProblemInput("D", 1500));

    private static ProblemResult of(Result result, String label) {
        return result.problems().stream().filter(p -> p.label().equals(label)).findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("문제마다 연 · 첫 실행 · 첫 제출 · 푼 시각을 시작 후 초로 주고 결과를 넷으로 가른다")
    void timelineAndOutcome() {
        List<Event> events = List.of(
                new Event("B", Kind.OPENED, null, at(10)),
                new Event("B", Kind.RUN, null, at(200)),
                new Event("B", Kind.SUBMITTED, 1L, at(250)),
                new Event("B", Kind.SUBMITTED, 2L, at(400)),
                new Event("A", Kind.OPENED, null, at(500)),
                new Event("A", Kind.SUBMITTED, 3L, at(2000)),
                new Event("C", Kind.OPENED, null, at(3000)));
        Map<Long, String> status = Map.of(1L, "WRONG_ANSWER", 2L, "ACCEPTED", 3L, "TIME_LIMIT");

        Result result = MockTestAnalysis.analyze(START, END, PROBLEMS, events, status);

        ProblemResult b = of(result, "B");
        assertThat(b.outcome()).isEqualTo(Outcome.SOLVED);
        assertThat(b.openedAtSeconds()).isEqualTo(10);
        assertThat(b.firstRunAtSeconds()).isEqualTo(200);
        assertThat(b.firstSubmitAtSeconds()).isEqualTo(250);
        assertThat(b.solvedAtSeconds()).as("처음 ACCEPTED 를 낸 제출 시각").isEqualTo(400);
        assertThat(b.submissions()).isEqualTo(2);
        assertThat(b.timeSpentSeconds()).isEqualTo(390);
        assertThat(b.overExpected()).as("390 > 300").isTrue();
        assertThat(b.lateGiveUp()).as("기대보다 오래 걸렸지만 풀었다 - 포기할 문제가 아니었다").isFalse();

        ProblemResult a = of(result, "A");
        assertThat(a.outcome()).as("냈지만 못 풀었다 - 버린 문제").isEqualTo(Outcome.ATTEMPTED);
        assertThat(a.solvedAtSeconds()).isNull();
        assertThat(a.timeSpentSeconds()).as("못 풀었으면 시험 끝까지").isEqualTo(3100);
        assertThat(a.overExpected()).isTrue();
        assertThat(a.lateGiveUp()).as("연 뒤 1200 초 기준을 넘긴 2000 초에 제출했고 끝내 못 풀었다").isTrue();

        assertThat(of(result, "C").outcome()).isEqualTo(Outcome.OPENED);
        assertThat(of(result, "C").overExpected()).as("600 <= 900").isFalse();
        assertThat(of(result, "C").lateGiveUp()).as("열기만 하고 손대지 않았다").isFalse();
        ProblemResult d = of(result, "D");
        assertThat(d.outcome()).isEqualTo(Outcome.UNOPENED);
        assertThat(d.openedAtSeconds()).isNull();
        assertThat(d.timeSpentSeconds()).isNull();
        assertThat(d.overExpected()).isNull();
        assertThat(d.lateGiveUp()).isNull();

        assertThat(result.solved()).isEqualTo(1);
        assertThat(result.openOrder()).containsExactly("B", "A", "C");
        assertThat(result.easiestFirst()).as("B 가 기대 시간이 가장 짧다").isTrue();
    }

    @Test
    @DisplayName("처음 연 문제가 가장 쉬운 문제가 아니면 거짓, 아무것도 안 열었으면 null")
    void easiestFirst() {
        Result hardFirst = MockTestAnalysis.analyze(START, END, PROBLEMS,
                List.of(new Event("D", Kind.OPENED, null, at(1)),
                        new Event("B", Kind.OPENED, null, at(2))), Map.of());
        assertThat(hardFirst.easiestFirst()).isFalse();

        Result nothing = MockTestAnalysis.analyze(START, END, PROBLEMS, List.of(), Map.of());
        assertThat(nothing.easiestFirst()).isNull();
        assertThat(nothing.openOrder()).isEmpty();
    }

    @Test
    @DisplayName("다시 연 것은 순서를 바꾸지 않고, 끝난 뒤의 기록은 세지 않는다")
    void reopenAndAfterTheEnd() {
        List<Event> events = List.of(
                new Event("A", Kind.OPENED, null, at(5)),
                new Event("B", Kind.OPENED, null, at(6)),
                new Event("A", Kind.OPENED, null, at(7)),
                new Event("C", Kind.SUBMITTED, 9L, END.plusSeconds(1)));

        Result result = MockTestAnalysis.analyze(START, END, PROBLEMS, events,
                Map.of(9L, "ACCEPTED"));

        assertThat(result.openOrder()).containsExactly("A", "B");
        assertThat(of(result, "A").openedAtSeconds()).isEqualTo(5);
        assertThat(of(result, "C").outcome()).isEqualTo(Outcome.UNOPENED);
        assertThat(result.solved()).isZero();
    }

    @Test
    @DisplayName("아직 채점 중인 제출이 있으면 못 풀었다고 단정하지 않는다 - JUDGING 이고 푼 수에 넣지 않는다")
    void pendingIsJudgingNotAttempted() {
        List<Event> events = List.of(new Event("A", Kind.OPENED, null, at(1)),
                new Event("A", Kind.SUBMITTED, 4L, at(50)),
                new Event("A", Kind.SUBMITTED, 5L, at(100)));

        Result judging = MockTestAnalysis.analyze(START, END, PROBLEMS, events,
                Map.of(4L, "WRONG_ANSWER", 5L, "QUEUED"));
        assertThat(of(judging, "A").outcome()).isEqualTo(Outcome.JUDGING);
        assertThat(judging.solved()).isZero();

        // 대조: 채점이 끝나 틀렸으면 그때 ATTEMPTED 다
        Result judged = MockTestAnalysis.analyze(START, END, PROBLEMS, events,
                Map.of(4L, "WRONG_ANSWER", 5L, "WRONG_ANSWER"));
        assertThat(of(judged, "A").outcome()).isEqualTo(Outcome.ATTEMPTED);
    }

    @Test
    @DisplayName("포기가 늦었는지는 기준을 넘긴 뒤의 실행 · 제출로만 본다 - 열고 바로 넘어간 문제는 경과가 커도 아니다")
    void lateGiveUpIsObservedNotElapsed() {
        List<Event> events = List.of(
                // A(기준 1200 초): 열고 30 초 만에 떠났다. 시험이 끝날 때까지의 경과는 크지만 붙잡고 있지 않았다
                new Event("A", Kind.OPENED, null, at(0)),
                // B(기준 300 초): 기준 안(100 초)에만 실행했고 그 뒤로 손대지 않았다
                new Event("B", Kind.OPENED, null, at(30)),
                new Event("B", Kind.RUN, null, at(130)),
                // C(기준 900 초): 기준을 넘긴 뒤(1500 초)에 제출했고 채점이 아직이다 - 모른다
                new Event("C", Kind.OPENED, null, at(400)),
                new Event("C", Kind.SUBMITTED, 7L, at(1500)));
        Result result = MockTestAnalysis.analyze(START, END, PROBLEMS, events, Map.of());

        assertThat(of(result, "A").overExpected()).as("경과(3600) 는 기준(1200) 을 넘는다").isTrue();
        assertThat(of(result, "A").lateGiveUp()).as("그래도 포기가 늦은 것은 아니다").isFalse();
        assertThat(of(result, "B").lateGiveUp()).as("기준 안에서만 손댔다").isFalse();
        assertThat(of(result, "C").outcome()).isEqualTo(Outcome.JUDGING);
        assertThat(of(result, "C").lateGiveUp()).as("채점 중이면 모른다").isNull();
    }
}
