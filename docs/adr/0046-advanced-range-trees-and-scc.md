# ADR-0046 · ADVANCED 구간 트리 · 펜윅 · SCC

- 상태: 채택
- 날짜: 2026-09-26
- 정본 근거: PRD §42(Segment Tree), §43(Fenwick Tree), §46(SCC), [ADR-0033](0033-an-accepted-answer-does-not-prove-the-skill.md),
  [ADR-0034](0034-the-road-to-the-full-prd.md), [ADR-0042](0042-intermediate-math-string-sweep-geometry.md)

## 결정

### 1. Skill 넷 - W9 의 첫 갈래

| 도메인 | Skill | needs_skill_control |
| --- | --- | --- |
| SEGMENT_TREE | SEGMENT_TREE_RANGE_QUERY · SEGMENT_TREE_LAZY | true · true |
| FENWICK_TREE | FENWICK_PREFIX_SUM | true |
| SCC | SCC_DECOMPOSITION | true |

tier 는 ADVANCED 라 ALGORITHM 트랙에서만 켜진다. 트랙 안에서 선수가 닫혀 있다(DIVIDE_AND_CONQUER · PREFIX_SUM ·
DIFFERENCE_ARRAY · DFS_TRAVERSAL · TOPOLOGICAL_ORDER).

두 구간 트리 Skill 을 가르는 것은 **되돌릴 수 있느냐**다. SEGMENT_TREE_RANGE_QUERY 는 최솟값처럼 빼서 되돌릴 수
없는 값을 재고, 합은 FENWICK_PREFIX_SUM 이 잰다 - 합은 접두사 둘의 차로 되돌릴 수 있어 펜윅으로 충분하다.
SEGMENT_TREE_LAZY 는 구간 갱신이 섞일 때다.

정의는 방법이 아니라 결과로 적었다(ADR-0041 · 0042). "질의마다 수열 길이보다 훨씬 적은 연산으로", "그래프 크기에
비례하는 시간에" 다 - 구간 트리 대신 펜윅 둘로 구간 갱신을 하는 풀이, 타잔 방법도 같은 결과를 낸다.

PRD 목록에서 Skill 로 두지 않은 것: `segment_tree_build` · `point_update` · `fenwick_update` 는 질의 Skill 의
일부라 따로 잴 수 없다(Addendum §29 - Technique). `range_max` · `range_sum` 은 `range_min` 과 같은 관측이다.
`inversion_count` 는 병합 정렬(DIVIDE_AND_CONQUER)로도 같은 시간에 풀려 펜윅을 가르지 못한다. §46 의 단절점 ·
단절선은 SCC 와 다른 관측이라 W9 다음 갈래에서 연다.

## 결과

`problem-v3` 로 Skill 마다 초안 2 개.

```
초안 요청 8 · 초안 7 · 채택 검사 통과 7 · 스스로 점검 뒤 4
```

| 거절 · 철회 | 단계 | 사유 |
| --- | --- | --- |
| SCC_DECOMPOSITION 1 | 계약 | 응답에서 JSON 객체를 찾지 못했다 |
| P116 | 중복(철회) | P115 와 같은 계산 - 이체는 점 갱신 둘이고 질의는 같은 구간 합이다 |
| P119 | 중복(철회) | P118 과 같은 계산 - 위치를 0 부터 세는 것과 명령 이름만 다르다 |
| P121 | 중복(철회) | P120 에 포함 - 최솟값의 위치를 함께 낼 뿐이고 제약도 더 작다 |

그래서 네 Skill 모두 문제가 하나씩이다(P115 · P117 · P118 · P120).

스스로 점검에서 지름길 풀이를 써서 실제로 채점했다.

| 문제 | 지름길 | 결과 |
| --- | --- | --- |
| P115 | 질의마다 `sum(a[l-1:r])` (C 수준 합) | TIME_LIMIT |
| P120 | 질의마다 `min(a[l-1:r])` | TIME_LIMIT |
| P120 | 작은 값부터 힙에서 꺼내 보며 구간 안의 첫 값을 답한다 | 채택된 case 에서 **ACCEPTED(122ms)** - 큰 case 의 답이 세 가지뿐이었다(긴 구간이 거의 늘 전체 최솟값을 품었다). 작은 값을 앞 절반에 몰고 뒤 절반을 묻는 case 로 바꿔 TIME_LIMIT, 답 434 가지, 정답 282ms |
| P115 · P118 · P120 | 제곱근 분할 | ACCEPTED (362 · 1032 · 531ms) - 아래 남는 위험 |
| P117 | 정점마다 정방향 · 역방향 BFS (대조 풀이) | TIME_LIMIT |

검증 에이전트 검토에서 더 나온 것:

