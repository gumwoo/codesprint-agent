# CodeSprint Agent — Implementation Specification Addendum
## Mastery / Skill Catalog / First Vertical Slice / Sandbox

---

# 0. 문서 목적

이 문서는 `CodeSprint Agent`의 전체 PRD나 전체 코딩테스트 커리큘럼을 다시 설명하지 않는다.

본 문서의 목적은 실제 개발 착수 전에 반드시 확정해야 하는 다음 네 가지 항목을 구현 가능한 수준으로 구체화하는 것이다.

1. `mastery_score` 갱신 규칙
2. Skill ID 표준화 및 Canonical Skill Catalog
3. 첫 Vertical Slice의 정확한 범위
4. Sandbox 실행 환경 및 격리 방식

이 네 항목은 전체 서비스의 학습 루프가 실제 코드로 동작하기 위한 기반 계약(contract)이다.

---

# 1. 핵심 설계 원칙

CodeSprint Agent의 최종 목표는 다음과 같다.

> 많이 풀게 하는 것이 아니라, 사용자가 모르는 Skill을 찾아 독립 풀이 가능한 상태까지 가장 짧은 경로로 만든다.

따라서 구현에서도 다음 원칙을 지킨다.

```text
문제 수
!=
실력

AC 횟수
!=
Mastery

LLM 평가
!=
정답 판정

하나의 고득점
!=
신뢰 가능한 숙련도
```

서비스에서 숙련도는 반드시 **관측된 학습 Evidence의 누적 결과**로 계산한다.

---

# PART I. Mastery Score Specification

---

# 2. Mastery 모델 개요

사용자의 Skill 상태를 단일 `mastery_score` 하나로만 저장하지 않는다.

최소 다음 값을 함께 관리한다.

```text
concept_score
recognition_score
implementation_score
independent_score
retention_score
speed_score

mastery_score
confidence_score
evidence_count
```

---

# 3. 점수 범위

모든 score는 다음 범위를 사용한다.

```text
0.0 ~ 1.0
```

예:

```text
0.00 = 전혀 검증되지 않음
0.25 = 매우 약함
0.50 = 부분 이해
0.70 = 실전 연습 필요
0.80 = 안정적
0.90 = 높은 숙련
1.00 = 매우 강한 근거
```

단, `1.0`을 "완벽"이라는 의미로 사용하지 않는다.

---

# 4. 신규 Skill 초기값

신규 사용자의 Skill은 **0점으로 시작하지 않는다.**

0점은 "전혀 못함"으로 해석될 수 있기 때문이다.

따라서 신규 Skill은 다음과 같이 표현한다.

```text
status = UNASSESSED

concept_score = null
recognition_score = null
implementation_score = null
independent_score = null
retention_score = null
speed_score = null

mastery_score = null
confidence_score = 0.0
evidence_count = 0
```

즉:

```text
null = 모름
0.0 = 평가했으며 거의 수행하지 못함
```

을 구분한다.

---

# 5. User Skill 상태

권장 상태:

```text
UNASSESSED
READY
LEARNING
PRACTICING
MASTERED
REVIEW_DUE
WEAKENED
LOCKED
```

---

# 6. Mastery 계산식

초기 버전에서 다음 가중치를 사용한다.

```text
concept        0.15
recognition    0.20
implementation 0.25
independent    0.25
retention      0.10
speed          0.05
```

즉:

```text
mastery_score =
concept_score        * 0.15
+ recognition_score  * 0.20
+ implementation_score * 0.25
+ independent_score  * 0.25
+ retention_score    * 0.10
+ speed_score        * 0.05
```

단, 아직 평가되지 않은 차원은 계산에서 제외하고,
평가된 차원의 가중치를 재정규화한다.

예:

```text
implementation = 0.8
independent = 0.6

두 차원만 평가됨
```

이면 0으로 간주하지 않는다.

---

# 7. Evidence 기반 업데이트

각 제출이나 학습 행동을 `SkillEvidence`로 저장한다.

예:

```json
{
  "userId": 10,
  "skillId": "BFS_GRID_TRAVERSAL",
  "evidenceType": "PROBLEM_SUBMISSION",
  "result": "ACCEPTED",
  "independent": true,
  "hintLevel": 0,
  "solveTimeRatio": 0.82,
  "reviewerConfidence": 0.94,
  "createdAt": "2026-09-05T10:00:00"
}
```

---

# 8. Evidence 유형

```text
DIAGNOSTIC_RESULT
PROBLEM_SUBMISSION
MICRO_DRILL_RESULT
REVIEW_RESULT
CONCEPT_CHECK
EXPLAIN_BACK
MOCK_TEST_RESULT
```

---

# 9. 기본 업데이트 방식

MVP에서는 복잡한 Knowledge Tracing 모델을 바로 도입하지 않는다.

초기에는 다음 형태의 EMA(Exponential Moving Average)를 사용한다.

```text
new_score
=
old_score * (1 - alpha)
+
observed_value * alpha
```

기본 `alpha`:

