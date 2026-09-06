package dev.codesprint.reviewer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 평가 하네스가 애플리케이션과 <b>같은 설정</b>을 쓰는가.
 *
 * <p>하네스 본체는 실제 모델이 필요해 CI 에서 돌지 않는다(ADR-0016). 그래서 모델
 * 없이 확인할 수 있는 것 - 무엇을 가지고 모델을 부르는가 - 은 여기서 고정한다.
 *
 * <p>이것이 갈리면 <b>평가가 조용히 다른 것을 잰다.</b> 앱은 reviewer-v2 인데 평가는
 * v1 을 재고 있어도 어느 숫자도 이상해 보이지 않는다.
 */
class ReviewerSettingsTest {

    @Test
    @DisplayName("기본값이 application.yml 그대로다")
    void defaultsComeFromApplicationYml() {
        ReviewerSettings.Values values = ReviewerSettings.load(name -> null);

        assertThat(values.promptVersion()).isEqualTo("reviewer-v1");
        assertThat(values.timeoutSeconds()).isEqualTo(120);
        assertThat(values.command()).startsWith("claude", "-p");
    }

    @Test
    @DisplayName("빈 문자열 인자가 살아남는다")
    void emptyArgumentsSurvive() {
        // `--tools ""` 의 그 자리가 사라지면 --no-session-persistence 가 --tools 의
        // 값으로 먹혀 **도구가 살아난다**. ReviewerWiringTest 가 앱 쪽에서 보는 것을
        // 평가 경로에서도 본다.
        assertThat(ReviewerSettings.load(name -> null).command())
                .containsSequence("--tools", "")
                .containsSequence("--setting-sources", "");
    }

    @Test
    @DisplayName("--bare 는 들어 있지 않다")
    void neverBare() {
        // 이 프로젝트는 API 키를 받지 않는다. --bare 는 OAuth 와 keychain 을 읽지
        // 않으므로 넣으면 Reviewer 가 절대 붙지 않는다.
        assertThat(ReviewerSettings.load(name -> null).command()).doesNotContain("--bare");
    }

    @Test
    @DisplayName("애플리케이션이 쓰는 환경변수 이름을 그대로 본다")
    void environmentOverridesUseTheSameNames() {
        // 이름이 다르면 앱만 v2 로 바뀌고 평가는 v1 을 잰다. 실제로 그렇게 어긋나
        // 있었다 - 하네스가 CODESPRINT_REVIEWER_PROMPT 를 보고 있었다.
        Map<String, String> env = Map.of(
                "CODESPRINT_PROMPT_VERSION", "reviewer-v2",
                "CODESPRINT_REVIEWER_TIMEOUT", "300");
        ReviewerSettings.Values values = ReviewerSettings.load(env::get);

        assertThat(values.promptVersion()).isEqualTo("reviewer-v2");
        assertThat(values.timeoutSeconds()).isEqualTo(300);
    }
}
