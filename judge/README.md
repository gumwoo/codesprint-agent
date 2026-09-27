# Judge / Sandbox

사용자가 제출한 Python 코드를 **안전하게 실행하고 결정론적으로 판정한다.**
AI 는 여기 관여하지 않는다(ADR-0001, Addendum 81).

```text
worker.py                  큐에서 제출을 꺼내 채점하고 결과를 큐에 쓴다 (ADR-0013)
  └─ run_submission.py     호스트(신뢰). 컨테이너를 만들고, 정답과 비교하고, 판정을 조립한다
       └─ Dockerfile       python:3.12-slim, non-root, 하네스를 구워 넣는다
            └─ runner/harness.py  컨테이너 안(신뢰 안 함). **실행만 한다. 채점하지 않는다**

fixtures/                  판정을 재현하는 최소 문제 + 제출 코드
tests/test_judge.py        판정 9건 + 격리 8건 + 기밀성 3건
tests/test_worker.py       큐 동작 (배정 · 리스 · 재시도 상한 · 경계)
```

## Worker — 채점은 요청 밖에서 일어난다

백엔드는 제출을 저장하고 `judge_jobs` 에 행 하나를 넣고 끝난다. Worker 가 그것을 꺼내
채점하고 결과를 같은 행에 쓴다([ADR-0013](../docs/adr/0013-judging-happens-outside-the-request.md)).

```bash
python judge/worker.py --once   # 큐를 한 번 비운다
python judge/worker.py          # 계속 돈다
```

접속 정보는 환경변수다 — `CODESPRINT_DB_URL`, 또는 `DB_HOST` / `DB_PORT` / `DB_NAME` /
`DB_USER` / `DB_PASSWORD`. 백엔드와 **같은 DB** 를 본다. 큐가 곧 경계이기 때문이다.

**Worker 는 학습 상태를 건드리지 않는다.** Evidence 도, mastery 도, 다음 행동도 만들지
않는다. 그건 전부 Java 가 결과를 반영할 때 한다(ADR-0011). `test_worker.py` 가 그것을
확인한다 - 채점을 끝낸 뒤에도 `skill_evidence` 와 `user_skills` 가 비어 있어야 한다.

## 신뢰 경계 — 정답은 컨테이너에 들어가지 않는다

```text
호스트 (신뢰)                          컨테이너 (신뢰하지 않음)
─────────────                          ────────────────────────
job.json 전체
expectedOutput           ── input ──>  solution.py
정답 비교                              현재 case 만 실행
판정 조립                <── stdout ──  사용자 출력
```

**read-only 마운트는 수정을 막을 뿐 읽기를 막지 않는다.** 정답표를 컨테이너에 두면
아래 코드가 알고리즘을 한 줄도 풀지 않고 전 case 를 통과한다. 실제로 그랬다.

```python
job = json.load(open("/job/job.json"))
for case in job["cases"]:
    if case["input"] == sys.stdin.read():
        sys.stdout.write(case["expectedOutput"])
```

근거와 경위: [ADR-0006](../docs/adr/0006-expected-output-never-enters-sandbox.md)

## 실행

```bash
docker build -t codesprint-judge:py312 -f judge/Dockerfile .
python judge/run_submission.py judge/fixtures/sol-accepted.py judge/fixtures/job-grid-area.json
python judge/tests/test_judge.py --build
```

## 신뢰 경계

**사용자 코드는 신뢰할 수 없는 입력이다.** API 서버 프로세스 안에서 실행하지 않는다
(Addendum 47). 실행되는 유일한 장소는 아래 제한이 걸린 일회용 컨테이너다.

