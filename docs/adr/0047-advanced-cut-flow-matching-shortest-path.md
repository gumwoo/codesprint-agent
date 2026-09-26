# ADR-0047 · ADVANCED 단절점 · 단절선 · 유량 · 매칭 · 최단 경로 심화

- 상태: 채택
- 날짜: 2026-09-26
- 정본 근거: PRD §46(SCC / Articulation / Bridge), §47(Network Flow / Matching), §48(Advanced Shortest Path),
  [ADR-0033](0033-an-accepted-answer-does-not-prove-the-skill.md), [ADR-0034](0034-the-road-to-the-full-prd.md),
  [ADR-0046](0046-advanced-range-trees-and-scc.md)

## 결정

### 1. Skill 여섯 - W9 의 둘째 갈래

| 도메인 | Skill | needs_skill_control |
| --- | --- | --- |
| SCC | ARTICULATION_POINT · BRIDGE | true · true |
| FLOW | MAX_FLOW · BIPARTITE_MATCHING | true · true |
| ADVANCED_SHORTEST_PATH | MULTI_SOURCE_SHORTEST_PATH · STATE_GRAPH_DIJKSTRA | true · true |

tier 는 ADVANCED 라 ALGORITHM 트랙에서만 켜진다. 단절점 · 단절선은 PRD 가 SCC 절(§46)에 둔 대로 SCC 도메인에
넣었다 - 그래서 이 갈래는 W9a 위에 있다.

PRD 목록에서 Skill 로 두지 않은 것(Addendum §29 - Technique, 또는 같은 관측):

- `tarjan` · `kosaraju` · `ford_fulkerson` · `edmonds_karp` · `dinic` - 같은 결과를 내는 방법이다. 정의는 결과로
  적었다("그래프 크기에 비례하는 시간에", "다항 시간에")
- `zero_one_bfs` - 다익스트라가 같은 결과를 로그 배 안에 낸다. Python 에서 둘을 시간 제한으로 가를 수 없다
- `k_shortest_basic` · `johnson_basic` - 코딩테스트 범위에서 드물고, 대조 풀이를 세우기 어렵다. 필요해지면 연다

## 결과

`problem-v3` 로 Skill 마다 초안 2 개.

```
초안 요청 12 · 초안 11 · 채택 검사 통과 11 · 스스로 점검 뒤 7
```

| 거절 · 철회 | 단계 | 사유 |
| --- | --- | --- |
| STATE_GRAPH_DIJKSTRA 1 | 계약 | 초안이 계약을 지키지 않았다 |
| P123 | 중복(철회) | P122 와 같은 계산 - 같은 order · low 에서 정점마다 떨어져 나가는 부분 트리 수를 세고, 모으는 값만 다르다 |
| P126 | 중복(철회) | P127 에 포함 - 둘 다 단절선 집합이고, P127 은 중복 간선까지 다룬다 |
| P129 | 중복(철회) | P128 과 같은 계산 - 최소 컷의 비용이 최대 유량이다 |
| P131 | 중복(철회) | P130 과 같은 계산 - 둘 다 가중치 없는 여러 출발점 BFS 다 |

스스로 점검에서 지름길 풀이를 써서 실제로 채점했다.

| 문제 | 지름길 | 결과 |
| --- | --- | --- |
| P124 | 차수가 작은 작업자부터 후보가 적은 작업을 준다(증가 경로 없음) | WRONG_ANSWER |
| P125 | 이웃이 가장 적은 칸부터 타일을 놓는다 | WRONG_ANSWER |
| P128 | 역방향 간선 없이 BFS 로 흘린다 | 채택된 case 에서 **ACCEPTED** - 교차 경로를 먼저 고르게 되는 층 그래프(용량 1)를 무작위로 찾아 case 로 넣었다(정답 2, 그 풀이 1). WRONG_ANSWER |
| P132 | 통행권 없는 최단 경로에서 통행료가 큰 도로 K 개에 통행권 | 채택된 case 에서 **ACCEPTED** - 돌아가는 길이 이기는 작은 그래프를 찾아 넣었다. WRONG_ANSWER |
| P132 | 상태 다익스트라지만 정점을 한 번 꺼내면 닫는다 | WRONG_ANSWER |
| P130 | 칸마다 BFS 하고 대피소를 만나면 멈춘다 | TIME_LIMIT (대피소 1500 개, 벽 22201 칸) |
| 여섯 모두 | 대조 풀이(지우고 다시 확인 · 컷 나열 · 짝 조합 · 출발점마다 · 쿠폰 사용 나열) | TIME_LIMIT |

두 반례 case 의 기대 출력은 reference 를 샌드박스에서 돌려 만들었다 - 초안 코드는 신뢰할 수 없는 입력이다.

## 남는 위험

- **단절선은 서로소 집합으로도 구한다.** 신장 숲을 만든 뒤 트리 밖 간선마다 두 끝 사이 경로를 합치는 풀이는 거의
  선형이라 정의를 만족한다. BRIDGE 의 Evidence 는 "단절선을 효율적으로 구했다" 까지만 말한다
- MAX_FLOW 의 큰 case 는 정점 60 · 간선 1000 이다. 에드몬드-카프 · 디닉 · 용량 스케일링이 모두 들어온다 - 정의가
  "다항 시간" 이므로 받아들인다
- BIPARTITE_MATCHING 은 최대 유량으로도 풀린다. 둘 다 FLOW 도메인이고 같은 결과다
- STATE_GRAPH_DIJKSTRA 의 대조는 "쿠폰 쓰는 방법 나열" 이다. 부가 상태가 작은(K ≤ 10) 문제라, 층을 K 번 쌓아
  다익스트라를 K+1 번 도는 풀이도 들어온다 - 같은 상태 그래프를 다르게 도는 것이다
- 탐욕 · 역방향 없는 흐름 말고 시도하지 않은 지름길이 남아 있을 수 있다
- Skill 마다 문제가 하나다(ADR-0030 - 같은 문제를 여러 번 실패하면 개념 자료로 간다)
- 시간 제한과 대조 풀이는 Python 기준이다(ADR-0045). C++ 로는 대조 풀이(지우고 다시 확인 · 출발점마다 탐색 등)가
  시간 안에 들어올 수 있다 - ADR-0046 의 P115 · P118 과 같은 위험이며, 이 갈래에서 C++ 로 재 보지는 않았다
