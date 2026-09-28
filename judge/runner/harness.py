#!/usr/bin/env python3
"""컨테이너 안에서 도는 실행 하네스.

**이 하네스는 채점하지 않는다.** 실행만 하고 결과를 그대로 돌려준다.

정답(expectedOutput)은 컨테이너 안으로 들어오지 않는다. 들어오면 사용자 코드가
그것을 읽어 그대로 출력할 수 있다 - read-only 마운트는 수정을 막을 뿐 읽기를 막지
않는다. 실제로 그렇게 짜서 알고리즘을 한 줄도 풀지 않고 4/4 ACCEPTED 를 받아봤다.
근거: docs/adr/0006-expected-output-never-enters-sandbox.md

그래서 비교는 신뢰 경계 바깥(호스트)에서 한다. 여기서 하는 일은 이것뿐이다.

  /job/solution.py 를 읽어 문법을 확인한다
  호스트가 stdin 으로 보내주는 case input 을 사용자 코드에 먹인다
  사용자 코드의 stdout 과 실행 결과를 stdout 으로 돌려준다

프로토콜은 줄 단위 JSON(NDJSON)이며 한 번에 한 case 씩 주고받는다.

  호스트 -> 하네스   {"type":"config",...} {"type":"case",...} {"type":"end"}
  하네스 -> 호스트   {"type":"ready"|"compile_error"} {"type":"case_result",...} {"type":"done"}

한쪽이 쓰는 동안 다른 쪽은 읽고 있으므로 파이프가 막히지 않는다.

이 파일은 사용자 코드를 **import 하지 않는다.** 별도 프로세스로 띄우고 stdin/stdout
으로만 통신한다. import 하면 사용자 코드가 이 하네스의 메모리 공간에서 돌아
프로토콜 자체를 조작할 수 있다.
"""
from __future__ import annotations

import json
import os
import pathlib
import re
import resource
import signal
import subprocess
import sys
import time

# -- 언어 (ADR-0045) ---------------------------------------------------------
#
# **언어는 이미지가 정한다.** judge/Dockerfile(.cpp/.java) 의 ENV 가 이 값을 박아 둔다 - 사용자 입력으로
# 받지 않는다. 호스트는 제출 행의 language 로 이미지를 고르고, 이미지 안에서는 바꿀 수 없다.
#
# 컴파일 산출물은 /build(실행 가능한 tmpfs)에 둔다. /job 은 읽기 전용이고 /tmp 는 noexec 다 - 그
# 둘은 그대로 둔다. C++ 는 어차피 사용자의 기계어가 돌므로 /build 의 실행 권한이 더 주는 것이 없다.
LANGUAGE = os.environ.get("JUDGE_LANGUAGE", "PYTHON")

# JVM 은 스레드를 여럿 띄운다. 컨테이너의 --pids-limit(64) 안에 들도록 병렬 GC 와 JIT 스레드를
# 줄인다. 힙은 컨테이너 메모리(256m) 안에 둔다 - 넘으면 JVM 이 아니라 커널이 죽이고, 그때 판정이
# OutOfMemoryError 가 아니라 SIGKILL 이 된다.
_JVM = ["-Xmx192m", "-Xss64m", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1",
        "-XX:ActiveProcessorCount=1"]

LANGUAGES = {
    "PYTHON": {
        "source": "solution.py",
        "compile": None,
        "run": [sys.executable, "/job/solution.py"],
    },
    "CPP": {
        "source": "solution.cpp",
        "compile": ["g++", "-O2", "-std=gnu++17", "-pipe", "-o", "/build/main",
                    "/job/solution.cpp"],
        "run": ["/build/main"],
    },
    "JAVA": {
        "source": "Main.java",
        "compile": ["javac", *[f"-J{flag}" for flag in _JVM], "-encoding", "UTF-8",
                    "-d", "/build", "/job/Main.java"],
        "run": ["java", *_JVM, "-cp", "/build", "Main"],
    },
}

# 컴파일에 쓸 수 있는 시간. 전체 제출의 마지막 방어선(호스트의 SUBMISSION_HARD_TIMEOUT_S)보다 짧아야
# 컴파일이 멈춘 것을 COMPILE_ERROR 로 돌려줄 수 있다.
COMPILE_TIMEOUT_S = 20

