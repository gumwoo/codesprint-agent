# ADR-0042 · INTERMEDIATE 수학 · 문자열 · 스위핑 · 기하

- 상태: 채택
- 날짜: 2026-09-26
- 정본 근거: PRD §23(Number Theory), §24(Combinatorics), §40(Trie), §41(String Matching), §44(Sweep Line),
  §45(Geometry), [ADR-0033](0033-an-accepted-answer-does-not-prove-the-skill.md),
  [ADR-0034](0034-the-road-to-the-full-prd.md), [ADR-0041](0041-intermediate-dp-sequence-monotonic.md)

## 결정

### 1. Skill 여덟 - W6 의 마지막 갈래

| 도메인 | Skill | needs_skill_control |
| --- | --- | --- |
| NUMBER_THEORY | PRIME_SIEVE · SMALLEST_PRIME_FACTOR | true · true |
| COMBINATORICS | BINOMIAL_MOD | true |
| TRIE | TRIE_PREFIX | false |
| STRING_MATCHING | PREFIX_FUNCTION | true |
| SWEEP_LINE | INTERVAL_SWEEP | true |
| GEOMETRY | CCW_ORIENTATION · CONVEX_HULL | false · true |

이것으로 INTERMEDIATE 도메인 중 COMPOSITE(섞인 문제)만 남는다 - 그것은 ADVANCED 의 Mixed 와 함께 W9 에서 연다.

대조를 두지 않은 둘:

- **TRIE_PREFIX** - 단어를 정렬해 두고 접두사 구간을 이분 탐색으로 찾는 풀이가 트라이와 같은 시간에 접두사
  질의에 답한다. 채점으로 가를 수 없다
- **CCW_ORIENTATION** - 외적 한 번이라 느린 대안이 없다. 판정은 맞느냐로만 갈린다(일직선 · 큰 좌표)

모듈러 거듭제곱 · 역원은 Skill 로 두지 않았다 - `pow(a, b, m)` · `pow(a, -1, m)` 한 줄이라 잴 것이 없다.

정의는 방법이 아니라 결과로 적었다(ADR-0041). PREFIX_FUNCTION 은 "경계 길이를 선형 시간에", CONVEX_HULL 은
"N log N 에" 다 - Z 배열, 그레이엄 스캔도 같은 결과를 낸다.

## 결과

`problem-v3` 로 Skill 마다 초안 2 개.

```
초안 요청 16 · 초안 15 · 채택 검사 통과 14 · 스스로 점검 뒤 11
```

| 거절 · 철회 | 단계 | 사유 |
| --- | --- | --- |
| CCW_ORIENTATION 1 | (초안 없음) | Claude CLI 가 300 초 안에 끝나지 않음 |
| PRIME_SIEVE 1 | 교차 검증 | 완전탐색이 작은 입력에서 틀림 |
| P102 | 중복(철회) | P101 과 같은 계산 - 격자 경로 수가 곧 C(r+c, r) |
| P105 | 중복(철회) | P104 에 포함 - 볼록 껍질 넓이를 P104 가 이미 묻는다 |
| P112 | 중복(철회) | P111 과 같은 계산 - 가장 작은 소인수로 분해한 뒤 모으는 값만 다르다(P25 · P26 과 같은 이유) |

그래서 BINOMIAL_MOD · CCW_ORIENTATION · CONVEX_HULL · PRIME_SIEVE · SMALLEST_PRIME_FACTOR 는 문제가 하나씩이다.

스스로 점검에서 지름길 풀이를 써서 실제로 채점했다.

| 문제 | 지름길 | 결과 |
| --- | --- | --- |
| P108 | 접두사 해시로 길이의 약수마다 주기를 확인 | TIME_LIMIT |
| P111 | 모든 수의 배수에 1 을 더하는 약수 개수 표 | TIME_LIMIT |
| P111 | 제곱근까지 나누되 같은 수는 한 번만(메모) | TIME_LIMIT - 큰 case 의 수가 10 만 개 모두 다르다 |
| P104 | 껍질 점마다 모든 점을 훑는 선물 포장(대조 풀이) | TIME_LIMIT - 큰 case 의 껍질 점이 3 만 개 |
| P103 · P113 · P114 | 큰 case 없음 | 제약 끝 case |

## 남는 위험

- 정의상 받아들이는 풀이: Z 배열로 접두사 등장을 세는 풀이(P109), 역원을 질의마다 `pow` 로 구하는 풀이(P101) -
  둘 다 같은 Skill 의 다른 구현이다
- 가지치기 · 해시 말고 시도하지 않은 지름길이 남아 있을 수 있다
- 검증 에이전트 검토는 이 ADR 을 쓴 뒤에 받는다. 결과는 PR 에 남긴다
