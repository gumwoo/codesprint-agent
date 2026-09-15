#!/usr/bin/env python3
"""LLM 이 만든 문제 초안을 **검사를 통과할 때만** 문제은행에 들인다. 정본: ADR-0032.

    python tools/adopt_problem.py generated/drafts/<id>.json [...]

에이전트가 만들고, 시스템이 채택한다. 판정은 LLM 에게 묻지 않는다(ADR-0001) - 초안이
문제가 되는지도 마찬가지다.

차례로 거른다. 하나라도 걸리면 그 자리에서 멈추고, 사유를 generated/rejected/ 에 남긴다.

  계약            problem-draft.llm.schema.json
  참조            Skill · Mistake 가 실재하는가, SYSTEM 실수를 쓰지 않았는가
  중복            기존 문제와 본문이 겹치는가
  입력 생성기     seed 30 개로 무작위 입력을 실제로 만들 수 있는가
  교차 검증       reference 와 bruteForce 가 모든 입력에서 같은 답을 내는가
  문제 데이터 검사 tools/check_problems.py (힌트 사다리 · 정답 코드 유출 포함)
  실제 채점       tools/verify_problems.py (정답 통과 · 오답이 의도한 이유로 실패)

**기대 출력은 LLM 에게 받지 않는다.** reference 를 실행해서 만든다. 그런데 그러면
"reference 가 통과한다" 는 아무것도 증명하지 못한다 - 자기가 만든 답과 비교하기
때문이다. 그래서 서로 다른 방식의 풀이 둘이 같은 답을 내야 한다. 사람이 쓸 때는
필요 없던 검사이고, 생성에서는 없으면 안 되는 검사다.

초안 코드는 **신뢰할 수 없는 입력**이다. 입력 생성기까지 전부 샌드박스에서 돈다.
"""
from __future__ import annotations

import argparse
import datetime
import difflib
import json
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

import yaml
from jsonschema import Draft202012Validator

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = pathlib.Path(__file__).resolve().parent.parent
PROBLEMS = ROOT / "problems"
CURRICULUM = ROOT / "curriculum"
RECORDS = ROOT / "generated"

sys.path.insert(0, str(ROOT / "judge"))
import run_submission  # noqa: E402

DRAFT_SCHEMA = Draft202012Validator(json.loads(
    (ROOT / "contracts" / "problem-draft.llm.schema.json").read_text(encoding="utf-8")))

# 무작위 입력 개수. 교차 검증은 이만큼의 입력에서 두 풀이가 전부 같아야 통과한다.
RANDOM_INPUTS = 30
# 그중 숨은 case 로 남기는 개수. 나머지는 검증에만 쓰고 버린다.
RANDOM_CASES_KEPT = 3
# 본문이 이만큼 겹치면 같은 문제로 본다.
DUPLICATE_RATIO = 0.75
# 시간 · 메모리 제한은 시스템이 정한다. LLM 에게 받지 않는다.
TIME_LIMIT_MS = 2000
MEMORY_LIMIT_MB = 256

HINTS_HEADER = """# 단계별 힌트. 정본 형식: contracts/hint-ladder.schema.json
#
# H1 문제 관찰 포인트 / H2 알고리즘 범주 / H3 자료구조·상태 / H4 핵심 전이 / H5 의사코드
#
# 이 사다리는 문제 초안 생성기가 만들고 채택 검사가 받아 들였다(ADR-0032).
# H6(전체 풀이)는 여기 없다. reference.py 가 그것이다(ADR-0026).
"""


class Rejected(Exception):
    """채택하지 않는다. 어느 단계에서 왜 막혔는지가 거절 기록이 된다."""

    def __init__(self, stage: str, reasons: list[str]):
        super().__init__(stage)
        self.stage = stage
        self.reasons = list(reasons)


def _load_yaml(path: pathlib.Path) -> dict:
    return yaml.safe_load(path.read_text(encoding="utf-8"))


def _squash(text: str) -> str:
    return re.sub(r"\s+", " ", text or "").strip()


# -- 단계들 ----------------------------------------------------------------

def check_contract(draft: dict) -> None:
    errors = sorted(DRAFT_SCHEMA.iter_errors(draft), key=lambda e: list(e.path))
    if errors:
        raise Rejected("계약", [f"{list(e.path)}: {e.message}" for e in errors[:8]])


