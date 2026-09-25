# ADR-0034 · PRD 끝까지 가는 길 - 웨이브와 완료 조건

- 상태: 채택
- 날짜: 2026-09-26
- 정본 근거: PRD §4~51(도메인), §126~129(목표별 Skill Set), §141(구현 우선순위),
  §164(구현 체크리스트), Addendum §29(Skill 과 Technique), §35(Catalog 변경 정책),
  [ADR-0032](0032-the-agent-drafts-the-system-adopts.md),
  [ADR-0033](0033-an-accepted-answer-does-not-prove-the-skill.md)

## 맥락

슬라이스 1(도메인 3 · Skill 8 · 문제 18)은 루프 하나를 끝까지 만들었다. PRD 는 도메인 46,
Agent 7(Diagnostic · Planner · Tutor · Reviewer · Evaluator · Problem Generator · Exam
Strategy), 언어 3(Python · Java · C++), 화면 8(Onboarding · Diagnostic · Dashboard ·
Skill Map · Solver · Review · Mock Test · Analytics)을 요구한다.

한 번에 다 넣으면 검증 없이 들어간다. 이 저장소가 지금까지 지킨 것은 "검사가 일한다"
였다 - 그 기준을 낮추지 않고 범위를 넓히는 순서를 정한다.

## 결정

### 1. Skill 은 PRD 목록을 그대로 옮기지 않는다

PRD 도메인 절의 소문자 목록에는 Skill 과 Technique 이 섞여 있다(`enumerate`, `zip`,
`deque_api`). Addendum §29 대로 Technique 은 Skill 로 만들지 않는다. 그리고 ADR-0033 대로
**채점으로 관측할 수 없는 Skill 은 정의를 고치거나 합친다.**

그래서 도메인마다 채점으로 가를 수 있는 Skill 2~5개로 묶는다. 각 Skill 이 승격되는 조건은
슬라이스 1 과 같다.

```
skills.yaml        정의 · tier · needs_skill_control (관측 가능한 행동으로)
prerequisites.yaml 선수 관계 (순환 금지 - CI)
concepts.yaml      개념 자료 하나 (ADR-0024)
problems/          PRIMARY 로 갖는 NORMAL 문제 하나 이상, 채택 검사 전 단계 통과
검증 에이전트       지름길 · 의미 중복 · 모호한 본문 검토 (사람 검토를 대신한다)
```

### 2. 사람 검토는 검증 에이전트가 한다

ADR-0032·0033 에서 기계 검사가 놓친 것(의미 중복, 초안이 말하지 않은 지름길)은 사람이
PR 에서 찾았다. 이 역할을 **검증 에이전트**가 맡는다 - 구현한 쪽과 다른 에이전트가, 지름길
풀이를 직접 써서 실제로 채점해 본다. 주장만으로 통과시키지 않는다.

모든 PR 은 검증 에이전트의 `MERGE_OK` 뒤에만 머지한다. `BLOCK` 이면 고치고 다시 받는다.

### 3. 웨이브

| 웨이브 | 내용 | PRD |
| --- | --- | --- |
| W1 기반 | 문제 code 3자리, 목표(학습 트랙) 선택과 목표별 활성 Skill, 진단 범위를 목표로 | §56, §129, §152 |
| W2 CORE-1 | Programming · Implementation · Array · String · Hash · Sorting · Stack/Queue | §6~12 |
| W3 CORE-2 | Heap · Recursion · Brute Force · Backtracking · Two Pointer · Sliding Window · Prefix Sum | §13~19 |
| W4 CORE-3 | Binary Search · Greedy · Math · DFS · Graph · Basic Tree · Basic DP | §20~22, §26, §28, §33~34 |
| W5 학습 운영 | Daily Planner · 학습 우선순위 · Skill Map / Dashboard 화면 | §80~81, §121~122 |
| W6 INTERMEDIATE | Number Theory · Combinatorics · Bitmask · Shortest Path · Union Find · MST · Topo · LIS/LCS · Knapsack · State Search · Coord Compression · Monotonic · Trie · String Matching · Sweep Line · Geometry | §23~25, §29~41, §44~45 |
| W7 시험 | Mock Test · 시간 관리 · 시험 직전 모드 · 학습 모드 | §82, §84~85, §151 |
| W8 언어 | Java · C++ Runner | §164 Judge |
| W9 ADVANCED | Segment · Fenwick · SCC · Flow · Advanced Shortest Path · Advanced DP · Advanced String · Mixed | §42~51 |

웨이브 하나는 PR 하나 이상이다. 도메인 웨이브는 PR 이 크면 나눈다.

### 4. 완료 조건

PRD §164 체크리스트의 각 항목이 **실행되는 코드와 그것을 깨뜨리는 검사**를 가질 때
체크한다. 문서에 적었다고 체크하지 않는다.

## 남는 위험

- 도메인이 늘면 문제 생성 비용(Claude CLI 호출)이 선형으로 는다
- 검증 에이전트도 모델이다. 같은 계열 모델의 같은 착각을 놓칠 수 있다
- `needs_skill_control` 이 필요한 Skill 이 늘면 큰 case 가 저장소를 키운다
