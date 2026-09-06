#!/usr/bin/env python3
"""호스트 측 채점기. 신뢰 경계의 바깥쪽이다.

    python judge/run_submission.py <solution.py> <job.json>

사용자가 제출한 코드는 신뢰할 수 없다. API 서버 프로세스 안에서 직접 실행하지
않는다(Addendum 47). 이 파일도 최종적으로는 API 서버가 아니라 별도 Judge Worker
프로세스에서 호출된다.

컨테이너는 매 제출마다 만들고 버린다(Addendum 50). 재사용하면 앞 제출이 남긴
상태(파일, 환경변수, 좀비 프로세스)가 다음 제출의 판정에 섞인다.

── 신뢰 경계 (ADR-0006) ────────────────────────────────────────────────
컨테이너에 들어가는 것은 **제출 코드와 현재 case 의 input 뿐**이다.

    호스트 (신뢰)                     컨테이너 (신뢰하지 않음)
    ─────────────                     ────────────────────────
    job.json 전체                     solution.py
    expectedOutput      ── input ──>  현재 case 만
    정답 비교           <── stdout ──  사용자 출력
    판정 조립

expectedOutput 을 컨테이너에 넣으면 사용자 코드가 그것을 읽어 그대로 출력할 수 있다.
read-only 마운트는 수정을 막을 뿐 읽기를 막지 않는다.
"""
from __future__ import annotations

import argparse
import json
import os
import pathlib
import shutil
import stat
import subprocess
import sys
import tempfile
import threading
import uuid

IMAGE = "codesprint-judge:py312"

# Addendum 51. 각 옵션이 막는 것을 함께 적는다 - 지우려는 사람이 이유를 알아야 한다.
DOCKER_LIMITS = [
    "--network", "none",              # 외부 통신 차단. 데이터 유출과 원격 도구 다운로드
    "--memory", "256m",               # OOM 으로 호스트를 끌어내리는 것
    "--memory-swap", "256m",          # swap 으로 메모리 상한을 우회하는 것
    "--cpus", "0.5",                  # CPU 독점
    "--pids-limit", "64",             # fork bomb
    "--read-only",                    # 루트 파일시스템 변조
    "--cap-drop", "ALL",              # 리눅스 capability 를 이용한 권한 상승
    "--security-opt", "no-new-privileges",  # setuid 를 통한 권한 상승
    "--tmpfs", "/tmp:rw,noexec,nosuid,size=16m",  # 쓸 곳은 주되 실행은 막는다
]

# 마운트 모드. 사용자가 자기 제출물을 바꿔치기하는 것을 막는다(Addendum 60).
# 상수로 빼둔 이유는 격리 테스트가 대조군을 만들 때 이 값만 뒤집어
# "정말 이것 때문에 막히는가" 를 확인하기 위해서다.
MOUNT_MODE = "ro"

# 컨테이너가 통째로 멈춘 경우의 마지막 방어선(Addendum 65).
# case 별 timeout 은 하네스가 관리한다. 이건 하네스 자체가 죽었을 때를 위한 것이다.
SUBMISSION_HARD_TIMEOUT_S = 30

# 사용자 출력을 호스트가 받을 때의 상한. 하네스도 자체 한도가 있지만,
# 하네스가 고장났을 때 호스트가 무한정 읽는 것을 막는 두 번째 방어선이다.
HOST_LINE_LIMIT = 4 * 1024 * 1024

# 여기서 만나면 남은 case 를 돌리지 않는다(ADR-0015).
#
# 나머지 실패(WRONG_ANSWER / RUNTIME_ERROR)는 프로세스가 이미 끝난 뒤라 다음 case 로
# 넘어가는 비용이 정상 case 와 같다. 반면 이 셋은 **제한에 걸릴 때까지 기다린 것**이라
# 계속 돌리면 case 수만큼의 timeout 을 먹는다 - ADR-0005 가 조기 종료를 택한 이유가
# 그것이고, 그 비용 특성은 그대로 지킨다.
STOP_ON = ("TIME_LIMIT", "MEMORY_LIMIT", "OUTPUT_LIMIT")

# 실패한 뒤 진단을 위해 더 돌리는 데 쓸 수 있는 시간.
#
# 싼 실패라고 해서 case 가 빠른 것은 아니다. 제한 직전까지 돌다가 WRONG_ANSWER 가
# 나는 case 가 이어지면 최악은 여전히 `case 수 x timeLimit` 이고, 그러면
# SUBMISSION_HARD_TIMEOUT_S 에 걸려 **사용자 코드가 느린 것이 SYSTEM_ERROR(우리 잘못)로
# 둔갑한다.**
#
# 판정에는 영향이 없다 - 판정은 첫 실패가 이미 정했고, 여기서 멈추면 실패의 모양이
# 덜 모일 뿐이다. 덜 모이면 뒷받침이 성립하지 않아 확정되지 않는 쪽으로 틀린다.
DIAGNOSTIC_BUDGET_MS = 10_000


