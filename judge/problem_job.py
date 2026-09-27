"""문제 하나를 Judge 가 받는 job 으로 옮긴다. **채점과 검증이 같은 함수를 부른다**(ADR-0058).

    job = problem_job.load(ROOT / "problems" / "P01_QUEUE_BASIC")
    # {"problemId", "timeLimitMs", "memoryLimitMb", "cases"}

부르는 곳은 둘이다 - Judge Worker(실서비스 채점 · 제출 전 실행 · 모의고사)와 tools/verify_problems.py(CI).
둘이 job 을 따로 만들던 때, 검증은 problem.yaml 의 제한으로 돌고 Worker 는 cases.json 만 넘겨
**모든 문제가 기본값 2000ms · 컨테이너 256m 로 채점됐다.** 화면은 problem.yaml 의 값(P111 은 1000ms)을
보여 주는데 채점은 그 두 배를 허용했고, 검증은 그 차이를 보지 못했다 - 검증이 실서비스와 다른 job 을
채점했기 때문이다.

제한의 정본은 ``problems/<CODE>/problem.yaml`` 하나다. 백엔드(ProblemCatalog)는 같은 파일에서 화면에
보일 값을 읽는다. 채점 큐의 행(judge_jobs)에는 싣지 않는다 - Worker 는 이미 같은 디렉터리의
cases.json 을 직접 읽고 있었고, 제한도 그 옆에 있다.

**기본값을 두지 않는다.** 없으면 채점하지 않는다. 결함이 바로 "없으면 2000" 이라는 조용한 기본값이었다.
"""
from __future__ import annotations

import json
import pathlib


class ProblemDataError(Exception):
    """문제 데이터로 job 을 만들 수 없다. 사용자 잘못이 아니라 우리 잘못이다."""


def _limit(problem: dict, key: str, source: pathlib.Path) -> int:
    value = problem.get(key)
    # bool 은 int 의 하위 타입이다 - `timeLimitMs: true` 가 1ms 로 채점되지 않게 따로 막는다.
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise ProblemDataError(f"{source} 의 {key} 가 양의 정수가 아니다: {value!r}")
    return value


def load(problem_dir: pathlib.Path) -> dict:
    """``problems/<CODE>/`` 의 problem.yaml 과 cases.json 을 job 하나로 합친다.

    정답(expectedOutput)이 들어 있다. 이 job 은 **호스트에만** 있다 - run_submission.py 가 컨테이너에는
    현재 case 의 input 만 보낸다(ADR-0006).
    """
    import yaml  # 여기서 import 한다 - 이 모듈을 부르지 않는 도구까지 pyyaml 을 요구하지 않게

    cases_path = problem_dir / "cases.json"
    if not cases_path.exists():
        raise ProblemDataError(f"Test Case 파일이 없다: {cases_path}")
    problem_path = problem_dir / "problem.yaml"
    if not problem_path.exists():
        raise ProblemDataError(f"문제 파일이 없다: {problem_path}")

    try:
        problem = yaml.safe_load(problem_path.read_text(encoding="utf-8")) or {}
        cases_doc = json.loads(cases_path.read_text(encoding="utf-8"))
    except (OSError, ValueError, yaml.YAMLError) as e:
        raise ProblemDataError(f"{problem_dir.name} 를 읽지 못했다: {type(e).__name__}: {e}") from e

    return {
        "problemId": problem.get("code", problem_dir.name),
        "timeLimitMs": _limit(problem, "timeLimitMs", problem_path),
        "memoryLimitMb": _limit(problem, "memoryLimitMb", problem_path),
        # case 는 그대로 넘긴다. `hidden` 이 제출 전 실행(--samples-only)의 필터다(ADR-0020).
        "cases": cases_doc.get("cases") or [],
    }
