#!/usr/bin/env python3
"""컨테이너 안에서 도는 채점 하네스.

호스트가 /job 에 read-only 로 마운트한 작업을 읽어 Test Case 를 순서대로 실행하고,
contracts/judge-result.schema.json 형태의 JSON 을 stdout 으로 낸다.

Submission 당 컨테이너 1개, 그 안에서 case 를 순회한다(Addendum 61).
case 마다 컨테이너를 새로 만들면 기동 비용이 실행 시간을 압도한다.

  /job/job.json       문제 메타 + Test Case
  /job/solution.py    사용자 코드 (신뢰할 수 없다)

이 파일은 사용자 코드를 **import 하지 않는다.** 별도 프로세스로 띄우고 stdin/stdout
으로만 통신한다. import 하면 사용자 코드가 이 하네스의 메모리 공간에서 돌아
결과 JSON 자체를 조작할 수 있다.
"""
from __future__ import annotations

import json
import pathlib
import re
import resource
import subprocess
import sys
import time

JOB_DIR = pathlib.Path("/job")
SOLUTION = JOB_DIR / "solution.py"
JOB_FILE = JOB_DIR / "job.json"

# 사용자가 무한 출력으로 디스크/파이프를 채우는 것을 막는다(Addendum 64).
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


def normalize(output: str) -> str:
    """출력 비교 정규화.

    줄 끝 공백과 마지막 개행 차이로 오답 처리하지 않는다. 이건 관용이 아니라
    정확성 문제다 - print() 가 붙이는 개행을 두고 WA 를 내면 사용자는 알고리즘을
    의심하게 되고, 오답 원인 분석 데이터도 그만큼 오염된다.
    """
    return "\n".join(line.rstrip() for line in output.replace("\r\n", "\n").split("\n")).rstrip("\n")


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


def run_case(case: dict, time_limit_ms: int) -> dict:
    """Test Case 하나를 별도 프로세스로 실행한다.

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
                input=case.get("input", "").encode(),
                stdout=out,
                stderr=err,
                timeout=hard_limit,
                preexec_fn=_limit_child,
            )
        returncode = proc.returncode
    except subprocess.TimeoutExpired:
        return {
            "id": case["id"],
            "status": "TIME_LIMIT",
            "executionMs": time_limit_ms,
            "stderr": None,
        }
    elapsed_ms = int((time.monotonic() - started) * 1000)

    stdout_text, stdout_capped = _read_capped(out_path, STDOUT_LIMIT)
    stderr_text, _ = _read_capped(err_path, STDERR_LIMIT)

    # SIGXFSZ(-25) 는 RLIMIT_FSIZE 초과. 파일 크기로도 한 번 더 본다 -
    # 시그널을 무시하도록 만든 코드가 있을 수 있다.
    if returncode == -25 or stdout_capped:
        return {
            "id": case["id"],
            "status": "OUTPUT_LIMIT",
            "executionMs": elapsed_ms,
            "stderr": None,
        }

    if returncode != 0:
        # 137 / -9 = SIGKILL. 컨테이너 메모리 상한에 걸린 경우가 대부분이다.
        status = "MEMORY_LIMIT" if returncode in (137, -9) else "RUNTIME_ERROR"
        if "MemoryError" in stderr_text:
            status = "MEMORY_LIMIT"
        return {
            "id": case["id"],
            "status": status,
            "executionMs": elapsed_ms,
            "stderr": sanitize_stderr(stderr_text),
        }

    if elapsed_ms > time_limit_ms:
        return {"id": case["id"], "status": "TIME_LIMIT", "executionMs": elapsed_ms, "stderr": None}

    if normalize(stdout_text) != normalize(case.get("expectedOutput", "")):
        return {
            "id": case["id"],
            "status": "WRONG_ANSWER",
            "executionMs": elapsed_ms,
            "stderr": None,
        }

    return {"id": case["id"], "status": "ACCEPTED", "executionMs": elapsed_ms, "stderr": None}


def emit(result: dict) -> None:
    sys.stdout.write(json.dumps(result, ensure_ascii=False))
    sys.stdout.flush()


def main() -> int:
    try:
        job = json.loads(JOB_FILE.read_text(encoding="utf-8"))
    except Exception as e:
        emit({
            "status": "SYSTEM_ERROR", "passed": 0, "total": 1,
            "executionMs": None, "memoryKb": None, "failedCaseId": None,
            "stderr": f"job 을 읽지 못했다: {type(e).__name__}", "cases": [],
        })
        return 0

    cases = job.get("cases") or []
    total = len(cases)
    if total == 0:
        emit({
            "status": "SYSTEM_ERROR", "passed": 0, "total": 1,
            "executionMs": None, "memoryKb": None, "failedCaseId": None,
            "stderr": "Test Case 가 없다", "cases": [],
        })
        return 0

    compile_error = compile_check(SOLUTION)
    if compile_error is not None:
        # case 를 하나도 실행하지 못했다. failedCaseId 는 null 이며,
        # 이 상태에서는 Reviewer 를 호출하지 않는다(ADR-0004).
        emit({
            "status": "COMPILE_ERROR", "passed": 0, "total": total,
            "executionMs": None, "memoryKb": None, "failedCaseId": None,
            "stderr": compile_error, "cases": [],
        })
        return 0

    time_limit_ms = int(job.get("timeLimitMs", 2000))
    results: list[dict] = []
    passed = 0
    max_ms = 0

    for case in cases:
        outcome = run_case(case, time_limit_ms)
        results.append({k: outcome[k] for k in ("id", "status", "executionMs")})
        if outcome["executionMs"] is not None:
            max_ms = max(max_ms, outcome["executionMs"])

        if outcome["status"] != "ACCEPTED":
            # 첫 실패에서 멈춘다. 남은 case 를 더 돌려도 판정은 바뀌지 않고,
            # 무한루프 코드에 전체 case 수만큼의 timeout 을 쓰게 된다.
            emit({
                "status": outcome["status"],
                "passed": passed,
                "total": total,
                "executionMs": max_ms,
                "memoryKb": peak_memory_kb(),
                "failedCaseId": outcome["id"],
                "stderr": outcome["stderr"],
                "cases": results,
            })
            return 0
        passed += 1

    emit({
        "status": "ACCEPTED", "passed": passed, "total": total,
        "executionMs": max_ms, "memoryKb": peak_memory_kb(),
        "failedCaseId": None, "stderr": None, "cases": results,
    })
    return 0


def peak_memory_kb() -> int | None:
    """자식 프로세스들의 최대 RSS. Linux 에서 ru_maxrss 는 KB 단위다."""
    try:
        return int(resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss)
    except Exception:
        return None


if __name__ == "__main__":
    sys.exit(main())