# 컴파일러가 쓰는 파일의 상한. 사용자 출력 상한(1MB)을 그대로 걸면 템플릿이 많은 C++ 의 실행 파일이
# 그보다 커서 정상 코드가 컴파일 오류로 둔갑한다.
COMPILE_FSIZE = 64 * 1024 * 1024

SOLUTION = pathlib.Path("/job") / LANGUAGES.get(LANGUAGE, LANGUAGES["PYTHON"])["source"]

# 사용자가 무한 출력으로 파이프를 채우는 것을 막는다(Addendum 64).
STDOUT_LIMIT = 1024 * 1024
STDERR_LIMIT = 256 * 1024

# traceback 의 `File "..."` 은 파일명만 남긴다.
_PATH_NOISE = re.compile(r'File "([^"]*/)?([^"/]+)"')

# 그 형태가 아닌 자리의 **우리 쪽 경로**도 가린다.
#
# 처음에는 위 정규식 하나였는데, 그것은 traceback 의 그 줄만 다룬다. 예외 메시지가
# 경로를 문자열로 담으면 그대로 나갔다 - 사용자가 open('/job/job.json') 한 줄만 써도
# 마운트 구조가 보인다.
#
#     FileNotFoundError: [Errno 2] No such file or directory: '/job/job.json'
#     PermissionError: [Errno 13] Permission denied: '/opt/judge/harness.py'
#     print(os.getcwd(), file=sys.stderr)  ->  /job
#
# 정답표는 애초에 컨테이너 안에 없지만(ADR-0006), 마운트 위치와 하네스 자리를
# 알려 줄 이유도 없다.
#
# **표준 라이브러리 경로(/usr/lib/python3.12/...)는 건드리지 않는다.** 그것은 우리
# 구조가 아니라 파이썬 배포판의 것이고, 지우면 실제 오류를 읽기 어려워진다.
_OUR_PATHS = (
    ("/job", "<제출>"),
    ("/opt/judge", "<채점기>"),
    ("/tmp", "<임시>"),
    ("/build", "<빌드>"),
)
# 긴 것부터 대조한다 - /opt/judge 가 /opt 로 먼저 잘리면 안 된다.
#
# 경로가 **거기서 시작할 때만** 바꾼다. `/job.json` 처럼 이름만 겹치는 파일까지
# 건드리면 사용자가 무엇을 열려 했는지 알 수 없게 된다.
_OUR_PATH_NOISE = re.compile(
    "("
    + "|".join(re.escape(prefix)
               for prefix, _ in sorted(_OUR_PATHS, key=lambda pair: -len(pair[0])))
    + r")(?=/|\s|['\"]|$)",
    re.MULTILINE,
)
_REPLACEMENT = dict(_OUR_PATHS)


def sanitize_stderr(text: str) -> str | None:
    """사용자에게 보여도 되는 형태로 다듬는다. 정본: Addendum 63.

    **이 값은 화면까지 간다.** 그래서 지우는 것과 남기는 것이 둘 다 중요하다 -
    경로를 지우다 파일명과 줄 번호까지 지우면 사용자가 어디를 고쳐야 하는지 알 수 없다.
    """
    if not text:
        return None
    cleaned = _PATH_NOISE.sub(lambda m: f'File "{m.group(2)}"', text)
    cleaned = _OUR_PATH_NOISE.sub(lambda m: _REPLACEMENT[m.group(1)], cleaned)
    if len(cleaned) > STDERR_LIMIT:
        cleaned = cleaned[:STDERR_LIMIT] + "\n... (생략됨)"
    return cleaned


