# ADR-0004 · Reviewer는 실패 Test Case가 있을 때만 호출한다

- 상태: 채택
- 날짜: 2026-09-05
- 정본 근거: Addendum §20 (Judge Evidence 우선), §37, §42

## 맥락

계약이 스스로 모순돼 있었다.

```text
reviewer-output.llm.schema.json
  required        : [..., failedCaseRefs, ...]
  failedCaseRefs  : minItems 1          -- 실패 case 를 반드시 하나 이상 참조하라
  mistakeCode     : [..., SYNTAX_ERROR] -- 문법 오류도 낼 수 있다

submit-response.schema.json
  judge.status    : [..., COMPILE_ERROR]
```

`COMPILE_ERROR`는 Test Case를 **하나도 실행하지 못한** 상태다. 참조할 실패 case가
존재하지 않는다. 그런데 같은 스키마가 `SYNTAX_ERROR`를 낼 수 있다고 해놓고,
그때 존재할 수 없는 근거를 필수로 요구한다.

구현하면 셋 중 하나가 된다.

1. 모델이 없는 case id를 **지어낸다** — 근거 강제가 근거 위조로 뒤집힌다
2. 스키마 검증에서 매번 거부된다 — 문법 오류를 낸 사용자가 아무 피드백도 못 받는다
3. `minItems`를 푼다 — 근거 없는 분석이 허용되고 ADR-0001의 강제가 무너진다

## 결정

**근거 요구를 낮추는 대신, Reviewer를 호출하는 조건을 좁힌다.**

| Judge 상태 | Reviewer | Mistake 부여 |
| --- | --- | --- |
| `ACCEPTED` | 호출 안 함 | 없음 |
| `COMPILE_ERROR` | **호출 안 함** | `SYNTAX_ERROR` (시스템이 결정론적으로) |
| `SYSTEM_ERROR` | 호출 안 함 | 없음 (사용자 잘못이 아니다) |
| `WRONG_ANSWER` | 호출 | Reviewer 분석 |
| `RUNTIME_ERROR` | 호출 | Reviewer 분석 |
| `TIME_LIMIT` | 호출 | Reviewer 분석 |
| `MEMORY_LIMIT` | 호출 | Reviewer 분석 |
| `OUTPUT_LIMIT` | 호출 | Reviewer 분석 |

호출하는 다섯 상태는 모두 **실패한 case가 특정된다.** 따라서 `failedCaseRefs`의
`minItems: 1`은 항상 만족 가능하고, 모델이 근거를 지어낼 압력이 사라진다.

## `assigned_by` 축

이 정책을 문서가 아니라 데이터로 만든다. `curriculum/mistakes.yaml`에 축을 추가했다.

```yaml
- code: BOUNDARY_CHECK
  assigned_by: REVIEWER     # LLM 이 실패 case 를 근거로 분류
- code: SYNTAX_ERROR
  assigned_by: SYSTEM       # Judge 결과만으로 결정론적 부여
```

`reviewer-output.llm.schema.json`의 `mistakeCode` enum에는 **`REVIEWER`인 것만**
들어간다. `SYNTAX_ERROR`는 빠졌다 — ADR-0001과 같은 논리다. 물어보지 않으므로
만들어낼 수 없다.

`tools/check_curriculum.py`가 세 방향을 전부 막는다.

- `assigned_by`가 `REVIEWER`/`SYSTEM` 밖의 값
- `REVIEWER`인데 enum에 없음 / enum에 있는데 yaml에 없음
- **`SYSTEM`인 code가 enum에 유입**

## 왜 taxonomy에서 아예 빼지 않는가

`SYNTAX_ERROR`를 `mistakes.yaml`에서 지우면 계약은 단순해지지만, 사용자의 문법 오류가
아무 Skill에도 기록되지 않는다. Addendum §12는 이 경우를 별도 Language Skill
(`PYTHON_SYNTAX` 등)의 Evidence로 남기라고 명시한다. 라벨은 필요하다 —
**LLM이 붙이지 않을 뿐이다.**

## 결과

**얻는 것**

- 근거 강제(`minItems: 1`)를 유지하면서 모순이 사라진다
- Reviewer 호출이 줄어 비용과 지연이 준다. `ACCEPTED`는 전체 제출의 다수를 차지한다
- `COMPILE_ERROR` 피드백이 LLM 지연 없이 즉시 나간다
- "누가 이 라벨을 붙일 수 있는가"가 데이터에 명시돼 검사 가능해진다

**치르는 비용**

- `mistakes.yaml`에 필드가 하나 늘고, 새 Mistake마다 판단이 필요하다
- `TIME_LIMIT`에서 실패 case를 특정하는 책임이 Judge에 생긴다. 타임아웃된 case의
  id를 기록하지 않으면 이 계약이 성립하지 않는다 — 슬라이스 1 Judge 구현 시 확인 항목

**미해결**

- `RUNTIME_ERROR` 중 `RecursionError`처럼 stderr만으로 결정론적 분류가 가능한 것이
  있다. 지금은 전부 Reviewer로 보내지만, 정적 Rule로 먼저 거르는 편이 Addendum §20의
  우선순위(정적 Rule > LLM)에 더 맞다. 슬라이스 1에서 실제 분포를 보고 결정한다.

## 관련

- [ADR-0001](0001-llm-analyzes-system-decides.md) — 물어보지 않으면 만들어낼 수 없다
- [ADR-0002](0002-next-action-decided-by-rule-engine.md)
