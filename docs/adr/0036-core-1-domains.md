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
초안 24 · 계약 통과 23 · 채택 검사 통과 20 · 검증 검토 뒤 15
```

| 거절 | 단계 | 사유 |
| --- | --- | --- |
| 1 | 계약 | SIMULATION_STATE 초안 하나가 계약을 어겼다 |
| 3 | 중복 | 같은 Skill 의 두 번째 초안이 같은 code · 제목을 냈다 |
| 5 | **검증 검토** | P30 · P32 · P35 · P28 · P26 이 각각 P29 · P31 · P34 · P27 · P25 와 같은 문제였다 - 글자 유사도로는 걸리지 않았다 |

ADR-0032 가 적은 "의미상 중복을 기계가 잡지 못한다" 가 여기서도 났다. 둘은 채택 직후 목록을 보고
걸렀고, 셋은 검증 에이전트가 찾았다. **Skill 마다 초안 둘을 같은 프롬프트로 만들면 비슷한 문제가
나온다** - 다음 웨이브부터는 둘째 초안에 첫째를 "이미 있는 문제" 로 보여 주는 편이 낫다.

검증 에이전트가 더 찾은 것:

| 문제 | 찾은 것 | 고친 것 |
| --- | --- | --- |
| P36 (SORT_THEN_SCAN) | 큰 case 에 중복 값이 있어 D=0 - `Counter` 로 정렬 없이 AC | 값이 전부 다르고 간격이 큰 큰 case. `Counter` · "d 를 1 부터 올리는 set" 풀이 모두 TIME_LIMIT |
| P29 (MATRIX_TRANSFORM) | 본문이 명령마다 인덱스 식을 줬다(P17 과 같은 모양) | 말과 예시로만 정의. 힌트도 군 합성 요령 대신 칸의 새 위치를 계산하는 사다리로 |
| 진단 계약 테스트 | 갈래가 여럿이 되자 "끝난 응답" 을 검사하는 시점에 진단이 안 끝나 있었다 | 진단을 끝까지 따라간 뒤 검사한다 |
| 채택 검사 | `check_problems` 가 죽으면 실패 줄이 없어 "새 실패 없음" 으로 통과 | 실패 줄 없는 실패는 crash 로 센다 · 기존 실패 위에서도 새 실패가 막히는지 메타 케이스 |

**정의상 받아들이는 풀이**(ADR-0033 이 deque 에서 한 것과 같다): 정렬 뒤 이분 탐색(`bisect`)은
P27 에서, (키, 위치) 로 정렬해 묶는 풀이는 P25 에서 ACCEPTED 다. 둘 다 "매번 전체를 훑지
않는다" 를 만족한다 - 정렬을 아는 것이 HASH Evidence 로 쌓이는 것은 받아들인다.

## 남는 위험

- CORE-1 의 needs_skill_control 이 false 인 아홉 Skill 은 판단이지 검증이 아니다(ADR-0033)
- 문제는 Skill 마다 하나 또는 둘이다. 한 Skill 에 문제가 하나뿐이면 "다른 문제로 다시" 가 갈 곳이
  없다 - FailingJourneyTest 가 이 경우를 걷는다(ADR-0030)
- 오답 taxonomy 는 슬라이스 1 의 여덟 그대로다. 새 도메인의 실수는 대부분 `IMPLEMENTATION_MISC`
  로 적힌다
