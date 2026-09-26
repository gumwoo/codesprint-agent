# ADR-0041 · INTERMEDIATE DP · 수열 · 단조 스택 - 비트마스크 · LIS · 편집 거리 · 배낭 · 좌표 압축 · 단조 스택

- 상태: 채택
- 날짜: 2026-09-26
- 정본 근거: PRD §25(Bitmask), §35(LIS / Sequence DP), §36(Knapsack), §38(Coordinate Compression),
  §39(Monotonic Stack / Queue), [ADR-0033](0033-an-accepted-answer-does-not-prove-the-skill.md),
  [ADR-0034](0034-the-road-to-the-full-prd.md), [ADR-0040](0040-intermediate-graph-domains.md)

## 결정

### 1. Skill 일곱

| 도메인 | Skill | needs_skill_control |
| --- | --- | --- |
| BITMASK | BITMASK_DP | true |
| SEQUENCE_DP | LIS_NLOGN · EDIT_DISTANCE | true · true |
| KNAPSACK | ZERO_ONE_KNAPSACK · UNBOUNDED_KNAPSACK | true · true |
| COORDINATE_COMPRESSION | COORDINATE_COMPRESSION | false |
| MONOTONIC | MONOTONIC_STACK | true |

LCS 는 이미 DP_2D 의 문제(P62)로 있다. 단조 큐(창의 최솟값)는 넣지 않았다 - 힙에 지연 삭제를 붙인
O(N log N) 풀이와 채점으로 가를 수 없다. 좌표 압축도 대조를 두지 않는다 - 정렬한 목록에서 이분 탐색으로
순위를 찾든 사전으로 찾든 같은 압축이고, 압축하지 않는 풀이는 시간이 아니라 메모리에서 실패한다.

정의는 방법이 아니라 결과로 적는다. LIS_NLOGN 은 "N log N 에 구한다", MONOTONIC_STACK 은 "전체 N 번의 작업으로
구한다" 다. 다른 효율적인 방법(펜윅 트리 LIS, 건너뛰는 사슬)이 채점을 통과하므로, 정의에 방법을 적으면 정의와
Evidence 가 다른 말을 한다 - CORE-3 의 사이클 판정, INTERMEDIATE 그래프의 플로이드 · 위상 순서에서 검토가 같은
것을 찾았다.

## 결과

`problem-v3` 로 Skill 마다 초안 2 개, EDIT_DISTANCE 는 둘 다 거절돼 한 번 더 만들었다.

```
초안 요청 16 · 초안 15 · 채택 검사 통과 12
```

| 거절 | 단계 | 사유 |
| --- | --- | --- |
| EDIT_DISTANCE 1 | 교차 검증 | 완전탐색이 재귀 한도에 걸림 |
| EDIT_DISTANCE 1 | Skill 측정 | 대조 풀이가 작은 입력에서 이미 틀림 |
| UNBOUNDED_KNAPSACK 1 | Skill 측정 | 대조 풀이가 작은 입력에서 이미 틀림 |
| EDIT_DISTANCE 1 | (초안 없음) | Claude CLI 가 exit 1 로 끝남 |

P100 이 처음으로 세 자리 번호다(ADR-0035).

채택 검사를 통과한 뒤 스스로 점검에서 지름길 풀이를 써서 실제로 채점했다.

| 문제 | 지름길 | 고친 것 |
| --- | --- | --- |
| P89 | 비트마스크 없이 방문 순서를 DFS 로 늘리고 "지금 합 + 남은 장소마다 가장 싼 들어오는 길" 로 자르는 가지치기 - 무작위 행렬에서는 AC | 모든 장소의 가장 싼 들어오는 길이 한 장소에서 오는 case - 하한이 쓸모없어져 TIME_LIMIT |
| P98 · P99 | 표 없이 가치 밀도 순으로 가지를 따라가며 분할 배낭 상한으로 자르는 풀이 - AC | 무게(출력)가 짝수이고 가치(비용) = 무게, 한도가 홀수인 부분합형 - 상한이 늘 최선보다 커서 자르지 못해 TIME_LIMIT |
| P90 | 같은 가지치기 | 이미 TIME_LIMIT |
| P100 | - | 큰 case 가 무작위 두 문자열 하나뿐이라 거의 같은 두 문자열(거리 34), 길이가 크게 다른 두 문자열을 더했다 |
| P91 · P92 | 큰 case 가 없음 | 제약 끝 case |

검증 에이전트 검토에서 더 나온 것:

| 문제 | 찾은 것 | 고친 것 |
| --- | --- | --- |
| P89 | 들어오는 쪽과 나가는 쪽 하한 중 큰 것으로 자르는 가지치기가 AC - ADVERSARIAL case 는 들어오는 쪽만 무력하게 했다 | 두 장소씩 짝을 지어 짝 안 길만 싼 case - 두 하한 모두 짝 안의 길로 채워져 TIME_LIMIT |
| P98 · P99 | 가치들의 공약수로 상한을 내리는 가지치기가 AC - 부분합형 case 가 "전부 짝수" 에만 기대고 있었다 | 공약수가 1 인데 한도에 딱 맞는 합이 없는 case(하나만 3 으로 나눈 나머지가 1, 한도는 나머지 2) - TIME_LIMIT |
| P95 | case 12 에 제약(≤ 10^6) 밖의 값 1000001 | 10^6 으로 맞추고 기대 출력을 다시 만들었다 |
| P93 · P94 | 큰 case 입력이 바이트까지 같다 | P94 는 같은 번호가 이어지는 계단형 입력으로 바꿨다 |
| 정의 | MONOTONIC_STACK "N 번의 작업" 을 채점이 재지 못한다(아래) · BITMASK_DP 가 표현(비트)을 적었다 | 둘 다 결과로 적었다 |

## 남는 위험

- **LIS 는 tails 없이도 N log N 에 풀린다.** 값을 압축해 펜윅 트리에 "그 값으로 끝나는 가장 긴 길이" 를
  두는 풀이가 P93 에서 861ms 로 AC 다. 채점으로 가를 수 없는 다른 효율적 풀이라, LIS_NLOGN 의 Evidence 는
  "가장 긴 증가 부분 수열을 N log N 에 구했다" 까지만 말한다(GREEDY · P72 와 같은 처리)
- **정의상 받아들이는 풀이:** 다음 큰 원소를 스택 없이 "오른쪽 이웃의 다음 큰 원소" 로 건너뛰며 찾는 풀이는
  AC 다(P95 · P96). 건너뛰는 사슬이 곧 단조 스택의 내용이고 같은 분할 상환 O(N) 이다
- **단조 스택은 구간 최댓값 표로도 풀린다.** sparse table 을 만들고 큰 칸부터 건너뛰며 첫 큰 원소를 찾는 N log N
  풀이가 P95 · P96 에서 AC 다(1034ms · 992ms). 스택도 건너뛰는 사슬도 아니다. MONOTONIC_STACK 의 Evidence 는
  "원소마다 훑지 않고 다음 · 이전 큰 원소를 효율적으로 구했다" 까지만 말한다(LIS 의 펜윅 트리와 같은 처리)
- 가지치기에 할당 문제 완화(헝가리안) 같은 더 강한 하한을 붙인 풀이, 배낭에 DP 기반 상한을 붙인 풀이는
  시도하지 않았다
- 검증 에이전트 검토는 이 ADR 을 쓴 뒤에 받는다. 결과는 PR 에 남긴다
