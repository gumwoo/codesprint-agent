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
import subprocess
import sys
import threading
import time

# -- 언어 (ADR-0045) ---------------------------------------------------------
#
# **언어는 이미지가 정한다.** judge/Dockerfile(.cpp/.java) 의 ENV 가 이 값을 박아 둔다 - 사용자 입력으로
# 받지 않는다. 호스트는 제출 행의 language 로 이미지를 고르고, 이미지 안에서는 바꿀 수 없다.
#
# 컴파일 산출물은 /build(실행 가능한 tmpfs)에 둔다. /job 은 읽기 전용이고 /tmp 는 noexec 다 - 그
# 둘은 그대로 둔다. C++ 는 어차피 사용자의 기계어가 돌므로 /build 의 실행 권한이 더 주는 것이 없다.
LANGUAGE = os.environ.get("JUDGE_LANGUAGE", "PYTHON")

# JVM 은 스레드를 여럿 띄운다. 컨테이너의 --pids-limit 과 자식의 RLIMIT_NPROC(64) 안에 들도록
# 병렬 GC 와 JIT 스레드를 줄인다. 힙은 컨테이너 메모리(256m) 안에 둔다 - 넘으면 JVM 이 아니라 커널이
# 죽이고, 그때 판정이 OutOfMemoryError 가 아니라 SIGKILL 이 된다.
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
    """컴파일러에 거는 제한. 프로세스 수는 사용자 코드와 같고, 파일 크기는 실행 파일을 쓸 만큼 준다."""
    resource.setrlimit(resource.RLIMIT_NPROC, (64, 64))
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

    컨테이너의 --pids-limit 은 컨테이너 전체에 걸리므로, 사용자 코드가 프로세스를
    쏟아내면 하네스 자신도 fork 하지 못해 결과를 못 낸다. 자식에게 따로 걸어
    하네스의 몫을 남긴다.

    RLIMIT_FSIZE 는 출력 폭주를 커널에서 끊는다. 이게 없으면 커널이 아니라 하네스가
    출력을 다 받아야 하고, 그러다 컨테이너 메모리 상한에 먼저 걸려 **OUTPUT_LIMIT 이
    MEMORY_LIMIT 으로 둔갑한다.** 실제로 그렇게 나왔다.
    한도를 넘겨 쓰면 커널이 SIGXFSZ 를 보낸다.
    """
    resource.setrlimit(resource.RLIMIT_NPROC, (64, 64))
    resource.setrlimit(resource.RLIMIT_FSIZE, (STDOUT_LIMIT, STDOUT_LIMIT))


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
    killed = threading.Event()
    with out_path.open("wb") as out, err_path.open("wb") as err:
        proc = subprocess.Popen(
            LANGUAGES[LANGUAGE]["run"],
            stdin=subprocess.PIPE,
            stdout=out,
            stderr=err,
            preexec_fn=_limit_child,
        )

        def kill() -> None:
            killed.set()
            proc.kill()

        timer = threading.Timer(hard_limit, kill)
        timer.start()
        try:
            # 입력을 읽지 않는 코드면 쓰기가 막힌다. 그때는 타이머가 죽여서 풀어 준다.
            proc.stdin.write(case_input.encode())
            proc.stdin.close()
        except (BrokenPipeError, OSError):
            pass
        # **이 case 의 자식만** 기다려 그 자원 사용량을 받는다. RUSAGE_CHILDREN 은 지금까지 기다린
        # 모든 자식의 최댓값이라, 컴파일러(g++ 는 약 190MB)가 사용자 코드의 메모리로 둔갑한다.
        _, status, usage = os.wait4(proc.pid, 0)
        timer.cancel()
    elapsed_ms = int((time.monotonic() - started) * 1000)
    _case_memory.append(int(usage.ru_maxrss))
    if killed.is_set():
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


# case 마다 잰 최대 RSS(KB). 컴파일러는 들어가지 않는다.
_case_memory: list[int] = []


def emit(message: dict) -> None:
    sys.stdout.write(json.dumps(message, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def peak_memory_kb() -> int | None:
    """사용자 코드가 쓴 최대 RSS(KB). case 마다 그 자식에게서 잰 값의 최댓값이다 - 컴파일은 빼고 잰다."""
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
