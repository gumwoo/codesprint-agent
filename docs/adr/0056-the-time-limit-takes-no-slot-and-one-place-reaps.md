# ADR-0056 · 시간 제한은 자리를 쓰지 않고, 자식을 거두는 곳은 하나다 - 그리고 memoryKb 가 실제로 재는 것

- 상태: 채택
- 날짜: 2026-09-27
- 정본 근거: Addendum §51(`--pids-limit 64`), §65(case 별 timeout 은 하네스가), [ADR-0005](0005-judge-stops-at-first-failure.md),
  [ADR-0053](0053-process-limit-is-per-container-not-per-uid.md), [ADR-0055](0055-each-case-starts-with-the-harness-alone.md)

## 맥락

ADR-0055 의 남는 위험과 그 검토에서 나온 셋을 따로 재현했다. 둘은 결함이고, 하나는 결함이 아니라 원래 있던
측정 방식이 드러난 것이다.

### C. 자식이 먼저 64 를 채우면 하네스가 죽는다

하네스는 자식을 띄운 **직후** `threading.Timer` 로 hard limit 타이머 스레드를 만들었다. 스레드도
`--pids-limit 64` 의 한 자리다. 자식이 그보다 먼저 64 를 다 채우면 하네스가 `RuntimeError: can't start new
thread` 로 죽고, 호스트는 `SYSTEM_ERROR`("case 1 응답이 없다")를 돌려준다 - 사용자 코드의 결과가 우리 잘못이 된다.

| 조건 | 결과 |
| --- | --- |
| main 하네스, 곧바로 자리를 채우는 C++ 제출(`fork` 루프가 `main` 첫 줄) | 300 case 중 0 번 - 매번 `held 61`, 타이머 스레드가 이미 있었다 |
| main 하네스 사본에 "자식을 띄운 뒤 타이머를 만들기 전 3 초 멈춤" 을 넣고, 입력을 읽기 **전에** 자리를 채우는 제출 | 하네스가 `RuntimeError: can't start new thread` 로 죽는다 |
| 같은 사본, 입력을 먼저 읽고 자리를 채우는 제출 | 죽지 않는다 - 입력은 타이머를 만든 뒤에 쓰므로 그때는 이미 스레드가 있다 |

경로는 실재하고, 자연 상태에서는 하네스가 수십 µs 안에 스레드를 만들어 이긴다. 이기는 이유가 속도뿐이다.

### E. 자식이 hard limit 과 같은 순간에 끝나면 하네스가 죽는다

타이머가 부르던 `proc.kill()` 은 CPython `Popen.send_signal` 이 먼저 `poll()` - `waitpid(pid, WNOHANG)` - 을
부른다(이미지의 3.12.14 소스에서 확인, bpo-38630). 자식이 이미 끝나 좀비인데 메인 스레드의 `os.wait4(proc.pid,
0)` 가 아직 거두지 못했다면 타이머 스레드가 거둬 가고, 메인 스레드의 `wait4` 는 `ChildProcessError` 로 하네스를
죽인다. 종료 상태와 `ru_maxrss` 도 `Popen` 쪽으로 가 버린다.

| 조건 | 결과 |
| --- | --- |
| main 하네스, hard limit 0.6 초 근처(0.568~0.580 초 잠든 뒤 끝남) 480 case | 0 번 (126 번은 타이머가 죽였고 354 번은 먼저 끝났다) |
| main 하네스 사본에 "거두기 전 hard limit + 0.3 초 멈춤" | `ChildProcessError: [Errno 10] No child processes` 로 죽는다 |

역시 경로는 실재하고 자연 상태의 창은 µs 단위다.

### D. 작은 풀이의 memoryKb 는 하네스의 크기다

검토에서 ADR-0055 뒤 작은 파이썬 풀이의 memoryKb 가 0.3~0.6MB 줄었다고 했다. 판정은 같았다. 원인을 쟀다.

