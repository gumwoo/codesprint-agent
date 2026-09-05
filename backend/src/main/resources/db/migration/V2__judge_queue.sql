-- 채점 큐. 정본 근거: ADR-0013, Addendum §47 · §67~68.
--
-- 채점은 API 요청 스레드 밖에서 일어난다. 요청은 이 테이블에 행 하나를 넣고 끝나고,
-- Python Worker 가 꺼내 컨테이너에서 실행한 뒤 결과를 같은 행에 쓴다.
--
-- 이 테이블이 **언어 경계**다. 행의 모양은 contracts/judge-job.schema.json 이 정본이며
-- Java 가 쓰고 Python 이 읽는다.
--
-- 메시지 브로커를 두지 않은 이유는 ADR-0013 에 적었다. 요약하면, 제출과 큐가 같은
-- 트랜잭션에 들어가야 "제출은 저장됐는데 큐에 못 넣었다" 를 따로 다루지 않아도 된다.

CREATE TABLE judge_jobs (
    id             BIGSERIAL PRIMARY KEY,

    -- 결과를 되돌릴 곳. 한 제출에 job 은 하나다 - 재시도는 attempts 로 세지
    -- 행을 새로 만들지 않는다. 행이 늘면 같은 제출의 Evidence 가 두 번 쌓일 수 있다.
    submission_id  BIGINT       NOT NULL UNIQUE REFERENCES submissions (id),

    problem_code   VARCHAR(100) NOT NULL,
    language       VARCHAR(20)  NOT NULL,

    -- 사용자가 낸 코드. 신뢰할 수 없는 입력이며 여기서는 운반만 한다.
    source_code    TEXT         NOT NULL,

    status         VARCHAR(20)  NOT NULL DEFAULT 'QUEUED',

    -- 몇 번 집어갔는가. 리스가 만료되어 다시 배정되면 늘어난다.
    -- 상한이 없으면 계속 죽는 job 하나가 큐를 영원히 막는다.
    attempts       INTEGER      NOT NULL DEFAULT 0,

    -- 이 시각까지는 집어간 Worker 의 것이다. Worker 가 죽어도 지나면 다른 Worker 가
    -- 가져간다 - 그러지 않으면 job 이 영원히 RUNNING 으로 남는다.
    lease_expires_at TIMESTAMPTZ,

    -- 채점 결과. 끝나지 않았으면 NULL 이다.
    -- 값의 모양은 contracts/judge-result.schema.json 이다.
    result         JSONB,

    -- 채점 자체가 불가능했던 이유. 사용자 문구가 아니라 운영 로그다.
    failure_reason TEXT,

    -- 결과가 학습 상태(Evidence / mastery / 다음 행동)에 반영됐는가.
    --
    -- status 와 따로 두는 이유는 둘이 다른 것을 말하기 때문이다. DONE 은 "채점이
    -- 끝났다", 이 값은 "그 결과를 우리가 처리했다" 이다. 하나로 합치면 반영 도중
    -- 실패했을 때 다시 시도할 방법이 없다.
    applied_at     TIMESTAMPTZ,

    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT judge_jobs_status_known CHECK (status IN ('QUEUED', 'RUNNING', 'DONE', 'FAILED')),
    CONSTRAINT judge_jobs_attempts_non_negative CHECK (attempts >= 0),

    -- 슬라이스 1 은 Python 뿐이다. Worker 가 무엇이든 solution.py 로 써서 Python 으로
    -- 돌리므로, 다른 언어가 큐에 들어가면 language 와 실제 판정이 어긋난 기록이 남는다.
    -- API 에서 400 으로 막지만, 그 경로를 거치지 않는 INSERT 를 여기서 막는다.
    CONSTRAINT judge_jobs_language_supported CHECK (language IN ('PYTHON')),

    -- 끝난 job 은 결과나 실패 이유 중 하나를 반드시 갖는다.
    -- 둘 다 없이 DONE 이면 "채점했는데 아무것도 모른다" 가 되고, 그 상태로는
    -- Evidence 를 만들 수도 사용자에게 보여줄 수도 없다.
    CONSTRAINT judge_jobs_terminal_has_outcome CHECK (
        status NOT IN ('DONE', 'FAILED')
        OR result IS NOT NULL
        OR failure_reason IS NOT NULL
    ),

    -- 반영은 끝난 job 에만 일어난다.
    CONSTRAINT judge_jobs_applied_only_when_terminal CHECK (
        applied_at IS NULL OR status IN ('DONE', 'FAILED')
    )
);

-- Worker 가 집어갈 것을 찾는 경로. 오래 기다린 것부터 가져간다.
CREATE INDEX idx_judge_jobs_claimable ON judge_jobs (status, lease_expires_at, id);

-- 결과를 학습 상태에 반영할 것을 찾는 경로.
CREATE INDEX idx_judge_jobs_unapplied ON judge_jobs (applied_at, status)
    WHERE applied_at IS NULL;

-- 결정 결과를 제출 행에 남긴다.
--
-- 채점이 요청 밖으로 나가면서 사용자는 나중에 결과를 다시 물어본다. 그때 다음 행동을
-- 새로 계산하면 안 된다 - Decision 은 그 시점의 상태에 대한 판단이고, 나중에 다시
-- 돌리면 그 사이 다른 제출이 바꿔 놓은 상태를 보게 된다. 같은 제출을 두 번 조회했을
-- 때 다른 답이 나오는 것이다.
--
-- skill_updates 도 같은 이유다. before 값(이번 제출 전의 mastery)은 반영하는 그
-- 순간에만 알 수 있고, 지나가면 복원할 방법이 없다.
ALTER TABLE submissions
    ADD COLUMN next_action_type   VARCHAR(30),
    ADD COLUMN next_action_target VARCHAR(100),
    ADD COLUMN next_action_reason TEXT,
    ADD COLUMN skill_updates      JSONB;

ALTER TABLE submissions
    ADD CONSTRAINT submissions_next_action_known CHECK (
        next_action_type IS NULL OR next_action_type IN (
            'CONTINUE', 'HARDER', 'EASIER', 'MICRO_DRILL', 'REVIEW_CONCEPT',
            'RETRY_VARIANT', 'CHANGE_SKILL', 'UNLOCK_NEXT', 'SCHEDULE_REVIEW',
            'MOCK_TEST', 'END_SESSION'
        )
    );
