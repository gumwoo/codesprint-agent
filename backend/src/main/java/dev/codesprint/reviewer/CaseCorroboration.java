package dev.codesprint.reviewer;

import java.util.Set;

/**
 * 채점 결과의 <b>모양</b>이 Reviewer 의 주장을 뒷받침하는지 본다. 정본: ADR-0015.
 *
 * <p>순수 함수다. LLM 을 부르지 않고, Reviewer 출력을 입력으로 받지도 않는다 -
 * 받으면 그 순간 순환이 된다(ADR-0014).
 *
 * <h2>무엇이 근거이고 무엇이 아닌가</h2>
 *
 * <p>Reviewer 가 실패 case 번호를 인용한 것은 근거가 아니다. 그 번호를 요청으로
 * 알려줬으므로 되돌려주기만 하면 된다. 반면 <b>어떤 case 가 실패하고 어떤 case 가
 * 통과했는가</b> 는 Reviewer 가 만들지 않았다 - Judge 가 관측한 것이다.
 *
 * <p>그래서 이렇게 묻는다.
 *
 * <pre>
 *   그 실수를 겨냥해 만든 case 가 **전부** 실패했는가   (필요조건)
 *   그 실수를 겨냥하지 않은 case 중 통과한 것이 있는가  (대조군)
 * </pre>
 *
 * <p>뒤쪽이 없으면 아무것도 확인하지 못한다. 전부 실패한 제출은 어떤 태그든
 * 만족시키므로, "무엇이 틀렸든 그 Mistake" 가 된다.
 *
 * <h2>이것이 증명하지 않는 것</h2>
 *
 * <p><b>그 실수가 있었음을 증명하지 않는다.</b> 겨냥한 case 를 실패시키는 다른 원인이
 * 얼마든지 있다. 이 검사가 배제하는 것은 "실패가 그 실수와 아무 관계도 없는" 경우이며,
 * §21-A 는 여기에 confidence 0.90 이라는 별도 조건을 함께 요구한다.
 *
 * <p>태그가 실제로 그 실수를 잡는지는 {@code tools/verify_problems.py} 가 <b>실제
 * 채점으로</b> 확인한다 - 그 실수를 담은 풀이가 태그된 case 를 전부 실패시키는지,
 * 그리고 다른 실수를 담은 오답은 만족시키지 <b>못하는지</b>. 여기의 조건과 그쪽의
 * 조건이 갈리면 CI 는 통과하는데 실제로는 확정되지 않는 상태가 된다.
 */
public final class CaseCorroboration {

    /** 아무것도 뒷받침하지 않는다. 문제를 모르거나 case 를 하나도 실행하지 못했을 때. */
    public static final CaseCorroboration NONE =
            new CaseCorroboration(Set.of(), Set.of(), java.util.Map.of());

    private final Set<Integer> passed;
    private final Set<Integer> failed;
    private final java.util.Map<String, Set<Integer>> probes;

    /**
     * @param passed 통과한 case
     * @param failed 통과하지 못한 case. <b>실행되지 않은 case 는 여기 없다</b> -
     *     "실패했다" 와 "거기까지 가지 못했다" 를 섞으면, 앞에서 멈춘 제출이 뒤쪽
     *     태그까지 만족한 것으로 읽힌다.
     * @param probes Mistake -> 그것을 겨냥한 case 들. {@code cases.json} 의 probes 다.
     */
    public CaseCorroboration(Set<Integer> passed, Set<Integer> failed,
            java.util.Map<String, Set<Integer>> probes) {
        this.passed = Set.copyOf(passed);
        this.failed = Set.copyOf(failed);
        this.probes = java.util.Map.copyOf(probes);
    }

    /**
     * @return 이 Mistake 를 뒷받침하는가. 겨냥한 case 가 하나도 없으면 false 다 -
     *     <b>태그가 없는 것은 "뒷받침한다" 가 아니라 "말할 수 없다" 이다.</b>
     */
    public boolean supports(String mistakeCode) {
        Set<Integer> probed = probes.get(mistakeCode);
        if (probed == null || probed.isEmpty()) {
            return false;
        }
        if (!failed.containsAll(probed)) {
            return false;
        }
        // 대조군: 그 실수를 겨냥하지 않은 case 중 통과한 것.
        return passed.stream().anyMatch(id -> !probed.contains(id));
    }

    /** 감사 로그에 남길 근거. 왜 확정됐는지 나중에 추적해야 한다. */
    public String describe(String mistakeCode) {
        Set<Integer> probed = probes.getOrDefault(mistakeCode, Set.of());
        return mistakeCode + " 를 겨냥한 case " + new java.util.TreeSet<>(probed)
                + " 가 전부 실패했고, 겨냥하지 않은 case 중 "
                + new java.util.TreeSet<>(passed.stream()
                        .filter(id -> !probed.contains(id)).toList())
                + " 가 통과했다";
    }
}