```text
NORMAL_PROBLEM     0.18
DIAGNOSTIC         0.25
MICRO_DRILL        0.12
REVIEW             0.22
MOCK_TEST          0.28
```

---

# 10. 첫 Evidence 처리

기존 score가 null이면:

```text
new_score = observed_value
```

으로 시작한다.

단, confidence는 낮게 시작한다.

---

# 11. 정답 결과별 observed value

## 11.1 힌트 없는 AC

```text
implementation = 0.90
independent = 0.95
```

Recognition을 실제 문제 선택 과정에서 측정할 수 있다면:

```text
recognition = 0.90
```

---

## 11.2 Hint 1 사용 후 AC

```text
implementation = 0.88
independent = 0.78
```

---

## 11.3 Hint 2 사용 후 AC

```text
implementation = 0.84
independent = 0.68
```

---

## 11.4 Hint 3 사용 후 AC

```text
implementation = 0.80
independent = 0.55
```

---

## 11.5 Hint 4 사용 후 AC

```text
implementation = 0.74
independent = 0.42
```

---

## 11.6 Hint 5 사용 후 AC

```text
implementation = 0.65
independent = 0.25
```

---

## 11.7 전체 풀이 확인 후 AC

```text
implementation = 0.55
independent = 0.10
```

이 경우 Mastered 판정 근거로 사용할 수 없다.

---

# 12. 실패 결과별 observed value

## Wrong Answer

기본:

```text
implementation = 0.30
independent = 0.25
```

단, 알고리즘 선택이 맞고 구현 오류만 있을 경우:

```text
recognition에는 penalty 없음
```

---

## Time Limit Exceeded

알고리즘 복잡도 자체가 잘못된 경우:

```text
recognition = 0.35
implementation = 0.45
```

최적화 구현 문제라면:

```text
recognition = 유지
implementation = 0.45
```

---

## Runtime Error

개념 이해도를 바로 낮추지 않는다.

예:

```text
IndexError
RecursionError
TypeError
```

은 관련 implementation sub-skill에만 반영한다.

---

## Compile / Syntax Error

알고리즘 Skill의 mastery에 직접 penalty를 주지 않는다.

별도 Language Skill에 Evidence를 기록한다.

예:

```text
PYTHON_SYNTAX
PYTHON_API_USAGE
```

---

# 13. 시간 점수

문제에는 다음 값이 있다.

```text
expected_solve_seconds
```

계산:

```text
ratio =
actual_solve_seconds
/
expected_solve_seconds
```

Speed observed value:

```text
ratio <= 0.70 → 1.00
ratio <= 1.00 → 0.90
ratio <= 1.25 → 0.75
ratio <= 1.50 → 0.60
ratio <= 2.00 → 0.40
ratio >  2.00 → 0.20
```

단:

```text
정답 실패 시 speed_score 갱신 안 함
```

---

# 14. Concept Score

Concept는 문제 제출만으로 강하게 갱신하지 않는다.

다음 Evidence를 사용한다.

```text
Concept Quiz
Explain Back
Algorithm Choice Reason
```

예:

```text
"왜 BFS를 사용했나요?"
```

평가:

```text
CORRECT       0.90
PARTIAL       0.60
INCORRECT     0.20
```

---

# 15. Recognition Score

사용자가 문제의 유형을 사전에 안내받지 않은 상태에서
알고리즘을 스스로 선택했을 때 가장 강한 Evidence로 본다.

예:

```text
EXAM / MIXED / DIAGNOSTIC
```

에서:

```text
Correct Algorithm Choice → 0.90
Partially Correct        → 0.55
Wrong Choice             → 0.20
```

Guided Mode에서 유형을 미리 알려준 문제는
Recognition Evidence로 사용하지 않는다.

---

# 16. Retention Score

복습 성공 여부를 사용한다.

```text
1일 후 독립 AC  → 0.75
3일 후 독립 AC  → 0.82
7일 후 독립 AC  → 0.90
14일 후 독립 AC → 0.95
30일 후 독립 AC → 1.00
```

복습 실패:

```text
0.25 ~ 0.45
```

로 갱신한다.

---

# 17. Confidence Score

`mastery_score`와 별도다.

예:

```text
mastery = 0.85
confidence = 0.20
```

이면:

> 잘할 가능성은 높아 보이지만 증거가 적다.

반대로:

```text
mastery = 0.72
confidence = 0.91
```

이면:

> 현재 0.72 수준이라는 판단의 신뢰도는 높다.

---

# 18. Confidence 계산 초기안

Evidence weight:

```text
DIAGNOSTIC      1.0
NORMAL_PROBLEM  1.0
MICRO_DRILL     0.5
REVIEW           1.3
MOCK_TEST        1.5
```

누적 evidence weight를 `W`라 하면:

```text
confidence = min(1.0, 1 - exp(-W / 5))
```

구현이 복잡하면 MVP에서는 아래 단순식도 허용한다.

```text
confidence = min(1.0, total_evidence_weight / 8.0)
```