def check_references(draft: dict, skill: str) -> None:
    skills = {s["code"] for s in _load_yaml(CURRICULUM / "skills.yaml")["skills"]}
    mistakes = {m["code"]: m for m in _load_yaml(CURRICULUM / "mistakes.yaml")["mistakes"]}
    reasons = []
    if skill not in skills:
        reasons.append(f"요청한 Skill {skill} 이 skills.yaml 에 없다")
    for code in draft["secondarySkills"]:
        if code == skill:
            reasons.append(f"보조 Skill 에 요청한 Skill {code} 이 다시 들어 있다")
        elif code not in skills:
            reasons.append(f"skills.yaml 에 없는 보조 Skill {code}")
    for code in draft["commonMistakes"]:
        if code not in mistakes:
            reasons.append(f"mistakes.yaml 에 없는 실수 {code}")
        elif mistakes[code].get("assigned_by") != "REVIEWER":
            # SYSTEM 이 부여하는 실수는 Reviewer 가 주장하지 않는다. 문제에 적어 두면
            # 절대 확정되지 않는 실수가 된다.
            reasons.append(f"{code} 는 SYSTEM 이 부여하는 실수라 문제에 적을 수 없다")
    if draft["negativeControl"]["mistake"] not in draft["commonMistakes"]:
        reasons.append("negativeControl.mistake 가 commonMistakes 에 없다")
    if reasons:
        raise Rejected("참조", reasons)


def check_duplicate(draft: dict) -> None:
    mine = _squash(draft["statement"])
    for d in sorted(p for p in PROBLEMS.iterdir() if p.is_dir()):
        file = d / "problem.yaml"
        if not file.exists():
            continue
        other = _load_yaml(file)
        if other.get("title") == draft["title"]:
            raise Rejected("중복", [f"{d.name} 와 제목이 같다"])
        ratio = difflib.SequenceMatcher(None, _squash(other.get("statement", "")), mine).ratio()
        if ratio >= DUPLICATE_RATIO:
            raise Rejected("중복", [f"{d.name} 와 본문이 {ratio:.2f} 만큼 같다"])
        if d.name.endswith("_" + draft["codeSuffix"]):
            raise Rejected("중복", [f"{d.name} 와 code 가 겹친다"])


def run_program(source: str, inputs: list[str], what: str, stage: str) -> list[str]:
    """샌드박스에서 돌려 **출력**을 모은다. 판정이 아니라 출력이 필요하다.

    기대 출력을 비워 둔 공개 case 로 돌린다(제출 전 실행과 같은 경로, ADR-0020).
    하나라도 실행되지 않았거나 중간에 멈추면 거절이다 - **덜 돈 결과로 비교하지 않는다.**
    """
    job = {
        "problemId": "draft",
        "timeLimitMs": TIME_LIMIT_MS,
        "memoryLimitMb": MEMORY_LIMIT_MB,
        "cases": [{"id": i, "input": text, "expectedOutput": "", "hidden": False}
                  for i, text in enumerate(inputs, start=1)],
    }
    with tempfile.TemporaryDirectory() as d:
        base = pathlib.Path(d)
        (base / "solution.py").write_text(source, encoding="utf-8", newline="")
        (base / "job.json").write_text(json.dumps(job, ensure_ascii=False),
                                       encoding="utf-8", newline="")
        result = run_submission.run(base / "solution.py", base / "job.json", samples_only=True)

    if result["status"] in ("COMPILE_ERROR", "SYSTEM_ERROR"):
        detail = (result.get("stderr") or "").strip().splitlines()[-1:] or [""]
        raise Rejected(stage, [f"{what} 가 실행되지 않았다: {result['status']} {detail[0][:160]}"])
    cases = {c["id"]: c for c in result.get("cases", [])}
    if len(cases) != len(inputs):
        raise Rejected(stage, [
            f"{what} 가 {len(inputs)}개 중 {len(cases)}개만 돌고 멈췄다 "
            f"(case {result.get('failedCaseId')} {result['status']})"])

    outputs = []
    for i, text in enumerate(inputs, start=1):
        case = cases[i]
        # 기대 출력이 비어 있으므로 출력이 있으면 WRONG_ANSWER 가 정상이다.
        if case["status"] not in ("ACCEPTED", "WRONG_ANSWER"):
            detail = (case.get("stderr") or "").strip().splitlines()[-1:] or [""]
            raise Rejected(stage, [
                f"{what} 가 입력 {i} 에서 {case['status']} - 입력 {text[:60]!r} {detail[0][:120]}"])
        outputs.append(case.get("stdout", ""))
    return outputs


def gather_random_inputs(draft: dict) -> list[str]:
    seeds = [f"{seed}\n" for seed in range(1, RANDOM_INPUTS + 1)]
    produced = run_program(draft["inputGenerator"], seeds, "입력 생성기", "입력 생성기")
    inputs = []
    for seed, text in enumerate(produced, start=1):
        if not text.strip():
            raise Rejected("입력 생성기", [f"seed {seed} 에서 아무 입력도 만들지 않았다"])
        inputs.append(text if text.endswith("\n") else text + "\n")
    return inputs


