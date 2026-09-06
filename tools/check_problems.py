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
# probes/<MISTAKE>.py 는 선택이다 - cases.json 이 그 실수를 겨냥할 때만 요구한다.

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
    control_mistakes: dict[str, list[str]] = {}
    drill_targets: set[str] = set()
    covered_skills: set[str] = set()
    # NORMAL 문제가 PRIMARY 로 겨냥하는 Skill. CHANGE_SKILL 의 착지점이다.
    normal_primary: set[str] = set()

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

        # -- 공개 저장소에는 fixture 만 (ADR-0008) --
        # 이 저장소는 public 이다. CURATED 문제를 넣으면 Hidden Test 와 정답 풀이가
        # 그대로 공개된다 - 그러면 "Hidden" 이라는 말이 성립하지 않는다.
        if problem.get("source") != "DEV_FIXTURE":
            fail("visibility",
                 f"{rel}: 공개 저장소에는 source: DEV_FIXTURE 만 둔다 "
                 f"(지금 {problem.get('source')!r})")

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

        # -- case 성격 태그 (probes) --
        #
        # "그 실수가 있으면 이 case 는 반드시 실패한다" 는 주장이다. 주장이므로
        # 여기서는 **주장이 확인 가능한 형태인지**만 본다 - 실제로 실패하는지는
        # tools/verify_problems.py 가 진짜로 채점해서 확인한다(ADR-0015).
        tagged: dict[str, list[int]] = {}
        for c in cases:
            for mc in c.get("probes") or []:
                tagged.setdefault(mc, []).append(c.get("id"))

        probe_dir = d / "probes"
        for mc, case_ids in sorted(tagged.items()):
            if mc not in mistakes:
                fail("probe", f"{rel}: mistakes.yaml 에 없는 Mistake {mc!r} 를 겨냥한다")
                continue
            if mistakes[mc].get("assigned_by") != "REVIEWER":
                # SYSTEM 이 부여하는 Mistake 는 Reviewer 가 주장하지 않으므로
                # 뒷받침할 일도 없다. 태그해 두면 절대 쓰이지 않는 데이터가 된다.
                fail("probe",
                     f"{rel}: {mc} 는 assigned_by 가 REVIEWER 가 아니다 "
                     f"- Reviewer 주장을 뒷받침하는 태그로 쓸 수 없다")
            if mc not in (problem.get("commonMistakes") or []):
                fail("probe", f"{rel}: {mc} 를 겨냥하면서 commonMistakes 에는 없다")

            # 대조군이 될 수 있는 case 가 있어야 한다. 모든 case 가 같은 실수를
            # 겨냥하면 "그 실수가 아닌 것도 통과한다" 를 보일 수 없고, 전부 실패한
            # 제출이 무조건 그 실수로 뒷받침된다.
            if len(case_ids) == len(cases):
                fail("probe",
                     f"{rel}: 모든 case 가 {mc} 를 겨냥한다 - 대조군이 없다")

        # 태그가 있으면 **경쟁하는 실수 전부**의 오답이 저장소에 있어야 한다.
        #
        # 하나의 오답만으로는 부족하다. 실제로 그랬다 - P05 와 P10 의
        # BOUNDARY_CHECK 태그를 OUTPUT_FORMAT 오답이 그대로 만족했고, 그 상태로
        # 두면 출력 형식만 틀린 사용자가 경계 검사 드릴을 받는다. 태그가 이 문제의
        # 어떤 실수와도 구별되는지는 verify_problems.py 가 실제 채점으로 본다.
        control = (problem.get("negativeControl") or {}).get("mistake")
        if tagged:
            for mc in problem.get("commonMistakes") or []:
                if mc == control:
                    continue  # 그 실수의 오답은 wrong.py 다(ADR-0007)
                if not (probe_dir / f"{mc}.py").exists():
                    fail("probe",
                         f"{rel}: 태그가 있는 문제인데 probes/{mc}.py 가 없다 "
                         f"- {mc} 와 구별되는지 확인할 방법이 없다")

        if probe_dir.exists():
            common = set(problem.get("commonMistakes") or [])
            for f in sorted(probe_dir.glob("*.py")):
                if f.stem == control:
                    fail("probe",
                         f"{rel}: probes/{f.name} 는 negativeControl 과 같은 실수다 "
                         f"- 그 오답은 wrong.py 하나로 둔다")
                elif f.stem not in common:
                    fail("probe",
                         f"{rel}: probes/{f.name} 가 commonMistakes 에 없다 "
                         f"- 아무와도 대조되지 않는 파일이다")

        # -- negativeControl --
        # wrong.py 가 "실패했는가" 가 아니라 "의도한 이유로 실패했는가" 를 확인하려면
        # 무엇을 심었는지가 데이터에 있어야 한다.
        nc = problem.get("negativeControl") or {}
        nc_mistake = nc.get("mistake")
        if nc_mistake not in mistakes:
            fail("negative-control", f"{rel}: mistakes.yaml 에 없는 negativeControl.mistake {nc_mistake!r}")
        elif nc_mistake not in (problem.get("commonMistakes") or []):
            # 심어둔 실수가 그 문제의 "자주 나오는 실수" 가 아니면 둘 중 하나가 틀렸다.
            fail("negative-control",
                 f"{rel}: negativeControl.mistake {nc_mistake} 가 commonMistakes 에 없다")

        if problem.get("kind") == "NORMAL" and primaries:
            normal_primary.add(primaries[0]["code"])

        if problem.get("kind") == "MICRO_DRILL" and primaries:
            drill_targets.add(primaries[0]["code"])
            # MICRO_DRILL 은 자기가 교정하려는 실수를 심어둬야 한다.
            drill_mistake = (mistakes.get(nc_mistake) or {}).get("target_skill")
            if drill_mistake != primaries[0]["code"]:
                fail("negative-control",
                     f"{rel}: MICRO_DRILL 인데 negativeControl.mistake({nc_mistake})가 "
                     f"PRIMARY Skill({primaries[0]['code']})을 겨냥하지 않는다")
        if nc_mistake:
            control_mistakes.setdefault(nc_mistake, []).append(rel)

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

    # -- CHANGE_SKILL 이 갈 곳이 있는가 --
    #
    # Decision Engine 은 선수 조건이 미충족이면 그 Skill 로 보낸다(CHANGE_SKILL).
    # 그때 주는 것은 **일반 문제**다 - 드릴은 이미 배운 것을 좁게 다시 보는 것이라
    # 아직 시작도 안 한 Skill 에 주면 맥락이 없다(NextProblemService).
    #
    # 그래서 선수 조건으로 지목될 수 있는 Skill 에는 그것을 PRIMARY 로 갖는 NORMAL
    # 문제가 있어야 한다. 없으면 사용자가 추천을 따라갔을 때 "줄 문제가 없다" 로
    # 끝난다 - 화면을 붙이고 나서야 눈에 보인 구멍이다.
    #
    # SECONDARY 로 거드는 것은 세지 않는다. 문제를 고르는 쪽이 PRIMARY 만 보기 때문이다.
    prerequisites = load(CURRICULUM / "prerequisites.yaml") or {}
    targets = {row["requires"] for row in prerequisites.get("prerequisites", [])}
    for code in sorted(targets):
        if code not in normal_primary:
            fail(
                "change-skill-landing",
                f"{code}: 선수 조건으로 지목되는데 그것을 PRIMARY 로 갖는 NORMAL 문제가 "
                f"없다 - CHANGE_SKILL 이 갈 곳 없는 Skill 을 가리키게 된다",
            )

    # -- 오답 라벨 분포 --
    # IMPLEMENTATION_MISC 가 많다는 것은 taxonomy 가 실제 오답을 담지 못한다는 신호다
    # (curriculum/mistakes.yaml 의 IMPLEMENTATION_MISC 설명). 실패는 아니지만 보여준다.
    misc = control_mistakes.get("IMPLEMENTATION_MISC", [])
    if misc and len(misc) * 2 >= len(dirs):
        print(f"[!] negativeControl 의 {len(misc)}/{len(dirs)} 가 IMPLEMENTATION_MISC 다 "
              f"- 슬라이스 1 taxonomy 로 이름 붙일 수 없는 오답이 절반 이상이다")

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
