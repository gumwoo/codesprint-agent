# ADR-0020 · 돌려 보는 것과 답을 내는 것은 다르다

- 상태: 채택
- 날짜: 2026-09-08
- 정본 근거: PRD §117, Addendum §38,
  [ADR-0006](0006-expected-output-never-enters-sandbox.md),
  [ADR-0013](0013-judging-happens-outside-the-request.md),
  [ADR-0009](0009-mastery-is-recomputed-from-evidence.md)

## 맥락

코드를 시험해 보려면 제출해야 했다. 그런데 제출은 Evidence 를 만들고 mastery 를
움직인다. **돌려 보는 것과 답을 내는 것이 같은 일이었다.**

값이 어떻게 매겨지는지를 생각하면 이건 단순한 불편이 아니다. Evidence 는
append-only 이고 mastery 는 거기서 재계산된다(ADR-0009). 즉 `print(1)` 을 넣고
돌려 본 한 번이 **영구히 남아 그 Skill 의 점수를 끌어내린다.** 사용자는 시험해 볼수록
못하는 사람이 된다.

초기 진단(ADR-0018)이 붙으면서 더 아파졌다. 진단이 준 첫 문제는 시작점을 정하는
문제인데, 그것을 시험 삼아 돌려볼 수 없다.

## 결정

`POST /api/problems/{code}/run` 을 만든다. **제출이 아니다.**

```
제출  채점 -> Evidence -> mastery -> 다음 행동
실행  채점만. 그 자리에서 보고 버린다
```

### 같은 큐를 쓴다

큐의 정본은 `judge_jobs` 하나다(ADR-0013). 표를 하나 더 만들면 리스 · 재시도 ·
fencing 을 두 벌 유지하게 되고, **언어 경계가 둘이 된다.**

대신 실행이 학습 상태에 닿을 수 없다는 것을 **행 모양으로 보장한다.**

```sql
CHECK ((kind = 'SUBMIT' AND submission_id IS NOT NULL)
    OR (kind = 'RUN'    AND submission_id IS NULL))
```

결과를 반영하는 경로는 `submission_id` 로만 이어진다. RUN 행에 그 값이 들어갈 수
없으므로, **코드가 실수해도 갈 곳이 없다.** Poller 의 `kind = 'SUBMIT'` 조건은 그 위에
덧댄 것이지 그것 하나에 기대지 않는다 — 조건은 지워질 수 있고, 지워지면 실행 한 번이
Evidence 가 되어 mastery 를 깎는다.

### 공개 case 만 돈다

숨은 case 를 돌리면 사용자는 제출하지 않고도 채점 결과를 얻는다. 그건 실행이 아니라
제출이며, 게다가 점수가 남지 않는 제출이다.

하네스의 `--samples-only` 는 **두 가지를 한 플래그로 묶는다** — 공개 case 만 돌리고,
그때만 출력을 돌려준다. 나누면 "숨은 case 에서 출력을 돌려주는" 조합이 만들어질 수
있다. 조합 자체를 없앤다.

공개 case 의 입력과 기대 출력을 함께 보여주는 것은 새로 드러나는 것이 없다 — 이미
문제 본문에 있는 값이다. `expectedOutput` 이 컨테이너로 들어가지 않는다는 규칙
(ADR-0006)은 그대로다. 비교는 여전히 호스트가 한다.

### 응답에 다음 행동이 없다

실행에 `nextAction` 을 붙이면 사용자는 돌려 보기만 해도 학습 경로가 움직이는 것으로
읽는다. 실제로 움직이지 않으므로 **화면이 거짓을 말하게 된다.**

## 결과

- 시험해 보는 것이 점수를 깎지 않는다. 진단이 준 첫 문제도 돌려볼 수 있다
- 실행 결과는 저장되지만 학습 이력이 아니다. `judge_jobs` 행 하나로 끝나고,
  `submissions` · `skill_evidence` · `user_skills` 어디에도 남지 않는다
- 결과는 **낸 사람만 본다.** 거기에는 그 사람이 쓴 코드의 출력이 담긴다. 인증이
  없어 화면이 `userId` 를 보내는데(ADR-0017), 인증이 붙으면 그 파라미터는 사라진다

### 남는 위험

- **실행 job 이 큐를 나눠 쓴다.** 실행이 몰리면 채점이 밀린다. 지금은 만든 사람이
  자기 PC 에서 돌리므로 미룬다 — 나눠야 할 때는 우선순위 컬럼이지 표가 아니다
- 실행 job 은 지워지지 않고 쌓인다. 보존 정책이 아직 없다
- 사용자가 입력을 직접 넣어 돌리는 것은 아직 없다. 공개 예제만 돈다

## 관련

- [ADR-0013](0013-judging-happens-outside-the-request.md) — 큐가 하나인 이유
- [ADR-0006](0006-expected-output-never-enters-sandbox.md) — 비교는 호스트가 한다
- [ADR-0009](0009-mastery-is-recomputed-from-evidence.md) — 한 번의 실행이 영구히 남는 이유
- [ADR-0018](0018-the-diagnostic-orders-problems-it-does-not-score.md) — 진단이 준 첫 문제