def cross_check(draft: dict, inputs: list[str]) -> list[str]:
    """reference 와 bruteForce 가 모든 입력에서 같은 답을 내는가. 같으면 그 답을 돌려준다."""
    ref = run_program(draft["reference"], inputs, "정답(reference)", "교차 검증")
    brute = run_program(draft["bruteForce"], inputs, "완전탐색(bruteForce)", "교차 검증")

    normalized = [run_submission.normalize(o) for o in ref]
    mismatches = [i for i, (a, b) in enumerate(zip(ref, brute))
                  if run_submission.normalize(a) != run_submission.normalize(b)]
    if mismatches:
        raise Rejected("교차 검증", [
            f"입력 {i + 1} 에서 두 풀이가 다른 답을 냈다 - "
            f"reference {normalized[i][:40]!r} / bruteForce "
            f"{run_submission.normalize(brute[i])[:40]!r} / 입력 {inputs[i][:60]!r}"
            for i in mismatches[:3]])
    empty = [i + 1 for i, out in enumerate(normalized) if not out]
    if empty:
        raise Rejected("교차 검증", [f"정답이 입력 {empty[:5]} 에서 아무것도 출력하지 않는다"])
    return [out + "\n" for out in normalized]


class _Literal(str):
    pass


def _literal_representer(dumper, data):
    return dumper.represent_scalar("tag:yaml.org,2002:str", data, style="|")


yaml.add_representer(_Literal, _literal_representer, Dumper=yaml.SafeDumper)


def next_code(draft: dict) -> str:
    numbers = [int(m.group(1)) for p in PROBLEMS.iterdir()
               if (m := re.match(r"^P(\d{2})_", p.name))]
    number = max(numbers, default=0) + 1
    if number > 99:
        raise Rejected("중복", ["문제 번호가 99 를 넘는다 - code 형식이 두 자리다"])
    return f"P{number:02d}_{draft['codeSuffix']}"


