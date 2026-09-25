# ADR-0036 · CORE-1 - 도메인 여섯을 연다

- 상태: 채택
- 날짜: 2026-09-26
- 정본 근거: PRD §7~12 · §15(도메인별 Skill 목록), §57(분기형 진단), Addendum §29(Skill 과
  Technique), [ADR-0018](0018-the-diagnostic-orders-problems-it-does-not-score.md),
  [ADR-0032](0032-the-agent-drafts-the-system-adopts.md),
  [ADR-0033](0033-an-accepted-answer-does-not-prove-the-skill.md),
  [ADR-0034](0034-the-road-to-the-full-prd.md)

## 결정

### 1. PRD 의 목록을 채점으로 가를 수 있는 Skill 열둘로 묶는다

PRD 도메인 절의 소문자 목록(`split`, `join`, `counter`, `defaultdict` …)은 대부분 Technique
이다(Addendum §29). 채점으로 관측할 수 있는 **행동**으로 묶었다.

| 도메인 | Skill | needs_skill_control |
| --- | --- | --- |
| IMPLEMENTATION | SIMULATION_STATE | false |
| ARRAY_MATRIX | MATRIX_TRANSFORM · MATRIX_TRAVERSAL | false |
| STRING | STRING_MANIPULATION · STRING_PARSING | false |
| HASH | HASH_SET_MEMBERSHIP · HASH_MAP_COUNTING | **true** |
| SORTING | SORT_CUSTOM_KEY · **SORT_THEN_SCAN** | false · **true** |
| STACK_QUEUE | STACK_BASIC | false |
| BRUTE_FORCE | BRUTE_FORCE_ENUMERATION · COMBINATORIAL_ENUMERATION | false |

true 인 셋은 "매번 전체를 훑는 풀이도 작은 입력에서는 같은 답을 낸다" 는 공통점이 있다.
정의에 그 풀이가 시간 안에 끝나지 않는다는 것을 적고, 문제는 대조 풀이가 실제로 걸려야
채택된다(ADR-0033).

선수는 전부 문턱 0.65(Addendum §76 기본값)다. 새 값을 만들 근거가 없다.

### 2. 채택 검사는 초안이 **새로 만든** 실패만 거절한다

첫 채택 실행에서 초안 23 개가 **전부** 거절됐다. 사유는 초안이 아니었다 - "아직 문제가 없는
다른 Skill" 같은 문제은행 전체의 실패였다. 새 도메인을 열면 처음엔 문제가 하나도 없으므로,
전체 검사를 통과해야 채택하는 규칙으로는 **어떤 초안도 들어오지 못한다.**

들이기 전과 뒤의 `check_problems` 실패를 비교해 늘어난 것만 사유로 삼는다. 메타테스트가 관계없는
기존 문제를 깨 둔 채 정상 초안이 채택되는지 보고, 대조군(옛 규칙)에서는 실제로 거절되는 것을
확인했다.

### 3. 진단은 실패한 갈래의 선수부터 묻는다

슬라이스 1 은 그래프가 한 갈래라 "가장 많이 밝혀 주는 것" 이 곧 실패한 Skill 의 선수였고,
"실패하면 선수로 내려간다" 는 저절로 됐다. 갈래가 여럿이 되자 BFS 최단거리를 틀린 직후에
문자열 문제로 건너뛰었다. PRD §57("DFS 실패 → DFS 기초 확인")을 규칙으로 적었다 - 물어봤는데
선수를 함의하지 못한 Skill 이 있으면 그 아래의 아직 모르는 선수를 먼저 묻는다. 문턱은 함의와
같은 간선의 값이다.

### 4. 테스트는 진단 횟수를 가정하지 않는다

슬라이스 1 의 테스트 여럿이 "P05 하나를 통과하면 진단이 끝난다" 를 가정했다. 도메인이 늘 때마다
깨지는 가정이라, 진단이 가리키는 문제를 통과시키며 끝까지 따라가는 도우미로 바꿨다.

## 결과

`problem-v2` 로 Skill 마다 초안 2 개, 실제 Claude CLI 로 만들었다.

```
초안 24 · 계약 통과 23 · 채택 검사 통과 20 · 검증 검토 뒤 18
```

| 거절 | 단계 | 사유 |
| --- | --- | --- |
| 1 | 계약 | SIMULATION_STATE 초안 하나가 계약을 어겼다 |
| 3 | 중복 | 같은 Skill 의 두 번째 초안이 같은 code · 제목을 냈다 |
| 2 | **검증 검토** | P30 · P32 가 P29 · P31 과 같은 문제였다 - 글자 유사도로는 걸리지 않았다 |

ADR-0032 가 적은 "의미상 중복을 기계가 잡지 못한다" 가 여기서도 났다. 이번에는 검증 에이전트가
PR 에서 보기 전에 채택 직후 목록을 보고 걸렀다.

## 남는 위험

- CORE-1 의 needs_skill_control 이 false 인 아홉 Skill 은 판단이지 검증이 아니다(ADR-0033)
- 문제는 Skill 마다 하나 또는 둘이다. 한 Skill 에 문제가 하나뿐이면 "다른 문제로 다시" 가 갈 곳이
  없다 - FailingJourneyTest 가 이 경우를 걷는다(ADR-0030)
- 오답 taxonomy 는 슬라이스 1 의 여덟 그대로다. 새 도메인의 실수는 대부분 `IMPLEMENTATION_MISC`
  로 적힌다
