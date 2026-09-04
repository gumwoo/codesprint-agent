#!/usr/bin/env python3
"""하네스 자체가 고장나지 않았는지 검사한다.

`check_curriculum.py` 가 **통과하는 것**만으로는 부족하다.
검사가 아무것도 안 하고 있어도 통과하기 때문이다.

그래서 커리큘럼/계약을 일부러 망가뜨린 뒤 검사가 실제로 실패하는지 확인한다.
여기서 하나라도 "검사가 놓침"이 나오면 데이터가 깨진 게 아니라 **하네스가 깨진 것**이다.

exit code 만 보지 않고 **의도한 규칙이 잡았는지**까지 확인한다.
fixture 가 엉뚱한 규칙에 걸려도 exit 1 이라 통과한 것처럼 보이기 때문이다.

    python tools/meta_test_curriculum.py

⚠️ 이 스크립트는 실제 파일을 수정한 뒤 되돌린다.
   중단되면 파일이 변형된 채 남을 수 있으므로 CI 는 실행 후 git status 로 확인한다.
"""
from __future__ import annotations

import json
import pathlib
import subprocess
import sys

import yaml

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
sys.stderr.reconfigure(encoding="utf-8", errors="replace")

ROOT = pathlib.Path(__file__).resolve().parent.parent
CHECKER = ROOT / "tools" / "check_curriculum.py"


# -- 변형 함수 -----------------------------------------------------------
# 각 함수는 파싱된 문서를 받아 제자리에서 망가뜨린다.


def dup_skill_code(doc):
    doc["skills"].append(dict(doc["skills"][0]))


def lowercase_skill_code(doc):
    doc["skills"][0]["code"] = doc["skills"][0]["code"].lower()


def unknown_domain(doc):
    doc["skills"][0]["domain"] = "NOT_A_REAL_DOMAIN"


def drop_language_field(doc):
    doc["skills"][0].pop("language", None)


def make_cycle(doc):
    # BFS_BASIC 이 BFS_SHORTEST_PATH 를 요구하게 만들면
    # BFS_BASIC -> BFS_SHORTEST_PATH -> BFS_GRID_TRAVERSAL -> BFS_BASIC 순환이 생긴다.
    doc["prerequisites"].append(
        {"skill": "BFS_BASIC", "requires": "BFS_SHORTEST_PATH", "minimum_mastery": 0.65}
    )


def dangling_prerequisite(doc):
    doc["prerequisites"].append(
        {"skill": "BFS_BASIC", "requires": "SKILL_THAT_DOES_NOT_EXIST", "minimum_mastery": 0.65}
    )


def self_prerequisite(doc):
    doc["prerequisites"].append(
        {"skill": "BFS_BASIC", "requires": "BFS_BASIC", "minimum_mastery": 0.65}
    )


def mastery_out_of_range(doc):
    doc["prerequisites"][0]["minimum_mastery"] = 1.5


def auto_drill_without_target(doc):
    for m in doc["mistakes"]:
        if m["code"] == "BOUNDARY_CHECK":
            m["target_skill"] = None


def dangling_target_skill(doc):
    for m in doc["mistakes"]:
        if m["code"] == "BOUNDARY_CHECK":
            m["target_skill"] = "GHOST_SKILL"


def drop_mistake_code(doc):
    doc["mistakes"] = [m for m in doc["mistakes"] if m["code"] != "NO_VISITED"]


def bad_assigned_by(doc):
    doc["mistakes"][0]["assigned_by"] = "SOMEONE"


def system_code_leaks_into_llm_enum(doc):
    # SYNTAX_ERROR 를 REVIEWER 로 바꾸면 LLM enum 에 없는 REVIEWER code 가 되어
    # mistake-sync 가 걸린다. 반대 방향(스키마에 SYSTEM code 유입)은 아래 케이스.
    for m in doc["mistakes"]:
        if m["code"] == "SYNTAX_ERROR":
            m["assigned_by"] = "REVIEWER"


def llm_enum_gains_system_code(schema):
    schema["$defs"]["mistakeCode"]["enum"].append("SYNTAX_ERROR")


def schema_breaks_spec(schema):
    # 규격 위반: minItems 에 문자열. meta-schema 가 잡는다.
    schema["properties"]["failedCaseRefs"]["minItems"] = "1"


