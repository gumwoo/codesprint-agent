# Contracts

Agent 출력과 애플리케이션 경계에서 **실제로 검증에 사용하는 계약**이다.
**이 폴더의 계약은 전부 아래 표에 있다** - 빠지면 `tools/check_curriculum.py` 가 막는다.
문서가 아니라 실행 가능한 파일이므로 `docs/` 바깥에 둔다.

| 파일 | 정본 문서 | 쓰이는 곳 |
| --- | --- | --- |
| [reviewer-output.llm.schema.json](reviewer-output.llm.schema.json) | [ADR-0001](../docs/adr/0001-llm-analyzes-system-decides.md) | **LLM 요청 스키마.** 오답 분석만. 점수·액션 필드 없음 |
| [problem-draft.llm.schema.json](problem-draft.llm.schema.json) | [ADR-0032](../docs/adr/0032-the-agent-drafts-the-system-adopts.md) | **LLM 요청 스키마.** 문제 초안. 기대 출력 · Skill 비중 · 시간 제한은 받지 않는다 - 시스템이 정한다 |
| [submit-response.schema.json](submit-response.schema.json) | Addendum §82 | **API 응답 스키마.** 시스템이 점수와 액션을 채운 최종 결과 |
| [next-problem.schema.json](next-problem.schema.json) | [ADR-0002](../docs/adr/0002-next-action-decided-by-rule-engine.md), [ADR-0024](../docs/adr/0024-review-concept-resolves-to-curriculum-material.md) | **다음 학습 단계 API 응답.** 결정된 액션을 문제 또는 개념 복습 자료로 옮긴 결과 |
| [concept-view.schema.json](concept-view.schema.json) | [ADR-0024](../docs/adr/0024-review-concept-resolves-to-curriculum-material.md) | **개념 복습 자료 응답.** `REVIEW_CONCEPT` 대상 Skill의 검증된 커리큘럼 자료 |
| [problem.schema.json](problem.schema.json) | [ADR-0007](../docs/adr/0007-problems-are-verified-by-a-wrong-solution.md) | 문제 메타 + Skill 매핑. `problems/<CODE>/problem.yaml` |
| [test-cases.schema.json](test-cases.schema.json) | Addendum §66 | Test Case 집합. `problems/<CODE>/cases.json` |
| [hint-ladder.schema.json](hint-ladder.schema.json) | [ADR-0026](../docs/adr/0026-hint-ladder-is-problem-data.md) | 단계별 힌트 H1~H5. `problems/<CODE>/hints.yaml`. **H6 은 들어오지 않는다** - `reference.py` 가 그것이다 |
| [hint-view.schema.json](hint-view.schema.json) | [ADR-0027](../docs/adr/0027-hint-level-is-observed-not-reported.md) | **힌트 발급 응답.** 내용 한 단계와 지금까지 본 최고 단계. 화면이 단계를 세지 않는다 |
| [skill-evidence.schema.json](skill-evidence.schema.json) | [ADR-0009](../docs/adr/0009-mastery-is-recomputed-from-evidence.md) | **학습의 정본.** mastery 는 이것으로부터 재계산된다 |
| [user-skill.schema.json](user-skill.schema.json) | Addendum §73 | Evidence 로부터 계산된 Skill 상태. 캐시이지 정본이 아니다 |
| [judge-result.schema.json](judge-result.schema.json) | [ADR-0005](../docs/adr/0005-judge-stops-at-first-failure.md) | **Sandbox Runner 출력.** 결정론적 판정. status enum 은 submit-response 와 동일해야 하며 CI 가 검증한다 |
| [run-judge-result.schema.json](run-judge-result.schema.json) | [ADR-0020](../docs/adr/0020-running-is-not-submitting.md), [ADR-0031](../docs/adr/0031-the-local-run-path-hides-nothing.md) | **제출 전 실행의 Sandbox 출력.** case 에 입력 · 기대 출력 · 실제 출력이 실린다. 제출 채점 결과에는 실리면 안 되므로 judge-result 와 나눈다 |
| [problem-list.schema.json](problem-list.schema.json) | [ADR-0002](../docs/adr/0002-next-action-decided-by-rule-engine.md) | 문제 목록 API 응답. **학습 경로가 아니라 첫 제출 이전의 진입점**이다 |
| [problem-view.schema.json](problem-view.schema.json) | [ADR-0008](../docs/adr/0008-public-repo-holds-fixtures-not-the-problem-bank.md) | 문제 한 개 API 응답. hidden Test Case 는 들어오지 않는다 |
| [submission-status.schema.json](submission-status.schema.json) | [ADR-0013](../docs/adr/0013-judging-happens-outside-the-request.md) | 제출 접수(202)와 결과 조회 응답. 두 엔드포인트가 같은 모양이다 |
| [run-result.schema.json](run-result.schema.json) | [ADR-0020](../docs/adr/0020-running-is-not-submitting.md) | 예제 실행 결과. **제출이 아니다** - Evidence 도 다음 행동도 없다 |
| [diagnostic-step.schema.json](diagnostic-step.schema.json) | [ADR-0018](../docs/adr/0018-the-diagnostic-orders-problems-it-does-not-score.md) | 초기 진단의 현재 단계. 점수를 만들지 않는다 |
| [reviews.schema.json](reviews.schema.json) | [ADR-0021](../docs/adr/0021-a-review-is-what-the-schedule-says-it-is.md) | 간격 복습 목록. **만기 여부는 서버가 정한다** |
| [skill-catalog.schema.json](skill-catalog.schema.json) | [ADR-0012](../docs/adr/0012-curriculum-is-packaged-from-one-source.md) | 활성 Skill 정의. 사용자와 무관하다 |
| [today.schema.json](today.schema.json) | [ADR-0038](../docs/adr/0038-the-plan-is-decided-not-generated.md) | 오늘의 학습 계획과 대시보드 숫자 |
| [mistake-summary.schema.json](mistake-summary.schema.json) | [ADR-0038](../docs/adr/0038-the-plan-is-decided-not-generated.md) | 최근 제출의 오답 탐지 개수(확정 · 주장 구분) |
| [analytics.schema.json](analytics.schema.json) | [ADR-0049](../docs/adr/0049-analytics-exam-strategy-evaluator.md) | 학습 분석 - 판정 분포 · 푼 문제 · 주별 추이 · Skill 상태 · 끝난 모의 시험 |
| [track-list.schema.json](track-list.schema.json) | [ADR-0035](../docs/adr/0035-a-learning-track-scopes-the-skill-graph.md) | 고를 수 있는 학습 트랙과 켜지는 Skill 수 |
| [user.schema.json](user.schema.json) | [ADR-0035](../docs/adr/0035-a-learning-track-scopes-the-skill-graph.md) | 사용자 자리와 그 트랙. 인증이 아니다 |
| [tutor-answer.llm.schema.json](tutor-answer.llm.schema.json) | [ADR-0044](../docs/adr/0044-the-tutor-explains-and-does-not-decide.md) | **LLM 요청 스키마.** Tutor 의 설명. 점수 · 숙련도 · 다음 행동 자리가 없다 |
| [tutor-answer.schema.json](tutor-answer.schema.json) | [ADR-0044](../docs/adr/0044-the-tutor-explains-and-does-not-decide.md) | 자유 질문의 답. FREE 모드에서만. Evidence 가 되지 않는다 |
| [mock-test.schema.json](mock-test.schema.json) | [ADR-0043](../docs/adr/0043-mock-test-and-learning-modes.md) | 모의 시험의 상태. 문제를 라벨로만 부른다 - code · 제목 · Skill 은 유형을 알려 준다 |
| [mock-test-problem.schema.json](mock-test-problem.schema.json) | [ADR-0043](../docs/adr/0043-mock-test-and-learning-modes.md) | 시험 중에 연 문제. 본문 · 제한 · 예시만. 여는 요청이 연 시각을 남긴다 |
| [mock-test-verdict.schema.json](mock-test-verdict.schema.json) | [ADR-0043](../docs/adr/0043-mock-test-and-learning-modes.md) | 시험 중의 제출 결과. 판정만 - 분석과 다음 행동은 끝난 뒤다 |
| [mock-test-run.schema.json](mock-test-run.schema.json) | [ADR-0043](../docs/adr/0043-mock-test-and-learning-modes.md) | 시험 중의 실행 결과. 문제 code 가 없다 |
| [mock-test-report.schema.json](mock-test-report.schema.json) | [ADR-0043](../docs/adr/0043-mock-test-and-learning-modes.md) | 끝난 시험의 보고서. 관측한 순간(열기 · 실행 · 제출)만 담는다 |
| [skill-map.schema.json](skill-map.schema.json) | [ADR-0009](../docs/adr/0009-mastery-is-recomputed-from-evidence.md) | 한 사용자의 Skill 상태 전체. 활성 Skill 을 빠뜨리지 않는다 |
| [judge-job.schema.json](judge-job.schema.json) | [ADR-0011](../docs/adr/0011-language-boundary.md), [ADR-0013](../docs/adr/0013-judging-happens-outside-the-request.md) | **채점 큐의 행 하나.** Java 가 쓰고 Python Worker 가 읽는 유일한 메시지 |
| [mastery-golden.schema.json](mastery-golden.schema.json) | [ADR-0010](../docs/adr/0010-java-implementation-is-checked-against-the-python-oracle.md) | Evidence 로부터의 **계산**을 두 구현에 고정하는 golden |
| [evidence-golden.schema.json](evidence-golden.schema.json) | [ADR-0010](../docs/adr/0010-java-implementation-is-checked-against-the-python-oracle.md) | **제출**을 Evidence 로 옮기는 매핑을 고정하는 golden |
| [review-evidence-golden.schema.json](review-evidence-golden.schema.json) | [ADR-0010](../docs/adr/0010-java-implementation-is-checked-against-the-python-oracle.md) | **복습**을 Evidence 로 옮기는 매핑을 고정하는 golden |
| [reviewer-eval-case.schema.json](reviewer-eval-case.schema.json) | [ADR-0016](../docs/adr/0016-reviewer-accuracy-is-measured-with-labelled-wrong-answers.md) | Reviewer 정확도를 재는 라벨된 오답 하나 |

## 검증 지점

```text
사용자 코드
    ↓
Sandbox Judge            ← 결정론적 판정 (AI 없음)
    ↓
ACCEPTED / COMPILE_ERROR / SYSTEM_ERROR ──→ Reviewer 호출 안 함 (ADR-0004)
    ↓                                        COMPILE_ERROR 는 SYNTAX_ERROR 를
WA / RE / TLE / MLE / OLE                    시스템이 결정론적으로 부여
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
