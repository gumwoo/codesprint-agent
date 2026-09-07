#!/usr/bin/env python3
"""stderr sanitize 규칙. 정본: Addendum 63.

`test_judge.py` 는 실제 컨테이너를 띄워 대표 traceback 하나를 본다. 그것만으로는
**정규식이 다루는 형태 하나만** 확인한 것이다 - 실제로 그랬다. `File "..."` 만 지우는
정규식이었는데, 그 형태가 아닌 예외 메시지의 경로는 그대로 나가고 있었다.

    FileNotFoundError: [Errno 2] No such file or directory: '/job/job.json'

여기서는 Docker 없이 함수만 불러 형태별로 확인한다. 이 값은 **화면까지 가므로**
지우는 것과 남기는 것이 둘 다 중요하다 - 경로를 지우다 파일명과 줄 번호까지 지우면
사용자가 어디를 고쳐야 하는지 알 수 없다.

    python judge/tests/test_sanitize.py
"""
from __future__ import annotations

import pathlib
import re
import sys
import types

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent


def load_sanitizer():
    """harness.py 에서 sanitize 부분만 가져온다.

    통째로 import 하지 않는 이유는 그 모듈이 리눅스 전용 `resource` 를 쓰기 때문이다.
    개발 기계가 Windows 여도 이 규칙은 확인할 수 있어야 한다.
    """
    source = (ROOT / "judge" / "runner" / "harness.py").read_text(encoding="utf-8")
    head = source.split("def compile_check")[0]
    body = head.split("import time", 1)[1].replace(
        'SOLUTION = pathlib.Path("/job/solution.py")', "")
    module = types.ModuleType("sanitize")
    module.re = re
    exec(compile(body, "harness.py(sanitize)", "exec"), module.__dict__)  # noqa: S102
    return module


sanitize = load_sanitizer()

# (설명, 입력, 남아 있어야 하는 것들, 사라져야 하는 것들)
#
# 사라져야 하는 것은 **그 입력에 실제로 들어 있던 경로**를 적는다. "/job" 처럼 짧은
# 조각으로 보면 `/job.json` 같은 파일 이름에 걸려 잡히지도 않을 것을 잡는다.
CASES = [
    (
        "traceback 의 경로는 파일명만 남는다",
        'Traceback (most recent call last):\n'
        '  File "/job/solution.py", line 7, in <module>\n'
        "IndexError: list index out of range",
        ["solution.py", "line 7", "IndexError"],
        ["/job/solution.py", "/job/"],
    ),
    (
        "예외 메시지 안의 경로도 가린다",
        "FileNotFoundError: [Errno 2] No such file or directory: '/job/job.json'",
        # 무엇을 열려 했는지는 남아야 한다. 사용자가 쓴 이름이다.
        ["FileNotFoundError", "job.json"],
        ["/job/job.json", "/job/"],
    ),
    (
        "하네스 자리를 알려 주지 않는다",
        "PermissionError: [Errno 13] Permission denied: '/opt/judge/harness.py'",
        ["PermissionError", "harness.py"],
        ["/opt/judge", "/opt/"],
    ),
    (
        "임시 디렉터리도 가린다",
        "OSError: cannot write /tmp/case-stdout",
        ["OSError"],
        ["/tmp/case-stdout", "/tmp/"],
    ),
    (
        "그냥 출력한 현재 위치도 가린다",
        "현재 위치: /job",
        ["현재 위치"],
        ["/job"],
    ),
    (
        "표준 라이브러리 경로는 건드리지 않는다",
        # 우리 구조가 아니라 파이썬 배포판의 것이다. 지우면 실제 오류를 읽기 어렵다.
        "RuntimeError: /usr/lib/python3.12/json/decoder.py 를 열지 못했다",
        ["/usr/lib/python3.12/json/decoder.py"],
        (),
    ),
    (
        "이름만 겹치는 경로는 그대로 둔다",
        "OSError: cannot open /jobs2/data",
        ["/jobs2/data"],
        (),
    ),
]


def main() -> int:
    failed = 0

    for name, text, keep, drop in CASES:
        out = sanitize.sanitize_stderr(text) or ""
        missing = [k for k in keep if k not in out]
        leaked = [d for d in drop if d in out]
        if missing:
            failed += 1
            print(f"[X] {name}: 남아야 할 것이 사라졌다 {missing}\n    {out!r}")
        elif leaked:
            failed += 1
            print(f"[X] {name}: 지워야 할 것이 남았다 {leaked}\n    {out!r}")
        else:
            print(f"[O] {name}")

    # 빈 입력은 null 이다. "에러 없이 끝났다" 와 "메시지가 비어 있다" 는 다르다.
    if sanitize.sanitize_stderr("") is not None:
        failed += 1
        print("[X] 빈 stderr 가 null 이 아니다")
    else:
        print("[O] 빈 stderr 는 null 이다")

    # 긴 출력은 잘린다. 자른 사실을 알려야 사용자가 뒤가 더 있는지 안다.
    long_text = "x" * (sanitize.STDERR_LIMIT + 1000)
    cut = sanitize.sanitize_stderr(long_text)
    if len(cut) > sanitize.STDERR_LIMIT + 100:
        failed += 1
        print(f"[X] 긴 stderr 가 잘리지 않았다 ({len(cut)}자)")
    elif "생략" not in cut:
        failed += 1
        print("[X] 잘라 놓고 알리지 않는다 - 뒤가 더 있는지 알 수 없다")
    else:
        print(f"[O] 긴 stderr 는 {sanitize.STDERR_LIMIT}자에서 자르고 알린다")

    if failed:
        print(f"\n[FAIL] sanitize 규칙 {failed}건 실패")
        return 1
    print(f"\n[OK] sanitize 규칙 {len(CASES) + 2}건 통과")
    return 0


if __name__ == "__main__":
    sys.exit(main())
