# CodeSprint Agent

> 코딩테스트 문제를 대신 풀어주는 AI가 아니라, 사용자가 **무엇을 모르는지 찾아내고
> 그 Skill을 독립 풀이 가능한 상태까지 가장 짧은 경로로 만드는** 학습 운영 Agent.

기존에는 학습자가 직접 하던 판단 — 무엇을 공부할지, 지금 이 문제를 풀어도 되는지,
틀린 이유가 개념인지 구현인지, 언제 복습할지, 시험이 임박했을 때 무엇을 버릴지 —
을 Agent와 Rule Engine이 대신 수행한다.

## Adaptive Learning Loop

```text
진단 → Skill 상태 → Planner → 개념/문제 → 사용자 코드
  ↑                                            ↓
  └── 다음 학습 행동 ← Decision ← Mastery ← Reviewer ← Judge
```

## 설계의 중심 — LLM은 분석하고, 시스템이 판정한다

| LLM | 시스템 |
| --- | --- |
| 개념 설명 · 힌트 · 문제 변형 | AC/WA 판정, 실행 시간, 메모리 (Judge) |
| 오답 원인 **후보** 도출 | Mistake **확정** 여부 (Rule) |
| 분석의 confidence | mastery / confidence **계산** |
| 코드 의미 분석 | 다음 학습 행동 (Decision Engine) |

이 경계를 **프롬프트가 아니라 스키마로 강제한다.** `contracts/*.llm.schema.json`에는
`score` / `mastery` / `nextAction` 필드가 없다. 물어보지 않으므로 만들어낼 수 없다.

근거: [ADR-0001](docs/adr/0001-llm-analyzes-system-decides.md) ·
[ADR-0002](docs/adr/0002-next-action-decided-by-rule-engine.md)

## 저장소 구조

```text
curriculum/    Skill Graph — 문서가 아니라 CI가 검증하는 데이터
contracts/     LLM 요청/응답 계약 (JSON Schema)
tools/         계약 검사 + 메타테스트
docs/adr/      결정과 그 이유
docs/_archive/ 원본 PRD / Implementation Spec (현재 정본)
```

## 검증

```bash
pip install pyyaml
python tools/check_curriculum.py      # 데이터/계약이 맞는가
python tools/meta_test_curriculum.py  # 검사가 실제로 잡는가
```

두 번째가 있는 이유: **검사가 통과하는 것과 검사가 일하는 것은 다르다.**
아무것도 안 하는 검사도 통과한다. 그래서 계약을 일부러 망가뜨린 뒤 검사가 실제로
실패하는지 확인한다. 여기서 "검사가 놓침"이 나오면 데이터가 아니라 **하네스가 깨진 것**이다.

현재 17개 위반 케이스를 차단한다.

## 현재 상태

첫 Vertical Slice 착수 전. 존재하는 것은 커리큘럼 데이터, LLM 계약, 그리고
그 둘을 지키는 하네스다.

| | 상태 |
| --- | --- |
| Skill Catalog (8개) + 도메인 골격 (46개) | 완료 |
| LLM 계약 + 검사 하네스 | 완료 |
| Judge / Sandbox | 미착수 |
| Backend / Frontend | 미착수 |
| Reviewer 평가 하네스 | 슬라이스 1 이후 (로깅은 지금부터) |

슬라이스 1 범위는 Python 3.12 + BFS Grid 계열 8개 Skill + Mistake 2종 자동 드릴이다.
전체 45개 도메인은 `curriculum/domains.yaml`에 골격으로 등록돼 있으며, 검증된 Skill만
`skills.yaml`로 승격한다.
