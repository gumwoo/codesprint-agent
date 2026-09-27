# ADR-0053 · 프로세스 수는 컨테이너마다 센다 - uid 로 세는 상한은 옆 채점을 바꾼다

- 상태: 채택
- 날짜: 2026-09-27
- 정본 근거: Addendum §51(`--pids-limit 64`), §59(root 로 실행하지 않는다),
  [ADR-0005](0005-judge-stops-at-first-failure.md), [ADR-0045](0045-java-and-cpp-runners.md)

## 맥락

하네스는 사용자 코드와 컴파일러에 `RLIMIT_NPROC = 64` 를 걸고 있었다. 주석은 "컨테이너의 --pids-limit
은 컨테이너 전체에 걸리므로 자식에게 따로 걸어 하네스의 몫을 남긴다" 였다.

`RLIMIT_NPROC` 은 컨테이너가 아니라 **커널 전체에서 실제 uid 별로** 센다. 채점 컨테이너는 세 이미지 모두
uid 10001(runner)로 돈다. 그래서 64 는 "이 제출의 프로세스 64개" 가 아니라 "지금 이 호스트에서 도는 모든
채점 컨테이너의 태스크를 합쳐 64개" 였다.

검증 에이전트가 찾았고 다시 재현했다. 한 컨테이너가 프로세스 · 스레드를 `--pids-limit` 까지 채워 붙잡는
동안 옆 컨테이너에서 정상 제출을 채점했다.

| 쥐는 쪽 | 옆 채점 | 결과 |
| --- | --- | --- |
| Java 스레드 | `java/Accepted.java` | `COMPILE_ERROR` - `pthread_create failed (EAGAIN)` "VM Periodic Task Thread" |
| C++ fork | `java/Accepted.java` | `COMPILE_ERROR` (같은 메시지) |
| C++ / Python fork | `cpp/accepted.cpp` | `COMPILE_ERROR` - `g++: cannot execute cc1plus: vfork: Resource temporarily unavailable` |
| C++ fork | 스레드 하나에서 푸는 Python 풀이 | `RUNTIME_ERROR` - `threading.start` 실패 |
| Python fork | `sol-accepted.py` | `ACCEPTED` - fork 도 스레드도 만들지 않아서 |

혼자 돌리면 전부 `ACCEPTED` 다. Judge Worker 가 둘이거나 테스트가 겹치면 **다른 사람의 제출이 내 판정을
정한다.** 그 판정은 Evidence 와 mastery 로 들어간다.

원인이 uid 공유라는 것은 대조로 확인했다.

- 쥐는 쪽만 `--user 10002` 로 띄우면(같은 `--pids-limit 64`) 옆 Java · C++ 채점은 `ACCEPTED`.
- 쥐는 쪽이 도는 동안 `--ulimit nproc=64:64` 를 건 새 컨테이너는 uid 10001 이면 시작조차 못 하고
  (`exec /usr/local/bin/python3: resource temporarily unavailable`), uid 10002 면 뜬다.
- `--pids-limit` 은 cgroup 이다 - 새 컨테이너의 `pids.max` 64 · `pids.current` 1 이고, 컨테이너 기본
  `RLIMIT_NPROC` 은 무제한이다. 64 는 하네스의 `setrlimit` 만 만들었다.

그리고 **컨테이너 안에서 `RLIMIT_NPROC` 이 더 막는 것은 없었다.** 컨테이너의 태스크는 하네스까지 전부
uid 10001 이라, 센 대상도 상한(64)도 `--pids-limit` 과 같다. 하네스도 함께 세어지므로 "하네스의 몫"
도 남기지 않았다. 옛 하네스와 고친 하네스에서 쥐는 쪽이 만든 fork 수는 똑같이 61(하네스 · 타이머 스레드 ·
사용자 프로세스를 합쳐 64), Java 는 똑같이 `Thread-50` 에서 멈췄다.

오히려 가리고 있었다. `--pids-limit` 을 `DOCKER_LIMITS` 에서 빼도 `RLIMIT_NPROC` 이 fork bomb 을 막아
격리 case 세 개(Python · C++ fork bomb, Java 프로세스 폭주)가 그대로 통과했다 - "옵션을 빼면
test_judge.py 가 실패한다" 가 이 옵션에서는 거짓이었다.

## 결정

