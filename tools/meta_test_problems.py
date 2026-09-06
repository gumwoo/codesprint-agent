#!/usr/bin/env python3
"""문제 데이터 검사가 실제로 일하는지 확인한다.

`check_problems.py` 가 통과하는 것만으로는 부족하다. 검사가 아무것도 안 하고 있어도
통과하기 때문이다. 그래서 문제 데이터를 일부러 망가뜨린 뒤 검사가 실제로 실패하는지
확인한다.

여기서 하나라도 "검사가 놓침" 이 나오면 데이터가 깨진 게 아니라 **하네스가 깨진 것**이다.

    python tools/meta_test_problems.py

⚠️ 실제 파일을 수정한 뒤 되돌린다. CI 는 실행 후 git status 로 복원을 확인한다.
"""
from __future__ import annotations

import json
import pathlib
import subprocess
import sys

import yaml

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = pathlib.Path(__file__).resolve().parent.parent
CHECKER = ROOT / "tools" / "check_problems.py"


def drop_primary(doc):
    for s in doc["skills"]:
        if s["role"] == "PRIMARY":
            s["role"] = "SECONDARY"


def break_weight_sum(doc):
    doc["skills"][0]["weight"] = round(doc["skills"][0]["weight"] + 0.2, 3)


def dangling_skill(doc):
    doc["skills"][0]["code"] = "SKILL_THAT_DOES_NOT_EXIST"


def dangling_mistake(doc):
    doc["commonMistakes"].append("MISTAKE_THAT_DOES_NOT_EXIST")


def code_mismatch(doc):
    doc["code"] = "P99_SOMETHING_ELSE"


def drop_expected_solve_seconds(doc):
    doc.pop("expectedSolveSeconds")


def drill_becomes_normal(doc):
    doc["kind"] = "NORMAL"


def source_becomes_curated(doc):
    doc["source"] = "CURATED"


def dangling_control_mistake(doc):
    doc["negativeControl"]["mistake"] = "MISTAKE_THAT_DOES_NOT_EXIST"


def control_mistake_not_in_common(doc):
    doc["commonMistakes"] = [m for m in doc["commonMistakes"]
                             if m != doc["negativeControl"]["mistake"]]


def drill_control_misses_its_skill(doc):
    # P08 의 드릴 대상은 BFS_VISITED_MANAGEMENT 다. 겨냥이 어긋나면 잡아야 한다.
    doc["negativeControl"]["mistake"] = "OUTPUT_FORMAT"
    doc["commonMistakes"].append("OUTPUT_FORMAT")


def duplicate_case_id(doc):
    doc["cases"].append(dict(doc["cases"][0]))


def empty_expected_output(doc):
    doc["cases"][1]["expectedOutput"] = ""


def unhide_all_cases(doc):
    for c in doc["cases"]:
        c["hidden"] = False


def drop_probes_field(doc):
    # 생략은 "모른다" 다. 겨냥하는 실수가 없으면 [] 라고 적는다.
    doc["cases"][0].pop("probes")


def dangling_probe(doc):
    doc["cases"][0]["probes"] = ["MISTAKE_THAT_DOES_NOT_EXIST"]


def probe_on_system_mistake(doc):
    # SYNTAX_ERROR 는 시스템이 부여한다. Reviewer 가 주장하지 않으므로 뒷받침할 일이 없다.
    doc["cases"][0]["probes"] = ["SYNTAX_ERROR"]


def probe_not_in_common(doc):
    # INPUT_PARSE 는 REVIEWER 가 붙이지만 P03 의 commonMistakes 에는 없다.
    doc["cases"][0]["probes"] = ["INPUT_PARSE"]


def competing_mistake_without_solution(doc):
    # 태그가 있는 문제에 경쟁 실수를 하나 늘린다. 그것을 담은 오답이 없으면
    # 태그가 그 실수와 구별되는지 확인할 수 없다.
    doc["commonMistakes"].append("INPUT_PARSE")


def probe_every_case(doc):
    for c in doc["cases"]:
        c["probes"] = ["BOUNDARY_CHECK"]


def control_duplicates_a_probe_solution(doc):
    # wrong.py 가 BOUNDARY_CHECK 가 되면 probes/BOUNDARY_CHECK.py 와 같은 실수의
    # 오답이 둘이 된다. 어느 쪽이 정본인지 알 수 없다.
    doc["negativeControl"]["mistake"] = "BOUNDARY_CHECK"


