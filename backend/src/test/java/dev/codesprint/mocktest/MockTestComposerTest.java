package dev.codesprint.mocktest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.codesprint.mocktest.MockTestComposer.Candidate;
import dev.codesprint.mocktest.MockTestComposer.Composition;
import dev.codesprint.mocktest.MockTestComposer.Pick;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 모의 시험의 문제 고르기. 정본: PRD §84 · §86, ADR-0043.
 *
 * <p>순수 함수라 Spring 도 DB 도 없다.
 */
class MockTestComposerTest {

    private static Candidate c(int number, String domain, int seconds) {
        return new Candidate("P" + number + "_X", number, domain, seconds);
    }

    private static List<Integer> numbers(Composition composition) {
        return composition.picks().stream().map(pick -> pick.problem().number()).toList();
    }

    @Test
    @DisplayName("가장 쉬운 것 · 1/3 · 2/3 · 가장 어려운 것에서 고른다")
    void spreadsOverDifficulty() {
        // 기대 시간 순: 10(100s) 11(200) 12(300) 13(400) 14(500) 15(600) 16(700)
        List<Candidate> candidates = List.of(
                c(10, "A", 100), c(11, "B", 200), c(12, "C", 300), c(13, "D", 400),
                c(14, "E", 500), c(15, "F", 600), c(16, "G", 700));

        Composition composition = MockTestComposer.compose(candidates);

        // 자리 0, 2, 4, 6 - 쉬운 것과 어려운 것이 함께 들어야 문제 선택을 볼 수 있다
        assertThat(numbers(composition)).containsExactly(10, 12, 14, 16);
    }

    @Test
    @DisplayName("같은 도메인은 한 번만 넣는다 - 가장 가까운 다른 도메인으로 넘긴다")
    void oneProblemPerDomain() {
        List<Candidate> candidates = List.of(
                c(10, "A", 100), c(11, "A", 200), c(12, "A", 300), c(13, "B", 400),
                c(14, "C", 500), c(15, "D", 600), c(16, "D", 700));

        Composition composition = MockTestComposer.compose(candidates);

        assertThat(composition.picks()).extracting(pick -> pick.problem().domain())
                .doesNotHaveDuplicates();
        assertThat(numbers(composition)).containsExactly(10, 13, 14, 16);
    }

    @Test
    @DisplayName("라벨은 난이도 순이 아니라 문제 번호 순이다 - 라벨로 쉬운 문제를 알 수 없다")
    void labelsFollowNumberNotDifficulty() {
        // 번호가 작을수록 어렵게 둔다. 난이도 순으로 붙였다면 A 가 가장 쉬운 문제다.
        List<Candidate> candidates = List.of(
                c(40, "A", 100), c(30, "B", 300), c(20, "C", 500), c(10, "D", 700));

        List<Pick> picks = MockTestComposer.compose(candidates).picks();

        assertThat(picks).extracting(Pick::label).containsExactly("A", "B", "C", "D");
        assertThat(picks).extracting(pick -> pick.problem().number())
                .containsExactly(10, 20, 30, 40);
        assertThat(picks.get(0).problem().expectedSolveSeconds()).as("A 가 가장 어렵다")
                .isEqualTo(700);
    }

    @Test
    @DisplayName("시험 시간은 기대 풀이 시간의 합을 분으로 올려 30~180 분으로 자른다")
    void minutesComeFromExpectedSolveTimes() {
        assertThat(MockTestComposer.compose(List.of(
                c(1, "A", 900), c(2, "B", 1200), c(3, "C", 1500), c(4, "D", 1830))).minutes())
                .isEqualTo(91); // 5430 초 = 90.5 분 -> 91
        assertThat(MockTestComposer.compose(List.of(c(1, "A", 60), c(2, "B", 60))).minutes())
                .as("아래로 자른다").isEqualTo(30);
        assertThat(MockTestComposer.compose(List.of(
                c(1, "A", 4000), c(2, "B", 4000), c(3, "C", 4000))).minutes())
                .as("위로 자른다").isEqualTo(180);
    }

    @Test
    @DisplayName("후보가 둘보다 적으면 시험을 만들지 않는다")
    void tooFewCandidates() {
        assertThat(MockTestComposer.compose(List.of(c(1, "A", 100))).picks()).isEmpty();
        assertThat(MockTestComposer.compose(List.of()).picks()).isEmpty();
        assertThat(MockTestComposer.compose(List.of(c(1, "A", 100), c(2, "A", 200))).picks())
                .as("도메인이 하나뿐이면 한 문제라 만들지 않는다").isEmpty();
        assertThat(MockTestComposer.compose(List.of(c(1, "A", 100), c(2, "B", 200))).picks())
                .hasSize(2);
    }

    @Test
    @DisplayName("같은 후보면 같은 시험이 나온다 - 입력 순서에 기대지 않는다")
    void deterministic() {
        List<Candidate> forward = List.of(
                c(10, "A", 100), c(11, "B", 200), c(12, "C", 300), c(13, "D", 400),
                c(14, "E", 500));
        List<Candidate> backward = new java.util.ArrayList<>(forward);
        java.util.Collections.reverse(backward);

        assertThat(MockTestComposer.compose(backward)).isEqualTo(MockTestComposer.compose(forward));
    }
}
