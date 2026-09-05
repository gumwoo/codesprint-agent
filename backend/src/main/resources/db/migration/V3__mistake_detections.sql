-- Reviewer 가 탐지한 Mistake 기록. 정본 근거: Addendum §19~21, ADR-0014.
--
-- **확정된 것만 남기지 않는다.** 확정 조건 B("같은 Mistake 가 최근 3문제에서 2회
-- 이상")를 세려면 확정되지 않은 탐지도 남아 있어야 한다. 그리고 나중에 Reviewer
-- 정확도를 측정할 때 이 행들이 라벨이 된다.
--
-- 한 제출에 primary 하나와 secondary 여러 개가 나올 수 있으므로 행이 여러 개다.

CREATE TABLE mistake_detections (
    id             BIGSERIAL PRIMARY KEY,

    submission_id  BIGINT       NOT NULL REFERENCES submissions (id),
    user_id        BIGINT       NOT NULL REFERENCES users (id),

    mistake_code   VARCHAR(60)  NOT NULL,

    -- primary 하나와 secondary 들을 구분한다. 재발 계수와 자동 드릴은
    -- primary 만 본다 - secondary 까지 세면 "곁다리로 언급된 것" 이 확정을 만든다.
    role           VARCHAR(20)  NOT NULL,

    -- Reviewer 가 낸 값. 시스템이 고치지 않는다.
    confidence     NUMERIC(5,4) NOT NULL,

    -- 시스템이 부여한다. LLM 이 스스로 CONFIRMED 를 선언할 수 없다(ADR-0001).
    status         VARCHAR(20)  NOT NULL,

    -- 왜 그 status 인가. 감사 로그다 - 사용자가 왜 이 드릴을 받았는지 추적한다.
    reason         TEXT,

    -- 어떤 프롬프트가 만든 분석인가. 이것이 없으면 나중에 프롬프트를 바꿨을 때
    -- 이전 라벨과 이후 라벨을 섞어 정확도를 재게 된다(PRD §135).
    prompt_version VARCHAR(40)  NOT NULL,

    detected_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT mistake_detections_role_known CHECK (role IN ('PRIMARY', 'SECONDARY')),
    CONSTRAINT mistake_detections_status_known CHECK (status IN (
        'LOGGED_ONLY', 'POSSIBLE', 'PROBABLE', 'CONFIRMED'
    )),
    CONSTRAINT mistake_detections_confidence_range CHECK (confidence BETWEEN 0 AND 1),

    -- 같은 제출에 같은 Mistake 가 두 번 들어오지 않는다. Reviewer 를 재시도해도
    -- 재발 계수가 부풀지 않는다 - 그 값이 확정 조건 B 를 좌우한다.
    CONSTRAINT mistake_detections_unique UNIQUE (submission_id, mistake_code)
);

-- 재발을 셀 때의 경로. 최근 것부터 훑는다.
CREATE INDEX idx_mistake_detections_recent
    ON mistake_detections (user_id, mistake_code, detected_at DESC);

-- 제출 하나의 분석을 통째로 읽는 경로 (조회 API).
CREATE INDEX idx_mistake_detections_submission ON mistake_detections (submission_id);

-- 제출에 Reviewer 분석 결과를 붙인다.
--
-- mistake_detections 와 따로 두는 이유는 둘이 다른 것을 답하기 때문이다.
-- 저쪽은 "어떤 Mistake 가 몇 번 탐지됐는가"(재발 계수), 이쪽은 "이 제출의 응답에
-- 무엇을 담아 돌려줄 것인가" 다.
ALTER TABLE submissions
    ADD COLUMN review_primary_mistake VARCHAR(60),
    ADD COLUMN review_confidence      NUMERIC(5,4),
    ADD COLUMN review_status          VARCHAR(20),
    ADD COLUMN review_explanation     TEXT,
    ADD COLUMN review_secondary       JSONB,
    ADD COLUMN prompt_version         VARCHAR(40);

ALTER TABLE submissions
    ADD CONSTRAINT submissions_review_status_known CHECK (
        review_status IS NULL OR review_status IN (
            'LOGGED_ONLY', 'POSSIBLE', 'PROBABLE', 'CONFIRMED'
        )
    ),
    ADD CONSTRAINT submissions_review_confidence_range CHECK (
        review_confidence IS NULL OR review_confidence BETWEEN 0 AND 1
    ),
    -- Reviewer 를 불렀으면 네 값이 함께 있다. 하나만 남으면 응답이 계약을 어긴다 -
    -- review 객체는 모든 필드가 required 다.
    ADD CONSTRAINT submissions_review_is_complete CHECK (
        (review_primary_mistake IS NULL AND review_confidence IS NULL
             AND review_status IS NULL AND prompt_version IS NULL)
        OR (review_primary_mistake IS NOT NULL AND review_confidence IS NOT NULL
             AND review_status IS NOT NULL AND prompt_version IS NOT NULL)
    );
