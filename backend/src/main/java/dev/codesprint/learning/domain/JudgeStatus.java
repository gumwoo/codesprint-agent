package dev.codesprint.learning.domain;

/**
 * 제출의 판정 상태. 정본: contracts/judge-result.schema.json + submissions 테이블의 CHECK.
 *
 * <p>문자열로 다루지 않는 이유는 <b>모르는 값이 조용히 실패 분기를 타기</b> 때문이다.
 * {@code "ABC"} 나 아직 끝나지 않은 {@code QUEUED} 가 들어오면 Decision Engine 이
 * 그것을 오답으로 보고 드릴이나 변형 문제로 보낸다 - 채점이 끝나지도 않았는데.
 *
 * <p>같은 이유로 Python 쪽 {@code learning/evidence.py} 도 알 수 없는 status 를 거부하고,
 * 그 목록이 계약과 같은지 테스트가 대조한다.
 */
public enum JudgeStatus {

    // 아직 판정이 나지 않은 상태. Decision Engine 이 받으면 안 된다.
    QUEUED(false),
    RUNNING(false),

    ACCEPTED(true),
    WRONG_ANSWER(true),
    TIME_LIMIT(true),
    MEMORY_LIMIT(true),
    RUNTIME_ERROR(true),
    COMPILE_ERROR(true),
    OUTPUT_LIMIT(true),
    SYSTEM_ERROR(true);

    private final boolean terminal;

    JudgeStatus(boolean terminal) {
        this.terminal = terminal;
    }

    /** 판정이 끝났는가. 끝나지 않은 제출로는 다음 행동을 정할 수 없다. */
    public boolean isTerminal() {
        return terminal;
    }

    /** Reviewer 를 호출하는 판정인가(ADR-0004). 실패 case 가 특정되는 것들이다. */
    public boolean invokesReviewer() {
        return switch (this) {
            case WRONG_ANSWER, RUNTIME_ERROR, TIME_LIMIT, MEMORY_LIMIT, OUTPUT_LIMIT -> true;
            default -> false;
        };
    }
}
