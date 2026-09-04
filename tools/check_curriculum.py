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
from jsonschema import Draft202012Validator

# 콘솔 코드페이지와 무관하게 출력한다 (Windows cp949 대응)
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
sys.stderr.reconfigure(encoding="utf-8", errors="replace")

ROOT = pathlib.Path(__file__).resolve().parent.parent
CURRICULUM = ROOT / "curriculum"
CONTRACTS = ROOT / "contracts"

CODE_RE = re.compile(r"^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$")
VALID_TIERS = {"FOUNDATION", "CORE", "INTERMEDIATE", "ADVANCED"}

# Mistake 라벨을 누가 붙이는가 (ADR-0004).
#   REVIEWER  LLM 이 실패 Test Case 를 근거로 분류 -> LLM enum 에 포함
#   SYSTEM    Judge 결과만으로 결정론적 부여      -> LLM enum 에서 제외
VALID_ASSIGNERS = {"REVIEWER", "SYSTEM"}

# 우리 계약이 쓰는 JSON Schema 키워드. 이 밖의 키워드가 나오면 실패한다.
#
# meta-schema validation 만으로는 부족하다. JSON Schema 는 미지의 키워드를 확장
# 지점으로 허용하도록 설계돼 있어서 meta-schema 가 additionalProperties: false 가
# 아니다. 즉 "minItmes" 같은 오타는 규격 위반이 아니라 그냥 무시되는 어노테이션이
# 되고, **제약이 조용히 사라진다.** 우리에게 가장 위험한 종류의 실수인데
# check_schema() 로는 잡히지 않는다.
#
# 계약 파일이 소수이고 확장 키워드를 쓸 일이 없으므로, 저장소 안에서만 이 개방성을
# 닫는다. 새 키워드가 필요해지면 여기에 추가한다 - 추가한다는 행위 자체가 리뷰 지점이다.
ALLOWED_SCHEMA_KEYWORDS = {
    "$schema", "$id", "$ref", "$defs",
    "title", "description",
    "type", "enum", "const",
    "properties", "required", "additionalProperties",
    "items", "minItems", "maxItems",
    "minimum", "maximum", "pattern",
}

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

        # 누가 이 라벨을 붙이는가가 명시돼야 LLM enum 과의 대조가 성립한다 (ADR-0004).
        assigner = m.get("assigned_by")
        if assigner not in VALID_ASSIGNERS:
            fail(
                "mistakes",
                f"{code}: assigned_by 가 {sorted(VALID_ASSIGNERS)} 중 하나가 아니다 ({assigner!r})",
            )
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


def check_schemas_are_valid_json_schema() -> None:
    """1단계 - 규격 위반을 잡는다.

    우리 custom 검사는 우리가 아는 것만 본다. JSON Schema 규격 자체를 어긴 스키마
    (type 오타, minItems 에 문자열, required 가 배열이 아님 등)는 런타임에
    검증기가 터지거나 조용히 검증을 건너뛰게 만든다.
    """
    for path in sorted(CONTRACTS.glob("*.schema.json")):
        schema = load_json(path)
        if schema is None:
            continue
        try:
            Draft202012Validator.check_schema(schema)
        except Exception as e:
            first = str(e).splitlines()[0]
            fail("meta-schema", f"{path.name}: JSON Schema 규격 위반 - {first}")


def _walk_schema_keywords(node, path_name: str, trail: str) -> None:
    """2단계 - 알려지지 않은 키워드를 잡는다.

    properties / $defs 의 자식은 **이름 -> 스키마** 맵이므로 키를 키워드로 보지 않는다.
    """
    if not isinstance(node, dict):
        return
    for key, value in node.items():
        if key not in ALLOWED_SCHEMA_KEYWORDS:
            fail(
                "schema-keyword",
                f"{path_name}{trail}: 알 수 없는 키워드 {key!r} "
                f"(오타이거나 allowlist 에 추가가 필요하다)",
            )
        if key in ("properties", "$defs"):
            if isinstance(value, dict):
                for name, sub in value.items():
                    _walk_schema_keywords(sub, path_name, f"{trail}/{key}/{name}")
        elif key == "items":
            _walk_schema_keywords(value, path_name, f"{trail}/items")
        elif key not in ("enum", "required", "const"):
            if isinstance(value, dict):
                _walk_schema_keywords(value, path_name, f"{trail}/{key}")