`ru_maxrss` 는 그 프로세스가 **exec 하기 전의 주소 공간까지** 포함한다. 자식은 하네스를 `fork` 한 사본으로
시작하고(그때 하네스의 익명 메모리가 사본의 RSS 로 잡힌다), exec 할 때 커널이 그 사본의 최고 RSS 를
프로세스의 `maxrss` 에 합친 뒤 사용자 프로그램으로 바꾼다. 그래서 `wait4` 가 돌려주는 값은
`max(fork 순간 하네스의 사본, 사용자 프로그램의 최고 RSS)` 다. 커널 소스 줄은 이 저장소에서 확인하지 않았고,
아래 실측이 근거다(사용자 프로그램은 `/proc/self/status` 의 `VmHWM` - exec 뒤 주소 공간만 - 을 스스로 적고,
하네스의 크기는 `/proc/1/status` 에서 읽었다).

| 조건 | 사용자 프로그램 자신의 최고 RSS | 하네스 RssAnon | memoryKb |
| --- | --- | --- | --- |
| Python, ADR-0055 전 하네스 | 약 10.0MB | 10.41MB | 13.25MB |
| Python, ADR-0055 하네스(main) | 약 10.0MB | 9.82MB | 12.53MB |
| Python, 하네스 사본에 50MB 를 더 쥐게 함 | 10.1MB | 61.6MB | **64.3MB** |
| Python, 4.3MB 입력을 한 줄씩 읽고 버림 | 10.0MB | (입력 사본을 쥔 채 fork) | **23.7MB** |
| C++ `accepted` 류 | **3.2MB** | 6.1MB | 9.4MB |
| Java `Accepted.java` | JVM 이 훨씬 크다 | - | 34.9MB (하네스와 무관) |

`sol-accepted.py` 를 8 번씩 채점한 memoryKb 중앙값은 ADR-0055 전 13,248 · main 12,800 · 이 ADR 13,240 이다
(같은 순서로 한 번 더 잰 앞선 측정은 13,128 · 12,800 · 13,182). 0055 가 무엇을 바꾼 것이 아니라 하네스의 힙
배치가 한 칸 움직였고, 작은 풀이의 memoryKb 가 그것을 그대로 따라간 것이다 - 계측 줄을 넣은 사본에서는 순서가
뒤집혔다(0055 전 12,984 < main 13,044). 이 ADR 은 타이머 스레드를 없애 그 값을 다시 0055 전 수준으로 옮겼다.
문제 116 개의 풀이 307 개(reference · wrong · skill_control · probes)를 main 과 이 하네스로 채점해 대 보면
판정 · failedCaseId · case 별 판정은 전부 같고, memoryKb 차이의 중앙값은 +240KB 다. 차이가 수 MB 인 셋은 전부
`TIME_LIMIT` 로 죽인 풀이로, 죽기 전까지 얼마나 쌓았는지가 실행마다 다르다.

**판정은 영향이 없다.** `MEMORY_LIMIT` 은 memoryKb 로 정하지 않는다 - 컨테이너 메모리 상한에 걸린 SIGKILL 과
`MemoryError` · `OutOfMemoryError` · `std::bad_alloc` 으로 정한다. memoryKb 는 제출 행에 저장되어 화면에 보이기만
하고, Evidence · mastery · 다음 행동 어디에도 들어가지 않는다.

그래도 **정확하지 않다.** 하네스 크기(Python 약 12.5~13MB, C++ 이미지 약 9.4MB)보다 작은 풀이는 전부 하네스
크기로 보이고, 입력이 큰 문제는 하네스가 쥔 입력 사본까지 더해진다. ADR-0055 가 만든 것이 아니다 - 하네스는
처음부터 파이썬 프로세스를 fork 해 자식을 띄웠다(`RUSAGE_CHILDREN` 으로 모으던 때도 같은 값이다).

## 결정

### C · E: 시간 제한은 커널 타이머와 신호 처리기로 건다

```text
Popen(자식)
pidfd_open(자식) + setitimer(ITIMER_REAL, hard limit)   자리를 쓰지 않는다
입력을 쓴다                                             막히면 SIGALRM 이 끊는다 (처리기 뒤 다시 쓰면 EPIPE)
os.wait4(자식)                                          자식을 거두는 곳은 여기 하나뿐
setitimer(0), pidfd 닫기
```

- **자식을 띄운 뒤에는 새 태스크(스레드 · 프로세스)를 만들지 않는다.** 타이머는 커널에 걸고, 울리면 SIGALRM
  처리기가 메인 스레드에서 돈다. 자식이 64 를 다 채운 채 돌아도 시간 제한은 걸린다.
