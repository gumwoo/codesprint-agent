# ADR-0003 · Skill ID는 UPPER_SNAKE_CASE 정본 하나만 쓴다

- 상태: 채택
- 날짜: 2026-09-05
- 정본 근거: Addendum §25~30, §35

## 맥락

두 정본 문서의 Skill ID 표기가 다르다.

| 문서 | 표기 | 예 |
| --- | --- | --- |
| PRD v2 (본문 전체) | 소문자 | `bfs_basic`, `grid_bfs`, `shortest_path_unweighted` |
| Addendum §26 | 대문자 | `BFS_BASIC`, `BFS_GRID_TRAVERSAL`, `BFS_SHORTEST_PATH` |

Skill ID는 DB · API · Event · Prompt · Problem Metadata · Reviewer Output ·
Analytics 전체에서 같은 값으로 쓰인다(§25). 표기가 둘이면 어딘가에서 반드시
정규화 코드가 생기고, 정규화를 빠뜨린 경로에서 **조용히 매칭이 실패한다.**

Skill이 매칭되지 않으면 Evidence가 유실되고, Evidence가 유실되면 mastery가 갱신되지
않는다. 사용자에게는 "문제를 풀었는데 점수가 안 오른다"로 나타난다. 원인에서 아주
먼 지점의 증상이다.

## 결정

**`UPPER_SNAKE_CASE`를 정본으로 확정한다.** PRD 본문의 소문자 표기는 폐기한다.

형식은 `{DOMAIN}_{CONCEPT}`이며, 정규식으로 강제한다.

```
^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$
```

Addendum을 택한 이유는 나중 문서여서가 아니라, 그 문서가 **표기 통일 자체를 목적으로
작성됐고**(§25~30) 금지 규칙(§28 유사 Skill 중복 금지, §29 Technique를 Skill로 만들지
않기, §30 Mistake와 구분)까지 함께 정의하기 때문이다. 소문자를 택하면 그 규칙들을
다시 옮겨 써야 한다.

## 함께 확정하는 것

**1. DB PK와 code를 분리한다** (§27)

```
id   BIGINT      -- 내부 PK. 조인에만 쓴다.
code VARCHAR(100) UNIQUE  -- 외부 식별자. API/Prompt/Event가 쓴다.
```

**2. code는 Production 사용 후 변경 금지** (§35)

바꿔야 하면 `deprecated: true` + `replacement_skill_code`로 대체한다.
과거 Evidence가 가리키는 대상이 사라지면 학습 이력이 끊긴다.

**3. Skill / Technique / Mistake를 섞지 않는다** (§29, §30)

```
Skill    할 수 있어야 하는 것    GRID_BOUNDARY_CHECK   curriculum/skills.yaml
Mistake  틀리는 방식             BOUNDARY_CHECK        curriculum/mistakes.yaml
Technique 다른 Skill의 구성요소   (Skill로 만들지 않음)
```

`BFS_QUEUE`라는 Skill은 만들지 않는다. BFS가 `PYTHON_DEQUE_BASIC`을 prerequisite으로
요구하는 것으로 표현한다.

## 강제 방법

`tools/check_curriculum.py`가 CI에서 검증한다.

- code 정규식 위반 → 실패
- code 중복 → 실패
- Mistake code가 Skill code와 겹침 → 실패
- `domains.yaml`에 없는 domain 참조 → 실패

`tools/meta_test_curriculum.py`가 이 검사들이 실제로 동작하는지 검증한다.

## 결과

- PRD 본문을 `docs/10-curriculum/`으로 분해할 때 소문자 표기를 전부 변환한다.
  변환 누락은 CI가 아니라 **사람이 봐야** 잡히므로, 분해 시 일괄 처리한다.
- 원본 문서는 `docs/_archive/`에 그대로 둔다. 변환 과정에서 의미가 바뀌었는지
  대조할 근거가 필요하다.

## 관련

- [ADR-0001](0001-llm-analyzes-system-decides.md)