def materialize(draft: dict, skill: str, sample_inputs: list[str], edge_cases: list[dict],
                random_inputs: list[str], expected: list[str]) -> str:
    code = next_code(draft)
    target = PROBLEMS / code
    target.mkdir()

    secondaries = draft["secondarySkills"]
    # 비중은 시스템이 정한다 - LLM 요청 스키마에는 weight 가 들어갈 수 없다(ADR-0001).
    skills = [{"code": skill, "role": "PRIMARY", "weight": 1.0 if not secondaries else 0.7}]
    for other in secondaries:
        skills.append({"code": other, "role": "SECONDARY",
                       "weight": round(0.3 / len(secondaries), 4)})

    problem = {
        "code": code,
        "title": draft["title"],
        "kind": "NORMAL",
        "source": "DEV_FIXTURE",
        "timeLimitMs": TIME_LIMIT_MS,
        "memoryLimitMb": MEMORY_LIMIT_MB,
        "expectedSolveSeconds": draft["expectedSolveSeconds"],
        "referenceComplexity": draft["referenceComplexity"],
        "statement": _Literal(draft["statement"].rstrip("\n") + "\n"),
        "skills": skills,
        "commonMistakes": draft["commonMistakes"],
        "negativeControl": draft["negativeControl"],
    }
    header = ("# 정본 형식: contracts/problem.schema.json\n"
              "# 문제 초안 생성기가 만들고 채택 검사가 받아 들였다(ADR-0032).\n"
              "# 기대 출력은 reference.py 를 실행해 만들었고, bruteForce 와 무작위 입력 "
              f"{RANDOM_INPUTS}개에서 교차 검증했다.\n\n")
    (target / "problem.yaml").write_text(
        header + yaml.safe_dump(problem, allow_unicode=True, sort_keys=False),
        encoding="utf-8", newline="")

    cases = []
    ordinal = 0
    for text in sample_inputs:
        cases.append({"id": ordinal + 1, "type": "SAMPLE", "hidden": False, "input": text,
                      "expectedOutput": expected[ordinal], "probes": []})
        ordinal += 1
    for edge in edge_cases:
        cases.append({"id": ordinal + 1, "type": edge["type"], "hidden": True,
                      "input": edge["input"], "expectedOutput": expected[ordinal], "probes": []})
        ordinal += 1
    for text in random_inputs[:RANDOM_CASES_KEPT]:
        cases.append({"id": ordinal + 1, "type": "RANDOM", "hidden": True, "input": text,
                      "expectedOutput": expected[ordinal], "probes": []})
        ordinal += 1
    (target / "cases.json").write_text(
        json.dumps({"cases": cases}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8", newline="")

    (target / "reference.py").write_text(draft["reference"], encoding="utf-8", newline="")
    # 오답 첫 줄 주석이 "무엇을 틀리게 했는가" 다(ADR-0007). 모델이 이미 주석으로
    # 시작했으면 덧붙이지 않는다 - 파일럿에서 설명이 두 줄로 겹쳤다.
    wrong = draft["wrong"]
    if not wrong.lstrip().startswith("#"):
        description = draft["wrongDescription"].strip().splitlines()[0]
        wrong = f"# {description}\n" + wrong
    (target / "wrong.py").write_text(wrong, encoding="utf-8", newline="")
    ladder = "".join(
        f"  - level: {level}\n    text: {json.dumps(text, ensure_ascii=False)}\n"
        for level, text in enumerate(draft["hints"], start=1))
    (target / "hints.yaml").write_text(HINTS_HEADER + "hints:\n" + ladder,
                                       encoding="utf-8", newline="")
    return code


def run_repo_checks(code: str) -> None:
    check = subprocess.run([sys.executable, "tools/check_problems.py"], cwd=ROOT,
                           capture_output=True, text=True, encoding="utf-8", errors="replace")
    if check.returncode != 0:
        lines = [line.strip() for line in check.stdout.splitlines() if code in line]
        raise Rejected("문제 데이터 검사", lines[:6] or check.stdout.strip().splitlines()[-4:])

    verify = subprocess.run([sys.executable, "tools/verify_problems.py", code], cwd=ROOT,
                            capture_output=True, text=True, encoding="utf-8", errors="replace")
    if verify.returncode != 0:
        lines = [line.strip() for line in verify.stdout.splitlines() if line.strip()]
        raise Rejected("실제 채점", [line for line in lines if line.startswith("[X]")][:4]
                       or lines[-4:])


# -- 조립 ------------------------------------------------------------------

def _record(path: pathlib.Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
                    encoding="utf-8", newline="")


def adopt(envelope_path: pathlib.Path, records: pathlib.Path = RECORDS) -> dict:
    envelope = json.loads(envelope_path.read_text(encoding="utf-8"))
    draft_id = envelope.get("id") or envelope_path.stem
    skill = envelope.get("skill", "")
    draft = envelope.get("draft") or {}
    stamp = datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds")
    code = None
    try:
        check_contract(draft)
        check_references(draft, skill)
        check_duplicate(draft)
        random_inputs = gather_random_inputs(draft)
        sample_inputs = list(draft["sampleInputs"])
        edge_cases = list(draft["edgeCases"])
        all_inputs = sample_inputs + [e["input"] for e in edge_cases] + random_inputs
        expected = cross_check(draft, all_inputs)
        code = materialize(draft, skill, sample_inputs, edge_cases, random_inputs, expected)
        run_repo_checks(code)
    except Rejected as rejected:
        if code is not None:
            # 들어갔다가 막힌 문제는 흔적 없이 걷는다. 반쯤 남으면 다음 검사가 그것에 걸린다.
            shutil.rmtree(PROBLEMS / code, ignore_errors=True)
        _record(records / "rejected" / f"{draft_id}.json", {
            "id": draft_id, "skill": skill, "promptVersion": envelope.get("promptVersion"),
            "decidedAt": stamp, "stage": rejected.stage, "reasons": rejected.reasons,
            "draft": draft})
        return {"id": draft_id, "stage": rejected.stage, "reasons": rejected.reasons}

    _record(records / "adopted" / f"{code}.json", {
        "id": draft_id, "skill": skill, "promptVersion": envelope.get("promptVersion"),
        "decidedAt": stamp, "code": code, "crossCheckedInputs": len(all_inputs),
        "bruteForce": draft["bruteForce"], "inputGenerator": draft["inputGenerator"]})
    return {"id": draft_id, "code": code}


def main() -> int:
    parser = argparse.ArgumentParser(description="문제 초안을 채택 검사에 넣는다")
    parser.add_argument("drafts", nargs="+", type=pathlib.Path)
    args = parser.parse_args()

    adopted = 0
    for path in args.drafts:
        outcome = adopt(path)
        if "code" in outcome:
            adopted += 1
            print(f"[O] {path.name} -> {outcome['code']}")
        else:
            print(f"[X] {path.name} — {outcome['stage']}")
            for reason in outcome["reasons"][:4]:
                print(f"    {reason}")
    print(f"\n채택 {adopted} / {len(args.drafts)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