MVP 권장은 단순식이다.

---

# 19. AI Reviewer Confidence 반영 규칙

AI 분석은 정답 판정을 대체하지 않는다.

Reviewer는 다음 confidence를 반환한다.

```text
0.0 ~ 1.0
```

처리 기준:

```text
confidence < 0.60
→ Mistake 확정 금지
→ 참고 로그만 저장
→ mastery 직접 반영 금지

0.60 <= confidence < 0.80
→ POSSIBLE Mistake로 저장
→ micro drill 자동 실행 금지
→ 동일 패턴 재발 시 확정 가능

0.80 <= confidence < 0.90
→ PROBABLE Mistake
→ sub-skill에 약한 penalty 가능

confidence >= 0.90
→ CONFIRMED candidate
→ Judge/test evidence와 일치하면 확정
```

---

# 20. Judge Evidence 우선 원칙

우선순위:

```text
1. 실제 실행 결과
2. 테스트케이스 차이
3. 정적 Rule
4. LLM Reviewer
```

LLM이:

```text
"Boundary Check 문제"
```

라고 했더라도 실제 실패 테스트가
Boundary Case와 무관하면 자동 확정하지 않는다.

---

# 21. Mistake 확정 조건

다음 중 하나:

```text
A.
Reviewer confidence >= 0.90
AND
실패 Test Case 특성이 해당 Mistake와 일치

B.
Reviewer confidence >= 0.80
AND
동일 Mistake가 최근 3문제에서 2회 이상 탐지

C.
Deterministic Rule로 직접 검출
```

---

# 22. Mastered 판정

다음 조건을 모두 만족해야 한다.

```text
mastery_score >= 0.80
confidence_score >= 0.60

최근 독립 풀이 3개 중 2개 이상 AC

AND

Hint 4 이상 사용한 문제를
최근 독립 성공으로 계산하지 않음

AND

최소 1회 Review 성공
```

---

# 23. Mastery 해제

다음 중 하나면:

```text
review 실패
최근 3개 독립 문제 중 2개 실패
동일 핵심 Mistake 2회 반복
```

상태:

```text
MASTERED
→ WEAKENED
```

---

# 24. 점수 업데이트 pseudocode

```text
function updateSkill(skill, evidence):

    observed = mapEvidenceToScores(evidence)

    for dimension in observed:
        alpha = getAlpha(evidence.type)

        if skill.dimension is null:
            skill.dimension = observed[dimension]
        else:
            skill.dimension =
                skill.dimension * (1 - alpha)
                + observed[dimension] * alpha

    skill.evidenceCount += 1
    skill.confidence = calculateConfidence(skill)

    skill.mastery = calculateMastery(skill)

    skill.status = determineStatus(skill)

    save(skill)
```

---

# PART II. Canonical Skill Catalog

---

# 25. Skill ID 설계 원칙

Skill ID는 서비스 전 영역에서 동일하게 사용한다.

사용 위치:

```text
DB
API
Event
Prompt
Problem Metadata
Reviewer Output
Evaluator
Analytics
```

---

# 26. Skill ID 형식

권장 형식:

```text
{DOMAIN}_{CONCEPT}
```

대문자 `UPPER_SNAKE_CASE`.

예:

```text
DS_QUEUE_BASIC
BFS_BASIC
BFS_GRID_TRAVERSAL
GRID_BOUNDARY_CHECK
BFS_SHORTEST_PATH
```

---

# 27. DB ID와 Skill Code 분리

DB 내부 PK:

```text
BIGINT id
```

외부 Canonical Code:

```text
VARCHAR code UNIQUE
```

예:

```text
id = 104
code = BFS_GRID_TRAVERSAL
```

API, Prompt, Event에서는 `code`를 사용한다.

---

# 28. 금지 규칙

다음처럼 유사 Skill을 중복 생성하지 않는다.

금지:

```text
queue
queue_basic
basic_queue
bfs_queue
```

하나로 통일:

```text
DS_QUEUE_BASIC
```

---

# 29. Skill과 Technique 구분

예:

```text
Queue 자체 사용법
→ DS_QUEUE_BASIC

BFS에서 Queue 활용
→ BFS_BASIC
```

`bfs_queue`라는 Skill은 만들지 않는다.

BFS는 prerequisite로:

```text
DS_QUEUE_BASIC
```

을 요구한다.

---

# 30. Skill과 Mistake 구분

다음은 Skill이 아니다.

```text
BOUNDARY_CHECK_ERROR
OFF_BY_ONE_ERROR
VISITED_TIMING_ERROR
```

Skill:

```text
GRID_BOUNDARY_CHECK
BFS_VISITED_MANAGEMENT
```

Mistake:

```text
BOUNDARY_CHECK
OFF_BY_ONE
VISITED_TIMING
```

---

# 31. 첫 Vertical Slice용 Canonical Skill Catalog

첫 구현에서 사용하는 Skill은 아래로 고정한다.

