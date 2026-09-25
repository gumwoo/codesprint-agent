-- 사용자의 학습 트랙(목표별 활성 Skill 범위). 정본: PRD §129, ADR-0035.
--
-- 값은 curriculum/tracks.yaml 의 code 다. CHECK 로 박지 않는다 - 트랙은 커리큘럼 데이터라
-- 여기 적으면 정본이 둘이 된다. 없는 트랙은 애플리케이션이 400 으로 막는다.
--
-- 이미 있는 사용자는 슬라이스 1 이 가르치던 범위(BFS 까지)를 담는 JOB 으로 둔다. 그 뒤
-- 기본값을 걷는다 - 새 사용자는 목표를 **골라야** 한다. 기본값이 남으면 고르지 않은
-- 사용자와 JOB 을 고른 사용자가 구별되지 않는다.
ALTER TABLE users ADD COLUMN track VARCHAR(30) NOT NULL DEFAULT 'JOB';
ALTER TABLE users ALTER COLUMN track DROP DEFAULT;
