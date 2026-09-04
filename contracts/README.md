# Contracts

Agent 출력과 애플리케이션 경계에서 **실제로 검증에 사용하는 계약**이다.
문서가 아니라 실행 가능한 파일이므로 `docs/` 바깥에 둔다.

| 파일 | 정본 문서 | 쓰이는 곳 |
| --- | --- | --- |
| [reviewer-output.llm.schema.json](reviewer-output.llm.schema.json) | [ADR-0001](../docs/adr/0001-llm-analyzes-system-decides.md) | **LLM 요청 스키마.** 오답 분석만. 점수·액션 필드 없음 |
| [submit-response.schema.json](submit-response.schema.json) | Addendum §82 | **API 응답 스키마.** 시스템이 점수와 액션을 채운 최종 결과 |

## 검증 지점

```text
사용자 코드
    ↓
Sandbox Judge            ← 결정론적 판정 (AI 없음)
    ↓
Reviewer (LLM)
    ↓
JSON Schema Validation   ← 여기서 막는다
    ↓
Mistake 확정 판단 (Rule)
    ↓
MasteryService           ← 점수는 여기서만 만들어진다
    ↓
DecisionService          ← 액션은 여기서만 만들어진다
    ↓
SubmitResponse
```

모델 응답을 검증 없이 DB 에 넣지 않는다.

## 규칙

1. **`additionalProperties: false` 를 유지한다.** 모델이 임의 필드를 덧붙이는 것을 막는다.
   **LLM 요청 스키마는 루트가 object 여야 한다.** Structured Output 이 배열 루트를 받지 않는다.

2. **`submit-response.schema.json` 을 LLM Structured Output 에 그대로 쓰지 않는다.**
   이 파일은 `skillUpdates` / `nextAction` 을 포함하며, 둘 다 시스템이 채우는 필드다.

   ```text
   LLM 요청 (reviewer-output.llm.schema.json)
     primaryMistake, confidence, affectedSkills, failedCaseRefs
             ↓
   Mistake 확정 (Addendum §21)
             ↓
   MasteryService / DecisionService
             ↓
   API 응답 (submit-response.schema.json)
     + review.status, skillUpdates, nextAction
   ```

3. **모델에게 시스템이 아는 값을 물어보지 않는다.**
   실행 시간·메모리·통과 케이스 수는 Judge 가 이미 결정론적으로 알고 있다.
   물어보면 추측한 값이 들어오고, 버릴 값의 형식이 틀렸다는 이유로
   **멀쩡한 오답 분석 전체가 거부될 수 있다.**

4. **근거 없는 분석을 허용하지 않는다.**
   `failedCaseRefs` 는 `minItems: 1` 이다. Judge 가 실제로 실패시킨 Test Case 를
   가리키지 못하는 분석은 확정 대상이 아니다(Addendum §20).
   부수 효과로, 나중에 평가 데이터셋으로 쓸 라벨이 지금부터 쌓인다.

5. **"모르는 값"은 `null` 을 허용하되 required 에서 빼지 않는다.**
   필드를 생략하는 것과 `null` 로 명시하는 것은 다르다. 후자만 "확인했고 없었다"를 뜻한다.
   `skillUpdates[].before` 의 `null` 은 UNASSESSED 이며 `0.0`(평가했고 못함)과 구분된다(Addendum §4).

## 검증

```bash
python tools/check_curriculum.py
```