```text
PYTHON_LIST_BASIC
PYTHON_DEQUE_BASIC

GRID_COORDINATE
GRID_BOUNDARY_CHECK

BFS_BASIC
BFS_VISITED_MANAGEMENT
BFS_GRID_TRAVERSAL
BFS_SHORTEST_PATH
```

---

# 32. 각 Skill 정의

## PYTHON_LIST_BASIC

```text
Domain: LANGUAGE
Tier: FOUNDATION

Description:
Python list와 2차원 list를 생성하고 인덱스로 접근한다.
```

---

## PYTHON_DEQUE_BASIC

```text
Domain: LANGUAGE / DATA_STRUCTURE
Tier: FOUNDATION

Description:
collections.deque의 append / popleft를 사용할 수 있다.
```

---

## GRID_COORDINATE

```text
Domain: IMPLEMENTATION
Tier: CORE

Description:
2차원 격자의 row/column과 좌표 이동을 올바르게 처리한다.
```

---

## GRID_BOUNDARY_CHECK

```text
Domain: IMPLEMENTATION
Tier: CORE

Description:
이동 후 좌표가 격자 범위 내부인지 올바르게 판정한다.
```

---

## BFS_BASIC

```text
Domain: BFS
Tier: CORE

Description:
Queue 기반 너비 우선 탐색의 기본 흐름을 이해하고 구현한다.
```

---

## BFS_VISITED_MANAGEMENT

```text
Domain: BFS
Tier: CORE

Description:
BFS에서 방문 상태를 기록하고 중복 탐색을 방지한다.
```

---

## BFS_GRID_TRAVERSAL

```text
Domain: BFS
Tier: CORE

Description:
2차원 격자를 BFS로 탐색한다.
```

---

## BFS_SHORTEST_PATH

```text
Domain: BFS
Tier: CORE

Description:
가중치가 동일한 그래프/격자에서 BFS로 최단거리를 계산한다.
```

---

# 33. 첫 Slice 선수 관계

```text
PYTHON_LIST_BASIC
       ↓
GRID_COORDINATE
       ↓
GRID_BOUNDARY_CHECK
       ↓
BFS_GRID_TRAVERSAL
```

그리고:

```text
PYTHON_DEQUE_BASIC
       ↓
BFS_BASIC
       ↓
BFS_VISITED_MANAGEMENT
       ↓
BFS_GRID_TRAVERSAL
       ↓
BFS_SHORTEST_PATH
```

---

# 34. YAML 예시

```yaml
skills:
  - code: PYTHON_DEQUE_BASIC
    domain: LANGUAGE
    tier: FOUNDATION
    prerequisites: []

  - code: BFS_BASIC
    domain: BFS
    tier: CORE
    prerequisites:
      - PYTHON_DEQUE_BASIC

  - code: BFS_VISITED_MANAGEMENT
    domain: BFS
    tier: CORE
    prerequisites:
      - BFS_BASIC

  - code: GRID_BOUNDARY_CHECK
    domain: IMPLEMENTATION
    tier: CORE
    prerequisites:
      - GRID_COORDINATE

  - code: BFS_GRID_TRAVERSAL
    domain: BFS
    tier: CORE
    prerequisites:
      - BFS_BASIC
      - BFS_VISITED_MANAGEMENT
      - GRID_BOUNDARY_CHECK
```

---

# 35. Skill Catalog 변경 정책

Skill code는 한번 Production 데이터에 사용되면 임의 변경 금지.

필요하면:

```text
deprecated = true
replacement_skill_code = ...
```

를 사용한다.

---

# PART III. First Vertical Slice

---

# 36. Vertical Slice 목표

첫 번째 Slice의 목표는:

> 전체 제품의 핵심 Adaptive Learning Loop가 실제로 끝까지 동작하는지 검증한다.

전체 유형을 구현하는 것이 목표가 아니다.

---

# 37. 첫 Slice Scope

## Language

```text
Python 3.12
```

## Curriculum

```text
BFS Basic
Grid BFS
Boundary Check
Visited Management
Shortest Path Basic
```

## Mistake Types

처음에는 딱 두 개만 자동 액션까지 연결한다.

```text
BOUNDARY_CHECK
VISITED_TIMING
```

Reviewer는 다른 오류를 발견할 수 있지만:

```text
IMPLEMENTATION_MISC
```

로 저장한다.

---

# 38. 필수 사용자 흐름

```text
Onboarding
↓
BFS Diagnostic
↓
Skill State 생성
↓
문제 제공
↓
Python 코드 작성
↓
Run
↓
Submit
↓
Judge
↓
Reviewer
↓
Evaluator
↓
Decision Engine
↓
Micro Drill 또는 Next Problem
```

---

# 39. 첫 Slice에서 구현하지 않는 것

```text
전체 코테 Curriculum UI
Java / C++
기업별 모드
Mock Test
고급 Spaced Repetition
LLM 문제 자동 생성
Knowledge Tracing ML
Segment Tree
DP
Dijkstra
전체 Dashboard
```

단:

