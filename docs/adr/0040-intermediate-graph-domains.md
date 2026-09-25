# ADR-0040 · INTERMEDIATE 그래프 - 최단 경로 · 서로소 집합 · 최소 신장 트리 · 위상 정렬

- 상태: 채택
- 날짜: 2026-09-26
- 정본 근거: PRD §37(도메인별 Skill 목록), §127(INTERMEDIATE),
  [ADR-0033](0033-an-accepted-answer-does-not-prove-the-skill.md), [ADR-0034](0034-the-road-to-the-full-prd.md),
  [ADR-0039](0039-core-3-domains.md)

## 결정

### 1. Skill 여섯 - INTERMEDIATE 의 그래프 갈래

| 도메인 | Skill | needs_skill_control |
| --- | --- | --- |
| SHORTEST_PATH | DIJKSTRA · FLOYD_WARSHALL · BELLMAN_FORD | true · false · false |
| UNION_FIND | DISJOINT_SET | true |
| MST | MINIMUM_SPANNING_TREE | true |
| TOPOLOGICAL_SORT | TOPOLOGICAL_ORDER | false |

ADR-0034 의 W6 을 셋으로 나눈다 - 그래프(이 ADR), DP · 수열, 수학 · 문자열. 한 PR 에 Skill 스물을 넣으면
검증 에이전트가 문제마다 지름길을 시도할 시간이 없다. CORE-3 검토에서 여섯 가지가 한 번에 나왔다.

FLOYD_WARSHALL · BELLMAN_FORD · TOPOLOGICAL_ORDER 는 대조를 두지 않는다. 플로이드는 N ≤ 100 에서 정점마다
다익스트라를 돌려도 같은 시간에 풀리고, 벨만-포드의 음수 간선은 다익스트라로 답이 틀리므로 "빠르냐" 가
아니라 "맞느냐" 로 갈린다. 위상 순서도 DFS 종료 역순과 진입 차수가 둘 다 위상 정렬이다.

### 2. 생성 프롬프트 problem-v3

CORE-2 · CORE-3 검토에서 되풀이된 결함을 초안 단계에서 막는다(파일 이름이 버전이다).

- 본문에 그 Skill 의 핵심 식 · 알고리즘을 적지 않는다
- 제약 끝에서도 정답 출력이 1MB 를 넘지 않게 한다
- 대조가 있으면 큰 입력이 다양해야 한다(정렬 · 등차 · 같은 값 · 자명한 답 금지)
- 모든 case 는 제약 안이어야 한다

## 결과

`problem-v3` 로 Skill 마다 초안 2 개.

```
초안 요청 12 · 초안 11 · 계약 통과 11 · 채택 검사 통과 11 · 스스로 점검 뒤 10
```

빠진 하나는 MINIMUM_SPANNING_TREE 의 첫 초안이다 - Claude CLI 가 300 초 안에 끝나지 않았다. 그래서
MINIMUM_SPANNING_TREE 는 문제가 하나(P86)다. BELLMAN_FORD 도 P79 를 철회해 하나(P78)다.

채택 검사를 통과한 뒤 스스로 점검에서 고친 것:

| 문제 | 약점 | 고친 것 |
| --- | --- | --- |
| P79 | P78 과 같은 문제(출발점에서 도달 불가 · 최소 비용 · 끝없이 작아짐). 번호 기준과 표기만 다름 | **철회** |
| P80 · P81 | 무작위 그래프에서는 우선순위 큐 없이 큐로 완화를 되풀이하는 풀이(SPFA)가 통과한다 | 5 행 격자(가로 간선 무작위, 세로 1) - SPFA 가 정점마다 수백 번 꺼내 TIME_LIMIT |
| P78 · P84 · P85 · P87 · P88 | 큰 case 가 없음(입력 115 바이트 이하) | 제약 끝 case. P78 은 음수 사이클에서 닿는 정점 41 개와 닿지 않는 정점 50 개를 함께 둔다 |

생성 문제 번호는 W4 가 P65 대신 손으로 쓴 P77 뒤로 한 칸씩 밀었다(P78 ~ P88). 채택 기록의 code 도 함께 바꿨다.

## 남는 위험

- **정의상 받아들이는 풀이:** 합칠 때 작은 쪽 원소의 번호를 큰 쪽으로 바꾸는 풀이(P82)는 union-find 가
  아니지만 서로소 집합 자료구조다. 간선이 늘 때마다 전체를 다시 칠하는 풀이는 TIME_LIMIT 이다
- 휴리스틱을 붙인 SPFA(SLF · LLL)는 시도하지 않았다
- 대조가 없는 셋(플로이드 · 벨만-포드 · 위상 순서)은 판단이지 검증이 아니다(ADR-0033)
- 검증 에이전트 검토는 이 ADR 을 쓴 뒤에 받는다. 결과는 PR 에 남긴다
