# Judge / Sandbox

사용자가 제출한 Python 코드를 **안전하게 실행하고 결정론적으로 판정한다.**
AI 는 여기 관여하지 않는다(ADR-0001, Addendum 81).

```text
run_submission.py          호스트. 일회용 컨테이너를 만들고 결과를 수거한다
  └─ Dockerfile            python:3.12-slim, non-root, 하네스를 구워 넣는다
       └─ runner/harness.py 컨테이너 안. Test Case 를 순회 실행하고 JSON 을 낸다

fixtures/                  판정 8종을 재현하는 최소 문제 + 제출 코드
tests/test_judge.py        판정 8건 + 격리 8건 (대조군 포함)
```

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
| `-v ...:/job:ro` | Test Case 변조로 오답을 정답으로 |
| `USER runner` (uid 10001) | 컨테이너 탈출 난이도 |

Docker 는 완전한 보안 샌드박스가 아니다(호스트 커널 공유). 외부 공개 전에 gVisor
도입 여부를 Security Gate 로 둔다(Addendum 49, 71).

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

## 아직 없는 것

- Judge Worker / 큐 (Addendum 67~69). 지금은 동기 호출만 있다
- 문제·Test Case 저장소. 지금은 job.json fixture 뿐이다
- stale submission 복구 (Addendum 69)
- gVisor (Addendum 71 Stage 2)
