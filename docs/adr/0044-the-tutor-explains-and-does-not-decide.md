# ADR-0044 · Tutor 는 설명만 하고 아무것도 정하지 않는다 - 자유 질문은 FREE 모드의 것이다

- 상태: 채택
- 날짜: 2026-09-26
- 정본 근거: PRD §90(Tutor Agent), §150(Anti-Overhelp), §151(FREE 모드), [ADR-0001](0001-llm-analyzes-system-decides.md),
  [ADR-0014](0014-reviewer-output-is-a-claim-until-judge-evidence-agrees.md),
  [ADR-0027](0027-hint-level-is-observed-not-reported.md), [ADR-0043](0043-mock-test-and-learning-modes.md)

## 맥락

PRD §151 의 FREE 모드는 "자유 질문" 이고, §150 은 자유 모드에서 전체 해설을 허용한다. 질문에 답하는 것은
LLM 이 할 수 있는 일이다(ADR-0001 - 개념 설명 · 힌트). 문제는 그 답이 **어디까지 영향을 주는가** 다.

## 결정

### 1. 답은 기록되지 않는다

`POST /api/tutor/questions` 는 설명을 돌려줄 뿐 Evidence 를 만들지 않는다. mastery 도 다음 행동도 바뀌지
않는다. 설명을 들은 것은 풀 수 있다는 관측이 아니다. 테스트가 질문 뒤에 `skill_evidence` 가 0 행인지 본다.

### 2. FREE 모드에서만, 시험 중에는 아니다

다른 모드는 "힌트 우선" 이다(§150). 튜터가 모든 모드에서 답하면 사다리(ADR-0026)를 건너뛰는 길이 되고,
그러면 힌트 기록(ADR-0027)이 받은 도움의 양을 말하지 못한다 - H1 만 본 사람이 튜터에게 풀이를 받아도 기록은
H1 이다. FREE 를 고른 사람은 그 교환을 스스로 고른 것이다.

진행 중인 모의 시험이 있으면 FREE 여도 409 다(PRD §84). 튜터는 무엇을 묻는지 모르고, 시험 중인지는 서버가 안다.

### 3. 경계는 스키마로 지킨다

`contracts/tutor-answer.llm.schema.json` 에는 `answer` 와 `followUpQuestion`(질문형 설명, §90)뿐이다. 점수 ·
숙련도 · 다음 행동 자리가 없고, 계약을 어긴 답은 고쳐 쓰지 않고 버린다(502). 테스트가 `mastery` 를 끼워 넣은
답이 버려지는 것과, 같은 경로에 올바른 답을 주면 통과하는 대조를 함께 본다.

프롬프트에는 그 Skill 의 **저장소 개념 자료**(concepts.yaml)를 함께 넣는다. 튜터가 자료와 어긋나게 말하지 않게
하려는 것이지, 그렇게 됐다는 보장은 아니다.

### 4. Reviewer 와 같은 스위치 모양

기본은 꺼져 있다(503). `CODESPRINT_TUTOR_ENABLED=true` 로 켜고, 명령은 Reviewer 와 같은 `application.yml`
앵커를 가리킨다 - 따로 적으면 한쪽만 도구 권한이 열린 채로 남는다(테스트가 두 명령이 같은지 본다). 프롬프트는
파일 이름이 버전이다(`tutor/prompts/tutor-v1.md`). API 키를 받지 않는다.

## 남는 위험

- **튜터 답의 정확도는 재지 않는다.** Reviewer 는 라벨된 오답(ADR-0016)으로 잴 수 있지만, 자유 질문에는 정답지가
  없다. 답이 학습 경로에 들어가지 않으므로 틀린 설명이 mastery 를 바꾸지는 않는다 - 사람을 헷갈리게 할 수는 있다
- Explain Back(§148 - 사용자가 개념을 설명하고 Evaluator 가 분석한다)은 이 ADR 에 없다. 설명을 듣는 것이 아니라
  설명하게 하는 것이고, 그 분석을 mastery 에 넣을지부터 따로 정해야 한다
