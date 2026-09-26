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
| COMPOSITE | SKILL_COMPOSITION | false | P142 · P143 |

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

needs_skill_control 은 false 다. "이 Skill 없이 같은 답을 내는 풀이" 를 하나로 정할 수 없다 - 구성 기법 하나만 빼도
여러 가지 느린 풀이가 나온다. 대신 두 문제 모두 제약 끝에 가까운 case 로 느린 조합을 떨어뜨린다(아래).

## 결과

`problem-v3` 로 Skill 마다 초안 2 개.

```
초안 요청 18 · 초안 15 · 채택 검사 통과 14 · 스스로 점검 뒤 11
```

| 거절 · 철회 | 단계 | 사유 |
| --- | --- | --- |
| DIGIT_DP 1 · SUFFIX_ARRAY 1 | 초안 | Claude CLI 가 300 초 안에 끝나지 않음 |
| SUFFIX_ARRAY 1 | 계약 | 초안이 계약을 지키지 않음(다시 만들어 2 개를 받았다) |
| TREE_DP 1 | Skill 측정 | 대조 풀이가 큰 입력에서 TIME_LIMIT 이 아니라 MEMORY_LIMIT |
| P137 | 중복(철회) | P136 과 같은 계산 - 같은 트라이 · 실패 링크, 출현을 패턴마다 모으는지 위치마다 모으는지만 다르다 |
| P138 | 중복(철회) | P139 와 같은 계산 - 모든 중심의 반지름을 더하는지 위치마다 큰 쪽을 내는지만 다르다 |
| P146 | 중복(철회) | P145 와 같은 계산 - 접미사 배열 · LCP 에서 최댓값을 내는지 새 부분 문자열을 세는지만 다르다 |

그래서 TREE_DP · DIGIT_DP · 문자열 셋은 문제가 하나씩이다.

스스로 점검에서 지름길 풀이를 써서 실제로 채점했다.

| 문제 | 지름길 | 결과 |
| --- | --- | --- |
| P143 | 끝나는 날로 정렬해 뒤로 훑어 겹치지 않는 앞 공연을 찾는 DP(제곱) | 채택된 case 의 가장 큰 입력이 56 바이트라 **걸리지 않았다** - 좌표 100 만 안에 겹침이 많은 6 만 개 case 를 넣어 TIME_LIMIT, 정답 72ms |
| P142 | 칸마다 한 번만 방문(부순 횟수를 상태에 넣지 않음) | WRONG_ANSWER. 100 x 100 · K = 5 case 를 더했다(정답 701ms) |
| P136 | 패턴 길이마다 본문의 그 길이 부분 문자열을 모두 센다 | MEMORY_LIMIT |
| P139 | 중심마다 양쪽으로 넓힌다 | TIME_LIMIT (같은 글자 10 만 개) |
| P145 | 길이마다 부분 문자열 집합 · 굴리는 해시 집합 | TIME_LIMIT · TIME_LIMIT |
| 대조가 있는 아홉 | 대조 풀이 | TIME_LIMIT |

두 case 의 기대 출력은 reference 를 샌드박스에서 돌려 만들었다. P143 은 입력을 1MB 안쪽으로 두려고 N 을 제약
끝(20 만)보다 작은 6 만으로 잡았다.

## 남는 위험

- **정의가 받아들이는 다른 방법**: 회문은 접두사 해시 + 이분 탐색(N log N), 서로 다른 부분 문자열은 접미사 자동자,
  여러 패턴은 패턴 길이가 몇 가지뿐일 때 길이별 해시, 모든 루트의 트리 값은 방향 간선마다 메모한 DP. 모두 정의
  (결과)를 만족하므로 Evidence 는 "그 결과를 효율적으로 구했다" 까지만 말한다
- SKILL_COMPOSITION 은 대조가 없다 - 구성 기법 중 무엇을 썼는지는 SECONDARY Evidence 로만 남는다. 섞인 문제를
  풀었다는 것이 어떤 조합이든 다룰 수 있다는 뜻은 아니다
- 시간 제한 · 대조는 Python 기준이다(ADR-0045). C++ 로는 재 보지 않았다 - ADR-0046 의 P115 · P118 처럼 C++ 에서
  대조 풀이가 들어올 수 있다
- 시도하지 않은 지름길이 남아 있을 수 있다. Skill 마다 문제가 한두 개다
- 검증 에이전트 검토는 이 ADR 을 쓴 뒤에 받는다. 결과는 PR 에 남긴다
