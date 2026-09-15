#!/usr/bin/env python3
"""채택 파이프라인이 실제로 거르는지 확인한다. Docker 가 필요하다. 정본: ADR-0032.

    python tools/meta_test_adoption.py

통과시키는 것만 보면 아무것도 거르지 않는 파이프라인도 통과한다. 그래서 검증된 고정
초안(tests/generation/good-draft.json)을 일부러 망가뜨려 **각 검사가 제 단계에서**
막는지 본다. "거절됐는가" 가 아니라 "의도한 단계에서 거절됐는가" 를 본다 - 엉뚱한
검사에 걸려도 거절은 거절이라 통과한 것처럼 보이기 때문이다.

모델을 부르지 않는다. CI 에서 돈다.

⚠️ 정상 초안은 실제로 problems/ 에 들어갔다가 지워진다. 끝난 뒤 problems/ 에 남은 것이
없는지도 확인한다.
"""
from __future__ import annotations

import copy
import json
import pathlib
import shutil
import sys
import tempfile

import yaml

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
import adopt_problem  # noqa: E402

GOOD = json.loads((ROOT / "tests" / "generation" / "good-draft.json").read_text(encoding="utf-8"))
# 정답만으로 잴 수 없는 Skill(PYTHON_DEQUE_BASIC)의 고정 초안. Skill 측정 단계를 본다(ADR-0033).
QUEUE = json.loads((ROOT / "tests" / "generation" / "good-queue-draft.json")
                   .read_text(encoding="utf-8"))
FIXTURES = {"list": (GOOD, "PYTHON_LIST_BASIC"), "queue": (QUEUE, "PYTHON_DEQUE_BASIC")}


def brute_disagrees(draft):
    # 완전탐색이 홀수 위치를 더한다. 두 풀이가 샘플에서부터 갈린다.
    draft["bruteForce"] = draft["bruteForce"].replace("index % 2 == 0", "index % 2 == 1")


def wrong_is_correct(draft):
    # 오답이 정답과 같다. 교차 검증은 통과하지만 Test Case 가 아무것도 거르지 못한다.
    draft["wrong"] = draft["reference"]


def system_mistake(draft):
    draft["commonMistakes"].append("SYNTAX_ERROR")


def hint_leaks_the_reference(draft):
    draft["hints"][1] = "print(sum(values[0::2]))"


def duplicate_statement(draft):
    p11 = yaml.safe_load((ROOT / "problems" / "P11_LIST_BASIC" / "problem.yaml")
                         .read_text(encoding="utf-8"))
    draft["statement"] = p11["statement"]


def generator_crashes(draft):
    draft["inputGenerator"] = "import sys\nraise ValueError('broken generator')\n"


def contract_breaks(draft):
    draft["hints"] = draft["hints"][:4]


def reference_hangs(draft):
    # 정답이 끝나지 않는다. 덜 돈 결과로 비교하지 않고 거절해야 한다.
    draft["reference"] = "while True:\n    pass\n"


def queue_without_control(draft):
    draft["skillControl"] = None


def control_is_efficient(draft):
    # P18 이 실제로 그랬다 - Skill 없이도 효율적으로 풀리는 풀이가 있었다. 여기서는 list 에
    # 앞 위치 번호를 두어 원소를 옮기지 않는다. 답이 같고 시간 안에 끝난다.
    draft["skillControl"]["solution"] = (
        "import sys\n\nn = int(sys.stdin.read().split()[0])\npile = list(range(1, n + 1))\n"
        "head = 0\nwhile len(pile) - head > 1:\n    head += 1\n    pile.append(pile[head])\n"
        "    head += 1\nprint(pile[head])\n")


def control_is_just_wrong(draft):
    # 느린 게 아니라 틀린 풀이. 큰 입력에서 걸려도 대조가 아니다.
    draft["skillControl"]["solution"] = draft["wrong"]


