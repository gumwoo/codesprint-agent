-- Java · C++ 채점(ADR-0045). 큐가 받는 언어를 넓힌다.
--
-- 언어마다 이미지가 따로 있고, 하네스는 이미지가 정한 언어로만 돈다. 그래서 여기서 받는 값은 Worker 가
-- 이미지를 고르는 데 쓰는 것뿐이다 - 모르는 값이 들어가면 이미지를 고를 수 없어 채점하지 않는다.
ALTER TABLE judge_jobs DROP CONSTRAINT judge_jobs_language_supported;
ALTER TABLE judge_jobs ADD CONSTRAINT judge_jobs_language_supported
    CHECK (language IN ('PYTHON', 'JAVA', 'CPP'));