> DB와 Skill Catalog 구조는 이후 전체 커리큘럼을 추가할 수 있게 설계한다.

---

# 40. 문제 세트

첫 Slice에서는 생성형 문제보다
**검증된 고정 문제 8~12개**를 사용한다.

권장:

```text
P01 Queue Basic
P02 Grid Traversal
P03 Connected Component
P04 Area Size
P05 Shortest Path
P06 Boundary Drill A
P07 Boundary Drill B
P08 Visited Timing Drill
P09 BFS Variant A
P10 BFS Review
```

---

# 41. Problem Skill Mapping 예

P03:

```text
Primary:
BFS_GRID_TRAVERSAL 0.50

Secondary:
BFS_VISITED_MANAGEMENT 0.25
GRID_BOUNDARY_CHECK 0.25
```

P05:

```text
Primary:
BFS_SHORTEST_PATH 0.55

Secondary:
BFS_GRID_TRAVERSAL 0.25
GRID_BOUNDARY_CHECK 0.10
BFS_VISITED_MANAGEMENT 0.10
```

---

# 42. 첫 Slice Mistake Taxonomy

```text
BOUNDARY_CHECK
VISITED_TIMING
NO_VISITED
INDEX_ERROR
INPUT_PARSE
OUTPUT_FORMAT
SYNTAX_ERROR
IMPLEMENTATION_MISC
```

자동 Micro Drill 연결:

```text
BOUNDARY_CHECK
VISITED_TIMING
```

만 우선 지원한다.

---

# 43. Decision Engine 규칙

MVP Rule:

```text
if submission == ACCEPTED:
    if mastery >= 0.80 and confidence >= 0.60:
        if review_completed:
            UNLOCK_NEXT
        else:
            SCHEDULE_REVIEW
    else:
        CONTINUE

else:
    if confirmedMistake == BOUNDARY_CHECK:
        MICRO_DRILL(GRID_BOUNDARY_CHECK)

    elif confirmedMistake == VISITED_TIMING:
        MICRO_DRILL(BFS_VISITED_MANAGEMENT)

    elif sameProblemAttempts >= 3:
        REVIEW_CONCEPT

    else:
        RETRY_VARIANT
```

---

# 44. 첫 Slice 핵심 데모 시나리오

사용자 코드:

```python
for dx, dy in directions:
    nx = x + dx
    ny = y + dy

    if graph[nx][ny] == 1:
        ...
```

문제:

```text
nx / ny 범위 검사 없음
```

실행:

```text
Runtime Error 또는 WA
```

Reviewer:

```json
{
  "mistakeType": "BOUNDARY_CHECK",
  "confidence": 0.96
}
```

Evaluator:

```text
GRID_BOUNDARY_CHECK
implementation score 하락
```

Decision:

```text
MICRO_DRILL
```

다음 문제:

```text
3~5분 Boundary Check 전용 문제
```

성공:

```text
GRID_BOUNDARY_CHECK Evidence 추가
```

이후 원래 BFS 변형으로 복귀.

---

# 45. 첫 Slice Acceptance Criteria

다음이 모두 가능해야 완료다.

## Judge

- [ ] Python 코드 실행
- [ ] Hidden Test 실행
- [ ] AC/WA/RE/TLE 구분
- [ ] 실행 시간 기록

## Reviewer

- [ ] 코드 + 실패 Test 정보를 입력으로 받음
- [ ] Structured JSON 반환
- [ ] confidence 포함
- [ ] BOUNDARY_CHECK 판별
- [ ] VISITED_TIMING 판별

## Evaluator

- [ ] Evidence 저장
- [ ] user_skill score 갱신
- [ ] confidence_score 갱신
- [ ] mastery_score 계산

## Decision

- [ ] BOUNDARY_CHECK → Micro Drill
- [ ] VISITED_TIMING → Micro Drill
- [ ] AC → 다음 문제/복습 결정

## End-to-End

- [ ] 문제 시작부터 다음 문제 추천까지 한 트랜잭션 흐름으로 확인 가능

---

# 46. Slice 성공 지표

개발 완료 판정은 UI가 예쁜지가 아니다.

아래 시나리오가 자동으로 이어져야 한다.

```text
WA
→ 원인 분석
→ Skill State 변경
→ Micro Drill
→ AC
→ Skill Recovery
→ Next Problem
```

이것이 첫 번째 Product Proof다.

---

# PART IV. Sandbox Implementation Decision

---

# 47. Sandbox 목표

사용자가 제출한 코드는 신뢰할 수 없는 코드다.

따라서 API 서버 프로세스 안에서 직접 실행하면 안 된다.

금지:

```text
ProcessBuilder("python", ...)
```

를 Web API 서버에서 직접 수행.

---

# 48. MVP 기술 결정

첫 구현은 다음으로 고정한다.

```text
Docker Container 기반 Judge Worker
```

구조:

```text
Spring Boot API
      ↓
Submission Queue
      ↓
Judge Worker
      ↓
Ephemeral Docker Container
      ↓
Python Runtime
```

---

