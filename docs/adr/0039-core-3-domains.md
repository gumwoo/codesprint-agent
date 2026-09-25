# ADR-0039 · CORE-3 - 이분 탐색 · 그리디 · 수학 · 그래프 · DFS · 트리 · DP · 상태 공간

- 상태: 채택
- 날짜: 2026-09-26
- 정본 근거: PRD §20~22 · §26 · §28 · §33~34 · §37(도메인별 Skill 목록), §126(CORE 취업 코테 필수),
  [ADR-0033](0033-an-accepted-answer-does-not-prove-the-skill.md), [ADR-0034](0034-the-road-to-the-full-prd.md),
  [ADR-0036](0036-core-1-domains.md), [ADR-0037](0037-core-2-domains.md)

## 결정

### 1. Skill 열둘 - CORE 가 여기서 닫힌다

| 도메인 | Skill | needs_skill_control |
| --- | --- | --- |
| BINARY_SEARCH | BINARY_SEARCH_BOUNDS · PARAMETRIC_SEARCH | true · true |
| GREEDY | GREEDY_CHOICE | true |
| MATH | MATH_GCD_LCM · NUMBER_BASE_CONVERSION | true · false |
| GRAPH | GRAPH_REPRESENTATION | true |
| DFS | DFS_TRAVERSAL · GRAPH_CYCLE_DETECTION | false · false |
| TREE | TREE_TRAVERSAL | false |
| DP | DP_1D · DP_2D | true · true |
| STATE_SPACE | STATE_SPACE_BFS | false |

이것으로 PRD §126 의 CORE 목록(Programming ~ Basic Tree)이 전부 켜진다. JOB 트랙(일반 취업 코테)의
범위가 여기서 닫힌다 - 이후 웨이브는 TOP_TIER · ALGORITHM 트랙만 넓힌다.

DFS 는 "DFS 여야 나오는 결과"(진입 · 종료 순서, 번호가 작은 이웃부터 들어갔을 때의 첫 경로)로 정의했다.
연결 요소처럼 BFS 로도 같은 답이 나오는 문제로는 DFS 를 잴 수 없다.

### 2. 생성기는 앞서 만든 초안을 다음 초안에게 보여 준다

CORE-1 · CORE-2 에서 채택된 문제 중 여덟이 같은 Skill 앞 초안과 같은 문제라 철회됐다. 같은 프롬프트로
둘을 만들었기 때문이다. 이번 실행부터 생성기는 한 실행에서 앞서 만든 초안을 "이미 있는 문제" 목록에
붙인다(`DraftPrompt.withDrafts`).

```
CORE-1  채택 20 → 의미 중복으로 철회 5
CORE-2  채택 14 → 의미 중복으로 철회 4
CORE-3  채택 21 → 의미 중복으로 철회 0
```

### 3. 진단 테스트는 기대값을 커리큘럼에서 구한다

"첫 질문은 BFS_SHORTEST_PATH" 처럼 이름을 박아 둔 테스트가 도메인이 늘 때마다 깨졌다(이번에는 그 위에
STATE_SPACE_BFS 가 생겼다). 규칙("선수를 가장 많이 거느린 것부터", "틀린 갈래의 선수로 내려간다")을
커리큘럼에서 다시 계산해 기대값으로 쓴다.

그리고 **"틀린 갈래로 내려간다" 를 실제로 가르는 테스트가 없었다.** 가장 큰 갈래의 꼭대기에서 틀리면
규칙이 없어도 가장 많이 밝혀 주는 것이 그 갈래 안에 있어 통과한다 - 규칙을 빼는 대조군이 실제로
통과했다. 작은 갈래(조합 → 완전 탐색)에서 틀리는 테스트를 더했고, 규칙을 빼면 실패한다.

## 결과

`problem-v2` 로 Skill 마다 초안 2 개.

```
초안 24 · 계약 통과 24 · 채택 검사 통과 21 · 스스로 점검 뒤 21
```

| 거절 | 단계 | 사유 |
| --- | --- | --- |
| 2 | Skill 측정 | 대조 풀이가 작은 입력에서 이미 틀림 |
| 1 | 문제 데이터 검사 | 힌트가 정답 코드를 그대로 담음 |

CORE-2 의 교훈(큰 case 에 다양성이 없으면 지름길이 통한다)으로 검증 에이전트에 넘기기 전에 스스로
점검했다.

| 문제 | 약점 | 고친 것 |
| --- | --- | --- |
| P56 | 수열이 등차수열 - 식으로 바로 답 | 무작위 정렬 수열 · 무작위 질의 |
| P62 | 두 수열이 전부 1 - LCS 가 곧 짧은 길이 | 1~5 무작위 수열 |
| P69 | 모든 쌍이 연속한 두 수 - 최대공약수가 늘 1, 차이의 약수만 보면 끝 | 공약수가 여러 크기인 무작위 쌍. 차이의 약수를 보는 풀이도 TIME_LIMIT |
| P55 · P56 · P65 · P66 | 제약 끝에서 정답 출력이 1MB 를 넘음 | Q · N 상한을 줄여 최악 출력을 1MB 아래로 |

## 남는 위험

- **그리디는 채점으로 가르기 어렵다.** 대조 풀이(모든 조합)는 걸리지만, 그리디가 아닌 다항 시간 풀이(예:
  구간 DP)도 통과한다. PRD §21 이 "정답만 맞았다고 Greedy 숙련으로 판정하지 않는다" 고 적은 이유이고,
  확인 질문(Explain Back, §148)이 붙기 전까지 GREEDY_CHOICE 의 Evidence 는 "최적해를 효율적으로 구했다" 까지만
  말한다
- DFS · 사이클 · 트리 · 상태 공간은 대조가 없다. 판단이지 검증이 아니다(ADR-0033)
- 검증 에이전트 검토는 이 ADR 을 쓴 뒤에 받는다. 결과는 PR 에 남긴다