def system_error(detail: str, total: int = 1) -> dict:
    return {
        "status": "SYSTEM_ERROR", "passed": 0, "total": total,
        "executionMs": None, "memoryKb": None, "failedCaseId": None,
        "stderr": detail, "cases": [],
    }


def normalize(output: str) -> str:
    """출력 비교 정규화. **호스트에서만 한다.**

    줄 끝 공백과 마지막 개행 차이로 오답 처리하지 않는다. 관용이 아니라 정확성
    문제다 - print() 가 붙이는 개행을 두고 WA 를 내면 사용자는 알고리즘을 의심하게
    되고, 오답 원인 분석 데이터도 그만큼 오염된다.
    """
    return "\n".join(
        line.rstrip() for line in output.replace("\r\n", "\n").split("\n")
    ).rstrip("\n")


def _force_remove(name: str) -> None:
    """컨테이너를 강제 회수한다.

    --rm 은 컨테이너가 **스스로 종료했을 때** 지워준다. hard timeout 으로 docker CLI
    를 끊으면 컨테이너는 계속 돌 수 있다. 이름을 붙여둔 이유가 이것이다 - 끊긴 뒤에도
    지목해서 죽일 수 있어야 한다. 남으면 CPU/메모리를 계속 먹는다.
    """
    subprocess.run(
        ["docker", "rm", "-f", name],
        capture_output=True, text=True, errors="replace", timeout=15,
    )


def _run_protocol(proc: subprocess.Popen, job: dict, total: int) -> dict:
    """하네스와 NDJSON 을 주고받으며 채점한다.

    한 번에 한 case 씩, 보내고 받는다. 양쪽이 번갈아 읽고 쓰므로 파이프가 막히지 않는다.
    """
    def send(message: dict) -> None:
        proc.stdin.write(json.dumps(message, ensure_ascii=False) + "\n")
        proc.stdin.flush()

    def recv() -> dict | None:
        line = proc.stdout.readline(HOST_LINE_LIMIT)
        if not line:
            return None
        try:
            return json.loads(line)
        except json.JSONDecodeError:
            return {"type": "protocol_error", "detail": "하네스 출력이 JSON 이 아니다"}

    hello = recv()
    if hello is None:
        return system_error("하네스가 시작되지 않았다", total)
    if hello.get("type") == "compile_error":
        # case 를 하나도 실행하지 못했다. failedCaseId 는 null 이며,
        # 이 상태에서는 Reviewer 를 호출하지 않는다(ADR-0004).
        return {
            "status": "COMPILE_ERROR", "passed": 0, "total": total,
            "executionMs": None, "memoryKb": None, "failedCaseId": None,
            "stderr": hello.get("stderr"), "cases": [],
        }
    if hello.get("type") != "ready":
        return system_error(f"하네스 handshake 실패: {hello}", total)

    send({"type": "config", "timeLimitMs": int(job.get("timeLimitMs", 2000))})

    results: list[dict] = []
    passed = 0
    max_ms = 0
    spent_ms = 0
    first_failure: dict | None = None

    for case in job["cases"]:
        # 컨테이너에 보내는 것은 input 뿐이다. expectedOutput 은 여기 남는다.
        send({"type": "case", "id": case["id"], "input": case.get("input", "")})
        reply = recv()
        if reply is None or reply.get("type") != "case_result":
            return system_error(f"case {case['id']} 응답이 없다: {reply}", total)

        elapsed = reply.get("executionMs")
        if elapsed is not None:
            max_ms = max(max_ms, int(elapsed))
            spent_ms += int(elapsed)

        outcome = reply.get("outcome")
        if outcome == "OK":
            # 비교는 신뢰 경계 바깥에서 한다 (ADR-0006).
            if normalize(reply.get("stdout", "")) == normalize(case.get("expectedOutput", "")):
                results.append({"id": case["id"], "status": "ACCEPTED", "executionMs": elapsed})
                passed += 1
                continue
            status, stderr = "WRONG_ANSWER", None
        else:
            status, stderr = outcome, reply.get("stderr")

        results.append({"id": case["id"], "status": status, "executionMs": elapsed})
        if first_failure is None:
            # 판정과 failedCaseId 는 **첫** 실패가 정한다. 뒤의 case 를 더 돌려도
            # 이 값은 바뀌지 않는다 - Reviewer 에게 주는 근거가 흔들리면 안 된다.
            first_failure = {"status": status, "id": case["id"], "stderr": stderr}

        if status in STOP_ON:
            # 비싼 실패에서는 멈춘다(ADR-0015). 무한 루프 하나가 case 수만큼의
            # timeout 을 먹는 것이 ADR-0005 가 조기 종료를 택한 이유였고,
            # 그 비용 특성은 그대로 지킨다.
            break
        if spent_ms >= DIAGNOSTIC_BUDGET_MS:
            # 이미 실패했고 예산도 다 썼다. 더 돌려도 판정은 바뀌지 않는다.
            break

    send({"type": "end"})
    done = recv() or {}
    if first_failure is not None:
        return {
            "status": first_failure["status"], "passed": passed, "total": total,
            "executionMs": max_ms, "memoryKb": done.get("memoryKb"),
            "failedCaseId": first_failure["id"], "stderr": first_failure["stderr"],
            "cases": results,
        }
    return {
        "status": "ACCEPTED", "passed": passed, "total": total,
        "executionMs": max_ms, "memoryKb": done.get("memoryKb"),
        "failedCaseId": None, "stderr": None, "cases": results,
    }


