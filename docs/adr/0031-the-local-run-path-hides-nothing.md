# ADR-0031 · 내 PC 실행 경로는 아무것도 숨기지 않는다

- 상태: 채택
- 날짜: 2026-09-15
- 정본 근거: [ADR-0005](0005-judge-stops-at-first-failure.md),
  [ADR-0013](0013-judging-happens-outside-the-request.md),
  [ADR-0026](0026-hint-ladder-is-problem-data.md),
  [ADR-0027](0027-hint-level-is-observed-not-reported.md)

## 맥락

슬라이스 1 의 기능은 다 들어갔는데, **만든 사람이 자기 PC 에서 켜서 눌러 볼 길이
없었다.** compose 파일도 실행 스크립트도 없었고, README 에는 테스트 명령만 있었다.

그리고 화면에서 실제 채점까지 이어 본 적이 한 번도 없었다. 테스트는 Worker 를
흉내 냈고(ADR-0013), 브라우저로 힌트를 눌러 볼 때는 Worker 를 띄우지 않아 제출이
`PENDING` 에서 멈췄다.

## 결정

### 떠야 하는 것은 넷이고, DB 만 컨테이너다

```
PostgreSQL     컨테이너 (compose.yaml)
백엔드 + 화면  호스트  java -jar
Judge Worker   호스트  python judge/worker.py
Reviewer       호스트  claude CLI (선택)
```

백엔드와 Worker 를 컨테이너에 넣지 않는 이유는 **둘 다 호스트의 무언가를 불러야 해서**다.
Reviewer 는 로그인된 `claude` CLI 를 쓰고(API 키를 받지 않는다), Worker 는 채점
샌드박스를 띄울 `docker` 를 쓴다. 컨테이너 안에서 둘을 부르려면 로그인 세션과 docker
소켓을 넘겨야 하고, 그것은 샌드박스 격리를 스스로 느슨하게 하는 일이다.

### 백엔드와 Worker 는 각자 한 터미널을 차지한다

`scripts/local.sh` 는 둘을 백그라운드로 숨기지 않는다. 둘 다 멈추지 않고 도는
프로세스라, 한 스크립트가 숨기면 **하나가 죽었을 때 화면은 "채점 중" 에서 멈추고 그
이유는 어디에도 보이지 않는다.** Worker 가 없으면 제출은 영원히 `PENDING` 이다 — 이
저장소에서 가장 헷갈리기 쉬운 고장이라, 보이는 자리에 둔다.

### 기본 포트를 비켜 둔다

화면은 `18080`, DB 는 `55440` 이다. 처음에 기본값 8080 으로 띄웠더니 **다른 프로그램이
이미 8080 을 쓰고 있었다.** 앱은 "포트 사용 중" 으로 죽고, 브라우저는 엉뚱한 서버의
404 를 보여줬다.

`scripts/local.sh check` 가 포트 충돌을 먼저 본다. 이 검사도 **처음엔 일하지 않았다** —
`set -o pipefail` 아래서 `netstat | grep -q` 로 쓰니, grep 이 먼저 끝날 때 netstat 이
SIGPIPE 로 죽어 조건 전체가 거짓이 됐다. 사용 중인 포트를 "비어 있다" 로 통과시켰다.
출력을 먼저 받아 두게 고치고, 실제로 사용 중인 포트에서 `exit 1` 로 멈추는 것을 확인했다.

### 실제로 끝까지 돌려 봤다

스크립트로 넷을 띄우고 화면에서 풀었다.

```
진단 -> P05_SHORTEST_PATH
정답 제출 -> Worker 가 샌드박스로 채점 -> 5초 뒤 ACCEPTED -> RETRY_VARIANT
"다음 단계 보기" -> P09_BFS_VARIANT_A
오답 제출 -> WRONG_ANSWER (1 / 7, 65 ms, 12984 KB) -> 다음 행동
```

`judge_jobs` 에서도 job 이 `DONE`, 시도 1회로 남았다. 흉내가 아니라 실물 채점이다.

## 함께 닫은 것

### 전체 풀이는 두 번 눌러야 열린다

H6 은 되돌릴 수 없다. 열면 그 문제의 다음 제출이 독립 풀이로 세지 않고
(`solutionViewed`), independent 관측값이 0.95 에서 0.10 으로 떨어진다(ADR-0027).
그런데 버튼은 앞 단계 힌트와 같은 자리에 있어서, **연달아 누르다 손이 한 번 더 가는
것으로 끝날 수 있었다.**