- **처리기는 죽이기만 하고 거두지 않는다.** `poll()` 을 부르는 `proc.kill()` 대신 `pidfd_send_signal` 을 쓴다.
  처리기는 메인 스레드에서만 돌므로 `wait4` 와 동시에 거둘 수 없다. pidfd 라서, 이미 거둔 뒤에 처리기가 돌아도
  같은 번호를 받은 다른 프로세스를 죽이지 않는다.
- `os.wait4` 는 EINTR 뒤 파이썬이 다시 부른다(PEP 475). 입력 쓰기도 같다.
- **시간 제한의 의미와 elapsed 는 그대로다.** 타이머는 전처럼 자식을 띄운 뒤에 걸고(hard limit = 제한 + 0.5 초),
  elapsed 는 전처럼 `Popen` 직전부터 `wait4` 가 돌아올 때까지다. 타이머가 울렸으면(자식이 막 끝난 순간이었더라도)
  전처럼 `TIME_LIMIT`(또는 출력 상한을 채웠으면 `OUTPUT_LIMIT`)이고 executionMs 는 제한값이다.
- 하네스가 한 자리를 덜 쓰므로 한 case 가 쓸 수 있는 자리가 61 에서 62 로 늘었다. `--pids-limit` 은 그대로 64 다.

| 선택지 | 판단 |
| --- | --- |
| **setitimer + SIGALRM 처리기 + pidfd** | 채택. 자리가 필요 없고, 거두는 곳이 하나가 되며, 입력 쓰기가 막혀도 끊긴다 |
| 자식을 띄우기 전에 타이머 스레드를 만들어 둔다 | 버림. C 는 막지만 스레드가 한 자리를 계속 쥐고, E 는 따로 고쳐야 한다 |
| 메인 스레드에서 `wait4(WNOHANG)` 를 돌며 기한을 본다 | 버림. elapsed 가 폴링 간격만큼 늦게 잡히고, 막힌 입력 쓰기를 풀려면 쓰기를 따로 논블로킹으로 바꿔야 한다 |
| 타이머는 두고 `proc.kill()` 을 `os.kill(pid)` 로 | 버림. E 만 막고 C 는 남는다 |

`DOCKER_LIMITS` · `MOUNT_MODE` 는 바꾸지 않았다. `pidfd_open` · `pidfd_send_signal` 은 Docker 기본 seccomp 에서
허용된다 - 세 이미지(Python 3.12 · 3.11 · 3.10)에서 `test_judge.py` 의 TIME_LIMIT fixture
(`sol-timeout.py` · `cpp/timeout.cpp` · `java/Timeout.java`)가 이 경로로 죽었다.

### D: 고치지 않고 적는다

**0.3~0.6MB 이동은 결함이 아니다.** 하네스의 크기가 움직였고, 작은 풀이의 memoryKb 가 원래 그것을 재고 있었다.
숫자를 빼서 맞추지 않는다 - 하네스 크기는 실행마다, 입력마다 다르다.

**밑에 있는 부정확은 결함이다. 이 ADR 에서는 고치지 않는다.** 정확히 재려면 사용자 프로그램이 작은 프로세스의
`fork` 에서 시작해야 한다. 파이썬 하네스의 `fork` 사본은 작아질 수 없고, `vfork` · `posix_spawn` 은 부모의 최고
RSS 를 그대로 물려받아 더 나쁘다. 남은 길은 셋 다 구조를 바꾼다.

| 후보 | 비용 |
| --- | --- |
| 작은 정적 실행기(C)를 이미지에 굽는다 - fork · exec · wait4 한 뒤 자식의 rusage 를 파이프로 돌려준다 | 신뢰 경계 안에 새 네이티브 코드. 세 이미지에 멀티 스테이지 빌드. 시간 제한 신호의 대상이 손자가 된다 |
| `sh` 를 거쳐 손자로 띄우고 `sh` 는 곧바로 끝낸다 - 손자는 PID 1(하네스)에게 넘어와 직접 거둔다 | 비대화형 셸의 백그라운드 규칙(표준 입력이 /dev/null, SIGINT 무시)을 우회해야 하고, 셸이 손자를 먼저 거두면 rusage 를 잃는다 |
| 하네스가 case 입력을 쥐지 않은 채 fork 한다(memfd 로 넘기기) | 입력 사본만 뺀다. 하네스 기본 크기는 그대로 남는다 |

