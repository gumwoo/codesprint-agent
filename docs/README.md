# 문서 지도

## 어디부터 읽는가

| 알고 싶은 것 | 볼 곳 |
| --- | --- |
| 왜 이렇게 설계했는가 | [adr/](adr/) |
| 제품 정의 · 전체 커리큘럼 | [_archive/](_archive/) PRD |
| Mastery 산식 · 슬라이스 범위 · Sandbox | [_archive/](_archive/) Addendum |
| 실제로 검증되는 Skill 목록 | [../curriculum/](../curriculum/) |
| LLM 이 낼 수 있는 값의 범위 | [../contracts/](../contracts/) |

## ADR

| | 결정 |
| --- | --- |
| [0001](adr/0001-llm-analyzes-system-decides.md) | LLM 은 분석하고, 시스템이 판정한다 |
| [0002](adr/0002-next-action-decided-by-rule-engine.md) | 다음 학습 행동은 Rule Engine 이 결정한다 |
| [0003](adr/0003-skill-id-canonical-uppercase.md) | Skill ID 는 UPPER_SNAKE_CASE 정본 하나 |
| [0004](adr/0004-reviewer-invocation-requires-case-evidence.md) | Reviewer 는 실패 Test Case 가 있을 때만 호출한다 |

## 분해 계획 (미착수)

원본 2개(82KB)를 아래 구조로 나눈다. **급하지 않다** — 구현하면서 틀린 것이 드러나면
그때 고치는 편이 낫고, 지금은 `curriculum/` + CI 가 도는 것이 우선이다.

```text
00-product/       제품 정의, 해결하는 문제, AX 논지
10-curriculum/    45개 도메인, Skill Graph, 목표별 활성 범위
20-learning-model/ Mastery, Evidence, Mistake, Hint, Decision, 복습
30-architecture/  시스템 구성, Agent, Judge/Sandbox, 데이터 모델, API
40-delivery/      Vertical Slice, 로드맵, 테스트 전략
```

분해 시 PRD 본문의 소문자 Skill ID 를 일괄 변환한다([ADR-0003](adr/0003-skill-id-canonical-uppercase.md)).
CI 가 아니라 사람이 봐야 잡히는 작업이다.
