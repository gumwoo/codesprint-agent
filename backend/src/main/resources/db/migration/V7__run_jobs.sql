-- 제출 전 실행(Run). 근거: ADR-0020.
--
-- 같은 큐를 쓴다. 큐의 정본은 judge_jobs 하나이고(ADR-0013), 표를 하나 더 만들면
-- 언어 경계가 둘이 된다 - 리스 · 재시도 · fencing 을 두 벌 유지하게 된다.
--
-- 대신 **실행이 학습 상태에 닿을 수 없다는 것을 행 모양으로 보장한다.** RUN 행은
-- submission_id 를 가질 수 없다. Poller 가 결과를 반영하는 경로는 그 값으로만
-- 이어지므로, 코드가 실수해도 갈 곳이 없다.
--
-- 코드에서 kind 를 걸러 주는 것만으로는 부족하다. 그 검사는 지워질 수 있고,
-- 지워지면 실행 한 번이 Evidence 가 되어 mastery 를 깎는다.

ALTER TABLE judge_jobs
    ADD COLUMN kind VARCHAR(10) NOT NULL DEFAULT 'SUBMIT';

ALTER TABLE judge_jobs
    ALTER COLUMN submission_id DROP NOT NULL;

ALTER TABLE judge_jobs
    ADD CONSTRAINT judge_jobs_kind_known CHECK (kind IN ('SUBMIT', 'RUN'));

-- 제출은 반드시 제출을 가리키고, 실행은 반드시 가리키지 않는다.
ALTER TABLE judge_jobs
    ADD CONSTRAINT judge_jobs_submission_matches_kind CHECK (
        (kind = 'SUBMIT' AND submission_id IS NOT NULL)
        OR (kind = 'RUN' AND submission_id IS NULL)
    );

-- 실행은 누구의 것인지만 알면 된다. 남의 실행 결과를 보지 못하게 하는 데 쓴다.
ALTER TABLE judge_jobs
    ADD COLUMN user_id BIGINT REFERENCES users (id);

ALTER TABLE judge_jobs
    ADD CONSTRAINT judge_jobs_run_has_user CHECK (
        kind = 'SUBMIT' OR user_id IS NOT NULL
    );