| 옵션 | 막는 것 |
| --- | --- |
| `--network none` | 데이터 유출, 원격 도구 다운로드 |
| `--memory <memoryLimitMb>m` + `--memory-swap` 같은 값 | 호스트를 끌어내리는 OOM, swap 우회. 값은 문제의 `problem.yaml` 이 정하고 천장은 `MEMORY_CEILING_MB`(256)다 - 넘으면 채점하지 않는다(ADR-0058) |
| `--cpus 0.5` | CPU 독점 |
| `--pids-limit 64` | fork bomb. 컨테이너(cgroup)마다 센다 - uid 로 세는 `RLIMIT_NPROC` 은 옆 채점까지 합산해 쓰지 않는다(ADR-0053) |
| `--read-only` | 이미지 변조로 다음 제출에 영향 |
| `--cap-drop ALL` | capability 를 이용한 권한 상승 |
| `--security-opt no-new-privileges` | setuid 권한 상승 |
| `--tmpfs /tmp:noexec,nosuid` | 받아온 바이너리 실행 |
| `-v ...:/job:ro` | 제출 코드 바꿔치기 |
| 마운트에 `solution.py` 만 | **정답표 유출** (ADR-0006) |
| `USER runner` (uid 10001) | 컨테이너 탈출 난이도 |

Docker 는 완전한 보안 샌드박스가 아니다(호스트 커널 공유). 외부 공개 전에 gVisor
도입 여부를 Security Gate 로 둔다(Addendum 49, 71).

## 두 축을 따로 검사한다

격리(실행이 갇혀 있는가)와 기밀성(채점 데이터가 새지 않는가)은 **다른 축**이다.
처음에는 격리만 검사했고, 그 8종은 전부 통과하면서도 정답표 유출을 하나도 잡지 못했다.
쓰기만 확인하고 읽기를 확인하지 않았기 때문이다.

```text
Test Case 변조 방지   격리   (open('/job/job.json', 'w') 가 실패하는가)
Test Case 유출 방지   기밀성 (애초에 그 파일이 없는가)
```

## 격리 테스트에 대조군이 있는 이유

`--network none` 을 **적어두는 것**과 네트워크가 **실제로 안 되는 것**은 다르다.
옵션을 지우거나 오타를 내도 채점은 정상으로 보이고, 아무도 모른 채 신뢰 경계가 사라진다.

그런데 격리 테스트가 통과하는 것만으로도 부족하다. 테스트 코드에 오타가 있어도
`RUNTIME_ERROR` 가 나므로 "막혔다" 로 읽힌다 - 아무것도 검증하지 않는 테스트가 초록불을
낸다. 그래서 **제한을 걷어내고 한 번 더 돌려** 그때는 실행에 성공하는지 확인한다.

```text
제한 있음  RUNTIME_ERROR   <- 막혔다
제한 없음  WRONG_ANSWER    <- 실행 자체는 됐다 (답만 틀림)
```

둘 다 `RUNTIME_ERROR` 면 그 테스트는 격리를 검증하지 못하는 것이므로 실패로 처리한다.
실제로 이 대조군이 "마운트 읽기 전용" 테스트의 결함을 잡았다 - 그 항목은
`DOCKER_LIMITS` 가 아니라 `-v` 의 `:ro` 가 막는데, 대조군이 그 knob 을 안 건드리고
있었다. `MOUNT_MODE` 를 상수로 분리한 이유다.

## 판정

| status | 언제 | failedCaseId |
| --- | --- | --- |
| `ACCEPTED` | 모든 case 통과 | null |
| `WRONG_ANSWER` | 출력 불일치 | **필수** |
| `RUNTIME_ERROR` | 0 이 아닌 종료 코드 | **필수** |
| `TIME_LIMIT` | 제한 시간 초과 | **필수** |
| `MEMORY_LIMIT` | SIGKILL 또는 MemoryError | **필수** |
| `OUTPUT_LIMIT` | stdout 1MB 초과 | **필수** |
| `COMPILE_ERROR` | 문법 오류. case 를 하나도 실행 못 함 | null |
| `SYSTEM_ERROR` | 우리 잘못 | null |

**첫 실패에서 멈춘다**(ADR-0005). `passed` 는 그때까지 통과한 수이므로 `total` 과 합이
맞지 않을 수 있다. 버그가 아니라 정의다.

`failedCaseId` 가 "필수" 인 판정들은 Reviewer 를 호출하는 판정이다. 이 값이 없으면
Reviewer 출력의 `failedCaseRefs`(minItems 1)를 채울 수 없다(ADR-0004).
계약이 아니라 테스트로 강제한다 - `VERDICTS` 의 세 번째 열.

