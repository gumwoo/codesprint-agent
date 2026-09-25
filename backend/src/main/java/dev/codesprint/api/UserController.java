package dev.codesprint.api;

import dev.codesprint.curriculum.CurriculumCatalog;
import dev.codesprint.learning.persistence.UserRepository;
import dev.codesprint.learning.persistence.UserRow;
import java.util.List;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
    private final CurriculumCatalog catalog;

    public UserController(UserRepository users, CurriculumCatalog catalog) {
        this.users = users;
        this.catalog = catalog;
    }

    /**
     * @param nickname 화면에 보일 이름. 이메일은 여기서 만들어 준다 - 인증이 붙었을 때 진짜
     *     이메일과 구분되지 않게 하려고 사용자에게 묻지 않는다.
     * @param track 학습 트랙(ADR-0035). <b>기본값이 없다</b> - 목표를 고르지 않은 사용자와
     *     일반 취업 트랙을 고른 사용자가 구별되지 않게 되기 때문이다. 빠지면 400 이다.
     */
    public record CreateUserRequest(
            @NotBlank @Size(max = 100) String nickname,
            @NotBlank String track) {
    }

    /** 계약: contracts/user.schema.json. */
    /**
     * @param dailyMinutes 하루 공부 시간(분). 정하지 않았으면 null
     * @param examDate 시험일(YYYY-MM-DD). 없으면 null
     */
    public record UserView(long userId, String nickname, String track, Integer dailyMinutes,
            String examDate) {
    }

    public record ChangeTrackRequest(@NotBlank String track) {
    }

    /** 계약: contracts/track-list.schema.json. */
    public record TrackView(String code, String name, String description, int skillCount) {
    }

    public record TrackListResponse(List<TrackView> tracks) {
    }

    @PostMapping("/users")
    public ResponseEntity<UserView> create(@RequestBody @Validated CreateUserRequest request) {
        if (catalog.track(request.track()) == null) {
            return ResponseEntity.badRequest().build();
        }
        // 이메일은 유일해야 한다. 같은 이름으로 여러 번 시작할 수 있어야 하므로
        // 이름이 아니라 만든 시각으로 가른다.
        String email = "local-" + System.nanoTime() + "@codesprint.invalid";
        UserRow saved = users.save(new UserRow(email, request.nickname(), request.track()));
        return ResponseEntity.status(HttpStatus.CREATED).body(view(saved));
    }

    @GetMapping("/users/{userId}")
    public ResponseEntity<UserView> get(@PathVariable Long userId) {
        return users.findById(userId).map(UserController::view)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 목표를 바꾼다. <b>Evidence 는 그대로다</b> - 켜지는 범위만 달라지고, 이미 푼 기록은
     * 새 범위에서 다시 계산된다(ADR-0009, ADR-0035).
     */
    @PutMapping("/users/{userId}/track")
    @Transactional
    public ResponseEntity<UserView> changeTrack(@PathVariable Long userId,
            @RequestBody @Validated ChangeTrackRequest request) {
        if (catalog.track(request.track()) == null) {
            return ResponseEntity.badRequest().build();
        }
        return users.findById(userId).map(user -> {
            user.changeTrack(request.track());
            return ResponseEntity.ok(view(users.save(user)));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 고를 수 있는 목표. 켜지는 Skill 수는 서버가 센다 - 화면이 세면 갈린다(ADR-0001). */
    @GetMapping("/tracks")
    public TrackListResponse tracks() {
        return new TrackListResponse(catalog.tracks().stream()
                .map(track -> new TrackView(track.code(), track.name(), track.description(),
                        catalog.skillCodesFor(track.code()).size()))
                .toList());
    }

    @PutMapping("/users/{userId}/settings")
    @Transactional
    public ResponseEntity<UserView> changeSettings(@PathVariable Long userId,
            @RequestBody com.fasterxml.jackson.databind.JsonNode body) {
        // 두 키가 모두 있어야 한다. 생략은 "모른다", null 은 "정하지 않았다" 다.
        if (body == null || !body.has("dailyMinutes") || !body.has("examDate")) {
            return ResponseEntity.badRequest().build();
        }
        Integer minutes = body.get("dailyMinutes").isNull() ? null
                : body.get("dailyMinutes").isInt() ? body.get("dailyMinutes").asInt() : -1;
        if (minutes != null && (minutes < 10 || minutes > 720)) {
            return ResponseEntity.badRequest().build();
        }
        java.time.LocalDate exam;
        try {
            exam = body.get("examDate").isNull() ? null
                    : java.time.LocalDate.parse(body.get("examDate").asText());
        } catch (java.time.format.DateTimeParseException e) {
            return ResponseEntity.badRequest().build();
        }
        return users.findById(userId).map(user -> {
            user.changeSettings(minutes, exam);
            return ResponseEntity.ok(view(users.save(user)));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static UserView view(UserRow user) {
        return new UserView(user.id(), user.nickname(), user.track(), user.dailyMinutes(),
                user.examDate() == null ? null : user.examDate().toString());
    }
}
