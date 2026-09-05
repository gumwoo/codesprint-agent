package dev.codesprint.reviewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 전송 계층을 <b>실제 프로세스로</b> 검증한다.
 *
 * <p>Claude CLI 를 부르지 않는다. 여기서 확인할 것은 모델 응답이 아니라 프로세스를
 * 다루는 방식이다 - stdin 으로 보내는가, 봉투를 벗기는가, 실패와 지연을 끊는가.
 * 그건 {@code sh} 로 흉내 낼 수 있고, 흉내가 얕은 것이 아니라 <b>경계가 프로세스
 * 하나</b>이기 때문이다.
 *
 * <p>실제 CLI 를 부르면 CI 에서 깨진다 - 설치돼 있지 않고, 로그인도 없고,
 * 비결정적이며, 구독 사용량을 쓴다.
 */
class ClaudeCliLlmClientTest {

    /** 프롬프트를 그대로 되돌려주는 명령. stdin 으로 왔는지 확인한다. */
    private static LlmClient echoing() {
        return client("cat");
    }

    private static LlmClient client(String script) {
        return new ClaudeCliLlmClient(List.of("sh", "-c", script), Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("프롬프트를 stdin 으로 보낸다")
    void sendsThePromptOnStdin() {
        // 명령행 인자로 넘기면 사용자가 낸 코드가 프로세스 목록에 그대로 보인다.
        assertThat(echoing().complete("경계 검사를 빠뜨린 코드")).isEqualTo("경계 검사를 빠뜨린 코드");
    }

    @Test
    @DisplayName("긴 프롬프트도 잘리지 않는다")
    void handlesLargePrompts() {
        // 제출 코드가 통째로 들어가므로 짧지 않다. 파이프 버퍼를 넘기면 교착이
        // 나거나 잘린다.
        String big = "x".repeat(200_000);

        assertThat(echoing().complete(big)).hasSize(big.length());
    }

    @Test
    @DisplayName("--output-format json 의 봉투를 벗긴다")
    void unwrapsTheJsonEnvelope() {
        // CLI 가 실행 메타데이터와 함께 result 필드에 모델 텍스트를 담아 준다.
        LlmClient client = client(
                "cat > /dev/null; printf '{\"type\":\"result\",\"result\":\"{\\\\\"a\\\\\":1}\"}'");

        assertThat(client.complete("무엇이든")).isEqualTo("{\"a\":1}");
    }

    @Test
    @DisplayName("봉투가 아니면 출력을 그대로 넘긴다")
    void passesThroughWhenThereIsNoEnvelope() {
        // 여기서 JSON 인지 판단하지 않는다. 모델이 무엇을 답했는지는
        // PromptReviewer 가 계약으로 본다.
        LlmClient client = client("cat > /dev/null; printf 'not json at all'");

        assertThat(client.complete("무엇이든")).isEqualTo("not json at all");
    }

    @Test
    @DisplayName("명령이 실패하면 예외로 알린다")
    void reportsFailure() {
        LlmClient client = client("cat > /dev/null; echo '로그인이 필요합니다' >&2; exit 1");

        assertThatThrownBy(() -> client.complete("무엇이든"))
                .isInstanceOf(LlmClient.LlmUnavailable.class)
                .hasMessageContaining("exit=1");
    }

    @Test
    @DisplayName("끝나지 않는 명령을 끊는다")
    void killsAHangingCommand() {
        // CLI 가 굳으면 제출 처리 전체가 그 트랜잭션에 묶인다.
        LlmClient client = new ClaudeCliLlmClient(
                List.of("sh", "-c", "cat > /dev/null; sleep 30"), Duration.ofSeconds(2));

        assertThatThrownBy(() -> client.complete("무엇이든"))
                .isInstanceOf(LlmClient.LlmUnavailable.class)
                .hasMessageContaining("2초");
    }

    @Test
    @DisplayName("없는 명령도 예외로 알린다")
    void reportsAMissingCommand() {
        // CLI 가 설치되지 않은 환경에서 Reviewer 를 켜면 여기로 온다.
        LlmClient client = new ClaudeCliLlmClient(
                List.of("codesprint-no-such-binary"), Duration.ofSeconds(5));

        assertThatThrownBy(() -> client.complete("무엇이든"))
                .isInstanceOf(LlmClient.LlmUnavailable.class);
    }
}
