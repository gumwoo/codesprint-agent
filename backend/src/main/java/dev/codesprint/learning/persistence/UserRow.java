package dev.codesprint.learning.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** users 한 행. 인증은 아직 없다 - 슬라이스 1 은 userId 를 요청에서 받는다. */
@Entity
@Table(name = "users")
public class UserRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(nullable = false, length = 100)
    private String nickname;

    /** curriculum/tracks.yaml 의 code. 켜지는 Skill 범위를 정한다(ADR-0035). */
    @Column(nullable = false, length = 30)
    private String track;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected UserRow() {
    }

    public UserRow(String email, String nickname, String track) {
        this.email = email;
        this.nickname = nickname;
        this.track = track;
    }

    public Long id() {
        return id;
    }

    public String email() {
        return email;
    }

    public String nickname() {
        return nickname;
    }

    public String track() {
        return track;
    }

    public void changeTrack(String track) {
        this.track = track;
    }
}
