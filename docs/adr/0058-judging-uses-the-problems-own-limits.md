# ADR-0058 · 채점은 문제의 제한으로 돈다 - job 은 채점과 검증이 같은 함수로 만든다

- 상태: 채택
- 날짜: 2026-09-28
- 정본 근거: Addendum §51(`--memory` · `--memory-swap`), §65(case 별 timeout 은 하네스가),
  [ADR-0006](0006-expected-output-never-enters-sandbox.md), [ADR-0011](0011-language-boundary.md),
  [ADR-0013](0013-judging-happens-outside-the-request.md), [ADR-0020](0020-running-is-not-submitting.md)

> 번호: 동시에 진행 중인 다른 브랜치가 0057 을 쓸 수 있어 0058 로 잡았다.

## 맥락

검토에서 재현된 결함이다. Judge Worker 는 `problems/<CODE>/cases.json` 을 **그대로** 하네스에 넘겼다. cases.json
에는 `timeLimitMs` 가 없으므로 `run_submission.py` 의 `job.get("timeLimitMs", 2000)` 이 늘 기본값을 골랐다 -
**실서비스 채점은 모든 문제를 2000ms 로 돌았다.** 메모리는 어디서도 문제의 값을 읽지 않았고, 모든 채점이
`DOCKER_LIMITS` 의 전역 `--memory 256m` 로 돌았다(문제 116 개가 전부 256 이라 우연히 같았다).

한편 `tools/verify_problems.py` 는 `problem.yaml` 의 값으로 job 을 따로 만들어 채점했다. 검증과 실서비스가
**다른 job 을 채점한 것**이라, 검증은 이 차이를 볼 수 없었다. 화면(`ProblemCatalog`)은 `problem.yaml` 의 값을
보여 준다.

main(1d4ed20)에서 다시 재현했다. 각 문제의 `reference.py` 앞에 `time.sleep(1.5)` 를 붙여 Worker 경로
(`judge_jobs` 행 → `worker.drain`)로 채점했다.

| 문제 | problem.yaml timeLimitMs | 판정 | executionMs |
| --- | --- | --- | --- |
| `P111_DIVISOR_COUNT_QUERIES` | 1000 | `ACCEPTED` | 1721 |
| `P01_QUEUE_BASIC` | 2000 | `ACCEPTED` | 1898 |

화면에 1000ms 라고 적힌 문제에서 1721ms 가 걸린 제출이 AC 를 받았다.

같은 Worker 를 거치는 길은 넷이다 - 제출(SUBMIT), 제출 전 실행(RUN, ADR-0020), 모의 시험의 제출 · 실행
(`MockTestService` 가 `SubmissionIntakeService` · `RunService` 를 그대로 부른다). 넷 다 같은 결함이었다.

## 결정

### 1. 제한의 정본은 `problem.yaml` 하나이고, Worker 가 직접 읽는다

`judge/problem_job.py` 의 `load(problem_dir)` 가 `problem.yaml` 의 `timeLimitMs` · `memoryLimitMb` 와 `cases.json`
의 case 를 job 하나로 합친다. **Worker 와 `verify_problems` 가 이 함수 하나를 부른다**(`gen_reviewer_eval_cases`
도 `verify_problems` 를 거쳐 같은 함수를 쓴다). 따로 만들 자리가 없으므로 검증과 실서비스가 다시 갈릴 수 없다.

**기본값을 두지 않는다.** 제한이 없거나 양의 정수가 아니면 채점하지 않는다(Worker 는 `(None, 이유)` 를 내고
재시도 상한 뒤 FAILED). 결함이 바로 "없으면 2000" 이라는 조용한 기본값이었다.

job 파일은 Worker 의 호스트 임시 디렉터리에 쓴다. `run_submission.py` 는 전과 같이 제출 코드만 마운트하고
case 의 input 만 컨테이너에 보낸다 - 정답은 여전히 컨테이너에 들어가지 않는다(ADR-0006).

| 선택지 | 판단 |
| --- | --- |
| **Worker 가 `problem.yaml` 을 읽는다 (공유 함수)** | 채택. Worker 는 이미 같은 디렉터리의 cases.json 을 직접 읽고 있었다 - 제한은 그 옆에 있다. 채점과 검증이 **같은 코드**로 job 을 만든다 |
| `judge_jobs` 에 `time_limit_ms` · `memory_limit_mb` 컬럼 (Java 가 `ProblemCatalog` 에서 채움) | 버림. 마이그레이션 · 계약 · Java writer · Worker reader 네 곳을 고쳐야 하고, 그래도 `verify_problems` 는 DB 없이 `problem.yaml` 을 따로 읽는다 - **같은 값을 두 언어가 따로 파싱하는** 자리가 생겨, 이번 결함과 같은 모양의 갈림이 남는다 |
| 행에 싣되 제출 시각의 값을 얼린다 | 버림. 얼리는 이유가 없다 - case(cases.json)는 이미 채점 시각에 읽는다. 제한만 얼리면 한 채점 안에서 case 와 제한의 시점이 갈린다 |

