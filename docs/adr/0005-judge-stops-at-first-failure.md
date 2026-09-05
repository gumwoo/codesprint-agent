# ADR-0005 · 채점은 첫 실패에서 멈추고, 실패 case를 반드시 특정한다

- 상태: 채택
- 날짜: 2026-09-05
- 정본 근거: Addendum §61~65, §68 / [ADR-0004](0004-reviewer-invocation-requires-case-evidence.md)

## 맥락

Test Case를 어디까지 돌릴지 정해야 한다. 두 갈래가 있다.

**전부 돌린다** — "8개 중 5개 통과"처럼 부분 점수를 보여줄 수 있다.
**첫 실패에서 멈춘다** — 판정이 확정되는 즉시 끝낸다.

부분 점수는 학습 서비스에서 매력적으로 보인다. 그런데 이 제품은 부분 점수를 쓰지
않는다. `mastery`는 case 통과율이 아니라 Judge 판정 + 힌트 의존도 + 풀이 시간으로
계산된다(Addendum §11). "5/8"이라는 숫자가 갈 곳이 없다.

반면 전부 돌리는 비용은 실제로 크다. 무한 루프 코드 하나가 **case 수만큼의 timeout**을
소모한다. 2초 제한에 case 20개면 40초다. 그동안 Judge Worker 하나가 묶인다.

## 결정

**첫 실패에서 멈춘다.** 그리고 그 case를 반드시 `failedCaseId`로 특정한다.

```text
ACCEPTED        모든 case 통과. failedCaseId = null
WRONG_ANSWER    첫 실패 case 에서 멈춤. failedCaseId 필수
RUNTIME_ERROR   〃
TIME_LIMIT      〃  ← 시간 초과에서도 반드시 특정한다
MEMORY_LIMIT    〃
OUTPUT_LIMIT    〃
COMPILE_ERROR   case 를 하나도 실행하지 못함. failedCaseId = null
SYSTEM_ERROR    우리 잘못. failedCaseId = null
```

`passed`는 "실패 전까지 통과한 수"이고 `total`과 합이 맞지 않을 수 있다. 이건 버그가
아니라 정의다. `cases` 배열도 실행한 것까지만 담는다.

## `TIME_LIMIT`에서도 case를 특정하는 이유

이게 이 ADR의 핵심이고, [ADR-0004](0004-reviewer-invocation-requires-case-evidence.md)의
직접적인 요구다.

Reviewer 출력의 `failedCaseRefs`는 `minItems: 1`이다. Reviewer를 호출하는 판정에서
`failedCaseId`가 없으면 **모델이 근거를 만들 수 없고**, 스키마 검증에서 매번 거부되거나
없는 id를 지어내게 된다. 근거 강제가 근거 위조로 뒤집히는 지점이다.

시간 초과는 "어느 case에서 터졌는지"를 기록하기 쉬운 자리가 아니다 — 프로세스를 죽이는
쪽에 관심이 쏠려 case id를 흘리기 쉽다. 그래서 계약이 아니라 **테스트로** 못박았다.
`judge/tests/test_judge.py`의 `VERDICTS` 세 번째 열이 그것이며, `TIME_LIMIT` /
`MEMORY_LIMIT` / `OUTPUT_LIMIT`에서 `failedCaseId`가 `null`이면 실패한다.

## 함께 확정한 실행 결정

**컨테이너는 Submission당 1개, 그 안에서 case를 순회한다** (Addendum §61)

case마다 컨테이너를 만들면 기동 비용이 실행 시간을 압도한다. 실측으로 case 하나당
30~80ms인데 컨테이너 기동은 그보다 한 자릿수 크다.

**채점 하네스는 이미지에 굽는다. 마운트에 두지 않는다**

마운트는 read-only지만, 신뢰 경계를 마운트 옵션 하나에 의존하지 않는다. 사용자 코드가
닿을 수 있는 자리에 채점 로직을 두지 않는 편이 단순하다.

**하네스는 사용자 코드를 import하지 않는다**

별도 프로세스로 띄우고 stdin/stdout으로만 통신한다. import하면 사용자 코드가 하네스의
메모리 공간에서 돌아 결과 JSON 자체를 조작할 수 있다.

**출력은 파이프가 아니라 tmpfs의 파일로 받는다**

구현 중에 실제로 밟은 문제다. 파이프로 받으면 하네스가 출력을 메모리에 쌓고, 무한 출력하는
코드 하나가 컨테이너를 OOM으로 끌어내린다. 그러면 **`OUTPUT_LIMIT`이 `MEMORY_LIMIT`으로
둔갑한다.** 파일로 받으면 `RLIMIT_FSIZE`가 커널 수준에서 끊는다.

오분류가 그냥 라벨 하나로 끝나지 않는 이유는, 그 라벨이 Reviewer 입력과 Skill Evidence로
흘러가기 때문이다. 잘못된 판정은 잘못된 학습 경로가 된다.

## 결과

- 부분 점수를 UI에 노출할 수 없다. 필요해지면 "전부 돌리는 모드"를 문제 메타에
  플래그로 두는 편이 낫다 — 지금 필요하지 않은 것을 위해 매 제출의 비용을 올리지 않는다.
- `passed`/`total` 불일치를 프론트가 "5/8 통과"로 오해하지 않도록 계약 description에
  명시했다.
- 무한 루프 제출의 최악 소요가 `case 수 × timeout`에서 `1 × timeout`으로 줄었다.

## 관련

- [ADR-0004](0004-reviewer-invocation-requires-case-evidence.md) — `failedCaseId`를 요구하는 쪽
- [ADR-0001](0001-llm-analyzes-system-decides.md) — Judge는 AI 판단을 하지 않는다
