package dev.codesprint.mocktest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 모의 시험에 넣을 문제를 고른다. 정본: PRD §84 · §86, ADR-0043.
 *
 * <p><b>순수 함수다.</b> LLM 에게 묻지 않고, 같은 후보면 같은 시험이 나온다. 후보를 거르는 것
 * (트랙 안 · 처음 보는 문제 · 잠기지 않은 Skill)은 호출자가 하고, 여기서는 그중 무엇을 넣을지만
 * 정한다.
 *
 * <p>후보를 기대 풀이 시간 순으로 세우고 네 곳 - 가장 쉬운 것, 1/3, 2/3, 가장 어려운 것 - 에서
 * 고른다. 시험에 쉬운 문제와 어려운 문제가 함께 있어야 "쉬운 것부터 푸는가" 를 볼 수 있다(§86).
 * 도메인이 겹치면 다음 후보로 넘긴다 - 같은 유형 둘은 문제 선택을 보여 주지 못한다.
 */
public final class MockTestComposer {

    /** 시험 한 번의 문제 수. PRD §84 의 Problem A~D 다. */
    public static final int SIZE = 4;

    /** 이보다 적으면 시험을 만들지 않는다 - 한 문제로는 문제 선택을 볼 수 없다. */
    public static final int MINIMUM = 2;

    public static final int MIN_MINUTES = 30;
    public static final int MAX_MINUTES = 180;

    private MockTestComposer() {
    }

    /**
     * @param number 문제 번호(P80 이면 80). 라벨과 같은 조건의 순서를 정한다.
     * @param domain PRIMARY Skill 의 도메인.
     */
    public record Candidate(String code, int number, String domain, int expectedSolveSeconds) {
    }

    /** 고른 문제. {@code label} 은 A 부터다. */
    public record Pick(String label, Candidate problem) {
    }

    /**
     * @param picks 문제 번호 순으로 라벨을 붙였다. 비어 있으면 시험을 만들 수 없다.
     * @param minutes 시험 시간. 고른 문제의 기대 풀이 시간 합을 30~180 분으로 자른다.
     */
    public record Composition(List<Pick> picks, int minutes) {
    }

    public static Composition compose(List<Candidate> candidates) {
        List<Candidate> byDifficulty = new ArrayList<>(candidates);
        byDifficulty.sort(Comparator.comparingInt(Candidate::expectedSolveSeconds)
                .thenComparingInt(Candidate::number));

        List<Candidate> chosen = new ArrayList<>();
        Set<String> domains = new HashSet<>();
        int n = byDifficulty.size();
        for (int i = 0; i < SIZE && n > 0; i++) {
            int target = (int) Math.round((double) i * (n - 1) / (SIZE - 1));
            Candidate pick = nearest(byDifficulty, target, chosen, domains);
            if (pick != null) {
                chosen.add(pick);
                domains.add(pick.domain());
            }
        }
        if (chosen.size() < MINIMUM) {
            return new Composition(List.of(), 0);
        }

        chosen.sort(Comparator.comparingInt(Candidate::number));
        List<Pick> picks = new ArrayList<>();
        int seconds = 0;
        for (int i = 0; i < chosen.size(); i++) {
            picks.add(new Pick(String.valueOf((char) ('A' + i)), chosen.get(i)));
            seconds += chosen.get(i).expectedSolveSeconds();
        }
        int minutes = Math.max(MIN_MINUTES, Math.min(MAX_MINUTES, (seconds + 59) / 60));
        return new Composition(List.copyOf(picks), minutes);
    }

    /**
     * 목표 자리에서 가장 가까운, 아직 쓰지 않은 도메인의 후보. 같은 거리면 쉬운 쪽이다 -
     * 목표에서 멀어질수록 "그 난이도에서 고른다" 는 뜻이 약해지므로 가까운 것부터 본다.
     */
    private static Candidate nearest(List<Candidate> sorted, int target, List<Candidate> chosen,
            Set<String> domains) {
        for (int distance = 0; distance < sorted.size(); distance++) {
            for (int index : new int[] {target - distance, target + distance}) {
                if (index < 0 || index >= sorted.size()) {
                    continue;
                }
                Candidate candidate = sorted.get(index);
                if (!chosen.contains(candidate) && !domains.contains(candidate.domain())) {
                    return candidate;
                }
            }
        }
        return null;
    }
}