def compile_check(path: pathlib.Path) -> str | None:
    """실행 전에 문법을 확인한다.

    Python 은 컴파일 단계가 따로 없지만, SyntaxError 는 실행 시작과 동시에 나므로
    Test Case 를 하나도 실행하지 못한다. 그 상태를 RUNTIME_ERROR 로 묶으면
    "실패한 case" 가 없는데 case 근거를 요구하게 되어 계약이 모순된다(ADR-0004).
    그래서 여기서 미리 갈라 COMPILE_ERROR 로 분류한다.

    py_compile 을 쓰지 않는다. 그쪽은 /job 옆에 __pycache__ 를 쓰려 하는데 마운트가
    read-only 라 실패하고, 그 실패가 "문법 오류" 로 둔갑한다. compile() 은 파일을
    만들지 않는다. 코드를 실행하지도 않는다 - 바이트코드로 바꾸기만 한다.
    """
    try:
        source = path.read_text(encoding="utf-8", errors="replace")
    except OSError as e:
        return f"제출 코드를 읽지 못했다: {type(e).__name__}"
    try:
        compile(source, "solution.py", "exec")
    except SyntaxError as e:
        return f"{type(e).__name__}: {e.msg} (line {e.lineno})"
    except ValueError as e:
        # null 바이트가 섞인 소스 등. 실행 자체가 불가능하다.
        return f"ValueError: {e}"
    return None


def _limit_compiler() -> None:
    """컴파일러에 거는 제한. 파일 크기는 실행 파일을 쓸 만큼 준다.

    프로세스 수는 여기서 걸지 않는다 - 컨테이너의 --pids-limit 이 막는다(_limit_child, ADR-0053).
    """
    resource.setrlimit(resource.RLIMIT_FSIZE, (COMPILE_FSIZE, COMPILE_FSIZE))


def compile_native(language: str) -> str | None:
    """C++ · Java 를 /build 로 컴파일한다. 실패하면 사용자에게 보일 오류를, 성공하면 None.

    컴파일러의 출력도 사용자 입력에서 나온 것이라 경로를 가려서 돌려준다(sanitize_stderr).
    """
    command = LANGUAGES[language]["compile"]
    try:
        proc = subprocess.run(command, capture_output=True, timeout=COMPILE_TIMEOUT_S,
                              preexec_fn=_limit_compiler)
    except subprocess.TimeoutExpired:
        return f"컴파일이 {COMPILE_TIMEOUT_S}초 안에 끝나지 않았다"
    except OSError as e:
        return f"컴파일러를 실행하지 못했다: {type(e).__name__}"
    if proc.returncode != 0:
        text = (proc.stderr or proc.stdout).decode("utf-8", errors="replace")
        return sanitize_stderr(text) or f"컴파일 실패(종료 코드 {proc.returncode})"
    return None


def _limit_child() -> None:
    """자식 프로세스에만 거는 제한.

    **프로세스 수(RLIMIT_NPROC)는 걸지 않는다**(ADR-0053). fork bomb 은 컨테이너의
    --pids-limit 이 막는다 - 그것은 cgroup 이라 컨테이너 하나에만 걸린다.
    RLIMIT_NPROC 은 컨테이너가 아니라 **커널 전체에서 실제 uid 별로** 센다. 채점 컨테이너는
    전부 uid 10001(runner)로 돌므로, 64 를 걸면 옆 컨테이너의 스레드까지 합쳐 64 가 된다 -
    스레드를 많이 쥔 제출 하나가 도는 동안 **다른 컨테이너의 정상 Java 제출이 JVM 을 띄우지
    못해 COMPILE_ERROR 가 났다.** 컨테이너 안에서는 막는 것이 없었다: 컨테이너의 태스크는
    전부 uid 10001 이라 센 대상도 상한(64)도 --pids-limit 과 같았다. 이전 주석은 이것이
    "하네스의 몫을 남긴다" 고 했지만, 하네스도 같은 uid 라 함께 세어졌다.

    RLIMIT_FSIZE 는 출력 폭주를 커널에서 끊는다. 이게 없으면 커널이 아니라 하네스가
    출력을 다 받아야 하고, 그러다 컨테이너 메모리 상한에 먼저 걸려 **OUTPUT_LIMIT 이
    MEMORY_LIMIT 으로 둔갑한다.** 실제로 그렇게 나왔다.
    한도를 넘겨 쓰면 커널이 SIGXFSZ 를 보낸다. 이것은 프로세스가 쓰는 파일 하나의 크기라
    uid 로 합산되지 않는다 - 옆 컨테이너와 섞이지 않는다.
    """
    resource.setrlimit(resource.RLIMIT_FSIZE, (STDOUT_LIMIT, STDOUT_LIMIT))