ADR-0011 의 경계와도 맞는다. 제한은 샌드박스의 매개변수이고 샌드박스는 Python 의 몫이다. Java 는 같은 파일을
**보여 주기 위해서만** 읽는다. `judge_jobs` 의 행 모양은 바뀌지 않았으므로 계약(`judge-job.schema.json`)은
설명 한 줄만 고쳤고 마이그레이션은 없다.

### 2. 메모리 상한은 job 마다 건다 - 천장은 전역으로 남긴다

`DOCKER_LIMITS` 에서 `--memory` · `--memory-swap` 을 빼고, `memory_limits(memory_mb)` 가 job 의 값으로
`--memory <n>m --memory-swap <n>m` 을 만든다. **swap 은 memory 와 같다** - 다르면 그 차이만큼 swap 으로 상한을
우회한다(Addendum 51 이 막으려던 것). `DOCKER_LIMITS` **뒤에** 붙인다. docker 는 같은 옵션이 거듭되면 뒤의 것을
쓴다(`--memory 512m ... --memory 64m --memory-swap 64m` 로 만든 컨테이너의 `HostConfig.Memory` 가 64MiB 인 것을
확인했다) - 그래서 문제의 값이 늘 이긴다.

**천장(`MEMORY_CEILING_MB = 256`)은 전역으로 남긴다.** 문제 데이터가 넘으면 채점하지 않는다(`SYSTEM_ERROR`).
이유는 `--memory` 가 원래 막던 것이 **호스트**이기 때문이다. 문제별 값에 위를 열어 두면 데이터의 오타 하나가
제출 하나에 호스트 메모리를 내준다. 천장은 이 결정 전의 전역값 그대로라 격리가 약해지는 곳이 없다 - 문제는
그보다 낮출 수만 있다. 천장으로 **낮춰서 돌리지 않는** 이유는 기본값을 두지 않는 이유와 같다 - 화면이 보여 주는
값과 채점이 쓰는 값이 갈린다. `verify_problems` 가 같은 경로로 돌므로, 천장을 넘는 문제는 CI 에서 reference 가
`SYSTEM_ERROR` 로 걸린다.

손으로 만든 job(테스트 fixture 등)에 `memoryLimitMb` 가 없으면 천장으로 돈다 - 문제 데이터로 만든 job 에는
늘 있다. 하네스로 가는 `timeLimitMs` 의 기본값(2000)도 같은 이유로 남겨 두었다(아래 남는 위험).

부수 효과: `test_judge.py` 의 대조군(`judge_unrestricted`)은 `DOCKER_LIMITS` 를 `--memory 512m` 로 바꿔 끼우는데,
이제 뒤에 붙는 job 의 값(fixture 는 256)이 이긴다. 대조군이 걷어내려는 것은 네트워크 · 파일시스템 · 권한 쪽이고
메모리를 쓰는 격리 case 는 없다.

`MEMORY_LIMIT` 판정 방식은 그대로다 - 컨테이너 상한에 걸린 SIGKILL 과 `MemoryError` 류로 정한다(`judge/runner/harness.py` 의 판정 분기). memoryKb 가 판정에 쓰이지 않는다는 것은 ADR-0056 D 가 적었다.
하네스도 같은 cgroup 에서 세어지는 것도 전과 같다.

## 검사

`judge/tests/test_worker.py`(실물 PostgreSQL + Docker) 에 셋을 넣었다. **제한만 다른 문제 사본 둘**을 만들어
Worker 가 그 디렉터리를 보게 하고(`worker.PROBLEMS`), 같은 코드를 채점한다. 전역 값 하나로 돌면 두 판정이 같다.

- **시간** - `P01` 의 reference 앞에 `time.sleep(1.1)`. 1000ms 사본은 `TIME_LIMIT`(걸린 시간이 1000~2000ms,
  즉 옛 기본값이었다면 통과했을 시간), 2000ms 사본은 `ACCEPTED` 이고 그때도 1000ms 를 넘겨 돌았어야 한다
  (아니면 `[VACUOUS]`). **제출 전 실행(RUN)** 도 1000ms 사본에서 `TIME_LIMIT` 이어야 한다.
- **메모리** - 100MB 를 실제로 쓰는 할당 + reference. 64MB 사본은 `MEMORY_LIMIT`, 256MB 사본은 `ACCEPTED` 이고
  memoryKb 가 64MB 를 넘어야 한다.
- **기본값 없음** - `timeLimitMs` 가 null 인 사본과 `memoryLimitMb` 가 천장의 두 배인 사본은 판정을 내지 않는다.