**하네스는 `RLIMIT_NPROC` 을 걸지 않는다.** 프로세스 수는 컨테이너의 `--pids-limit 64`(cgroup) 하나가
막는다. `RLIMIT_FSIZE` 는 그대로 둔다 - 프로세스가 쓰는 파일 하나의 크기라 uid 로 합산되지 않는다.

고른 이유와 버린 것:

| 선택지 | 판단 |
| --- | --- |
| **`RLIMIT_NPROC` 을 뺀다** | 채택. 컨테이너 안에서 막는 것이 `--pids-limit` 과 같았으므로 격리가 줄지 않는다 |
| 상한을 올린다 | 버림. 경계가 uid 인 한 겹침 수에 따라 다시 터진다 - 문턱만 옮긴다 |
| 컨테이너마다 다른 uid | 버림. 할당 · 충돌 관리가 생기고, 얻는 것이 cgroup 이 이미 주는 것이다 |
| user namespace(userns-remap) | 버림. 데몬 설정이라 저장소가 보장할 수 없고, 한 데몬의 컨테이너는 같은 하위 uid 범위를 나눠 쓴다 |

`--pids-limit` 은 그대로 64 다. `DOCKER_LIMITS` 는 바꾸지 않았다.

## 검사

`test_judge.py` 의 **동시 채점** - 한 컨테이너가 fork 로 `--pids-limit` 까지 채워 30 초 붙잡는 동안 옆에서
`java/Accepted.java` · `cpp/accepted.cpp` 를 채점해 `ACCEPTED` 인지 본다. 파이썬 `sol-accepted.py` 는 넣지
않았다 - uid 상한이 있어도 통과하므로 아무것도 보지 않는다.

- **게이트로 순서를 쥔다.** 쥐는 쪽 컨테이너의 프로세스 수(docker stats)가 60 에 닿은 뒤 옆 채점을 시작하고,
  채점이 끝난 뒤에도 여전히 60 이상인지 본다. 아니면 `[VACUOUS]` 로 실패한다. 처음 20 초로 잡았을 때 C++ 채점
  중에 놓아 이 검사가 그것을 잡았다.
- **대조군.** 같은 순간 `--ulimit nproc=64:64` 를 건 채점은 실패해야 한다(실측 `SYSTEM_ERROR`). `ACCEPTED`
  면 쥐는 쪽이 uid 10001 에 압력을 주지 못한 것이라 `[VACUOUS]` 다.
- **fix 를 되돌리면 깨진다.** `RLIMIT_NPROC` 을 되살린 하네스로 세 이미지를 구우면 Java `COMPILE_ERROR`
  · C++ `COMPILE_ERROR` 두 건으로 실패하는 것을 확인했다.

부수 효과로 `--pids-limit` 을 빼면 이제 격리 case 세 개가 실패한다(Python · C++ `WRONG_ANSWER`, Java
`TIME_LIMIT`). 대조군(`judge_unrestricted`)은 원래부터 `--pids-limit` 없이 root 로 돌았으므로 바뀌지 않는다.

## 남는 위험

- 사용자 코드가 컨테이너의 64 를 다 채우면 **하네스도 fork · 스레드를 못 만든다.** 옛 `RLIMIT_NPROC` 도
  이것을 막지 못했다(하네스가 같은 uid 로 세어졌다).
- **fork bomb 이 남긴 좀비가 다음 case 까지 남는다.** 사용자 프로세스가 끝나면 그 자식은 PID 1 인 하네스에게
  넘어가는데, 하네스는 제 자식(`os.wait4(proc.pid)`)만 거둔다. 매 case 시작 때 좀비 수를 찍어 보니 옛 · 새
  하네스 모두 case 1 이 `0` 에서 fork 61 번, case 2~5 는 좀비 `61` 에 fork `0` 이었다. 그러면 하네스는 자식
  하나와 타이머 스레드 하나만 들어갈 자리로 다음 case 를 돈다. 되돌린 상태의 전체 실행에서 C++ fork bomb 격리
  case 가 한 번 `SYSTEM_ERROR`("case 4 응답이 없다")로 나왔는데, 이것 때문이라고 **추측**할 뿐 확인하지는
  않았다. 이 ADR 의 변경과는 무관하다(하네스 자신에게는 원래 `RLIMIT_NPROC` 이 없었다).
- 동시 채점 검사는 테스트 시간에 약 30 초를 더한다.