def _clear_leftovers() -> None:
    """case 가 남긴 프로세스를 전부 죽이고 거둔다. 다음 case 는 하네스 혼자인 컨테이너에서 시작한다.

    **하네스는 컨테이너의 PID 1 이다**(ENTRYPOINT, --init 없음). 사용자 프로세스가 자식을 두고 끝나면
    그 자식은 PID 1 에게 넘어오는데, 하네스는 제 자식(os.wait4(proc.pid))만 기다렸다. 그래서
    - 끝난 자손은 **좀비로 남았다.** fork bomb case 뒤 좀비 61 개가 --pids-limit 64 를 차지해, 다음
      case 는 스레드 4 개도 못 만들어 RUNTIME_ERROR 가 났다(혼자 돌리면 ACCEPTED).
    - 살아 있는 자손은 **다음 case 로 넘어갔다.** 자리를 계속 채우는 자손이 하나 남으면 다음 case 에서
      하네스가 타이머 스레드를 못 만들어 죽고, 사용자 코드가 SYSTEM_ERROR(우리 잘못)가 됐다. 늦게 쓰는
      자손의 출력은 다음 case 의 stdout 파일에 섞였다 - 실패가 엉뚱한 case 에 붙는다(ADR-0015).

    kill(-1) 은 이 pid namespace 안에서 **나와 init 을 뺀 모든 프로세스**에 보낸다. 하네스가 곧 init 이므로
    컨테이너 안의 나머지 전부다 - setsid 로 프로세스 그룹을 빠져나간 자손도 포함된다(프로세스 그룹을
    죽이는 방식은 여기서 샌다). 모두 같은 uid 라 capability 없이 보낼 수 있다. 죽은 것은 전부 PID 1 에게
    넘어오므로 ECHILD 까지 기다리면 좀비도 남지 않는다. 그 사이 새로 생긴 것이 있을 수 있어, kill(-1) 이
    보낼 곳이 없다(ESRCH)고 할 때까지 되풀이한다.

    docker --init(tini)은 좀비만 거둔다. 살아 있는 자손은 그대로 다음 case 로 넘어가고, tini 가 자리를
    하나 더 차지한다 - 실측으로 넘어간 자손 때문에 같은 SYSTEM_ERROR 가 났다(ADR-0055).

    **이 case 의 자식은 이미 wait4 로 거둔 뒤에만 부른다.** 먼저 부르면 여기서 그 종료 상태와 자원
    사용량을 가져가 버린다.

    PID 1 이 아니면(컨테이너 밖에서 하네스를 직접 돌리는 경우) 아무것도 하지 않는다 - 그때 kill(-1) 은
    그 사용자의 프로세스 전부를 죽인다.
    """
    if os.getpid() != 1:
        return
    while True:
        try:
            os.kill(-1, signal.SIGKILL)
        except ProcessLookupError:
            return
        try:
            while True:
                os.waitpid(-1, 0)
        except ChildProcessError:
            pass


# -- 시간 제한 (ADR-0056) ----------------------------------------------------
#
# **자식을 띄운 뒤에는 새 태스크(스레드 · 프로세스)를 만들지 않는다.** 전에는 자식을 띄운 직후
# threading.Timer 로 타이머 스레드를 만들었다. 스레드도 --pids-limit 64 의 한 자리라, 자식이 그보다 먼저
# 64 를 채우면 하네스가 `can't start new thread` 로 죽고 사용자 코드가 SYSTEM_ERROR(우리 잘못)가 됐다.
# 지금은 커널 타이머(setitimer)가 hard limit 에 SIGALRM 을 보내고, 그 처리기가 메인 스레드에서 자식을 죽인다.
# 둘 다 자리를 쓰지 않는다.
#
# **죽이기만 하고 거두지 않는다.** 전의 proc.kill() 은 먼저 poll() - waitpid(pid, WNOHANG) - 을 불렀다.
# 자식이 hard limit 과 같은 순간에 끝나면 타이머 스레드가 그것을 거둬 가고, 메인 스레드의 os.wait4 가
# ChildProcessError 로 하네스를 죽였다(자원 사용량도 함께 잃는다). 이제 거두는 곳은 os.wait4 하나뿐이다.
#
# 신호는 pidfd 로 보낸다. 이미 거둔 뒤에 처리기가 돌아도 같은 번호를 받은 다른 프로세스를 죽이지 않는다.
_alarm_pidfd: int | None = None
_alarm_fired = False


