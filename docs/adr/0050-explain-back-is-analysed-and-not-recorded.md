# ADR-0050 · Explain Back 은 분석하되 기록하지 않는다

- 상태: 채택
- 날짜: 2026-09-26
- 정본 근거: PRD §148(Explain Back), §150(Anti-Overhelp), [ADR-0001](0001-llm-analyzes-system-decides.md),
  [ADR-0026](0026-hint-ladder-is-problem-data.md), [ADR-0044](0044-the-tutor-explains-and-does-not-decide.md),
  [ADR-0049](0049-analytics-exam-strategy-evaluator.md)

## 맥락

PRD §148 은 "사용자가 개념을 직접 설명하게 할 수 있다 … 설명을 Evaluator 가 분석한다" 고 적는다. 그런데
Evaluator 는 시스템이다(ADR-0049) - 자유로운 문장을 읽는 것은 LLM 이 할 수 있는 일이고, ADR-0001 대로 그
결과는 점수가 될 수 없다. 정할 것은 셋이다 - 언제 받는가, 무엇을 돌려주는가, 결과가 어디에 들어가는가.

## 결정

### 1. 푼 문제만 받는다

`POST /api/problems/{code}/explanations` 는 그 사용자가 그 문제에서 **ACCEPTED 를 받은 적이 있을 때만** 받는다
(아니면 409). 풀기 전에 설명을 분석해 주면 "빠진 요점" 이 곧 힌트가 되어, 힌트 사다리(ADR-0026) 밖에서 도움을
받는 길이 된다 - 그러면 힌트 기록(ADR-0027)이 받은 도움의 양을 말하지 못한다. 모의 시험 중에는 시험 밖 문제라도
409 다(PRD §84).

학습 모드는 가리지 않는다. 푼 뒤의 설명은 도움이 아니라 확인이다.

### 2. 질문은 시스템이 정한다

"왜 이 문제에서 {PRIMARY Skill 이름} 이(가) 통하는지 두 문장으로 설명해 보세요." - PRD §148 의 예를 따른 한
문장이다. 모델이 질문을 고르면 같은 문제에서 매번 다른 것을 묻게 되고, 사용자는 무엇을 설명해야 하는지 모른다.

### 3. 분석은 주장이고, 어디에도 기록하지 않는다

모델은 `coveredPoints` · `missingPoints` · `misconception` · `followUpQuestion` 만 돌려준다
(`contracts/explain-back.llm.schema.json`). 점수 · 숙련도 · 다음 행동 자리가 없고, CI 가 `*.llm.schema.json` 에
그런 필드가 들어오면 막는다. 계약을 어긴 답(예: `score` 를 붙여 온 답)은 고쳐 쓰지 않고 버린다(502).

분석은 **Evidence · mastery · 다음 행동 어디에도 들어가지 않는다.** 설명을 잘했다는 것은 풀 수 있다는 관측이
아니다 - Tutor 의 답을 기록하지 않는 것(ADR-0044)과 같은 이유다. 테스트가 분석 전후의 `skill_evidence` 행 수를
본다.

### 4. 나머지는 Tutor 와 같다

기본은 꺼져 있다(503, `CODESPRINT_EXPLAIN_ENABLED=true` 로 켠다). 명령은 Reviewer · Tutor 와 같은
`*llm-command` 앵커를 쓴다(도구 없음 - 테스트가 본다). 프롬프트는 파일 이름이 버전이다
(`explain/prompts/explain-v1.md`). API 키를 받지 않는다.

화면은 푼 결과(ACCEPTED) 아래에만 "설명해 보기" 를 연다. 문제를 옮기거나 사용자를 바꾸면 닫고, 늦게 온 분석은
버린다(`claimView("explain")`, e2e 가 본다).

## 검증

| 검사 | 무엇을 보는가 |
| --- | --- |
| `ExplainTest` | 푼 문제 → 200 · 계약 · 프롬프트에 질문과 설명이 그대로 · Evidence 행 수 그대로 / 제출 없음 · 틀리기만 함 → 409(모델을 부르지 않음), 풀면 200(대조) / score 를 붙인 답 · 정상 JSON 뒤에 무언가 붙은 답 502 · 꺼짐 503 · 빈 설명 400 · 시험 중 409, 끝나면 200(대조). "푼 문제만" 확인을 끄면 실패 |
| `ExplainSharesReviewerSettingsTest` | 명령이 Reviewer 와 같고 도구가 없으며 기본이 꺼져 있다. 자리 이름 목록으로 템플릿이 렌더된다(코드가 실제로 채우는지는 `ExplainTest` 가 잡는다 - 자리 하나를 빼면 502) |
| e2e | 늦게 온 분석이 다른 문제 화면에 붙지 않는다(무효화를 빼면 실패), 틀린 결과에서는 열리지 않는다(조건을 빼면 실패) |

## 남는 위험

- 모델이 개념 자료와 다른 말을 할 수 있다. 개념 자료를 프롬프트에 함께 주지만 강제할 수단은 없다 - 분석이
  기록되지 않으므로 틀린 분석이 학습 경로를 바꾸지는 않는다
- 질문이 Skill 이름 하나로 만든 한 문장이라, 섞인 문제(SKILL_COMPOSITION)에서는 "기법을 골라 잇기" 가 무엇이 통하는
  지로 묻기에 어색하다
- 실제 모델로 분석 품질을 잰 적은 없다 - Reviewer 처럼 라벨된 정답지가 없다
