-- 채점이 남긴 표준 에러를 제출 행에 옮겨 적는다. 정본 근거: ADR-0013, Addendum 63.
--
-- 계약(contracts/submit-response.schema.json)에는 judge.stderr 가 있고 Worker 는
-- sanitize 해서 만든다. 그런데 저장할 자리가 없어 조회는 **언제나 null 을 돌려줬다.**
--
-- 이 저장소의 원칙에서 null 은 "확인했고 없었다" 다. 실제로는 있는데 없다고 답하고
-- 있었던 셈이고, 화면에서는 RUNTIME_ERROR 를 받고도 이유를 볼 수 없었다.
--
-- judge_jobs.result 에서 조회 시점에 꺼내지 않는다. 큐(judge_jobs)와 학습 상태
-- (submissions)를 가른 것이 ADR-0013 이고, 판정 · 통과 수 · 실행 시간은 이미 반영
-- 시점에 이쪽으로 옮겨 적는다. stderr 만 큐를 들여다보면 읽기 경로가 두 갈래가 되고,
-- 나중에 큐 행을 정리하는 순간 조용히 사라진다.
ALTER TABLE submissions
    ADD COLUMN stderr TEXT;

-- 이미 반영된 제출을 채운다.
--
-- V4 에서 배운 것과 같다 - 컬럼만 추가하고 두면 이 마이그레이션 이전에 처리된
-- 제출은 영영 "확인했고 없었다" 로 남는다. 그 값은 큐에 아직 있으므로 옮겨 온다.
--
-- 큐 행이 이미 정리된 제출은 채울 수 없다. 그건 정말로 모르는 것이고, 그때의 null 은
-- 사실이다.
UPDATE submissions s
   SET stderr = j.result ->> 'stderr'
  FROM judge_jobs j
 WHERE j.submission_id = s.id
   AND j.applied_at IS NOT NULL
   AND j.result IS NOT NULL
   AND j.result ->> 'stderr' IS NOT NULL;
