# ADR-0006 · 정답(expectedOutput)은 샌드박스 안으로 들어가지 않는다

- 상태: 채택
- 날짜: 2026-09-05
- 정본 근거: Addendum §60~62, §66, §87 / [ADR-0005](0005-judge-stops-at-first-failure.md)

## 맥락

첫 구현은 Addendum §60의 문장을 그대로 따랐다.

```text
Host temp directory
→ solution.py 생성
→ read-only bind mount
```

그래서 `solution.py`와 `job.json`을 같은 디렉터리에 두고 `/job`에 read-only로 마운트했다.
컨테이너 안의 하네스가 `job.json`을 읽어 case를 순회하고, **정답과 비교까지** 했다.

문제는 `job.json` 안에 `expectedOutput`이 전부 들어 있다는 것이다.
그리고 **read-only는 수정을 막을 뿐 읽기를 막지 않는다.**

실제로 뚫린다. 아래 코드는 알고리즘을 한 줄도 풀지 않는다.

```python
import json, sys
data = sys.stdin.read()
job = json.load(open("/job/job.json"))
for case in job["cases"]:
    if case["input"] == data:
        sys.stdout.write(case["expectedOutput"])
        break
```

```text
판정: ACCEPTED (4/4)
```

격리 테스트 8종은 이걸 하나도 잡지 못했다. 그 테스트들은 **쓰기**만 확인하고 있었기
때문이다 — `open('/job/job.json', 'w')`가 실패하는지는 보지만, `'r'`로 여는 것은
아무도 보지 않았다.

```text
Test Case 변조 방지   O
Test Case 유출 방지   X
```

## 결정

**정답은 신뢰 경계를 넘지 않는다.** 컨테이너에 들어가는 것은 제출 코드와 현재 case의
input뿐이다.

```text
호스트 (신뢰)                          컨테이너 (신뢰하지 않음)
─────────────                          ────────────────────────
job.json 전체
expectedOutput           ── input ──>  solution.py
정답 비교                              현재 case 만 실행
판정 조립                <── stdout ──  사용자 출력
```

하네스의 역할이 바뀐다. **채점하지 않고 실행만 한다.**

- `/job` 마운트에는 `solution.py` 하나만 둔다
- case input은 stdin으로 한 건씩 흘려보낸다
- 하네스는 사용자 stdout과 실행 결과(`OK` / `TIME_LIMIT` / `RUNTIME_ERROR` / …)를 돌려준다
- 정답 비교와 `WRONG_ANSWER` 판정은 호스트가 한다

프로토콜은 줄 단위 JSON이고 한 번에 한 case씩 주고받는다. 한쪽이 쓰는 동안 다른 쪽은
읽고 있으므로 파이프가 막히지 않는다.

## 컨테이너를 case마다 새로 만들지 않는 이유

정답을 빼내는 가장 단순한 방법은 case마다 컨테이너를 새로 띄우고 input을 하나씩만
주는 것이다. 그러면 스트리밍 프로토콜이 필요 없다.

그런데 [ADR-0005](0005-judge-stops-at-first-failure.md)가 "컨테이너는 제출당 1개"를
Addendum §61 근거로 정했다. 기동 비용이 실행 시간을 압도하기 때문이다 — 실측으로 case
하나가 30~80ms인데 컨테이너 기동은 그보다 한 자릿수 크다. case 10개면 판정 시간이
수 배가 된다.

스트리밍은 그 결정을 유지하면서 기밀성을 얻는 방법이다. 대신 프로토콜이라는 복잡도가
생기고, 하네스가 죽었을 때의 처리(응답이 없으면 `SYSTEM_ERROR`)가 필요해진다.

## 부수 효과 — 정규화가 한 곳으로 모였다

출력 비교가 호스트로 옮겨오면서 `normalize()`도 따라왔다. 결과적으로 **정답 판정 로직
전체가 신뢰 경계 안쪽 한 곳에 모였다.** 컨테이너 쪽 코드가 판정에 영향을 줄 방법이
"stdout에 무엇을 쓰는가" 하나로 줄었다.

## 결과

**얻는 것**

- 정답표를 읽어 되뱉는 제출이 불가능해진다. 실행 격리와 별개의 축인 **채점 데이터
  기밀성**이 신뢰 경계로 보장된다.
- Hidden Test의 의미가 유지된다. 이것이 없으면 "Hidden"이 이름뿐이다.
- 판정 로직이 한곳에 모여 감사하기 쉬워진다.

**치르는 비용**

- 호스트-컨테이너 프로토콜이 생겼다. 하네스가 죽거나 프로토콜을 어기면 `SYSTEM_ERROR`로
  떨어뜨려야 한다.
- 사용자 stdout이 파이프를 통해 호스트로 온다. 하네스의 `RLIMIT_FSIZE` 외에 호스트에도
  읽기 상한을 뒀다.
- case 하나마다 왕복이 생긴다. 실측 영향은 case당 1ms 미만이라 무시할 수준이다.

## 강제 방법

계약으로는 막을 수 없다. **테스트로 막는다.**

`judge/tests/test_judge.py`의 `CONFIDENTIALITY` 3종이 컨테이너 안을 직접 훑는다.

| 프로브 | 확인하는 것 |
| --- | --- |
| 정답표가 컨테이너 안에 없다 | `/job`, `/tmp` 아래 `cases` 키를 가진 JSON이 없다 |
| `expectedOutput` 키가 어디에도 없다 | 마운트 전체 + 환경변수에 그 문자열이 없다 |
| 마운트에는 제출 코드만 있다 | `os.listdir('/job') == ['solution.py']` |

그리고 `judge/fixtures/sol-answer-leak.py`가 실제 공격 코드로 남아 있다. 신뢰 경계가
무너지면 이 fixture가 `ACCEPTED`를 받으면서 테스트가 깨진다.

**새 제한을 추가할 때는 그것을 뚫으려는 케이스를 함께 넣는다** — 저장소의 다른 하네스와
같은 규율이다. 이번 건은 그 규율을 지켰는데도 축 하나(읽기)를 통째로 빠뜨려서 생겼다.

## 남은 것

- **input 자체는 여전히 컨테이너에 들어간다.** 현재 case의 input 하나뿐이며, 그것 없이는
  코드를 실행할 수 없다. input만으로는 정답을 알 수 없으므로 허용한다. 단, 문제 자체가
  input에 답을 담고 있다면(예: "입력을 그대로 출력하라") 이 가정이 약해진다 —
  문제 설계 단계의 고려사항이다.
- **`SYSTEM_ERROR`로 새는 정보.** 하네스 오류 메시지에 호스트 정보가 섞이지 않도록
  `sanitize_stderr`가 경로를 지우지만, 앞으로 메시지를 늘릴 때 같이 봐야 한다.

## 관련

- [ADR-0005](0005-judge-stops-at-first-failure.md) — 컨테이너 제출당 1개를 정한 쪽
- [ADR-0001](0001-llm-analyzes-system-decides.md) — 판정은 시스템이 한다
