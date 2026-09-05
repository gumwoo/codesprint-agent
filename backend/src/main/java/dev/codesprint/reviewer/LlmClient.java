package dev.codesprint.reviewer;

/**
 * 모델에게 한 번 물어본다. <b>이 인터페이스는 전송만 한다.</b>
 *
 * <p>프롬프트를 만드는 것도, 응답을 해석하는 것도, 그것을 어디까지 믿을지 정하는
 * 것도 여기 없다. 그 셋이 섞이면 모델을 바꿀 때마다 판단 규칙까지 다시 짜게 된다.
 *
 * <p>구현이 없으면 Reviewer 는 동작하지 않는다. 그때도 나머지 흐름은 그대로 돈다 -
 * 판정도 mastery 도 다음 행동도 Reviewer 없이 계산된다.
 */
public interface LlmClient {

    /**
     * @param prompt 완성된 프롬프트. 이미 채워진 상태로 온다.
     * @return 모델이 낸 텍스트. 여기서 JSON 인지 확인하지 않는다 - 그건 해석하는
     *     쪽의 일이다.
     * @throws LlmUnavailable 부르지 못했을 때. <b>분석 실패와 구분하지 않는다</b> -
     *     어느 쪽이든 Mistake 는 확정되지 않는다.
     */
    String complete(String prompt);

    /** 모델을 부르지 못했다. 제출 처리는 계속되어야 한다. */
    class LlmUnavailable extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public LlmUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