# 49. 왜 Docker부터 시작하는가

장점:

```text
구현 난이도 낮음
로컬 개발 쉬움
자원 제한 가능
네트워크 차단 가능
파일시스템 제한 가능
향후 gVisor로 교체 가능
```

단점:

```text
Docker 자체가 완전한 보안 Sandbox는 아님
Host Kernel 공유
운영 공개 서비스에서는 추가 격리 필요
```

따라서:

```text
MVP / 내부 검증
→ Docker

외부 사용자 공개
→ gVisor / Firecracker 등 강화
```

---

# 50. Container Lifecycle

매 Submission마다:

```text
Create
→ Mount code/input
→ Run
→ Collect result
→ Destroy
```

Container 재사용은 초기 구현에서는 하지 않는다.

---

# 51. Docker 실행 제한

권장 옵션:

```text
--network none
--memory 256m
--memory-swap 256m
--cpus 0.5
--pids-limit 64
--read-only
--cap-drop ALL
--security-opt no-new-privileges
```

추가:

```text
tmpfs /tmp
```

---

# 52. 파일시스템

Container 내부:

```text
/app
  solution.py
  input.txt
```

읽기 전용 Root FS.

필요한 임시 공간만:

```text
/tmp
```

tmpfs로 제공.

---

# 53. 네트워크

반드시:

```text
--network none
```

외부 API 호출 차단.

사용자 코드에서:

```python
requests.get(...)
socket...
```

등이 성공하면 안 된다.

---

# 54. CPU 제한

MVP:

```text
0.5 CPU
```

Judge 시간 제한은 Container CPU 제한과 별도로 애플리케이션에서 관리한다.

예:

```text
problem time limit = 2 seconds
hard kill = 3 seconds
```

---

# 55. Memory 제한

기본:

```text
256 MB
```

문제별 metadata로 변경 가능.

```text
memory_limit_mb
```

---

# 56. Process 제한

Fork Bomb 방지:

```text
--pids-limit 64
```

향후 더 낮출 수 있다.

---

# 57. Capability 제한

```text
--cap-drop ALL
```

추가 권한을 부여하지 않는다.

---

# 58. Privilege Escalation 차단

```text
--security-opt no-new-privileges
```

---

# 59. Python Runtime Image

직접 minimal image를 만든다.

예:

```dockerfile
FROM python:3.12-slim

RUN useradd -m runner

USER runner

WORKDIR /app

ENTRYPOINT ["python3"]
```

Container 내부 root 실행 금지.

---

# 60. 제출 코드 전달

초기에는 다음 방식 권장:

```text
Host temp directory
→ solution.py 생성
→ read-only bind mount
```

예:

```text
-v /judge/submission/123:/app:ro
```

---

# 61. Test Case 실행 방식

한 Container에서 모든 Test를 실행하는 방식과
Test마다 Container를 새로 만드는 방식이 있다.

MVP 권장:

```text
Submission당 Container 1개
```

Worker 내부 harness가 여러 Test Case를 순서대로 실행한다.

---

# 62. Runner Harness

Runner는 다음 정보를 반환한다.

```json
{
  "status": "WRONG_ANSWER",
  "executionMs": 182,
  "memoryKb": 14200,
  "passed": 4,
  "total": 8,
  "failedCaseId": 5,
  "stderr": ""
}
```

---

# 63. stderr 처리

사용자에게 전체 Host 정보가 노출되지 않도록 sanitize한다.

예:

```text
/app/solution.py
```

정도만 보여준다.

Host path는 제거한다.

---

# 64. Output 제한

사용자가 무한 출력하는 것을 막아야 한다.

예:

```text
stdout max = 1 MB
stderr max = 256 KB
```

초과 시:

```text
OUTPUT_LIMIT
```

---

# 65. Timeout

각 Test Case:

```text
soft timeout = problem.limit
```

Submission 전체:

```text
hard timeout
```

예:

```text
2초 문제

single case hard limit = 2.5s
submission hard limit = 10s
```

---

# 66. Judge Worker Isolation

Web API와 Judge는 별도 process/service로 분리한다.

```text
API Server
!=
Judge Worker
```

이유:

```text
악성 코드 영향 격리
CPU spike 격리
Scale 독립
Timeout 격리
```

---

# 67. Queue

MVP:

```text
Redis List / Stream
```

또는 단순 DB polling도 가능하다.

권장:

```text
Redis Stream
```

Message:

```json
{
  "submissionId": 1001
}
```

---

# 68. Submission 상태

```text
QUEUED
RUNNING
ACCEPTED
WRONG_ANSWER
TIME_LIMIT
MEMORY_LIMIT
RUNTIME_ERROR
COMPILE_ERROR
SYSTEM_ERROR
```

---

# 69. Worker Failure

Worker가 죽더라도:

```text
RUNNING 상태 영구 고정
```

되지 않게 lease/timeout 복구가 필요하다.

MVP:

```text
started_at
heartbeat_at
```

기준으로 stale submission 복구.

---