## 출력 정규화

줄 끝 공백과 마지막 개행 차이로 오답 처리하지 않는다. 관용이 아니라 정확성 문제다 -
`print()` 가 붙이는 개행을 두고 WA 를 내면 사용자는 알고리즘을 의심하게 되고,
오답 원인 분석 데이터도 그만큼 오염된다.

## hard timeout 과 컨테이너 회수

`--rm` 은 컨테이너가 **스스로 종료했을 때** 지워준다. hard timeout 으로 docker CLI 를
끊으면 컨테이너는 계속 돌 수 있고, 그러면 CPU 와 메모리를 계속 먹는다.

그래서 컨테이너에 이름을 붙이고(`codesprint-judge-<uuid>`), timeout 과 finally 양쪽에서
`docker rm -f` 로 회수한다. 테스트가 짧은 hard timeout 을 걸고 무한 루프를 돌려
잔존 컨테이너가 없는지 확인한다.

## case 사이에 남는 프로세스

컨테이너는 제출마다 하나고 case 는 그 안에서 돈다. 그래서 **case 가 남긴 프로세스는 다음 case 로
넘어간다** - 하네스는 컨테이너의 PID 1 이라 사용자 프로세스가 두고 간 자손을 받는다. 제 자식만 기다리던
때는 fork bomb case 뒤 좀비 61 개가 `--pids-limit 64` 를 차지해 다음 case 가 스레드 4 개도 못 만들었고,
살아 남은 자손은 다음 case 의 출력에 섞이거나 하네스를 죽여 `SYSTEM_ERROR` 를 만들었다.

지금은 case 가 끝나면 하네스가 `kill(-1, SIGKILL)` 로 컨테이너의 나머지를 전부 죽이고 거둔 뒤 출력을
읽는다([ADR-0055](../docs/adr/0055-each-case-starts-with-the-harness-alone.md)). `--init` 은 좀비만 거두고
살아 있는 자손을 남겨서 쓰지 않는다. `test_judge.py` 의 "case 사이에 남는 프로세스" 가 확인한다.

## 시간 제한은 자리를 쓰지 않는다

case 의 hard limit 은 커널 타이머(`setitimer`)와 SIGALRM 처리기로 건다. 자식을 띄운 뒤에는 스레드도
프로세스도 새로 만들지 않는다 - 타이머 스레드를 쓰던 때는 자식이 먼저 `--pids-limit` 을 다 채우면 하네스가
스레드를 못 만들고 죽어 `SYSTEM_ERROR` 가 됐다. 처리기는 pidfd 로 죽이기만 하고, 자식을 거두는 곳은
`os.wait4` 하나뿐이다([ADR-0056](../docs/adr/0056-the-time-limit-takes-no-slot-and-one-place-reaps.md)).

둘 다 경주라, `test_judge.py` 가 하네스를 그 순서로 세워 두는 게이트 이미지를 따로 구워 확인한다.

## memoryKb 가 재는 것

memoryKb 는 case 마다 그 자식의 `ru_maxrss` 중 최댓값이다. 이 값은 exec 전의 주소 공간(하네스를 fork 한
사본)까지 포함한다 - 그래서 **하네스보다 작은 풀이는 하네스 크기로 보인다**(Python 약 12.5~13MB, C++ 이미지
약 9.4MB. C++ 풀이 자신은 3MB 남짓이다). 입력이 큰 case 는 하네스가 쥔 입력 사본만큼 더 크게 보인다.
판정(`MEMORY_LIMIT`)은 이 값으로 정하지 않고, 이 값은 화면에 보이기만 한다. 하네스를 고치면 작은 풀이의
값이 수백 KB 씩 움직일 수 있다 - 결함이 아니라 이 측정 방식이다(ADR-0056).

## 아직 없는 것

- Judge Worker / 큐 (Addendum 67~69). 지금은 동기 호출만 있다
- 문제·Test Case 저장소. 지금은 job.json fixture 뿐이다
- stale submission 복구 (Addendum 69)
- gVisor (Addendum 71 Stage 2)