**fix 를 되돌리면 깨진다.**

- Worker 가 cases.json 을 그대로 넘기게 되돌리면(원래 결함): 시간 사본 둘 다 `ACCEPTED`(1000ms 사본 1136~1489ms),
  RUN 도 `ACCEPTED`, 메모리 64MB 사본 `ACCEPTED`(memoryKb 194,228), 천장 초과 사본이 `WRONG_ANSWER` 로 채점됨 -
  4 건 실패.
- `memory_limits()` 만 고정 256m 로 되돌리면: 메모리 64MB 사본이 `ACCEPTED` - 1 건 실패.
- 고친 상태에서는 전부 통과한다.

## 남는 위험

- **문제별 메모리를 낮추는 데는 언어별 하한이 있다.** JVM 은 `-Xmx192m` 로 고정이고 하네스도 같은 cgroup 에서
  세어져, memoryLimitMb 를 64 로 두면 Java 정답(P01)이 MEMORY_LIMIT 다(검증 에이전트 실측, 96MB 까지는 AC). 지금은
  256 미만인 문제가 없다. 낮추려면 그 문제의 Java · C++ 정답을 같은 값으로 채점해 보고 정한다
- **깨진 문제 데이터가 Worker 를 죽일 수 있다.** problem.yaml 이나 cases.json 이 dict 가 아니면 `problem_job.load` 가
  `ProblemDataError` 가 아닌 예외를 내고 drain 밖으로 나간다(전에는 run_submission 서브프로세스 안의 SYSTEM_ERROR 였다).
  check_problems 가 CI 에서 막는 입력이다
- 테스트 대조군(`judge_unrestricted`)의 `--memory 512m` 은 뒤에 붙는 문제의 값(256m)이 이겨 효과가 없다
- `--memory-swap` 은 어느 테스트도 지키지 않는다(이전부터) - Docker Desktop VM 에서는 swap 이 실제로 쓰이지 않아
  빼도 64MB 테스트가 통과한다

- **제한은 채점 시각에 읽는다.** 제출과 채점 사이에 `problem.yaml` 이 바뀌면 새 값으로 채점된다. cases.json 과
  같은 성질이다. 백엔드의 `ProblemCatalog` 는 기동 때 읽어 두므로, 문제를 고친 뒤 백엔드를 다시 띄우기 전까지는
  화면의 값이 채점의 값보다 늦을 수 있다.
- 백엔드는 `CODESPRINT_PROBLEMS_DIR` 로 문제 디렉터리를 바꿀 수 있지만 Worker 는 늘 저장소의 `problems/` 를 본다.
  둘을 다르게 두면 화면과 채점이 다른 파일을 본다 - 이 결정 전부터 cases.json 에 있던 성질이다.
- `run_submission.py` 의 `timeLimitMs` 기본값(2000)은 남아 있다. 문제 데이터로 만든 job 은 늘 값을 싣고,
  `test_worker.py` 가 그 경로를 지킨다. 하네스 프로토콜 쪽은 다른 브랜치가 고치고 있어 여기서 건드리지 않았다.
- 문제 채택(`tools/adopt_problem.py`)은 초안을 상수(2000ms · 256MB)로 돌리고 같은 상수를 `problem.yaml` 에 쓴다.
  지금은 일치하지만 이 함수를 거치지 않는다 - 채택 뒤 `verify_problems` 가 이 함수로 다시 채점한다.
- 메모리 천장을 올리는 것은 호스트가 동시 채점 수만큼 감당하는지 보고 정할 일이다. 256 을 넘는 문제는 아직 없다.
- (뒤에 고침) 천장 초과 이유(`memoryLimitMb 512 는 1~256 이어야 한다`)가 **Windows 에서 깨진 채** `failure_reason`
  에 남았다. 이 결정과 별개로 `run_submission.py` 가 판정 JSON 을 로캘(cp949)로 쓰고 하네스 출력도 로캘로 읽었고,
  Worker 는 UTF-8 로 읽었다. 같은 원인으로 결과에 실리는 사용자 출력(실행의 stdout, 실패 판정의 stderr - 제출의 stdout 은 결과에 실리지 않는다)에
  한글 · 이모지가 있으면 `print` 가 죽어 평범한 제출이
  재시도 끝에 FAILED 가 됐다. 지금은 CLI 의 stdout · stderr 와 하네스 파이프를 UTF-8 로 고정한다.
  `test_worker.py` 의 `test_verdict_text_survives_a_non_utf8_locale` 가 자식 로캘을 UTF-8 이 아니게 만들어
  지킨다 - Linux CI 의 기본 로캘은 UTF-8 이라 그대로 두면 보이지 않는다(`judge/README.md` 의 "인코딩").
