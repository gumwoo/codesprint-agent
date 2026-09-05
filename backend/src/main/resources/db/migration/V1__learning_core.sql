-- 학습 상태의 최소 골격. 정본: Addendum §73~76.
--
-- 이 마이그레이션이 만드는 것은 세 가지다.
--   submissions      제출 기록 (Judge 결과가 붙는다)
--   skill_evidence   학습의 정본. append-only
--   user_skills      Evidence 로부터 재계산된 캐시
--
-- skill_evidence 가 정본이고 user_skills 는 파생값이다(ADR-0009).
-- 두 값이 어긋나면 Evidence 가 이긴다.

CREATE TABLE users (
    id          BIGSERIAL PRIMARY KEY,
    email       VARCHAR(255) NOT NULL UNIQUE,
    nickname    VARCHAR(100) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 문제는 아직 파일(problems/)에 있다. 여기에는 code 만 두고 본문은 두지 않는다.
-- 공개 저장소의 문제는 전부 DEV_FIXTURE 라 그대로 옮겨오면 안 된다(ADR-0008).
CREATE TABLE problems (
    id           BIGSERIAL PRIMARY KEY,
    code         VARCHAR(100) NOT NULL UNIQUE,
    source       VARCHAR(20)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT problems_source_known CHECK (source IN ('DEV_FIXTURE', 'CURATED'))
);

CREATE TABLE submissions (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT      NOT NULL REFERENCES users (id),
    problem_id    BIGINT      NOT NULL REFERENCES problems (id),
    language      VARCHAR(20) NOT NULL,

    -- 판정. 값 목록은 contracts/judge-result.schema.json 과 같아야 한다.
    -- CHECK 로 박아두는 이유는, 오타 난 status 가 들어오면 Evidence 매핑이
    -- 관측값 없는 Evidence 를 만들어 confidence 만 올리기 때문이다.
    status        VARCHAR(20) NOT NULL,
    passed        INTEGER,
    total         INTEGER,
    execution_ms  INTEGER,
    memory_kb     INTEGER,
    failed_case_id INTEGER,

    -- 힌트 의존도. 같은 AC 라도 이 값에 따라 독립 풀이 점수가 갈린다(Addendum §11).
    hint_level      INTEGER     NOT NULL DEFAULT 0,
    solution_viewed BOOLEAN     NOT NULL DEFAULT FALSE,
    solve_seconds   INTEGER,

    submitted_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT submissions_status_known CHECK (status IN (
        'QUEUED', 'RUNNING',
        'ACCEPTED', 'WRONG_ANSWER', 'TIME_LIMIT', 'MEMORY_LIMIT',
        'RUNTIME_ERROR', 'COMPILE_ERROR', 'OUTPUT_LIMIT', 'SYSTEM_ERROR'
    )),
    CONSTRAINT submissions_hint_level_range CHECK (hint_level BETWEEN 0 AND 6)
);

CREATE INDEX idx_submissions_user_problem ON submissions (user_id, problem_id);

-- 학습의 정본. append-only 이며 갱신하지 않는다.
CREATE TABLE skill_evidence (
    id                BIGSERIAL PRIMARY KEY,

    -- 멱등성 키. Judge Worker 재시도로 같은 제출이 두 번 처리되면 같은 Evidence 가
    -- 두 번 들어온다. 그대로 쌓으면 EMA 가 두 번 적용되고 confidence 도 두 번
    -- 오른다 - 재시도가 사용자 점수를 바꾼다(ADR-0009).
    source_event_id   VARCHAR(200) NOT NULL,
    evidence_id       VARCHAR(64)  NOT NULL,

    user_id           BIGINT       NOT NULL REFERENCES users (id),
    skill_code        VARCHAR(100) NOT NULL,
    evidence_type     VARCHAR(40)  NOT NULL,

    -- 시간대가 반드시 있어야 한다. EMA 는 순서 계산이라 재계산 시 시간순 정렬이
    -- 정확해야 하는데, naive timestamp 는 어느 지역 것인지 알 수 없다.
    -- TIMESTAMPTZ 가 그것을 타입으로 강제한다.
    occurred_at       TIMESTAMPTZ  NOT NULL,

    weight            NUMERIC(6,4) NOT NULL,
    source_confidence NUMERIC(5,4),

    -- 관측하지 못한 차원은 NULL 이다. 0 과 다르다 - "아직 안 봤다" 를 "못한다" 로
    -- 계산하면 초반 mastery 가 부당하게 낮아진다(Addendum §4, §6).
    observed_concept        NUMERIC(5,4),
    observed_recognition    NUMERIC(5,4),
    observed_implementation NUMERIC(5,4),
    observed_independent    NUMERIC(5,4),
    observed_retention      NUMERIC(5,4),
    observed_speed          NUMERIC(5,4),

    context           JSONB        NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- 한 제출이 Skill 3개에 Evidence 를 남기면 source_event_id 는 같고
    -- skill_code 가 다르다. 유일성은 두 값의 짝이다.
    CONSTRAINT skill_evidence_source_unique UNIQUE (user_id, source_event_id, skill_code),

    -- 관측값이 하나도 없는 Evidence 는 mastery 를 바꾸지 않으면서 confidence 와
    -- evidence_count 만 올린다. 측정한 게 없는데 측정 신뢰도가 오르는 상태이며
    -- 그대로 MASTERED 문턱을 넘을 수 있다.
    CONSTRAINT skill_evidence_has_observation CHECK (
        observed_concept IS NOT NULL
        OR observed_recognition IS NOT NULL
        OR observed_implementation IS NOT NULL
        OR observed_independent IS NOT NULL
        OR observed_retention IS NOT NULL
        OR observed_speed IS NOT NULL
    ),
    CONSTRAINT skill_evidence_type_known CHECK (evidence_type IN (
        'DIAGNOSTIC_RESULT', 'PROBLEM_SUBMISSION', 'MICRO_DRILL_RESULT',
        'REVIEW_RESULT', 'CONCEPT_CHECK', 'EXPLAIN_BACK', 'MOCK_TEST_RESULT'
    )),

    -- 값 범위를 DB 에서도 막는다.
    --
    -- 계약(skill-evidence.schema.json)이 0~1 을 요구하지만, 그 검증을 통과하지 않는
    -- 경로로 들어오는 값이 있을 수 있다 - 다른 서비스, 마이그레이션 스크립트, 수동 INSERT.
    -- user_skills 는 캐시라 다시 계산하면 되지만 **skill_evidence 는 정본이고
    -- append-only 다.** 잘못 저장되면 지울 수 없고, 그 값으로 접은 mastery 가
    -- 계속 나온다.
    --
    -- 실제로 확인했다. 제약이 없을 때 weight=-5 / source_confidence=-1.0 /
    -- observed_implementation=3.5 가 그대로 들어갔다.
    CONSTRAINT skill_evidence_weight_non_negative CHECK (weight >= 0),
    CONSTRAINT skill_evidence_source_confidence_range CHECK (
        source_confidence IS NULL OR source_confidence BETWEEN 0 AND 1
    ),
    CONSTRAINT skill_evidence_observed_range CHECK (
        (observed_concept        IS NULL OR observed_concept        BETWEEN 0 AND 1)
        AND (observed_recognition    IS NULL OR observed_recognition    BETWEEN 0 AND 1)
        AND (observed_implementation IS NULL OR observed_implementation BETWEEN 0 AND 1)
        AND (observed_independent    IS NULL OR observed_independent    BETWEEN 0 AND 1)
        AND (observed_retention      IS NULL OR observed_retention      BETWEEN 0 AND 1)
        AND (observed_speed          IS NULL OR observed_speed          BETWEEN 0 AND 1)
    )
);

-- 재계산은 (사용자, Skill) 의 Evidence 를 시간순으로 훑는다.
CREATE INDEX idx_skill_evidence_replay ON skill_evidence (user_id, skill_code, occurred_at, id);

-- Evidence 로부터 재계산된 캐시. 정본이 아니다.
CREATE TABLE user_skills (
    user_id     BIGINT       NOT NULL REFERENCES users (id),
    skill_code  VARCHAR(100) NOT NULL,

    concept_score        NUMERIC(5,4),
    recognition_score    NUMERIC(5,4),
    implementation_score NUMERIC(5,4),
    independent_score    NUMERIC(5,4),
    retention_score      NUMERIC(5,4),
    speed_score          NUMERIC(5,4),

    -- 신규 Skill 은 NULL 이다. status 가 UNASSESSED 인 상태와 짝을 이룬다.
    mastery_score   NUMERIC(5,4),
    confidence_score NUMERIC(5,4) NOT NULL DEFAULT 0,
    evidence_count  INTEGER      NOT NULL DEFAULT 0,

    status          VARCHAR(30)  NOT NULL DEFAULT 'UNASSESSED',
    last_studied_at TIMESTAMPTZ,
    next_review_at  TIMESTAMPTZ,
    recomputed_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),

    PRIMARY KEY (user_id, skill_code),

    CONSTRAINT user_skills_status_known CHECK (status IN (
        'UNASSESSED', 'READY', 'LEARNING', 'PRACTICING',
        'MASTERED', 'REVIEW_DUE', 'WEAKENED', 'LOCKED'
    )),
    -- mastery 가 NULL 이면 아직 평가되지 않은 상태여야 한다.
    --
    -- 양방향 동치(NULL <=> UNASSESSED)로 쓰면 안 된다. LOCKED 와 READY 는 Evidence 가
    -- 아니라 **선수 관계**에서 나오는 상태라(ADR-0009), 신규 사용자의 잠긴 Skill 은
    -- mastery 가 NULL 인 채로 LOCKED 다. 동치로 묶으면 그 정상 상태를 저장할 수 없다.
    --
    -- 반대로 mastery 가 있는데 LOCKED 인 것은 가능하다 - 다른 문제의 SECONDARY Skill 로
    -- Evidence 가 쌓였는데 선수 조건은 아직 못 채운 경우다. 그래서 mastery 가 있을 때는
    -- UNASSESSED 만 막는다.
    CONSTRAINT user_skills_mastery_matches_status CHECK (
        (mastery_score IS NULL AND status IN ('UNASSESSED', 'LOCKED', 'READY'))
        OR (mastery_score IS NOT NULL AND status <> 'UNASSESSED')
    ),

    -- Evidence 가 있는데 mastery 가 없을 수는 없다. 모든 Evidence 는 관측값을
    -- 최소 하나 갖기 때문이다(skill_evidence_has_observation). 어긋나면 캐시가 낡은 것이다.
    CONSTRAINT user_skills_evidence_implies_mastery CHECK (
        evidence_count = 0 OR mastery_score IS NOT NULL
    ),

    CONSTRAINT user_skills_score_range CHECK (
        (concept_score        IS NULL OR concept_score        BETWEEN 0 AND 1)
        AND (recognition_score    IS NULL OR recognition_score    BETWEEN 0 AND 1)
        AND (implementation_score IS NULL OR implementation_score BETWEEN 0 AND 1)
        AND (independent_score    IS NULL OR independent_score    BETWEEN 0 AND 1)
        AND (retention_score      IS NULL OR retention_score      BETWEEN 0 AND 1)
        AND (speed_score          IS NULL OR speed_score          BETWEEN 0 AND 1)
        AND (mastery_score        IS NULL OR mastery_score        BETWEEN 0 AND 1)
        AND confidence_score BETWEEN 0 AND 1
    )
);