def schema_keyword_typo(schema):
    # 오타: minItems -> minItmes. meta-schema 는 확장 키워드로 보고 통과시킨다.
    # 제약이 조용히 사라지므로 allowlist 검사가 잡아야 한다.
    props = schema["properties"]["failedCaseRefs"]
    props.pop("minItems")
    props["minItmes"] = 1


def response_drops_review_from_required(schema):
    schema["required"].remove("review")


def response_drops_prompt_version_from_required(schema):
    schema["required"].remove("promptVersion")


def mistake_named_like_skill(doc):
    doc["mistakes"].append(
        {
            "code": "BFS_BASIC",
            "name": "Skill 과 겹치는 이름",
            "auto_drill": False,
            "target_skill": None,
            "description": "Addendum 30 위반",
        }
    )


def domain_active_without_skill(doc):
    for d in doc["domains"]:
        if d["code"] == "DP":
            d["active"] = True


def llm_schema_gains_next_action(schema):
    schema["properties"]["nextAction"] = {"type": "string"}


def llm_schema_gains_score(schema):
    schema["properties"]["masteryScore"] = {"type": "number"}


def llm_schema_opens_up(schema):
    schema["additionalProperties"] = True


def response_schema_opens_up(schema):
    schema["properties"]["nextAction"]["additionalProperties"] = True


# (설명, 대상 파일, 망가뜨리는 방법, 기대 메시지 조각)
# 새 불변식을 check_curriculum.py 에 추가하면 그것을 깨뜨리는 케이스도 여기 함께 추가한다.
CASES = [
    # -- Skill Catalog --
    ("Skill code 가 중복되면", "curriculum/skills.yaml", dup_skill_code, "중복된 Skill code"),
    ("ADR-0003 · Skill code 가 소문자가 되면", "curriculum/skills.yaml", lowercase_skill_code, "UPPER_SNAKE_CASE 가 아니다"),
    ("등록되지 않은 domain 을 참조하면", "curriculum/skills.yaml", unknown_domain, "없는 domain"),
    ("language 필드를 생략하면", "curriculum/skills.yaml", drop_language_field, "language 필드가 없다"),

    # -- 선수 관계 --
    ("선수 관계에 순환이 생기면", "curriculum/prerequisites.yaml", make_cycle, "순환 선수 관계"),
    ("존재하지 않는 Skill 을 요구하면", "curriculum/prerequisites.yaml", dangling_prerequisite, "없는 prerequisite 참조"),
    ("자기 자신을 요구하면", "curriculum/prerequisites.yaml", self_prerequisite, "자기 자신을 prerequisite"),
    ("minimum_mastery 가 범위를 벗어나면", "curriculum/prerequisites.yaml", mastery_out_of_range, "0~1 범위 밖"),

    # -- Mistake Taxonomy --
    ("자동 드릴에 대상 Skill 이 없으면", "curriculum/mistakes.yaml", auto_drill_without_target, "auto_drill 이 true 인데"),
    ("존재하지 않는 Skill 로 드릴을 보내면", "curriculum/mistakes.yaml", dangling_target_skill, "없는 target_skill"),
    ("Addendum 30 · Mistake 가 Skill 이름을 쓰면", "curriculum/mistakes.yaml", mistake_named_like_skill, "Skill code 와 이름이 겹친다"),
    ("taxonomy 와 스키마 enum 이 갈라지면", "curriculum/mistakes.yaml", drop_mistake_code, "스키마에만 있는 code"),
    ("ADR-0004 · assigned_by 가 알 수 없는 값이면", "curriculum/mistakes.yaml", bad_assigned_by, "assigned_by 가"),
    ("ADR-0004 · SYSTEM code 가 REVIEWER 로 바뀌면", "curriculum/mistakes.yaml", system_code_leaks_into_llm_enum, "REVIEWER code"),
    ("ADR-0004 · LLM enum 에 SYSTEM code 가 유입되면", "contracts/reviewer-output.llm.schema.json", llm_enum_gains_system_code, "SYSTEM 이 부여하는 code 가 LLM enum 에 있다"),

    # -- 도메인 레지스트리 --
    ("active 인데 Skill 이 없으면", "curriculum/domains.yaml", domain_active_without_skill, "active 가 true 인데"),

    # -- LLM 계약 경계 (ADR-0001 / ADR-0002) --
    ("ADR-0002 · LLM 요청 스키마에 nextAction 이 생기면", "contracts/reviewer-output.llm.schema.json", llm_schema_gains_next_action, "LLM 요청 스키마에 있다"),
    ("ADR-0001 · LLM 요청 스키마에 점수 필드가 생기면", "contracts/reviewer-output.llm.schema.json", llm_schema_gains_score, "LLM 요청 스키마에 있다"),
    ("계약 폐쇄 · 모델이 임의 필드를 덧붙일 수 있게 되면", "contracts/reviewer-output.llm.schema.json", llm_schema_opens_up, "additionalProperties 가 false 가 아니다"),
    ("계약 폐쇄 · 응답 스키마가 열리면", "contracts/submit-response.schema.json", response_schema_opens_up, "additionalProperties 가 false 가 아니다"),

    # -- JSON Schema 자체의 유효성 (2단계) --
    ("규격 위반 · minItems 에 문자열이 들어가면", "contracts/reviewer-output.llm.schema.json", schema_breaks_spec, "JSON Schema 규격 위반"),
    ("오타 · minItems 를 minItmes 로 쓰면", "contracts/reviewer-output.llm.schema.json", schema_keyword_typo, "알 수 없는 키워드"),

    # -- 생략 vs null (contracts/README.md 규칙 5) --
    ("생략 vs null · review 가 required 에서 빠지면", "contracts/submit-response.schema.json", response_drops_review_from_required, "review 은 null 을 허용하는데 required 가 아니다"),
    ("생략 vs null · promptVersion 이 required 에서 빠지면", "contracts/submit-response.schema.json", response_drops_prompt_version_from_required, "promptVersion 은 null 을 허용하는데 required 가 아니다"),
]