# 70. Docker Socket 주의

Judge Worker가 Host Docker Socket에 직접 접근하면
Worker compromise가 Host compromise로 이어질 수 있다.

따라서 공개 서비스 고도화 시:

```text
별도 Judge Host
또는
Kubernetes isolated node
또는
gVisor
```

로 분리한다.

---

# 71. Production 강화 단계

## Stage 1

```text
Docker
Network None
Resource Limit
Non-root
Read-only
```

## Stage 2

```text
Docker + gVisor(runsc)
```

## Stage 3

```text
Kubernetes Job
+
gVisor RuntimeClass
+
Dedicated Judge Node Pool
```

## Stage 4 필요 시

```text
Firecracker MicroVM
```

---

# 72. MVP Sandbox 결론

첫 Vertical Slice는:

```text
Docker 기반
Python 3.12
non-root
network none
read-only
CPU 0.5
memory 256MB
pids 64
capabilities none
```

으로 구현한다.

외부 공개 전:

```text
gVisor
```

도입 여부를 Security Gate로 둔다.

---

# PART V. Concrete Data Model Additions

---

# 73. user_skills

```sql
CREATE TABLE user_skills (
    user_id BIGINT NOT NULL,
    skill_id BIGINT NOT NULL,

    concept_score NUMERIC(5,4),
    recognition_score NUMERIC(5,4),
    implementation_score NUMERIC(5,4),
    independent_score NUMERIC(5,4),
    retention_score NUMERIC(5,4),
    speed_score NUMERIC(5,4),

    mastery_score NUMERIC(5,4),
    confidence_score NUMERIC(5,4) NOT NULL DEFAULT 0,
    evidence_count INTEGER NOT NULL DEFAULT 0,

    status VARCHAR(30) NOT NULL DEFAULT 'UNASSESSED',

    last_studied_at TIMESTAMP,
    next_review_at TIMESTAMP,

    PRIMARY KEY (user_id, skill_id)
);
```

---

# 74. skill_evidence

```sql
CREATE TABLE skill_evidence (
    id BIGSERIAL PRIMARY KEY,

    user_id BIGINT NOT NULL,
    skill_id BIGINT NOT NULL,
    submission_id BIGINT,

    evidence_type VARCHAR(40) NOT NULL,
    result VARCHAR(40),

    observed_concept NUMERIC(5,4),
    observed_recognition NUMERIC(5,4),
    observed_implementation NUMERIC(5,4),
    observed_independent NUMERIC(5,4),
    observed_retention NUMERIC(5,4),
    observed_speed NUMERIC(5,4),

    weight NUMERIC(5,4) NOT NULL,
    source_confidence NUMERIC(5,4),

    metadata JSONB,

    created_at TIMESTAMP NOT NULL
);
```

---

# 75. skills

```sql
CREATE TABLE skills (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(100) NOT NULL UNIQUE,
    name VARCHAR(150) NOT NULL,
    domain VARCHAR(80) NOT NULL,
    tier VARCHAR(30) NOT NULL,
    description TEXT,
    deprecated BOOLEAN NOT NULL DEFAULT FALSE,
    replacement_skill_id BIGINT
);
```

---

# 76. skill_prerequisites

```sql
CREATE TABLE skill_prerequisites (
    skill_id BIGINT NOT NULL,
    prerequisite_skill_id BIGINT NOT NULL,
    minimum_mastery NUMERIC(5,4) NOT NULL DEFAULT 0.65,

    PRIMARY KEY (
        skill_id,
        prerequisite_skill_id
    )
);
```

---

# PART VI. Service Boundary

---

# 77. Backend 서비스

```text
SkillCatalogService
MasteryService
EvidenceService
ReviewerService
DecisionService
SubmissionService
JudgeClient
LearningSessionService
```

---

# 78. MasteryService 책임

```text
Evidence → Score Update
Mastery 계산
Confidence 계산
Status 전환
```

---

# 79. ReviewerService 책임

```text
Judge Result
+
Problem
+
User Code
+
Failed Case
↓
Mistake Analysis
```

Score를 직접 수정하지 않는다.

---

# 80. DecisionService 책임

```text
UserSkill
Mistakes
Current Problem
Session Context
↓
NextAction
```

---

# 81. Judge 책임

```text
코드 실행
Test Case 판정
실행 시간
메모리
stderr
```

AI 판단을 하지 않는다.

---

# PART VII. API Contracts

---

# 82. Submit Response

```json
{
  "submissionId": 1001,
  "judge": {
    "status": "WRONG_ANSWER",
    "passed": 5,
    "total": 8,
    "executionMs": 141
  },
  "review": {
    "primaryMistake": "BOUNDARY_CHECK",
    "confidence": 0.94
  },
  "skillUpdates": [
    {
      "skillCode": "GRID_BOUNDARY_CHECK",
      "before": 0.68,
      "after": 0.59
    }
  ],
  "nextAction": {
    "type": "MICRO_DRILL",
    "targetSkill": "GRID_BOUNDARY_CHECK"
  }
}
```