def drop_sample(doc):
    doc["cases"] = [c for c in doc["cases"] if c["type"] != "SAMPLE"]


# (설명, 대상 파일, 망가뜨리는 방법, 기대 메시지 조각)
# 새 불변식을 check_problems.py 에 추가하면 그것을 깨뜨리는 케이스도 여기 함께 추가한다.
CASES = [
    # -- Skill 매핑 --
    ("PRIMARY 가 사라지면", "problems/P03_CONNECTED_COMPONENT/problem.yaml", drop_primary, "PRIMARY 는 정확히 하나"),
    ("weight 합이 1 이 아니면", "problems/P03_CONNECTED_COMPONENT/problem.yaml", break_weight_sum, "weight 합이 1.0 이 아니다"),
    ("존재하지 않는 Skill 을 가리키면", "problems/P03_CONNECTED_COMPONENT/problem.yaml", dangling_skill, "없는 Skill"),

    # -- Mistake 참조 --
    ("존재하지 않는 Mistake 를 가리키면", "problems/P03_CONNECTED_COMPONENT/problem.yaml", dangling_mistake, "없는 Mistake"),

    # -- 레이아웃 / 메타 --
    ("code 와 디렉터리 이름이 다르면", "problems/P03_CONNECTED_COMPONENT/problem.yaml", code_mismatch, "디렉터리 이름이 다르다"),
    ("expectedSolveSeconds 가 없으면", "problems/P03_CONNECTED_COMPONENT/problem.yaml", drop_expected_solve_seconds, "expectedSolveSeconds"),

    # -- 공개 저장소 경계 (ADR-0008) --
    ("공개 저장소에 CURATED 문제가 들어오면", "problems/P03_CONNECTED_COMPONENT/problem.yaml", source_becomes_curated, "DEV_FIXTURE 만 둔다"),

    # -- negativeControl (ADR-0007) --
    ("심어둔 실수가 존재하지 않는 code 면", "problems/P03_CONNECTED_COMPONENT/problem.yaml", dangling_control_mistake, "없는 negativeControl.mistake"),
    ("심어둔 실수가 commonMistakes 에 없으면", "problems/P03_CONNECTED_COMPONENT/problem.yaml", control_mistake_not_in_common, "commonMistakes 에 없다"),
    ("드릴이 자기 Skill 을 겨냥하지 않으면", "problems/P08_VISITED_TIMING_DRILL/problem.yaml", drill_control_misses_its_skill, "겨냥하지 않는다"),

    # -- 자동 드릴 착지점 --
    # auto_drill 이 켜진 Mistake 는 그 Skill 을 노리는 MICRO_DRILL 이 있어야 한다.
    ("드릴 문제가 일반 문제가 되면", "problems/P08_VISITED_TIMING_DRILL/problem.yaml", drill_becomes_normal, "MICRO_DRILL 문제가 없다"),

    # -- Test Case --
    ("case id 가 중복되면", "problems/P03_CONNECTED_COMPONENT/cases.json", duplicate_case_id, "case id 가 중복"),
    ("expectedOutput 이 비면", "problems/P03_CONNECTED_COMPONENT/cases.json", empty_expected_output, "expectedOutput 이 비어 있다"),
    ("hidden case 가 하나도 없으면", "problems/P03_CONNECTED_COMPONENT/cases.json", unhide_all_cases, "hidden case 가 없다"),
    ("SAMPLE 이 없으면", "problems/P03_CONNECTED_COMPONENT/cases.json", drop_sample, "SAMPLE case 가 없다"),

    # -- case 성격 태그 (ADR-0015) --
    # 태그가 Reviewer 밖의 확정 근거가 되므로, 아무도 확인하지 않은 태그가
    # 들어오는 길을 전부 막아야 한다.
    ("probes 를 생략하면", "problems/P03_CONNECTED_COMPONENT/cases.json", drop_probes_field, "'probes' is a required property"),
    ("존재하지 않는 Mistake 를 겨냥하면", "problems/P03_CONNECTED_COMPONENT/cases.json", dangling_probe, "겨냥한다"),
    ("시스템이 부여하는 Mistake 를 겨냥하면", "problems/P03_CONNECTED_COMPONENT/cases.json", probe_on_system_mistake, "assigned_by 가 REVIEWER 가 아니다"),
    ("commonMistakes 에 없는 것을 겨냥하면", "problems/P03_CONNECTED_COMPONENT/cases.json", probe_not_in_common, "commonMistakes 에는 없다"),
    ("경쟁하는 실수의 오답이 없으면", "problems/P03_CONNECTED_COMPONENT/problem.yaml", competing_mistake_without_solution, "probes/INPUT_PARSE.py 가 없다"),
    ("모든 case 가 같은 실수를 겨냥하면", "problems/P03_CONNECTED_COMPONENT/cases.json", probe_every_case, "대조군이 없다"),
    ("같은 실수의 오답이 둘이면", "problems/P03_CONNECTED_COMPONENT/problem.yaml", control_duplicates_a_probe_solution, "wrong.py 하나로 둔다"),
]

