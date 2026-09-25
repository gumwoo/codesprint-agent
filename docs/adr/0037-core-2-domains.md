# ADR-0037 · CORE-2 - 힙 · 재귀 · 백트래킹 · 투 포인터 · 윈도우 · 누적 합

- 상태: 채택
- 날짜: 2026-09-26
- 정본 근거: PRD §13~19(도메인별 Skill 목록), [ADR-0033](0033-an-accepted-answer-does-not-prove-the-skill.md),
  [ADR-0034](0034-the-road-to-the-full-prd.md), [ADR-0035](0035-a-learning-track-scopes-the-skill-graph.md),
  [ADR-0036](0036-core-1-domains.md)

## 결정

### 1. Skill 아홉 - 여덟이 needs_skill_control

| 도메인 | Skill | needs_skill_control |
| --- | --- | --- |
| HEAP | HEAP_PRIORITY | true |
| RECURSION | RECURSION_BASIC · DIVIDE_AND_CONQUER | false · true |
| BACKTRACKING | BACKTRACKING | true |
| TWO_POINTER | TWO_POINTER | true |
| SLIDING_WINDOW | SLIDING_WINDOW | true |
| PREFIX_SUM | PREFIX_SUM · PREFIX_SUM_2D · DIFFERENCE_ARRAY | true |

이 여섯 도메인은 전부 **"매번 처음부터 다시 하는 풀이도 작은 입력에서는 같은 답"** 인 기법이다.
정답만으로는 그 기법을 썼는지 알 수 없으므로 대조 풀이가 큰 입력에서 걸려야 채택한다(ADR-0033).
재귀 기본만 정답이 곧 그 능력을 보여준다고 봤다 - 재귀 구조를 출력하는 문제는 반복문으로 풀어도
"더 작은 같은 문제로 나누는" 사고가 필요하다.

모두 CORE 이고 JOB 트랙 이상에서 켜진다. 입문(INTRO) 트랙은 PRD §129 대로 이 도메인들을 담지 않는다.

### 2. 진단의 함의는 트랙 밖 Evidence 까지 본다

CORE-2 를 더하자 `TrackTest` 가 깨졌다. JOB 으로 진단을 끝낸 사용자가 INTRO 로 바꾸면 **끝난 진단이
다시 열렸다.** BACKTRACKING 을 통과해 함의된 RECURSION_BASIC · COMBINATORIAL_ENUMERATION 이,
BACKTRACKING 이 INTRO 에 없어서 함의에서 사라진 것이다.

ADR-0035 §3-1 과 같은 원칙이다 - **판단은 전체에서, 묻고 보여 주는 것은 트랙 안에서.** 진단은
물을 범위(트랙)와 함의를 구할 상태(전체)를 따로 받는다. 수정 전에 실패하던 그 테스트가 회귀 테스트다.

## 결과

`problem-v2` 로 Skill 마다 초안 2 개.

```
초안 18 · 계약 통과 18 · 채택 검사 통과 14 · 검증 검토 뒤 11
```

| 거절 | 단계 | 사유 |
| --- | --- | --- |
| 3 | Skill 측정 | 대조 풀이가 작은 입력에서 이미 틀림(둘) · 정답이 큰 입력에서 OUTPUT_LIMIT(하나) |
| 1 | 중복 | 같은 Skill 둘째 초안이 같은 제목 |
| 3 | 검증 검토 | P45 · P48 · P54 가 P44 · P47 · P53 과 같은 문제 |

같은 프롬프트로 둘을 만들면 같은 문제가 나오는 것이 CORE-1 에 이어 또 났다. CORE-3 부터 생성기는
앞서 만든 초안을 다음 초안에게 "이미 있는 문제" 로 보여 준다.

## 남는 위험

- 다섯 Skill(HEAP_PRIORITY · DIVIDE_AND_CONQUER · BACKTRACKING · TWO_POINTER · DIFFERENCE_ARRAY)은
  문제가 하나뿐이다
- 백트래킹의 "가지를 잘랐는가" 는 시간으로만 가른다. 가지치기 없이 빠른 다른 풀이(예: 비트마스크
  DP)가 있으면 그것도 통과한다
