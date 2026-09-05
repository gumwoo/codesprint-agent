package dev.codesprint.learning.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 인증이 붙기 전까지 userId 는 요청에서 온다. 존재 확인은 여기서 한다. */
public interface UserRepository extends JpaRepository<UserRow, Long> {

    Optional<UserRow> findByEmail(String email);
}
