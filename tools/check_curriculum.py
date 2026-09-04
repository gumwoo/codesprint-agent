#!/usr/bin/env python3
"""커리큘럼(curriculum/)과 계약(contracts/) 자체를 검사한다.

이 스크립트가 막는 것은 코드 버그가 아니라 **데이터와 계약이 조용히 무너지는 것**이다.
Skill Graph 는 코드가 아니라 데이터이므로 컴파일러도 타입 체커도 잡아주지 않는다.
여기 있는 검사가 유일한 방어선이다.

새 불변식을 추가하면 tools/meta_test_curriculum.py 에 그것을 깨뜨리는 케이스도
함께 추가한다. 그러지 않으면 검사가 일하고 있는지 알 수 없다.

    python tools/check_curriculum.py
"""
from __future__ import annotations

import json
import pathlib
import re
import sys

import yaml

# 콘솔 코드페이지와 무관하게 출력한다 (Windows cp949 대응)
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
sys.stderr.reconfigure(encoding="utf-8", errors="replace")

ROOT = pathlib.Path(__file__).resolve().parent.parent
CURRICULUM = ROOT / "curriculum"
CONTRACTS = ROOT / "contracts"

CODE_RE = re.compile(r"^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$")
VALID_TIERS = {"FOUNDATION", "CORE", "INTERMEDIATE", "ADVANCED"}

# LLM 요청 스키마에 절대 나타나면 안 되는 필드명.
# 시스템이 계산해야 하는 값을 모델에게 물어보는 순간, 모델은 만들어낸다.
FORBIDDEN_IN_LLM_SCHEMA = {
    "score",
    "mastery",
    "masteryscore",
    "nextaction",
    "recommendedaction",
    "totalscore",
    "weight",
    "skillupdates",
}

failures: list[str] = []


def fail(check: str, detail: str) -> None:
    failures.append(f"[{check}] {detail}")


def load_yaml(name: str):
    path = CURRICULUM / name
    if not path.exists():
        fail("load", f"{name}: 파일이 없다")
        return None
    try:
        return yaml.safe_load(path.read_text(encoding="utf-8"))
    except yaml.YAMLError as e:
        fail("load", f"{name}: YAML 파싱 실패 - {e}")
        return None


def load_json(path: pathlib.Path):
    if not path.exists():
        fail("load", f"{path.name}: 파일이 없다")
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        fail("load", f"{path.name}: JSON 파싱 실패 - {e}")
        return None


# -- 1. 도메인 레지스트리 ------------------------------------------------


def check_domains(domains_doc) -> set[str]:
    """도메인 code 가 중복되거나 형식을 벗어나면 Skill 의 domain 참조가 무의미해진다."""
    if not domains_doc or "domains" not in domains_doc:
        fail("domains", "domains 키가 없다")
        return set()

    codes: set[str] = set()
    orders: dict[int, str] = {}
    for d in domains_doc["domains"]:
        code = d.get("code")
        if not code:
            fail("domains", "code 가 없는 도메인이 있다")
            continue
        if not CODE_RE.match(code):
            fail("domains", f"{code}: UPPER_SNAKE_CASE 가 아니다")
        if code in codes:
            fail("domains", f"{code}: 중복된 도메인 code")
        codes.add(code)

        order = d.get("order")
        if order is None:
            fail("domains", f"{code}: order 가 없다")
        elif order in orders:
            fail("domains", f"{code}: order {order} 가 {orders[order]} 와 중복")
        else:
            orders[order] = code

        tier = d.get("tier")
        if tier not in VALID_TIERS:
            fail("domains", f"{code}: 알 수 없는 tier {tier!r}")
    return codes


# -- 2. Skill Catalog ----------------------------------------------------


def check_skills(skills_doc, domain_codes: set[str]) -> dict[str, dict]:
    """code 중복/형식/미등록 domain 참조를 막는다.

    code 는 DB / API / Prompt / Event 전체에서 쓰이는 유일 식별자라(Addendum 25),
    중복이 들어가면 어느 쪽이 이기는지 알 수 없는 상태가 된다.
    """
    if not skills_doc or "skills" not in skills_doc:
        fail("skills", "skills 키가 없다")
        return {}

    skills: dict[str, dict] = {}
    for s in skills_doc["skills"]:
        code = s.get("code")
        if not code:
            fail("skills", "code 가 없는 Skill 이 있다")
            continue
        if not CODE_RE.match(code):
            fail("skills", f"{code}: UPPER_SNAKE_CASE 가 아니다 (Addendum 26)")
        if code in skills:
            fail("skills", f"{code}: 중복된 Skill code")
            continue
        skills[code] = s

        domain = s.get("domain")
        if domain not in domain_codes:
            fail("skills", f"{code}: domains.yaml 에 없는 domain {domain!r}")

        tier = s.get("tier")
        if tier not in VALID_TIERS:
            fail("skills", f"{code}: 알 수 없는 tier {tier!r}")

        if "language" not in s:
            fail("skills", f"{code}: language 필드가 없다 (언어 독립이면 null 을 명시한다)")
        if not s.get("description"):
            fail("skills", f"{code}: description 이 비어 있다")
    return skills