| 문제 | 찾은 것 | 고친 것 |
| --- | --- | --- |
| P117 | 묶이지 않은 정점으로만 정방향 · 역방향 탐색을 되풀이하는 풀이(SCC 알고리즘 없음, 최악 O(N·(N+M)))가 AC(301ms). 큰 case 가 거대 SCC 하나와 단독 정점들이라, 거대 SCC 를 한 번 묶으면 탐색할 곳이 거의 없었다. 대조 풀이가 느린 이유도 발상이 아니라 탐색마다 `[False]*n` 을 새로 만드는 데 있었다 | 크기 2(가끔 3) 사이클 묶음 약 2 만 개를 무작위 위상 순서로 놓고 묶음마다 뒤쪽으로 간선 3 개, 멈춤 모임 60 개인 case 13 을 더했다. 2 차 검토에서 그 case 도 정방향 · 역방향을 번갈아 넓혀 먼저 끝난 쪽을 쓰는 풀이(912ms)와 차수 0 을 떼다 막히면 들어오는 간선이 적은 정점에서 역방향 탐색하는 풀이(1255ms)가 뚫었다 - 뒤쪽 **아무** 묶음으로 이어 앞쪽은 조상이, 뒤쪽은 후손이 적어 min(조상, 후손) 이 작았다. 간선을 **바로 뒤 200 묶음 안으로만** 잇도록 case 13 을 바꿨다 - 가운데 묶음은 조상과 후손이 둘 다 많다. 되풀이 탐색 변형 여섯(번갈아 · 한 방향 도장 배열 · 차수 0 떼기 포함)과 C++ 번갈아 탐색 모두 TIME_LIMIT, 정답 125ms |
| P118 | `wrong.py` 가 숨은 큰 case 에서만 걸려 Reviewer 가 읽을 수 있는 실패 case 가 없었다 | 작은 BOUNDARY case(`8 2 / 1..8 / 1 1 7 10 / 2 5 6`, 정답 31)를 큰 case 앞에 두었다 - 이제 그 case 에서 걸린다 |

## 남는 위험

- **제곱근 분할이 셋 모두에서 AC 다.** 질의마다 √N 번 남짓의 연산이라 정의("수열 길이보다 훨씬 적은 연산")를
  만족한다. Python 에서 √N 과 log N 을 시간 제한으로 가르려면 N 을 몇 배로 키워야 하고, 그러면 정답도 1 초를
  넘는다. 그래서 이 Skill 들의 Evidence 는 "갱신이 섞인 구간 질의를 선형보다 효율적으로 풀었다" 까지만 말한다
  (LIS 의 펜윅 트리 · 실패 함수의 해시와 같은 처리)
- SEGMENT_TREE_LAZY 는 펜윅 둘(구간 갱신 · 구간 합)로도 풀린다. 둘 다 ADVANCED 이고 같은 결과다
- 누적 합을 주기적으로 다시 만드는 풀이(P115, 658ms)도 제곱근 분할과 같은 O(Q√N) 계열이라 AC 다
- P117 은 차수 0 을 떼고 무작위 피벗으로 나누는 전방-후방 분할(FW-BW)로도 풀릴 수 있다 - 기대 시간이 거의 선형이라
  정의("그래프 크기에 비례하는 시간")에 가깝다. 검증 에이전트의 구현은 이전 case 13 에서 호스트 849ms 였고 샌드박스에서는
  제한을 넘었다(최적화하지 않은 구현). 지금 case 로는 재지 않았다. 더 똑똑하게 피벗을 고르는 풀이도 남아 있을 수 있다
- P115 · P118 의 큰 case 는 연산 구간이 길다(P115 는 N 의 50~100%, P118 은 80~100%. P120 은 중앙값 31%). 바깥 조각만
  고치는 P118 풀이가 제한의 2.6 배(5146ms)로 가장 가깝다
- **C++ 로는 Skill 없는 풀이가 P115 · P118 에서 AC 다.** 질의마다 직접 더하는 P115 풀이 902ms, 원소를 하나씩 고치는
  P118 풀이 458ms(P120 의 `min_element` 풀이는 TIME_LIMIT). 시간 제한과 대조 풀이가 Python 기준이기 때문이다
  (ADR-0045 남는 위험). 그래서 C++ 제출은 이 둘에서 FENWICK_PREFIX_SUM · SEGMENT_TREE_LAZY 를 재지 못한다 - 언어별
  시간 제한이나 언어별 대조가 생기기 전까지 그렇다
- 가지치기 · 힙 · 제곱근 분할 말고 시도하지 않은 지름길이 남아 있을 수 있다
- 문제가 Skill 마다 하나라 같은 문제를 여러 번 실패하면 개념 자료로 간다(ADR-0030) - 변형 문제는 다음 생성 때
- 검증 에이전트 검토는 이 ADR 을 쓴 뒤에 받는다. 결과는 PR 에 남긴다