def _on_alarm(signum, frame) -> None:
    """hard limit 에 닿았다. 이 case 의 자식을 죽인다 - 거두지는 않는다(os.wait4 의 몫)."""
    global _alarm_fired
    if _alarm_pidfd is None:
        return
    _alarm_fired = True
    try:
        signal.pidfd_send_signal(_alarm_pidfd, signal.SIGKILL)
    except ProcessLookupError:
        pass


def _arm_hard_limit(pid: int, seconds: float) -> None:
    global _alarm_pidfd, _alarm_fired
    _alarm_pidfd = os.pidfd_open(pid)
    _alarm_fired = False
    signal.signal(signal.SIGALRM, _on_alarm)
    signal.setitimer(signal.ITIMER_REAL, seconds)


def _disarm_hard_limit() -> bool:
    """타이머를 끄고 hard limit 에 닿았었는지 돌려준다."""
    global _alarm_pidfd
    signal.setitimer(signal.ITIMER_REAL, 0)
    pidfd, _alarm_pidfd = _alarm_pidfd, None
    if pidfd is not None:
        os.close(pidfd)
    return _alarm_fired


# -- 사용자 프로그램 띄우기 (ADR-0059) ----------------------------------------
#
# **사용자 프로그램은 하네스가 아니라 작은 실행기(judge/runner/launch.c)의 fork 에서 시작한다.** 하네스가 직접
# fork 하면 자식이 하네스의 사본으로 시작하고, 커널이 exec 때 그 사본의 최고 RSS 를 maxrss 에 남긴다 - 그래서
# memoryKb 가 max(하네스 사본, 사용자 프로그램) 이었다(ADR-0056 D). 실행기는 fork 해 자식이 exec 하게 하고, 자식의
# pid 를 적은 뒤 곧바로 끝난다. 자식은 PID 1(하네스)에게 넘어오고, **거두는 곳은 여전히 run_case 의 os.wait4
# 하나다.** 종료 상태 · rusage · 시간 제한 신호는 전처럼 사용자 프로그램에게서 받고 그에게 보낸다.
LAUNCHER = "/opt/judge/launch"


def _spawn(out, err) -> tuple[subprocess.Popen, int]:
    """실행기로 사용자 프로그램을 띄운다. (실행기의 Popen - stdin 이 여기 있다, 사용자 프로그램의 pid).

    돌아올 때는 사용자 프로그램이 exec 까지 마쳤다 - 전에 subprocess.Popen 이 exec 를 기다린 뒤 돌아온 것과 같다.
    그래서 시간 제한 타이머를 거는 시점도 전과 같다.

    **새 태스크를 만들지 않는다**(ADR-0056). 실행기를 거두고 pid 를 읽는 것은 fd 와 wait4 뿐이다.
    """
    report_r, report_w = os.pipe()
    try:
        launcher = subprocess.Popen(
            [LAUNCHER, str(report_w), *LANGUAGES[LANGUAGE]["run"]],
            stdin=subprocess.PIPE,
            stdout=out,
            stderr=err,
            preexec_fn=_limit_child,  # 실행기에 걸면 사용자 프로그램이 물려받는다
            pass_fds=(report_w,),
        )
    finally:
        os.close(report_w)
    try:
        # 실행기는 pid 를 적고 곧바로 끝난다. 거둔 것을 Popen 에도 적어 둔다 - 그러지 않으면 Popen 이 나중에 그
        # pid 로 waitpid 를 부를 수 있다(ADR-0056 E 와 같은 길).
        _, status, _ = os.wait4(launcher.pid, 0)
        launcher.returncode = os.waitstatus_to_exitcode(status)
        report = b""
        # EOF 는 실행기가 끝나고 사용자 프로그램이 exec 했을 때(close-on-exec) 온다.
        while chunk := os.read(report_r, 256):
            report += chunk
    finally:
        os.close(report_r)
    fields = dict(line.split(" ", 1) for line in report.decode("ascii", "replace").splitlines()
                  if " " in line)
    if "exec" in fields:
        # 전에 Popen 이 FileNotFoundError 로 하네스를 죽이던 자리다. 사용자 잘못이 아니다 - SYSTEM_ERROR.
        raise OSError(int(fields["exec"]), "사용자 프로그램을 exec 하지 못했다")
    if launcher.returncode != 0 or "pid" not in fields:
        raise RuntimeError(f"실행기가 실패했다(종료 코드 {launcher.returncode}, {report!r})")
    return launcher, int(fields["pid"])