def check_active_domains_have_skills(domains_doc, skills: dict[str, dict]) -> None:
    """active: true 인데 Skill 이 하나도 없으면 레지스트리가 거짓말을 하는 것이다."""
    if not domains_doc or "domains" not in domains_doc:
        return
    used = {s.get("domain") for s in skills.values()}
    for d in domains_doc["domains"]:
        code, active = d.get("code"), d.get("active")
        if active and code not in used:
            fail("domains", f"{code}: active 가 true 인데 등록된 Skill 이 없다")
        if not active and code in used:
            fail("domains", f"{code}: Skill 이 있는데 active 가 false 다")


# -- 3. 선수 관계 --------------------------------------------------------


def check_prerequisites(prereq_doc, skills: dict[str, dict]) -> None:
    """dangling / self-reference / cycle 을 막는다.

    셋 다 런타임에는 "학습 경로가 영원히 LOCKED" 또는 "무한 순회"로 나타난다.
    증상이 데이터에서 아주 멀리 떨어진 곳에 나타나므로 여기서 잡아야 한다.
    """
    if not prereq_doc or "prerequisites" not in prereq_doc:
        fail("prerequisites", "prerequisites 키가 없다")
        return

    edges: dict[str, set[str]] = {}
    seen: set[tuple[str, str]] = set()

    for p in prereq_doc["prerequisites"]:
        skill, requires = p.get("skill"), p.get("requires")
        if not skill or not requires:
            fail("prerequisites", f"skill/requires 가 비었다: {p!r}")
            continue
        if skill not in skills:
            fail("prerequisites", f"skills.yaml 에 없는 skill 참조: {skill}")
            continue
        if requires not in skills:
            fail("prerequisites", f"skills.yaml 에 없는 prerequisite 참조: {requires}")
            continue
        if skill == requires:
            fail("prerequisites", f"{skill}: 자기 자신을 prerequisite 으로 지정")
            continue
        if (skill, requires) in seen:
            fail("prerequisites", f"{skill} <- {requires}: 중복된 선수 관계")
            continue
        seen.add((skill, requires))

        mm = p.get("minimum_mastery")
        if mm is None or not isinstance(mm, (int, float)) or not (0.0 <= mm <= 1.0):
            fail(
                "prerequisites",
                f"{skill} <- {requires}: minimum_mastery 가 0~1 범위 밖 ({mm!r})",
            )

        edges.setdefault(skill, set()).add(requires)

    # 순환 탐지: skill -> prerequisite 방향으로 DFS.
    WHITE, GRAY, BLACK = 0, 1, 2
    color = {c: WHITE for c in skills}

    def visit(node: str, trail: list[str]) -> bool:
        color[node] = GRAY
        for nxt in sorted(edges.get(node, ())):
            if color.get(nxt) == GRAY:
                cycle = trail[trail.index(nxt):] if nxt in trail else [nxt]
                fail("prerequisites", "순환 선수 관계: " + " -> ".join(cycle + [nxt]))
                return True
            if color.get(nxt) == WHITE and visit(nxt, trail + [nxt]):
                return True
        color[node] = BLACK
        return False

    for code in sorted(skills):
        if color[code] == WHITE:
            visit(code, [code])


# -- 4. Mistake Taxonomy -------------------------------------------------


def check_mistakes(mistakes_doc, skills: dict[str, dict]) -> dict[str, dict]:
    """Mistake code 형식, target_skill 실재성, 자동 드릴 조건을 검사한다."""
    if not mistakes_doc or "mistakes" not in mistakes_doc:
        fail("mistakes", "mistakes 키가 없다")
        return {}

    mistakes: dict[str, dict] = {}
    for m in mistakes_doc["mistakes"]:
        code = m.get("code")
        if not code:
            fail("mistakes", "code 가 없는 Mistake 가 있다")
            continue
        if not CODE_RE.match(code):
            fail("mistakes", f"{code}: UPPER_SNAKE_CASE 가 아니다")
        if code in mistakes:
            fail("mistakes", f"{code}: 중복된 Mistake code")
            continue
        mistakes[code] = m

        # Skill 과 Mistake 를 섞지 않는다 (Addendum 30).
        if code in skills:
            fail("mistakes", f"{code}: Skill code 와 이름이 겹친다 (Addendum 30)")

        target = m.get("target_skill")
        if target is not None and target not in skills:
            fail("mistakes", f"{code}: skills.yaml 에 없는 target_skill {target!r}")

        # 자동 드릴은 대상 Skill 없이는 성립하지 않는다.
        # 대상이 없는데 드릴을 켜면 Decision Engine 이 갈 곳 없는 액션을 낸다.
        if m.get("auto_drill") and target is None:
            fail("mistakes", f"{code}: auto_drill 이 true 인데 target_skill 이 없다")
    return mistakes


