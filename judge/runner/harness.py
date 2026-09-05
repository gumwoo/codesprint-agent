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
import pathlib
import re
import resource
import subprocess
import sys
import time

SOLUTION = pathlib.Path("/job/solution.py")

# 사용자가 무한 출력으로 파이프를 채우는 것을 막는다(Addendum 64).
STDOUT_LIMIT = 1024 * 1024
STDERR_LIMIT = 256 * 1024

# stderr 에서 지워야 하는 것들. Host 경로가 사용자에게 노출되면 안 된다(Addendum 63).
_PATH_NOISE = re.compile(r'File "([^"]*/)?([^"/]+)"')


def sanitize_stderr(text: str) -> str | None:
    """traceback 의 경로를 파일명만 남기고 잘라낸다."""
    if not text:
        return None
    cleaned = _PATH_NOISE.sub(lambda m: f'File "{m.group(2)}"', text)
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
    try:
        with out_path.open("wb") as out, err_path.open("wb") as err:
            proc = subprocess.run(
                [sys.executable, str(SOLUTION)],
                input=case_input.encode(),
                stdout=out,
                stderr=err,
                timeout=hard_limit,
                preexec_fn=_limit_child,
            )
        returncode = proc.returncode
    except subprocess.TimeoutExpired:
        return {"outcome": "TIME_LIMIT", "stdout": "", "stderr": None,
                "executionMs": time_limit_ms}
    elapsed_ms = int((time.monotonic() - started) * 1000)

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
        if "MemoryError" in stderr_text:
            outcome = "MEMORY_LIMIT"
        return {"outcome": outcome, "stdout": "", "stderr": sanitize_stderr(stderr_text),
                "executionMs": elapsed_ms}

    if elapsed_ms > time_limit_ms:
        return {"outcome": "TIME_LIMIT", "stdout": "", "stderr": None,
                "executionMs": elapsed_ms}

    return {"outcome": "OK", "stdout": stdout_text, "stderr": None,
            "executionMs": elapsed_ms}


def emit(message: dict) -> None:
    sys.stdout.write(json.dumps(message, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def peak_memory_kb() -> int | None:
    """자식 프로세스들의 최대 RSS. Linux 에서 ru_maxrss 는 KB 단위다."""
    try:
        return int(resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss)
    except Exception:
        return None


def main() -> int:
    compile_error = compile_check(SOLUTION)
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
