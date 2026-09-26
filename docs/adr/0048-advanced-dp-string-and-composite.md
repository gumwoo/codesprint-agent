# ADR-0048 · ADVANCED DP · 문자열 심화 · 섞인 문제 - 마지막 도메인 셋

- 상태: 채택
- 날짜: 2026-09-26
- 정본 근거: PRD §49(Advanced DP), §50(Advanced String), §51(Mixed / Composite Problems),
  [ADR-0033](0033-an-accepted-answer-does-not-prove-the-skill.md), [ADR-0034](0034-the-road-to-the-full-prd.md),
  [ADR-0047](0047-advanced-cut-flow-matching-shortest-path.md)

## 결정

### 1. Skill 여덟 - W9 의 마지막 갈래

| 도메인 | Skill | needs_skill_control | 문제 |
| --- | --- | --- | --- |
| ADVANCED_DP | TREE_DP · REROOTING_DP · INTERVAL_DP · DIGIT_DP | true | P144 · P140 · P141 · P134 · P135 · P133 |
| ADVANCED_STRING | PALINDROME_RADII · MULTI_PATTERN_MATCHING · SUFFIX_ARRAY | true | P139 · P136 · P145 |
| COMPOSITE | SKILL_COMPOSITION | false | P147 |

이것으로 도메인 레지스트리 46 개가 모두 켜진다. ADVANCED DP · 문자열은 ALGORITHM 트랙에서만, COMPOSITE(tier
INTERMEDIATE)는 TOP_TIER 에서도 켜진다.

PRD 목록에서 Skill 로 두지 않은 것:

- `bitmask_dp` - 이미 BITMASK_DP(INTERMEDIATE)가 있다
- `dag_dp` - 위상 순서(TOPOLOGICAL_ORDER) 위의 DP_1D 와 같은 관측이다
- `suffix_automaton` - SUFFIX_ARRAY 와 같은 결과(서로 다른 부분 문자열 수 등)를 낸다. 정의를 결과로 적어 둘 다 받는다
- `manacher` · `aho_corasick` 은 방법 이름이라 결과로 이름을 바꿨다(PALINDROME_RADII · MULTI_PATTERN_MATCHING)

### 2. 섞인 문제는 Skill 하나 + SECONDARY 로 적는다

PRD §51 의 "문제는 복수 Skill 을 가진다" 는 문제의 성질이다. 그런데 도메인을 켜려면 Skill 이 있어야 한다(CI).
그래서 COMPOSITE 에는 **SKILL_COMPOSITION** 하나를 둔다 - "본문이 기법을 알려 주지 않는 문제에서 필요한 기법 둘
이상을 스스로 골라 잇는다" 는 관측이다. 이어 붙이는 기법은 그 문제의 SECONDARY Skill 로 적어, 풀면 구성 기법에도
Evidence 가 남는다.

- needs_skill_control 은 false 다. "이 Skill 없이 같은 답을 내는 풀이" 를 하나로 정할 수 없다. 대신 제약 끝 case 로
  느린 조합을 떨어뜨린다
- **섞인 문제는 기존 문제의 다시 쓰기면 안 된다.** CORE 에서 푼 문제를 SKILL_COMPOSITION 으로 다시 받으면 그 Skill 의
  Evidence 가 이미 잰 Skill 의 재측정이 된다(아래 P142 · P143)
- 선수는 문제가 실제로 잇는 기법이다. P147(불길 속 늦은 출발)은 여러 출발점 BFS 로 불이 닿는 시각을 구하고, 답 W 위에서
  이분 탐색하며 BFS 로 판정한다 - 선수는 BFS_SHORTEST_PATH · PARAMETRIC_SEARCH
- PARAMETRIC_SEARCH 는 SECONDARY 로 적지 않았다. needs_skill_control 이 true 인 Skill 은 대조로 확인한 PRIMARY 로만 둘 수
  있다(CI) - 그래서 이분 탐색 쪽은 Evidence 를 남기지 않는다

## 결과

`problem-v3` 로 Skill 마다 초안 2 개, SKILL_COMPOSITION 은 검토 뒤 3 개를 더.

```
초안 요청 21 · 초안 18 · 채택 검사 통과 15 · 스스로 점검 뒤 11 · 검토 뒤 10
```

