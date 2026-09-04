#!/usr/bin/env python3
"""호스트 측 채점 실행기.

제출 하나를 일회용 컨테이너에서 실행하고 JudgeResult 를 돌려준다.

    python judge/run_submission.py <solution.py> <job.json>

사용자가 제출한 코드는 신뢰할 수 없다. API 서버 프로세스 안에서 직접 실행하지
않는다(Addendum 47). 이 파일도 최종적으로는 API 서버가 아니라 별도 Judge Worker
프로세스에서 호출된다.

컨테이너는 매 제출마다 만들고 버린다(Addendum 50). 재사용하면 앞 제출이 남긴
상태(파일, 환경변수, 좀비 프로세스)가 다음 제출의 판정에 섞인다.
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

# 작업 디렉터리 마운트 모드. 사용자 코드가 Test Case 나 자기 제출물을 고칠 수 있으면
# 오답을 정답으로 만들 수 있다(Addendum 60). 상수로 빼둔 이유는 격리 테스트가
# 대조군을 만들 때 이 값만 뒤집어 "정말 이것 때문에 막히는가"를 확인하기 위해서다.
MOUNT_MODE = "ro"

# 컨테이너가 통째로 멈춘 경우의 마지막 방어선(Addendum 65).
# case 별 timeout 은 하네스가 관리한다. 이건 하네스 자체가 죽었을 때를 위한 것이다.
SUBMISSION_HARD_TIMEOUT_S = 30


def system_error(detail: str, total: int = 1) -> dict:
    return {
        "status": "SYSTEM_ERROR",
        "passed": 0,
        "total": total,
        "executionMs": None,
        "memoryKb": None,
        "failedCaseId": None,
        "stderr": detail,
        "cases": [],
    }


def run(solution: pathlib.Path, job: pathlib.Path) -> dict:
    try:
        total = len(json.loads(job.read_text(encoding="utf-8")).get("cases") or []) or 1
    except Exception:
        total = 1

    # 마운트할 디렉터리를 따로 만든다. 저장소 경로를 그대로 마운트하면 컨테이너에
    # 보여줄 생각이 없던 파일까지 들어간다.
    workdir = pathlib.Path(tempfile.mkdtemp(prefix="codesprint-judge-"))
    try:
        shutil.copyfile(solution, workdir / "solution.py")
        shutil.copyfile(job, workdir / "job.json")

        # 컨테이너는 uid 10001(runner)로 돈다. 호스트의 uid 와 다르므로 마운트한
        # 파일을 읽으려면 other 에 읽기 권한이 있어야 한다.
        # mkdtemp 는 0700 으로 만들고 그 안의 파일도 원본 권한을 따라가므로,
        # 그대로 두면 컨테이너가 job.json 을 못 읽고 SYSTEM_ERROR 가 난다.
        #
        # Docker Desktop(Windows/macOS)은 마운트에서 unix 권한을 무시하기 때문에
        # 이 문제가 로컬에서는 드러나지 않는다. Linux CI 에서만 터졌다.
        os.chmod(workdir, stat.S_IRWXU | stat.S_IRGRP | stat.S_IXGRP | stat.S_IROTH | stat.S_IXOTH)
        for name in ("solution.py", "job.json"):
            os.chmod(workdir / name, stat.S_IRUSR | stat.S_IWUSR | stat.S_IRGRP | stat.S_IROTH)

        cmd = [
            "docker", "run", "--rm",
            "-v", f"{workdir}:/job:{MOUNT_MODE}",   # 바인드 마운트(Addendum 60)
            *DOCKER_LIMITS,
            IMAGE,
        ]
        try:
            proc = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                errors="replace",
                timeout=SUBMISSION_HARD_TIMEOUT_S,
            )
        except subprocess.TimeoutExpired:
            return system_error("컨테이너가 제한 시간 안에 끝나지 않았다", total)
        except FileNotFoundError:
            return system_error("docker 를 찾지 못했다", total)

        if not proc.stdout.strip():
            # 하네스가 결과를 못 냈다. 컨테이너 자체가 죽은 경우(OOM 등)가 여기 온다.
            detail = (proc.stderr or "").strip()[:400] or f"exit {proc.returncode}"
            if proc.returncode == 137:
                return {**system_error("", total), "status": "MEMORY_LIMIT",
                        "stderr": "컨테이너가 메모리 상한에서 종료됐다"}
            return system_error(f"하네스가 결과를 내지 못했다: {detail}", total)

        try:
            return json.loads(proc.stdout)
        except json.JSONDecodeError:
            return system_error("하네스 출력이 JSON 이 아니다", total)
    finally:
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