# (설명, 고정 초안, 망가뜨리는 방법, 기대 단계) - 기대 단계가 None 이면 채택돼야 한다.
CASES = [
    ("정상 초안은 채택된다", "list", None, None),
    ("계약을 어기면", "list", contract_breaks, "계약"),
    ("SYSTEM 이 부여하는 실수를 적으면", "list", system_mistake, "참조"),
    ("기존 문제와 본문이 같으면", "list", duplicate_statement, "중복"),
    ("입력 생성기가 터지면", "list", generator_crashes, "입력 생성기"),
    ("정답과 완전탐색이 갈라지면", "list", brute_disagrees, "교차 검증"),
    ("정답이 끝나지 않으면", "list", reference_hangs, "교차 검증"),
    ("힌트가 정답 코드를 담으면", "list", hint_leaks_the_reference, "문제 데이터 검사"),
    ("오답이 통과하면", "list", wrong_is_correct, "실제 채점"),
    # ADR-0033 · 정답이 Skill 사용을 증명하지 않는 Skill
    ("큐 초안이 Skill 대조를 갖추면 채택된다", "queue", None, None),
    ("잴 수 없는 Skill 인데 대조 풀이가 없으면", "queue", queue_without_control, "Skill 측정"),
    ("그 Skill 없이도 시간 안에 풀리면", "queue", control_is_efficient, "Skill 측정"),
    ("대조 풀이가 그냥 틀리면", "queue", control_is_just_wrong, "Skill 측정"),
]


def main() -> int:
    failures = 0
    before = {p.name for p in adopt_problem.PROBLEMS.iterdir()}

    with tempfile.TemporaryDirectory() as work:
        work = pathlib.Path(work)
        for index, (name, fixture, mutate, expected) in enumerate(CASES):
            base, skill = FIXTURES[fixture]
            draft = copy.deepcopy(base)
            if mutate:
                mutate(draft)
            envelope = work / f"case-{index}.json"
            envelope.write_text(json.dumps({
                "id": f"meta-{index}", "skill": skill,
                "promptVersion": "fixture", "generatedAt": "2026-09-15T00:00:00Z",
                "draft": draft}, ensure_ascii=False), encoding="utf-8")

            outcome = adopt_problem.adopt(envelope, records=work / "records")
            got = outcome.get("stage")

            if expected is None:
                ok = "code" in outcome
                if ok:
                    shutil.rmtree(adopt_problem.PROBLEMS / outcome["code"], ignore_errors=True)
                detail = outcome.get("code") or f"{got}: {outcome.get('reasons')}"
            else:
                ok = got == expected
                detail = f"{got}" + ("" if ok else f" (기대 {expected}) {outcome.get('reasons')}")
                if "code" in outcome:
                    # 거절돼야 할 초안이 채택됐다. 여기서 치우지 않으면 그 문제가 남아
                    # 뒤 케이스들이 전부 "중복" 으로 막혀 연쇄로 실패한다 - 대조군을
                    # 돌렸을 때 실제로 그랬다. 실패는 하나로 세고 흔적은 걷는다.
                    shutil.rmtree(adopt_problem.PROBLEMS / outcome["code"], ignore_errors=True)
                    detail = f"채택됨 {outcome['code']} (기대 {expected})"

            print(f"[{'O' if ok else 'X'}] {name} -> {detail}")
            failures += 0 if ok else 1

    after = {p.name for p in adopt_problem.PROBLEMS.iterdir()}
    leftover = sorted(after - before)
    if leftover:
        print(f"[X] problems/ 에 흔적이 남았다: {leftover}")
        failures += 1

    if failures:
        print(f"\n[FAIL] 채택 파이프라인 메타테스트 {failures}건 실패")
        return 1
    rejections = sum(1 for case in CASES if case[3] is not None)
    print(f"\n[OK] 채택 파이프라인이 {rejections}개 결함을 전부 제 단계에서 막았다")
    return 0


if __name__ == "__main__":
    sys.exit(main())