def check_schema_keywords_are_known() -> None:
    """오타 난 키워드를 잡는다.

    meta-schema validation 으로는 잡히지 않는다. JSON Schema 는 미지의 키워드를
    확장 지점으로 허용하므로 "minItmes" 는 규격 위반이 아니라 무시되는 어노테이션이
    된다. 즉 **제약이 사라졌는데 아무도 모르는** 상태가 된다.
    """
    for path in sorted(CONTRACTS.glob("*.schema.json")):
        schema = load_json(path)
        if schema is None:
            continue
        _walk_schema_keywords(schema, path.name, "")


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


def check_judge_status_enums_match() -> None:
    """Judge 판정 enum 이 두 계약에서 갈라지는 것을 막는다.

    judge-result.schema.json 은 Sandbox Runner 가 내는 것이고,
    submit-response.schema.json 의 judge.status 는 그것을 그대로 실어 나른다.
    한쪽에만 상태를 추가하면 Runner 가 낸 값을 API 계층이 거부하거나, 반대로
    아무도 내지 않는 상태를 프론트가 분기 처리하게 된다.

    ADR-0004 의 Reviewer 호출 정책이 이 enum 위에 서 있으므로 특히 중요하다 -
    새 상태가 한쪽에만 생기면 그 상태에서 Reviewer 를 부를지 아무도 정하지 않은
    채로 코드가 먼저 굴러간다.
    """
    judge = load_json(CONTRACTS / "judge-result.schema.json")
    response = load_json(CONTRACTS / "submit-response.schema.json")
    if judge is None or response is None:
        return

    a = judge.get("properties", {}).get("status", {}).get("enum")
    b = (
        response.get("properties", {})
        .get("judge", {})
        .get("properties", {})
        .get("status", {})
        .get("enum")
    )
    if a is None or b is None:
        fail("judge-sync", "judge status enum 을 찾지 못했다")
        return
    only_judge = sorted(set(a) - set(b))
    only_response = sorted(set(b) - set(a))
    if only_judge:
        fail("judge-sync", f"judge-result 에만 있는 status: {only_judge}")
    if only_response:
        fail("judge-sync", f"submit-response 에만 있는 status: {only_response}")


def check_dependencies_have_one_source() -> None:
    """CI 와 README 가 서로 다른 의존성을 설치하는 것을 막는다.

    실제로 한 번 갈라졌다. README 는 `pip install pyyaml`, CI 는
    `pip install pyyaml jsonschema` 였다. 새로 clone 한 사람이 README 대로 따라 하면
    하네스가 import 에서 죽는다. 더 나쁜 경우는 죽지 않고 **다른 의존성 트리로
    통과하는 것**이다 - 그러면 로컬과 CI 가 서로 다른 것을 검증한 셈이라
    하네스 결과의 근거가 통째로 흔들린다.

    의존성을 requirements-dev.txt 한 곳에 두고, 워크플로와 README 가 그 파일을
    가리키는지 확인한다.
    """
    req = ROOT / "requirements-dev.txt"
    if not req.exists():
        fail("deps", "requirements-dev.txt 가 없다")
        return

    for path in sorted((ROOT / ".github" / "workflows").glob("*.yml")):
        for i, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if "pip install" in line and "-r requirements-dev.txt" not in line:
                fail(
                    "deps",
                    f"{path.name}:{i}: 의존성을 직접 나열한다 "
                    f"(requirements-dev.txt 를 쓴다) - {line.strip()}",
                )

    readme = ROOT / "README.md"
    if readme.exists():
        for i, line in enumerate(readme.read_text(encoding="utf-8").splitlines(), 1):
            if "pip install" in line and "-r requirements-dev.txt" not in line:
                fail(
                    "deps",
                    f"README.md:{i}: 의존성을 직접 나열한다 "
                    f"(requirements-dev.txt 를 쓴다) - {line.strip()}",
                )


def _nullable(node) -> bool:
    t = node.get("type") if isinstance(node, dict) else None
    return isinstance(t, list) and "null" in t


