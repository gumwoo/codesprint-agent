#!/usr/bin/env python3
"""문제 데이터(problems/)를 검사한다.

커리큘럼과 같은 원칙이다 - 문제는 문서가 아니라 검증되는 데이터다.
Skill/Mistake 참조가 실재하는지, 가중치가 맞는지, Test Case 가 비어 있지 않은지 본다.

**Docker 가 필요한 검사는 여기 없다.** 정답이 실제로 AC 를 받는지, 오답이 실제로
걸리는지는 tools/verify_problems.py 가 Judge 로 확인한다. 두 단계를 나눈 이유는
이 검사가 1초 미만이고 Docker 없이도 돌아야 하기 때문이다.

    python tools/check_problems.py
"""
from __future__ import annotations

import json
import pathlib
import re
import sys

import yaml
from jsonschema import Draft202012Validator

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
sys.stderr.reconfigure(encoding="utf-8", errors="replace")

ROOT = pathlib.Path(__file__).resolve().parent.parent
PROBLEMS = ROOT / "problems"
CURRICULUM = ROOT / "curriculum"
CONTRACTS = ROOT / "contracts"

DIR_RE = re.compile(r"^P[0-9]{2}_[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$")
REQUIRED_FILES = ("problem.yaml", "cases.json", "reference.py", "wrong.py")

failures: list[str] = []


def fail(check: str, detail: str) -> None:
    failures.append(f"[{check}] {detail}")


def load(path: pathlib.Path):
    try:
        if path.suffix == ".json":
            return json.loads(path.read_text(encoding="utf-8"))
        return yaml.safe_load(path.read_text(encoding="utf-8"))
    except Exception as e:
        fail("load", f"{path.relative_to(ROOT)}: {type(e).__name__} - {e}")
        return None


def main() -> int:
    if not PROBLEMS.exists():
        fail("load", "problems/ 가 없다")
        return report()

    skills = {s["code"]: s for s in (load(CURRICULUM / "skills.yaml") or {}).get("skills", [])}
    mistakes = {m["code"]: m for m in (load(CURRICULUM / "mistakes.yaml") or {}).get("mistakes", [])}
    problem_schema = Draft202012Validator(load(CONTRACTS / "problem.schema.json"))
    cases_schema = Draft202012Validator(load(CONTRACTS / "test-cases.schema.json"))

    seen_codes: set[str] = set()
    drill_targets: set[str] = set()
    covered_skills: set[str] = set()

    dirs = sorted(d for d in PROBLEMS.iterdir() if d.is_dir())
    for d in dirs:
        rel = d.name
        if not DIR_RE.match(rel):
            fail("layout", f"{rel}: 디렉터리 이름이 P01_LIKE_THIS 형식이 아니다")
        for name in REQUIRED_FILES:
            if not (d / name).exists():
                fail("layout", f"{rel}: {name} 이 없다")
        if not all((d / n).exists() for n in REQUIRED_FILES):
            continue

        problem = load(d / "problem.yaml")
        cases_doc = load(d / "cases.json")
        if problem is None or cases_doc is None:
            continue

        for err in problem_schema.iter_errors(problem):
            fail("problem-schema", f"{rel}{list(err.path)}: {err.message}")
        for err in cases_schema.iter_errors(cases_doc):
            fail("cases-schema", f"{rel}{list(err.path)}: {err.message}")

        code = problem.get("code")
        if code != rel:
            fail("layout", f"{rel}: code({code!r})와 디렉터리 이름이 다르다")
        if code in seen_codes:
            fail("problem", f"{code}: 중복된 문제 code")
        seen_codes.add(code)

        # -- Skill 매핑 --
        entries = problem.get("skills") or []
        primaries = [s for s in entries if s.get("role") == "PRIMARY"]
        if len(primaries) != 1:
            fail("skill-map", f"{rel}: PRIMARY 는 정확히 하나여야 한다 (지금 {len(primaries)}개)")
        total = round(sum(float(s.get("weight", 0)) for s in entries), 6)
        if total != 1.0:
            # 가중치 합이 1 이 아니면 Evidence 배분이 조용히 어긋난다.
            fail("skill-map", f"{rel}: weight 합이 1.0 이 아니다 ({total})")
        for s in entries:
            sc = s.get("code")
            if sc not in skills:
                fail("skill-map", f"{rel}: skills.yaml 에 없는 Skill {sc!r}")
            else:
                covered_skills.add(sc)
        if len({s.get("code") for s in entries}) != len(entries):
            fail("skill-map", f"{rel}: 같은 Skill 이 두 번 들어 있다")

        # -- Mistake 참조 --
        for mc in problem.get("commonMistakes") or []:
            if mc not in mistakes:
                fail("mistake-ref", f"{rel}: mistakes.yaml 에 없는 Mistake {mc!r}")

        # -- Test Case --
        cases = cases_doc.get("cases") or []
        ids = [c.get("id") for c in cases]
        if len(set(ids)) != len(ids):
            fail("cases", f"{rel}: case id 가 중복된다")
        samples = [c for c in cases if c.get("type") == "SAMPLE"]
        hidden = [c for c in cases if c.get("hidden")]
        if not samples:
            fail("cases", f"{rel}: SAMPLE case 가 없다 (사용자에게 보여줄 예시가 없다)")
        if any(c.get("hidden") for c in samples):
            fail("cases", f"{rel}: SAMPLE 인데 hidden 이다")
        if not hidden:
            # 전부 공개면 Hidden Test 라는 말이 성립하지 않는다.
            fail("cases", f"{rel}: hidden case 가 없다")
        for c in cases:
            if not str(c.get("expectedOutput", "")).strip():
                fail("cases", f"{rel} case {c.get('id')}: expectedOutput 이 비어 있다")

        if problem.get("kind") == "MICRO_DRILL" and primaries:
            drill_targets.add(primaries[0]["code"])

    # -- 자동 드릴이 갈 곳이 있는가 --
    # auto_drill 이 켜진 Mistake 는 그 target_skill 을 노리는 MICRO_DRILL 이 있어야 한다.
    # 없으면 Decision Engine 이 MICRO_DRILL 을 고르고도 줄 문제가 없다 - 런타임에
    # "다음 행동이 없음" 으로 나타나며 원인이 데이터에 있다는 것을 알기 어렵다.
    for code, m in mistakes.items():
        if not m.get("auto_drill"):
            continue
        target = m.get("target_skill")
        if target not in drill_targets:
            fail(
                "drill-coverage",
                f"{code}: auto_drill 대상 {target} 을 노리는 MICRO_DRILL 문제가 없다",
            )

    # -- 활성 Skill 에 문제가 있는가 --
    for code in sorted(skills):
        if code not in covered_skills:
            fail("skill-coverage", f"{code}: 이 Skill 을 다루는 문제가 하나도 없다")

    return report(len(dirs))


def report(count: int = 0) -> int:
    if failures:
        print(f"\n[FAIL] 문제 데이터 검사 실패 ({len(failures)}건):")
        for f in failures:
            print("  - " + f)
        return 1
    print(f"[OK] 문제 데이터 검사 통과 (문제 {count}개)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
