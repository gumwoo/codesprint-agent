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

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected UserRow() {
    }

    public UserRow(String email, String nickname) {
        this.email = email;
        this.nickname = nickname;
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
}