---

# 83. Reviewer JSON Schema 개념

```json
{
  "algorithmSelection": "CORRECT",
  "primaryMistake": "BOUNDARY_CHECK",
  "secondaryMistakes": [],
  "confidence": 0.94,
  "affectedSkills": [
    "GRID_BOUNDARY_CHECK"
  ],
  "conceptIssue": false,
  "implementationIssue": true
}
```

---

# PART VIII. Test Specification

---

# 84. Mastery Unit Test

반드시 다음을 테스트한다.

```text
신규 Skill null 처리
첫 Evidence
힌트 없는 AC
힌트 5 AC
WA
Review AC
confidence 누적
Mastered 전환
Weakened 전환
```

---

# 85. Skill Catalog Test

CI에서 검증:

```text
code unique
존재하지 않는 prerequisite 금지
자기 자신 prerequisite 금지
Cycle 금지
Deprecated replacement 유효성
```

---

# 86. Decision Engine Test

예:

```text
BOUNDARY_CHECK confirmed
→ MICRO_DRILL
```

```text
VISITED_TIMING confirmed
→ MICRO_DRILL
```

```text
AC + mastery low
→ CONTINUE
```

```text
AC + mastery high + review done
→ UNLOCK_NEXT
```

---

# 87. Sandbox Security Test

자동 테스트:

```text
Network 요청 실패
/tmp 외 write 실패
Fork bomb 제한
Memory 초과 kill
Infinite loop timeout
stdout 무한 출력 제한
Host file 접근 실패
root 권한 없음
```

---

# PART IX. Delivery Plan

---

# 88. 개발 순서

## Step 1

```text
Canonical Skill Catalog
```

먼저 확정.

---

## Step 2

```text
Problem
Test Case
Submission
Judge
```

구현.

---

## Step 3

```text
Skill Evidence
Mastery Service
```

구현.

---

## Step 4

```text
Reviewer
Mistake
```

구현.

---

## Step 5

```text
Decision Engine
Micro Drill
```

구현.

---

## Step 6

End-to-End:

```text
Problem
→ Submit
→ Judge
→ Review
→ Mastery
→ Decision
→ Next Problem
```

완성.

---

# 89. 첫 Milestone Definition of Done

다음 데모가 실제로 가능하면 Milestone 1 완료다.

```text
1. 사용자가 BFS Grid 문제를 연다.

2. Boundary Check가 빠진 Python 코드를 제출한다.

3. Sandbox에서 코드가 실행된다.

4. Judge가 실패를 반환한다.

5. Reviewer가
   BOUNDARY_CHECK
   confidence >= 0.90
   을 반환한다.

6. GRID_BOUNDARY_CHECK Skill에 Evidence가 추가된다.

7. mastery / confidence가 재계산된다.

8. Decision Engine이
   MICRO_DRILL
   을 선택한다.

9. Boundary Check 전용 문제가 제공된다.

10. 사용자가 성공한다.

11. 해당 Skill 점수가 회복된다.

12. BFS 학습 흐름으로 복귀한다.
```

---

# 90. 구현 결정 요약

## Mastery

```text
null 초기값
+
Dimension Score
+
EMA
+
Evidence
+
Confidence
```

---

## Skill ID

```text
Canonical UPPER_SNAKE_CASE
```

예:

```text
BFS_BASIC
BFS_GRID_TRAVERSAL
GRID_BOUNDARY_CHECK
```

---

## First Vertical Slice

```text
Python 3.12
+
BFS Grid
+
Boundary Check
+
Visited Timing
```

단, 전체 제품 Curriculum은 전 범위를 유지한다.

---

## Sandbox

```text
Docker Ephemeral Container

network none
non-root
read-only
CPU 0.5
memory 256MB
pids 64
cap-drop all
no-new-privileges
```

공개 서비스 전:

```text
gVisor 검토/적용
```

---

# 91. 최종 결론

이 문서에서 확정한 네 가지는 단순 구현 디테일이 아니다.

각 항목은 제품의 핵심 가치와 직접 연결된다.

```text
Mastery 규칙
→ "정말 배웠는가?"를 판단

Skill Catalog
→ "무엇을 배웠는가?"를 정의

Vertical Slice
→ Agentic Learning Loop가 실제로 가능한지 검증

Sandbox
→ 사용자의 코드를 실제로 안전하게 실행
```

따라서 전체 시스템은 다음과 같이 연결된다.

```text
Problem
 ↓
Submission
 ↓
Sandbox Judge
 ↓
Deterministic Result
 ↓
Reviewer
 ↓
Skill Evidence
 ↓
Mastery Update
 ↓
Decision Engine
 ↓
Next Learning Action
```

이 흐름이 첫 번째로 완성되면
CodeSprint Agent는 단순한 AI 문제 설명기가 아니라

**사용자의 실제 코드와 학습 Evidence를 기반으로 다음 학습 행동을 스스로 결정하는 Adaptive Coding Test Learning Agent**

로 동작하기 시작한다.