# -- 5. LLM 계약 경계 ----------------------------------------------------


def property_names(node, acc: set[str]) -> None:
    if isinstance(node, dict):
        for key, value in node.items():
            if key == "properties" and isinstance(value, dict):
                acc.update(value.keys())
            if isinstance(value, (dict, list)):
                property_names(value, acc)
    elif isinstance(node, list):
        for item in node:
            property_names(item, acc)


def check_llm_schema_owns_nothing_systemic() -> None:
    """LLM 요청 스키마에 시스템이 계산하는 값이 새어 들어가는 것을 막는다.

    프롬프트로 "점수를 만들지 마"라고 부탁하는 것과, 스키마에서 필드를 없애
    물리적으로 불가능하게 만드는 것은 다르다. 후자만 보장이다.
    근거: docs/adr/0001-llm-analyzes-system-decides.md
    """
    for path in sorted(CONTRACTS.glob("*.llm.schema.json")):
        schema = load_json(path)
        if schema is None:
            continue
        names: set[str] = set()
        property_names(schema, names)
        for name in sorted(names):
            if name.lower() in FORBIDDEN_IN_LLM_SCHEMA:
                fail(
                    "llm-boundary",
                    f"{path.name}: 시스템이 계산해야 하는 필드가 LLM 요청 스키마에 있다 - {name}",
                )


def _is_object_node(node: dict) -> bool:
    t = node.get("type")
    if isinstance(t, list):
        return "object" in t
    return t == "object"


def check_schemas_are_closed() -> None:
    """additionalProperties 가 열려 있으면 모델이 임의 필드를 덧붙일 수 있다."""
    for path in sorted(CONTRACTS.glob("*.schema.json")):
        schema = load_json(path)
        if schema is None:
            continue
        if "$schema" not in schema:
            fail("schema-shape", f"{path.name}: $schema 선언이 없다")
        if "required" not in schema:
            fail("schema-shape", f"{path.name}: 루트에 required 가 없다")
        # Structured Output 은 배열 루트를 받지 않는다.
        if schema.get("type") != "object":
            fail("schema-shape", f"{path.name}: 루트 type 이 object 가 아니다")

        def walk(node, trail: str) -> None:
            if isinstance(node, dict):
                if "properties" in node and _is_object_node(node):
                    if node.get("additionalProperties") is not False:
                        fail(
                            "open-object",
                            f"{path.name}{trail}: additionalProperties 가 false 가 아니다",
                        )
                for key, value in node.items():
                    if isinstance(value, (dict, list)):
                        walk(value, f"{trail}/{key}")
            elif isinstance(node, list):
                for i, item in enumerate(node):
                    walk(item, f"{trail}[{i}]")

        walk(schema, "")


def check_mistake_enum_matches_yaml(mistakes: dict[str, dict]) -> None:
    """Reviewer 스키마의 enum 과 mistakes.yaml 이 갈라지는 것을 막는다.

    갈라지면 모델이 낸 code 를 backend 가 모르는(또는 그 반대) 상태가 되고,
    조용히 IMPLEMENTATION_MISC 로 뭉개진다. 정확도 저하가 원인 없이 나타난다.
    """
    schema = load_json(CONTRACTS / "reviewer-output.llm.schema.json")
    if schema is None or not mistakes:
        return
    enum = schema.get("$defs", {}).get("mistakeCode", {}).get("enum")
    if enum is None:
        fail("mistake-sync", "reviewer-output.llm.schema.json: $defs.mistakeCode.enum 이 없다")
        return
    only_schema = sorted(set(enum) - set(mistakes))
    only_yaml = sorted(set(mistakes) - set(enum))
    if only_schema:
        fail("mistake-sync", f"스키마에만 있는 code: {only_schema}")
    if only_yaml:
        fail("mistake-sync", f"mistakes.yaml 에만 있는 code: {only_yaml}")


# -- 실행 ----------------------------------------------------------------


def main() -> int:
    domains_doc = load_yaml("domains.yaml")
    skills_doc = load_yaml("skills.yaml")
    prereq_doc = load_yaml("prerequisites.yaml")
    mistakes_doc = load_yaml("mistakes.yaml")

    domain_codes = check_domains(domains_doc)
    skills = check_skills(skills_doc, domain_codes)
    check_active_domains_have_skills(domains_doc, skills)
    check_prerequisites(prereq_doc, skills)
    mistakes = check_mistakes(mistakes_doc, skills)

    check_schemas_are_closed()
    check_llm_schema_owns_nothing_systemic()
    check_mistake_enum_matches_yaml(mistakes)

    if failures:
        print(f"\n[FAIL] 커리큘럼/계약 검사 실패 ({len(failures)}건):")
        for f in failures:
            print("  - " + f)
        return 1

    print(
        f"[OK] 커리큘럼/계약 검사 통과 "
        f"(도메인 {len(domain_codes)} · Skill {len(skills)} · Mistake {len(mistakes)})"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