| 거절 · 철회 | 단계 | 사유 |
| --- | --- | --- |
| DIGIT_DP 1 · SUFFIX_ARRAY 1 | 초안 | Claude CLI 가 300 초 안에 끝나지 않음 |
| SUFFIX_ARRAY 1 | 계약 | 초안이 계약을 지키지 않음(다시 만들어 2 개를 받았다) |
| TREE_DP 1 | Skill 측정 | 대조 풀이가 큰 입력에서 TIME_LIMIT 이 아니라 MEMORY_LIMIT |
| P137 | 중복(철회) | P136 과 같은 계산 - 같은 트라이 · 실패 링크, 출현을 패턴마다 모으는지 위치마다 모으는지만 다르다 |
| P138 | 중복(철회) | P139 와 같은 계산 - 모든 중심의 반지름을 더하는지 위치마다 큰 쪽을 내는지만 다르다 |
| P146 | 중복(철회) | P145 와 같은 계산 - 접미사 배열 · LCP 에서 최댓값을 내는지 새 부분 문자열을 세는지만 다르다 |
| P142 | 중복(검토에서 철회) | 기존 P73_WALL_BREAK_K_BFS 와 같은 계산 - P73 reference 에 입력 문자만 바꾼 풀이가 모든 case 를 맞혔다 |
| P143 | 중복(검토에서 철회) | 기존 P67_MEETING_ROOM_MAX 와 같은 계산 - 닫힌 구간으로 보는지만 다르다 |
| SKILL_COMPOSITION 초안 둘 | 채택하지 않음 | 공장 작업 완료 시각은 P88 과, 두껍게 칠한 울타리는 P106 과 같은 계산이라 채택 검사에 넣지 않았다 |

그래서 TREE_DP · DIGIT_DP · 문자열 셋 · SKILL_COMPOSITION 은 문제가 하나씩이다.

스스로 점검에서 지름길 풀이를 써서 실제로 채점했다.

| 문제 | 지름길 | 결과 |
| --- | --- | --- |
| P136 | 패턴 길이마다 본문의 그 길이 부분 문자열을 모두 센다(Counter) | MEMORY_LIMIT |
| P139 | 중심마다 양쪽으로 넓힌다 | TIME_LIMIT |
| P145 | 길이마다 부분 문자열 집합 · 굴리는 해시 집합 | TIME_LIMIT · TIME_LIMIT |
| P147 | W 를 0 부터 하나씩 늘리며 BFS 로 확인(이분 탐색 없음) | 무작위 격자에서는 ACCEPTED(498ms) - 답이 5777 인 뱀 복도 case 를 넣어 TIME_LIMIT, 정답 82ms |
| 대조가 있는 아홉 | 대조 풀이 | TIME_LIMIT |

검증 에이전트 검토에서 더 나온 것:

| 문제 | 찾은 것 | 고친 것 (검토자가 샌드박스에서 확인한 case, 기대 출력은 reference 를 샌드박스에서) |
| --- | --- | --- |
| P142 · P143 | 기존 문제와 같은 계산(위 표) | 철회하고 P147 을 새로 채택 |
| P139 | 같은 글자 구간 경계까지 한 번에 건너뛰며 넓히는 풀이(Manacher 없음, 최악 제곱)가 AC(155ms). 가장 큰 case 는 같은 글자 10 만 개가 아니라 **같은 글자 구간 29 개**였다 | 'ab' 가 번갈아 길게 이어지고 사이사이 'c' 가 끼는 10 만 글자 case - TIME_LIMIT, 정답 277ms |
| P136 | 길이 종류마다 본문 슬라이스를 사전에 대조(1409ms) · 위치마다 트라이를 따라 내려가기(실패 링크 없음, 764ms)가 AC. 패턴 길이가 36 종류(5~40)뿐이었다 | 거의 같은 글자인 20 만 글자 본문과 길이 1~631 의 패턴 631 개 case - 둘과 섞은 변형 모두 TIME_LIMIT, 정답 456ms |
| P133 | 수를 늘 끝 18 자리로 보는 풀이가 모든 case 를 맞혔다(R = 10^18 · S > 140 case 없음) | 경계 case `10^18 10^18 1` · `1 10^18 162` · `999999999999999990 10^18 1` - WRONG_ANSWER |

## 남는 위험

- **정의가 받아들이는 다른 방법**: 회문은 접두사 해시 + 이분 탐색(N log N), 서로 다른 부분 문자열은 접미사 자동자,
  모든 루트의 트리 값은 방향 간선마다 메모한 DP, 트리 DP 는 잎을 떼며 부모 무게를 줄이는 환원(P144, 193ms). 모두
  정의(결과)를 만족하므로 Evidence 는 "그 결과를 효율적으로 구했다" 까지만 말한다
- P136 의 새 case 는 위치마다 출력 링크를 따라 등장을 하나씩 세는 아호-코라식(O(|T| + 등장 수))도 떨어뜨린다. 정의가
  "본문 길이와 패턴 길이 합에 비례" 이므로 등장 수에 비례하는 풀이는 받지 않는다
- SKILL_COMPOSITION 은 대조가 없다 - 무엇을 이었는지는 SECONDARY Evidence 로만 남고, 이분 탐색 쪽은 그마저 남지 않는다.
  섞인 문제를 하나 풀었다는 것이 어떤 조합이든 다룰 수 있다는 뜻은 아니다
- P141 · P144 의 가장 큰 case 는 깊이가 40 안팎이다 - 깊은 트리에서 재귀 깊이 한계에 걸리는 풀이를 보지 못한다
- 시간 제한 · 대조는 Python 기준이다(ADR-0045). C++ 로는 재 보지 않았다 - ADR-0046 의 P115 · P118 처럼 C++ 에서
  대조 풀이가 들어올 수 있다
- 시도하지 않은 지름길이 남아 있을 수 있다. Skill 마다 문제가 한두 개다