def mutate(path: pathlib.Path, fn) -> str:
    """파일을 파싱해 fn 으로 망가뜨린 뒤 다시 쓴다. 원본 텍스트를 돌려준다."""
    original = path.read_text(encoding="utf-8")
    if path.suffix == ".json":
        doc = json.loads(original)
        fn(doc)
        path.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    else:
        doc = yaml.safe_load(original)
        fn(doc)
        path.write_text(
            yaml.safe_dump(doc, allow_unicode=True, sort_keys=False), encoding="utf-8"
        )
    return original


def main() -> int:
    failed = 0

    for name, rel, fn, expect in CASES:
        path = ROOT / rel
        original = mutate(path, fn)
        try:
            res = subprocess.run(
                [sys.executable, str(CHECKER)],
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
            )
        finally:
            # 어떤 경우에도 원본을 되돌린다. 되돌리지 못하면 저장소가 오염된다.
            path.write_text(original, encoding="utf-8", newline="")

        output = (res.stdout or "") + (res.stderr or "")

        # 위반 fixture 이므로 exit code 가 1이어야 정상이다.
        if res.returncode != 1:
            failed += 1
            print(f"[X] meta: {name} -> 하네스가 위반을 못 잡음 (exit {res.returncode}) [FALSE NEGATIVE]")
            continue

        # exit 1 만으로는 의도한 규칙이 잡았는지 알 수 없다.
        if expect not in output:
            failed += 1
            print(f"[X] meta: {name} -> 실패는 했으나 의도한 규칙이 아님 (기대: {expect!r})")
            continue

        print(f"[O] meta: {name} -> 하네스가 정상적으로 차단")

    # 되돌리기가 실제로 됐는지 스스로 확인한다.
    res = subprocess.run(
        [sys.executable, str(CHECKER)],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if res.returncode != 0:
        failed += 1
        print("\n[X] meta: 원본 복원 실패 - 변형이 남아 있다")
        print((res.stdout or "") + (res.stderr or ""))

    if failed:
        print(f"\n[FAIL] 메타테스트 실패: {failed}건")
        return 1

    print(f"\n[OK] 메타테스트 통과: {len(CASES)}개 위반을 하네스가 전부 차단함")
    return 0


if __name__ == "__main__":
    sys.exit(main())
