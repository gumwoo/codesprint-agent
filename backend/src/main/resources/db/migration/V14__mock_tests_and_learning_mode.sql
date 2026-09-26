-- 모의 시험과 학습 모드. 정본: PRD §84~86 · §151, ADR-0043.

-- 학습 모드. 기본값은 NORMAL 이다 - PRD §151 이 "기본" 이라고 적은 모드다.
-- 모드는 "무엇을 내주는가" (힌트 · 유형 표시 · 개념 자료)만 바꾸고, 다음 행동과 mastery 는
-- 바꾸지 않는다(ADR-0043).
ALTER TABLE users ADD COLUMN learning_mode VARCHAR(10) NOT NULL DEFAULT 'NORMAL';
ALTER TABLE users ADD CONSTRAINT users_learning_mode_known
    CHECK (learning_mode IN ('GUIDED', 'NORMAL', 'STRICT', 'EXAM', 'FREE'));

-- 시험 하나. 끝나는 시각은 만들 때 정한다 - 시험 중에 늘일 수 없다.
-- finished_at 은 일찍 끝냈거나, 시간이 지난 시험을 다음 시험을 만들 때 닫은 시각이다.
-- 시간이 지났는데 아직 비어 있는 행도 "끝난 시험" 이다 - 끝남은 ends_at 으로 판정한다.
CREATE TABLE mock_tests (
    id           BIGSERIAL    PRIMARY KEY,
    user_id      BIGINT       NOT NULL REFERENCES users (id),
    started_at   TIMESTAMPTZ  NOT NULL,
    ends_at      TIMESTAMPTZ  NOT NULL,
    finished_at  TIMESTAMPTZ,
    CONSTRAINT mock_tests_ends_after_start CHECK (ends_at > started_at)
);

-- 닫히지 않은 시험은 사용자당 하나다. 두 요청이 나란히 "진행 중인 시험이 없다" 를 보고
-- 둘 다 만들면 한쪽이 여기서 막힌다.
CREATE UNIQUE INDEX mock_tests_one_open_per_user ON mock_tests (user_id) WHERE finished_at IS NULL;

-- 시험에 든 문제. 라벨은 문제 번호 순으로 A 부터 붙인다 - 난이도 순이면 "쉬운 것부터" 가
-- 라벨을 읽는 것으로 끝난다(ADR-0043).
CREATE TABLE mock_test_problems (
    mock_test_id  BIGINT       NOT NULL REFERENCES mock_tests (id),
    label         VARCHAR(1)   NOT NULL,
    problem_code  VARCHAR(100) NOT NULL,
    PRIMARY KEY (mock_test_id, label),
    CONSTRAINT mock_test_problems_one_per_code UNIQUE (mock_test_id, problem_code),
    CONSTRAINT mock_test_problems_label_known CHECK (label IN ('A', 'B', 'C', 'D'))
);

-- 시험 중에 사용자가 서버에 보낸 순간들. **이것이 시간 관리 평가의 정본이다.**
-- 주입된 시계로 남긴다 - 실행 큐의 created_at 은 DB 시계라 섞으면 "시작 후 몇 초" 가 틀린다.
-- 제출은 그 제출 행을 가리켜야 하고, 다른 것은 가리키지 않는다.
CREATE TABLE mock_test_events (
    id             BIGSERIAL    PRIMARY KEY,
    mock_test_id   BIGINT       NOT NULL,
    label          VARCHAR(1)   NOT NULL,
    kind           VARCHAR(10)  NOT NULL,
    submission_id  BIGINT       REFERENCES submissions (id),
    occurred_at    TIMESTAMPTZ  NOT NULL,
    FOREIGN KEY (mock_test_id, label) REFERENCES mock_test_problems (mock_test_id, label),
    CONSTRAINT mock_test_events_kind_known CHECK (kind IN ('OPENED', 'RUN', 'SUBMITTED')),
    CONSTRAINT mock_test_events_submission_matches_kind
        CHECK ((kind = 'SUBMITTED') = (submission_id IS NOT NULL))
);

CREATE INDEX mock_test_events_by_test ON mock_test_events (mock_test_id, occurred_at);
CREATE INDEX mock_test_events_by_submission ON mock_test_events (submission_id);