def _read_capped(path: pathlib.Path, cap: int) -> tuple[str, bool]:
    """파일 앞부분만 읽는다. 돌려주는 두 번째 값은 '한도를 넘겼는가'."""
    try:
        size = path.stat().st_size
        with path.open("r", encoding="utf-8", errors="replace") as f:
            return f.read(cap), size >= cap
    except OSError:
        return "", False


def run_case(case_input: str, time_limit_ms: int) -> dict:
    """사용자 코드를 한 번 실행하고 **날것의 결과**를 돌려준다.

    정답과 비교하지 않는다. 비교는 호스트가 한다.

    출력은 파이프가 아니라 tmpfs 의 파일로 받는다. 파이프로 받으면 하네스가 그것을
    메모리에 쌓게 되고, 무한 출력하는 코드 하나가 컨테이너 전체를 OOM 으로 끌어내린다.
    파일로 받으면 RLIMIT_FSIZE 가 커널 수준에서 끊어준다.
    """
    hard_limit = time_limit_ms / 1000.0 + 0.5  # soft 초과분을 관측할 여유(Addendum 65)
    out_path = pathlib.Path("/tmp/case-stdout")
    err_path = pathlib.Path("/tmp/case-stderr")

    started = time.monotonic()
    with out_path.open("wb") as out, err_path.open("wb") as err:
        launcher, pid = _spawn(out, err)
        # 여기서부터 자식을 거둘 때까지 새 태스크를 만들지 않는다(ADR-0056). 자식이 곧바로 --pids-limit 을
        # 다 채워도 시간 제한은 걸린다.
        _arm_hard_limit(pid, hard_limit)
        try:
            try:
                # 입력을 읽지 않는 코드면 쓰기가 막힌다. 그때는 SIGALRM 이 쓰기를 끊고 자식을 죽여서 풀어
                # 준다(처리기가 돈 뒤 다시 쓰면 EPIPE).
                launcher.stdin.write(case_input.encode())
                launcher.stdin.close()
            except (BrokenPipeError, OSError):
                pass
            # **이 case 의 사용자 프로그램만** 기다려 그 자원 사용량을 받는다. RUSAGE_CHILDREN 은 지금까지 기다린
            # 모든 자식의 최댓값이라, 컴파일러(g++ 는 약 190MB)가 사용자 코드의 메모리로 둔갑한다.
            # 자식을 거두는 곳은 여기 하나뿐이다 - 시간 제한 처리기는 죽이기만 한다.
            _, status, usage = os.wait4(pid, 0)
            elapsed_ms = int((time.monotonic() - started) * 1000)
        finally:
            killed = _disarm_hard_limit()
    # 출력을 읽기 전에 치운다. 남은 자손이 쓰기를 멈춰야 읽는 값이 이 case 의 것으로 굳는다.
    _clear_leftovers()
    _case_memory.append(int(usage.ru_maxrss))
    if killed:
        # 시간 안에 끝나지 않았지만 **출력 상한을 이미 채웠다면** 출력 폭주다. JVM 은 SIGXFSZ 를 무시해
        # 쓰기가 실패해도 죽지 않고, 실패를 삼키는 코드는 그대로 돌다 시간 제한에 걸린다 - 그때
        # TIME_LIMIT 으로 두면 "느리다" 로 읽힌다.
        if _read_capped(out_path, STDOUT_LIMIT)[1]:
            return {"outcome": "OUTPUT_LIMIT", "stdout": "", "stderr": None,
                    "executionMs": time_limit_ms}
        return {"outcome": "TIME_LIMIT", "stdout": "", "stderr": None,
                "executionMs": time_limit_ms}
    returncode = os.waitstatus_to_exitcode(status)

    stdout_text, stdout_capped = _read_capped(out_path, STDOUT_LIMIT)
    stderr_text, _ = _read_capped(err_path, STDERR_LIMIT)

    # SIGXFSZ(-25) 는 RLIMIT_FSIZE 초과. 파일 크기로도 한 번 더 본다 -
    # 시그널을 무시하도록 만든 코드가 있을 수 있다.
    if returncode == -25 or stdout_capped:
        return {"outcome": "OUTPUT_LIMIT", "stdout": "", "stderr": None,
                "executionMs": elapsed_ms}

    if returncode != 0:
        # 137 / -9 = SIGKILL. 컨테이너 메모리 상한에 걸린 경우가 대부분이다.
        outcome = "MEMORY_LIMIT" if returncode in (137, -9) else "RUNTIME_ERROR"
        # 파이썬의 MemoryError, JVM 의 OutOfMemoryError, C++ 의 std::bad_alloc.
        if any(sign in stderr_text
               for sign in ("MemoryError", "OutOfMemoryError", "std::bad_alloc")):
            outcome = "MEMORY_LIMIT"
        # 단, JVM 은 스레드를 더 만들지 못해도 OutOfMemoryError 라고 적는다("unable to create native thread").
        # 이것은 메모리가 아니라 프로세스 수 상한(pids-limit)에 걸린 것이다 - MEMORY_LIMIT 로 부르면 사용자는
        # 메모리를 줄이려 한다. CI 에서 프로세스 폭주 격리 case 가 이 때문에 가끔 MEMORY_LIMIT 로 나왔다.
        if "unable to create native thread" in stderr_text:
            outcome = "RUNTIME_ERROR"
        return {"outcome": outcome, "stdout": "", "stderr": sanitize_stderr(stderr_text),
                "executionMs": elapsed_ms}

    if elapsed_ms > time_limit_ms:
        return {"outcome": "TIME_LIMIT", "stdout": "", "stderr": None,
                "executionMs": elapsed_ms}

    return {"outcome": "OK", "stdout": stdout_text, "stderr": None,
            "executionMs": elapsed_ms}