def run(solution: pathlib.Path, job_path: pathlib.Path) -> dict:
    try:
        job = json.loads(job_path.read_text(encoding="utf-8"))
        cases = job.get("cases") or []
    except Exception as e:
        return system_error(f"job 을 읽지 못했다: {type(e).__name__}")
    if not cases:
        return system_error("Test Case 가 없다")
    total = len(cases)

    # 마운트할 디렉터리를 따로 만든다. **solution.py 하나만 넣는다** -
    # job.json 을 함께 두면 사용자 코드가 정답표를 읽을 수 있다(ADR-0006).
    workdir = pathlib.Path(tempfile.mkdtemp(prefix="codesprint-judge-"))
    name = f"codesprint-judge-{uuid.uuid4().hex[:16]}"
    proc = None
    try:
        shutil.copyfile(solution, workdir / "solution.py")

        # 컨테이너는 uid 10001(runner)로 돈다. 호스트의 uid 와 다르므로 마운트한
        # 파일을 읽으려면 other 에 읽기 권한이 있어야 한다. mkdtemp 는 0700 이라
        # 그대로 두면 컨테이너가 solution.py 를 못 읽는다.
        # Docker Desktop(Windows/macOS)은 마운트에서 unix 권한을 무시해 이 문제가
        # 로컬에서는 드러나지 않는다. Linux CI 에서만 터졌다.
        os.chmod(workdir, stat.S_IRWXU | stat.S_IRGRP | stat.S_IXGRP | stat.S_IROTH | stat.S_IXOTH)
        os.chmod(workdir / "solution.py",
                 stat.S_IRUSR | stat.S_IWUSR | stat.S_IRGRP | stat.S_IROTH)

        cmd = [
            "docker", "run", "--rm", "-i",
            "--name", name,                          # hard timeout 후 지목해 죽이기 위해
            "-v", f"{workdir}:/job:{MOUNT_MODE}",    # 바인드 마운트(Addendum 60)
            *DOCKER_LIMITS,
            IMAGE,
        ]
        try:
            proc = subprocess.Popen(
                cmd,
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                text=True, errors="replace", bufsize=1,
            )
        except FileNotFoundError:
            return system_error("docker 를 찾지 못했다", total)

        # 프로토콜은 블로킹 읽기라 하네스가 멈추면 여기도 멈춘다.
        # 감시 타이머가 컨테이너를 죽이면 파이프가 닫히고 읽기가 풀린다.
        timed_out = threading.Event()

        def watchdog() -> None:
            timed_out.set()
            _force_remove(name)

        timer = threading.Timer(SUBMISSION_HARD_TIMEOUT_S, watchdog)
        timer.start()
        try:
            result = _run_protocol(proc, job, total)
        except (BrokenPipeError, OSError) as e:
            result = system_error(f"하네스와의 통신이 끊겼다: {type(e).__name__}", total)
        finally:
            timer.cancel()

        if timed_out.is_set():
            return system_error("컨테이너가 제한 시간 안에 끝나지 않았다", total)
        return result
    finally:
        if proc is not None:
            for pipe in (proc.stdin, proc.stdout, proc.stderr):
                try:
                    if pipe:
                        pipe.close()
                except OSError:
                    pass
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                proc.kill()
        # --rm 에 기대지 않고 잔존을 직접 확인해 회수한다.
        _force_remove(name)
        shutil.rmtree(workdir, ignore_errors=True)


def main() -> int:
    parser = argparse.ArgumentParser(description="제출 하나를 샌드박스에서 채점한다")
    parser.add_argument("solution", type=pathlib.Path)
    parser.add_argument("job", type=pathlib.Path)
    args = parser.parse_args()

    for path in (args.solution, args.job):
        if not path.exists():
            print(json.dumps(system_error(f"파일이 없다: {path.name}"), ensure_ascii=False))
            return 1

    print(json.dumps(run(args.solution, args.job), ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
