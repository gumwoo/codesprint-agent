package dev.codesprint.reviewer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 실패의 모양이 Reviewer 주장을 뒷받침하는 조건. 정본: ADR-0015.
 *
 * <p>여기의 조건은 {@code tools/verify_problems.py} 의 {@code satisfies()} 와 <b>같아야
 * 한다.</b> 갈리면 CI 는 통과하는데 실제로는 확정되지 않는(또는 그 반대의) 상태가 된다.
 */
class CaseCorroborationTest {

    private static final Map<String, Set<Integer>> PROBES =
            Map.of("BOUNDARY_CHECK", Set.of(2, 6));

    private static CaseCorroboration of(Set<Integer> passed, Set<Integer> failed) {
        return new CaseCorroboration(passed, failed, PROBES);
    }

    @Test
    @DisplayName("겨냥한 case 가 전부 실패하고 대조군이 통과하면 뒷받침한다")
    void probesFailAndControlPasses() {
        assertThat(of(Set.of(1, 3), Set.of(2, 6)).supports("BOUNDARY_CHECK")).isTrue();
    }

    @Test
    @DisplayName("겨냥한 case 중 하나라도 통과하면 뒷받침하지 않는다")
    void onePassingProbeIsEnoughToRefuse() {
        // "그 실수가 있으면 반드시 실패한다" 는 태그이므로, 하나가 통과했다는 것은
        // 그 주장과 어긋난다.
        assertThat(of(Set.of(1, 6), Set.of(2)).supports("BOUNDARY_CHECK")).isFalse();
    }

    @Test
    @DisplayName("실행되지 않은 case 는 실패가 아니다")
    void unexecutedIsNotFailed() {
        // case 6 은 통과도 실패도 아니다. 여기서 실패로 치면, 앞에서 멈춘 제출이
        // 뒤쪽 태그까지 만족한 것으로 읽힌다.
        assertThat(of(Set.of(1), Set.of(2)).supports("BOUNDARY_CHECK")).isFalse();
    }

    @Test
    @DisplayName("통과한 case 가 하나도 없으면 뒷받침하지 않는다")
    void noControlNoCorroboration() {
        // 전부 실패한 제출은 어떤 태그든 만족한다 - 무엇이 틀렸든 그 Mistake 가 된다.
        assertThat(of(Set.of(), Set.of(1, 2, 3, 6)).supports("BOUNDARY_CHECK")).isFalse();
    }

    @Test
    @DisplayName("통과한 것이 겨냥한 case 뿐이면 대조군이 아니다")
    void passingOnlyProbedCasesIsNotAControl() {
        assertThat(of(Set.of(2), Set.of(6)).supports("BOUNDARY_CHECK")).isFalse();
    }

    @Test
    @DisplayName("겨냥한 case 가 없는 Mistake 는 뒷받침되지 않는다")
    void untaggedMistake() {
        // 태그가 없는 것은 "뒷받침한다" 가 아니라 "말할 수 없다" 다.
        assertThat(of(Set.of(1, 3), Set.of(2, 6)).supports("NO_VISITED")).isFalse();
    }

    @Test
    @DisplayName("아무 데이터도 없으면 아무것도 뒷받침하지 않는다")
    void none() {
        assertThat(CaseCorroboration.NONE.supports("BOUNDARY_CHECK")).isFalse();
    }
}