판정과 학습 상태에 들어가지 않는 표시값이라, 구조 변경은 따로 정한다(남는 위험).

## 검사

`test_judge.py` 에 둘을 넣었다. 둘 다 경주라 그대로는 재현되지 않으므로, **하네스를 그 순서로 세워 두는 게이트**를
채점 이미지 위에 따로 굽는다(`FROM <지금의 파이썬 채점 이미지>` + `gate.py`, 검사가 끝나면 지운다). 게이트는
하네스를 모듈로 불러 `subprocess.Popen` · `os.wait4` 를 감싼다. 채점 이미지에는 들어가지 않는다.

- **시간 제한은 자리를 쓰지 않는다.** `pids` 게이트는 자식을 띄운 직후 cgroup 의 `pids.current` 가 `pids.max` 에
  닿을 때까지 하네스를 세우고, 닿으면 `/tmp/gate-open` 을 만들고 놓는다. 제출은 곧바로 자리를 채운 뒤 게이트가
  열린 것을 확인하고 `exit` 면 `gated` 를 쓰고 끝나며(`ACCEPTED` 여야 한다), `spin` 이면 계속 돈다(`TIME_LIMIT`
  이어야 한다). 게이트가 열리지 않으면 제출이 `nogate` 를 써서 `[VACUOUS]` 로 실패한다.
- **자식을 거두는 곳은 하나다.** `reap` 게이트는 `wait4` 직전에 끝난 자식을 거두지 않고 4 초(hard limit 0.7 초)
  둔다. 그 사이 누가 먼저 거두면(`/proc/<pid>` 가 사라지면) 거기서 놓는다. `TIME_LIMIT` 이어야 하고,
  `SYSTEM_ERROR` 면 실패, `ACCEPTED` 면 붙잡은 동안 시간 제한이 울리지 않은 것이라 `[VACUOUS]` 다.

**fix 를 되돌리면 깨진다.** 별도 태그(`csagent-main:*`)에 main 하네스를 굽고 `run_submission.LANGUAGES` 를 그
태그로 바꿔 3 번 돌렸다. 3 번 모두 두 검사가 `SYSTEM_ERROR` 로 실패했다 - 컨테이너 stderr 는 각각
`RuntimeError: can't start new thread` 와 `ChildProcessError: [Errno 10] No child processes`. 처리기만
`proc.kill()` 처럼 먼저 `waitpid(WNOHANG)` 하게 되돌린 사본에서는 거두기 검사만 실패하고 자리 검사는 통과했다 -
둘은 서로를 대신하지 않는다. 고친 하네스로는 3 번 모두 통과했다.

## 남는 위험

- **memoryKb 는 사용자 프로그램만의 값이 아니다(D).** 하네스 크기보다 작은 풀이는 하네스 크기로, 입력이 큰
  문제는 하네스가 쥔 입력 사본만큼 크게 보인다. 하네스를 고칠 때마다 작은 풀이의 값이 움직인다. 판정 · Evidence ·
  mastery 에는 들어가지 않는다. 고치려면 위 후보 중 하나를 골라 구조를 바꿔야 한다.
- 게이트는 Python 이미지 위에서만 굽는다. 하네스는 세 이미지가 같은 파일이지만, C++ · Java 이미지 위에서 같은
  순서를 만든 검사는 없다.
- `reap` 게이트의 "자식이 hard limit 전에 좀비가 된다" 는 시간에 기댄다(`print` 한 줄 제출 대 0.7 초). 기계가
  그보다 느리면 되돌린 하네스에서도 통과할 수 있다 - 고친 하네스의 통과를 거짓으로 만들지는 않는다.
- SIGALRM 처리기는 파이썬 코드라 메인 스레드가 바이트코드로 돌아올 때 돈다. 메인 스레드는 그동안 `wait4` 나
  입력 쓰기(둘 다 EINTR 로 풀린다)에만 있으므로 늦어지는 곳은 없다고 보지만, 측정한 것은 위 게이트뿐이다.
