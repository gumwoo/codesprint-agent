# Judge / Sandbox

사용자가 제출한 Python 코드를 **안전하게 실행하고 결정론적으로 판정한다.**
AI 는 여기 관여하지 않는다(ADR-0001, Addendum 81).

```text
run_submission.py          호스트(신뢰). 컨테이너를 만들고, 정답과 비교하고, 판정을 조립한다
  └─ Dockerfile            python:3.12-slim, non-root, 하네스를 구워 넣는다
       └─ runner/harness.py 컨테이너 안(신뢰 안 함). **실행만 한다. 채점하지 않는다**

fixtures/                  판정을 재현하는 최소 문제 + 제출 코드
tests/test_judge.py        판정 9건 + 격리 8건 + 기밀성 3건
```

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
| `--memory 256m` + `--memory-swap 256m` | 호스트를 끌어내리는 OOM, swap 우회 |
| `--cpus 0.5` | CPU 독점 |
| `--pids-limit 64` | fork bomb |
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

## 아직 없는 것

- Judge Worker / 큐 (Addendum 67~69). 지금은 동기 호출만 있다
- 문제·Test Case 저장소. 지금은 job.json fixture 뿐이다
- stale submission 복구 (Addendum 69)
- gVisor (Addendum 71 Stage 2)