첫 번째 누름은 아무것도 열지 않고 무엇이 기록되는지 한 문장으로 말한다. 두 번째에
연다. 브라우저 `confirm` 창을 쓰지 않은 이유는, 결과를 제자리에서 말하는 이 화면의
방식과 맞지 않기 때문이다. 끝 번호는 화면이 6 이라고 가정하지 않고 서버가 준
`topLevel` 을 쓴다. 문제를 옮기면 눌러 둔 상태도 풀린다 — 남으면 다른 문제에서 한
번만 눌러도 열린다.

### 좁은 화면

900px 아래에서는 두 단을 위아래로 쌓는다. 새 색이나 모양은 넣지 않았다 — 한 화면에
가두던 높이 제한을 풀고, 끌 폭이 없는 가운데 핸들을 숨긴 것뿐이다.

### Java 테스트 fixture 도 계약을 지난다

PR #40 에서 테스트가 손으로 적은 채점 결과가 계약을 어겨(`caseId`, `executionMs` 누락)
반영 단계가 `SYSTEM_ERROR` 로 처리됐고, 테스트는 판정 대신 `CONTINUE` 를 받았다.
무엇이 틀렸는지는 말해 주지 않았다.

E2E stub 에는 같은 문을 이미 붙였다(ADR-0025). `JudgeResultFixture` 가 Java 쪽의 같은
문이다 — 쓰기 전에 `judge-result.schema.json` 에 대고 검증한다. 테스트 9개 파일의
12곳이 이 문을 지나고, PR #40 에서 실제로 틀렸던 모양을 막는 것을 테스트가 본다.

### 그 문이 계약의 빈자리를 찾았다

문을 붙이자마자 `RunTest` 4개가 실패했다. **틀린 쪽은 테스트가 아니라 계약이었다.**

제출 전 실행(`kind RUN`)에서 Worker 는 `--samples-only` 로 하네스를 부르고, 하네스는
case 마다 `input` · `expectedOutput` · `stdout` · `stderr` 를 붙인다
(`run_submission.py` 의 `with_output`). 그런데 `judge-result.schema.json` 은 그 필드를
허용하지 않는다. `RunTest` 의 fixture 는 실물을 그대로 옮긴 것이었다.

오래 드러나지 않은 이유도 찾았다. `test_judge.py` 는 **제출 채점 결과만** 계약에 대고
검증했고, 실행 결과는 계약에 대 본 적이 없었다.

필드를 허용해 계약을 느슨하게 풀지 않았다. 제출 채점 결과에 출력이 실리면 hidden
case 의 입력 · 출력이 새는 조합이 생기고, `test_judge.py` 가 이미 그것을 대조군으로
막고 있다. 그래서 **실행 결과용 계약을 따로 뒀다**(`run-judge-result.schema.json`).
출력은 거기서만 required 이고, 제출 채점 계약은 그대로 닫혀 있다. 하네스 테스트도
실행 결과를 새 계약에 대고 검증하게 했다.

### 계약 표

`contracts/README.md` 가 "실제로 검증에 사용하는 계약이다" 라고 단정했는데 23개 중
10개만 적혀 있었다. 나머지를 채우고, 빠지면 `check_curriculum.py` 가 막게 했다.

## 남는 위험

- **Windows 에서 Git Bash 로만 확인했다.** macOS · Linux 에서 `scripts/local.sh` 를 돌려
  보지 않았다. 포트 검사는 `netstat` 출력 형식에 기대므로 가장 먼저 어긋날 자리다
- Reviewer 를 켠 상태로 화면에서 끝까지 돌려 보지는 않았다. 평가 하네스로는 실제 모델을
  재 봤지만(ADR-0029), 화면 경로에서 켠 것은 아니다
- 좁은 화면은 레이아웃이 쌓이는지까지 봤다. 휴대폰에서 실제로 코드를 치는 경험은 이
  화면의 목적이 아니라 다듬지 않았다

## 관련

- [ADR-0013](0013-judging-happens-outside-the-request.md) — Worker 가 따로 도는 이유
- [ADR-0025](0025-browser-race-tests.md) — E2E 쪽의 같은 계약 문