# 파일이 있고 없고로만 깨뜨릴 수 있는 것. (설명, 대상, 동작, 기대 메시지 조각)
FILE_CASES = [
    ("겨냥한 실수의 풀이 파일을 지우면",
     "problems/P03_CONNECTED_COMPONENT/probes/BOUNDARY_CHECK.py", "delete",
     "probes/BOUNDARY_CHECK.py 가 없다"),
    ("경쟁하는 실수의 오답 파일을 지우면",
     "problems/P03_CONNECTED_COMPONENT/probes/NO_VISITED.py", "delete",
     "probes/NO_VISITED.py 가 없다"),
    ("commonMistakes 에 없는 실수의 풀이가 남아 있으면",
     "problems/P03_CONNECTED_COMPONENT/probes/INPUT_PARSE.py", "create",
     "commonMistakes 에 없다"),
]


def mutate(path: pathlib.Path, fn) -> str:
    original = path.read_text(encoding="utf-8")
    if path.suffix == ".json":
        doc = json.loads(original)
        fn(doc)
        path.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n",
                        encoding="utf-8", newline="")
    else:
        doc = yaml.safe_load(original)
        fn(doc)
        path.write_text(yaml.safe_dump(doc, allow_unicode=True, sort_keys=False),
                        encoding="utf-8", newline="")
    return original


def run_checker() -> tuple[int, str]:
    res = subprocess.run(
        [sys.executable, str(CHECKER)],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    return res.returncode, (res.stdout or "") + (res.stderr or "")


def report(name: str, code: int, output: str, expect: str) -> int:
    if code != 1:
        print(f"[X] meta: {name} -> 검사가 놓쳤다 (exit {code}) [FALSE NEGATIVE]")
        return 1
    if expect not in output:
        print(f"[X] meta: {name} -> 실패는 했으나 의도한 규칙이 아님 (기대: {expect!r})")
        return 1
    print(f"[O] meta: {name} -> 검사가 정상적으로 차단")
    return 0


def main() -> int:
    failed = 0
    for name, rel, action, expect in FILE_CASES:
        path = ROOT / rel
        if action == "delete":
            original = path.read_bytes()
            path.unlink()
        else:
            original = None
            path.write_text("# 메타테스트가 만든 파일" + chr(10), encoding="utf-8", newline="")
        try:
            code, output = run_checker()
        finally:
            if original is None:
                path.unlink()
            else:
                path.write_bytes(original)
        failed += report(name, code, output, expect)

    for name, rel, fn, expect in CASES:
        path = ROOT / rel
        original = mutate(path, fn)
        try:
            res = subprocess.run(
                [sys.executable, str(CHECKER)],
                capture_output=True, text=True, encoding="utf-8", errors="replace",
            )
        finally:
            path.write_text(original, encoding="utf-8", newline="")

        failed += report(name, res.returncode,
                         (res.stdout or "") + (res.stderr or ""), expect)

    res = subprocess.run([sys.executable, str(CHECKER)], capture_output=True, text=True,
                         encoding="utf-8", errors="replace")
    if res.returncode != 0:
        failed += 1
        print("\n[X] meta: 원본 복원 실패 - 변형이 남아 있다")
        print((res.stdout or "") + (res.stderr or ""))

    if failed:
        print(f"\n[FAIL] 메타테스트 실패: {failed}건")
        return 1
    print(f"\n[OK] 메타테스트 통과: {len(CASES)}개 위반을 검사가 전부 차단함")
    return 0


if __name__ == "__main__":
    sys.exit(main())