# case 마다 잰 최대 RSS(KB). 컴파일러도 하네스도 들어가지 않는다 - 사용자 프로그램은 실행기의 fork 에서
# 시작하므로(ADR-0059) 하네스의 사본이 섞이지 않는다.
_case_memory: list[int] = []


def emit(message: dict) -> None:
    sys.stdout.write(json.dumps(message, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def peak_memory_kb() -> int | None:
    """사용자 코드가 쓴 최대 RSS(KB). case 마다 그 프로그램에게서 잰 값의 최댓값이다 - 컴파일은 빼고 잰다.

    바닥은 실행기(launch.c)의 사본, 약 640KB 다. 그보다 작은 프로그램은 그 값으로 보인다 - 가장 작은 C++ 풀이도
    약 3MB 라 실제로는 닿지 않는다.
    """
    return max(_case_memory) if _case_memory else None


def main() -> int:
    if LANGUAGE not in LANGUAGES:
        # 이미지가 모르는 언어를 박았다. 사용자 잘못이 아니다 - 호스트가 SYSTEM_ERROR 로 돌려준다.
        emit({"type": "protocol_error", "detail": f"모르는 언어: {LANGUAGE}"})
        return 0
    compile_error = (compile_check(SOLUTION) if LANGUAGE == "PYTHON"
                     else compile_native(LANGUAGE))
    if compile_error is not None:
        emit({"type": "compile_error", "stderr": compile_error})
        return 0
    emit({"type": "ready"})

    time_limit_ms = 2000
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            message = json.loads(line)
        except json.JSONDecodeError:
            emit({"type": "protocol_error", "detail": "JSON 이 아닌 줄을 받았다"})
            return 0

        kind = message.get("type")
        if kind == "config":
            time_limit_ms = int(message.get("timeLimitMs", 2000))
        elif kind == "case":
            result = run_case(message.get("input", ""), time_limit_ms)
            emit({"type": "case_result", "id": message.get("id"), **result})
        elif kind == "end":
            break
        else:
            emit({"type": "protocol_error", "detail": f"알 수 없는 type: {kind!r}"})
            return 0

    emit({"type": "done", "memoryKb": peak_memory_kb()})
    return 0


if __name__ == "__main__":
    sys.exit(main())