def check_nullable_fields_are_required() -> None:
    """null 을 허용하는 필드는 required 여야 한다.

    이 저장소는 "생략 != null" 을 핵심 원칙으로 쓴다(Addendum 4, contracts/README.md 5).
      생략 = 모른다 / 물어보지 않았다
      null = 확인했고 없었다
    그런데 nullable 인데 required 가 아니면 두 상태가 계약상 구분되지 않는다.
    "Reviewer 를 호출하지 않아 review 가 null" 과 "직렬화에서 빠뜨림" 이 같아진다.

    원칙을 문서에만 두면 필드가 늘 때마다 조용히 어긋나므로 규칙으로 만든다.
    """
    for path in sorted(CONTRACTS.glob("*.schema.json")):
        schema = load_json(path)
        if schema is None:
            continue

        def walk(node, trail: str) -> None:
            if not isinstance(node, dict):
                return
            props = node.get("properties")
            if isinstance(props, dict):
                required = set(node.get("required") or ())
                for name, sub in props.items():
                    if _nullable(sub) and name not in required:
                        fail(
                            "nullable-required",
                            f"{path.name}{trail}: {name} 은 null 을 허용하는데 required 가 아니다 "
                            f"(생략과 null 이 구분되지 않는다)",
                        )
                    walk(sub, f"{trail}/{name}")
            for key in ("items", "$defs"):
                value = node.get(key)
                if key == "$defs" and isinstance(value, dict):
                    for name, sub in value.items():
                        walk(sub, f"{trail}/$defs/{name}")
                elif isinstance(value, dict):
                    walk(value, f"{trail}/{key}")

        walk(schema, "")


def check_mistake_enum_matches_yaml(mistakes: dict[str, dict]) -> None:
    """Reviewer 스키마의 enum 과 mistakes.yaml 이 갈라지는 것을 막는다.

    갈라지면 모델이 낸 code 를 backend 가 모르는(또는 그 반대) 상태가 되고,
    조용히 IMPLEMENTATION_MISC 로 뭉개진다. 정확도 저하가 원인 없이 나타난다.

    대조 대상은 **assigned_by: REVIEWER 인 것만**이다(ADR-0004).
    SYSTEM 이 부여하는 code 가 LLM enum 에 들어가면, 모델이 근거 없이 그 라벨을
    낼 수 있게 된다 - SYNTAX_ERROR 가 정확히 그 경우다.
    """
    schema = load_json(CONTRACTS / "reviewer-output.llm.schema.json")
    if schema is None or not mistakes:
        return
    enum = schema.get("$defs", {}).get("mistakeCode", {}).get("enum")
    if enum is None:
        fail("mistake-sync", "reviewer-output.llm.schema.json: $defs.mistakeCode.enum 이 없다")
        return

    reviewer_codes = {c for c, m in mistakes.items() if m.get("assigned_by") == "REVIEWER"}
    system_codes = {c for c, m in mistakes.items() if m.get("assigned_by") == "SYSTEM"}

    only_schema = sorted(set(enum) - reviewer_codes)
    only_yaml = sorted(reviewer_codes - set(enum))
    if only_schema:
        fail("mistake-sync", f"스키마에만 있는 code: {only_schema}")
    if only_yaml:
        fail("mistake-sync", f"mistakes.yaml 에만 있는 REVIEWER code: {only_yaml}")

    leaked = sorted(system_codes & set(enum))
    if leaked:
        fail(
            "mistake-sync",
            f"SYSTEM 이 부여하는 code 가 LLM enum 에 있다 (ADR-0004): {leaked}",
        )


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

    # 계약 검사는 아래에서 위로 쌓인다.
    #   1단계 규격 자체가 유효한가      (JSON Schema meta-schema)
    #   2단계 키워드가 우리가 아는 것인가 (오타로 제약이 사라지는 것을 막는다)
    #   3단계 우리 규칙을 지키는가       (폐쇄성 / LLM 경계 / taxonomy 동기화)
    check_schemas_are_valid_json_schema()
    check_schema_keywords_are_known()
    check_schemas_are_closed()
    check_judge_status_enums_match()
    check_dependencies_have_one_source()
    check_nullable_fields_are_required()
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
