package dev.codesprint.api;

import dev.codesprint.learning.persistence.UserRepository;
import dev.codesprint.learning.persistence.UserRow;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사용자를 만든다. <b>인증이 아니다.</b>
 *
 * <p>슬라이스 1 에는 인증이 없고 {@code userId} 를 요청 본문에서 받는다. 그런데
 * 화면에서 시작하려면 그 id 가 하나는 있어야 하는데, 만들 방법이 없어 <b>새 DB 에서는
 * 아무것도 할 수 없었다</b> - 화면을 실제로 띄워 보고 알았다.
 *
 * <p>여기서 만드는 것은 계정이 아니라 <b>학습 이력을 매달 자리</b>다. 비밀번호도
 * 세션도 없다. 인증이 붙으면 이 엔드포인트는 사라지고, {@code userId} 는 요청 본문이
 * 아니라 토큰에서 온다.
 *
 * <p>그때까지는 <b>아무나 아무 id 로 제출할 수 있다.</b> 이 저장소는 배포하지 않으며
 * 만든 사람이 자기 PC 에서 돌린다는 전제 위에 있다.
 */
@RestController
@RequestMapping("/api")
@Validated
public class UserController {

    private final UserRepository users;

    public UserController(UserRepository users) {
        this.users = users;
    }

    /**
     * @param nickname 화면에 보일 이름. 이메일은 여기서 만들어 준다 - 슬라이스 1 이
     *     쓰지 않는 값을 사용자에게 물으면, 나중에 인증이 붙었을 때 진짜 이메일과
     *     구분되지 않는다.
     */
    public record CreateUserRequest(
            @NotBlank @Size(max = 100) String nickname) {
    }

    public record CreatedUser(long userId, String nickname) {
    }

    @PostMapping("/users")
    public ResponseEntity<CreatedUser> create(@RequestBody @Validated CreateUserRequest request) {
        // 이메일은 유일해야 한다. 같은 이름으로 여러 번 시작할 수 있어야 하므로
        // 이름이 아니라 만든 시각으로 가른다.
        String email = "local-" + System.nanoTime() + "@codesprint.invalid";
        UserRow saved = users.save(new UserRow(email, request.nickname()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CreatedUser(saved.id(), saved.nickname()));
    }
}
