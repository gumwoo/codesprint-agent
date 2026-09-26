package dev.codesprint.mocktest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 시험 중에는 학습 상태 화면을 닫는다. 정본: PRD §84, ADR-0043.
 *
 * <p><b>왜 닫는가.</b> 시험 문제는 처음 보는 · 잠기지 않은 문제에서 고르는데, 오늘의 계획 · 진단 · 다음
 * 문제도 같은 조건으로 고른다 - 그대로 두면 오늘 탭이 시험 문제를 "오늘 풀 문제" 로 code 와 제목까지
 * 보여 준다. 그리고 시험에서 낸 제출은 평소처럼 Evidence 가 되므로, 제출 전후의 Skill 지도를 비교하면
 * 그 문제의 유형이 드러난다(검증 에이전트가 둘 다 재현했다).
 *
 * <p>Evidence 반영을 끝날 때까지 미루지 않고 화면을 닫는 쪽을 택했다. 미루면 채점 결과를 붙들어 둘 자리가
 * 하나 더 생기고, 그 사이 끊긴 반영을 다시 이을 규칙이 필요하다. 닫는 것은 한 곳에서 끝나고, 시험이
 * 끝나면 바로 열린다.
 *
 * <p>컨트롤러마다 따로 막지 않고 여기 한 곳에 둔다 - 따로 두면 새 화면이 생길 때 하나를 빠뜨린다.
 */
@Configuration
public class ExamLock implements WebMvcConfigurer {

    /** 시험 중에 닫는 사용자 화면. 사용자의 학습 상태 · 추천을 보여 주는 것 전부다. */
    public static final String[] LOCKED = {
        "/api/users/*/today",
        "/api/users/*/mistakes",
        "/api/users/*/skills",
        "/api/users/*/reviews",
        "/api/users/*/diagnostic",
    };

    private final MockTestService mockTests;

    public ExamLock(MockTestService mockTests) {
        this.mockTests = mockTests;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                    Object handler) throws Exception {
                @SuppressWarnings("unchecked")
                Map<String, String> variables = (Map<String, String>) request.getAttribute(
                        HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
                String userId = variables == null ? null : variables.get("userId");
                if (userId == null || !userId.matches("[0-9]{1,18}")
                        || !mockTests.inProgress(Long.parseLong(userId))) {
                    return true;
                }
                response.setStatus(HttpStatus.CONFLICT.value());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                response.getWriter().write(
                        "{\"message\":\"시험 중에는 학습 상태와 추천을 보여 주지 않는다 - 끝나면 열린다\"}");
                return false;
            }
        }).addPathPatterns(LOCKED);
    }
}
