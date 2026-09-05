# ADR-0008 · 공개 저장소는 fixture를 담고, 문제은행을 담지 않는다

- 상태: 채택
- 날짜: 2026-09-05
- 정본 근거: PRD §154 (외부 문제 연결 시 저작권 원칙) / [ADR-0006](0006-expected-output-never-enters-sandbox.md), [ADR-0007](0007-problems-are-verified-by-a-wrong-solution.md)

## 맥락

[ADR-0006](0006-expected-output-never-enters-sandbox.md)에서 정답이 샌드박스 안으로
들어가지 않도록 신뢰 경계를 다시 그었다. 실행 격리와 별개로 **채점 데이터 기밀성**을
보장한다고 썼다.

그런데 [PR #3](https://github.com/gumwoo/codesprint-agent/pull/3)에서 문제 10개를
넣으면서, 그 문제의 `cases.json`을 **공개 저장소에 그대로 커밋**했다.

```json
{ "id": 2, "type": "MINIMUM", "hidden": true,
  "input": "1\npop\n", "expectedOutput": "-1\n" }
```

`hidden: true`인데 GitHub에서 누구나 읽는다. `reference.py`도 함께 공개돼 있어
풀이까지 보인다.

즉 ADR-0006이 막은 것과 막지 못한 것이 다르다.

```text
사용자 코드 → 샌드박스에서 expectedOutput 접근   막힌다   (실제로 강제됨)
사람       → GitHub 에서 cases.json 열람         안 막힌다
```

두 문장 다 사실이다. 문제는 `hidden`이라는 필드 이름과 ADR-0006의 서술이 후자까지
막는 것처럼 읽힌다는 점이다. **보장하지 않는 것을 보장하는 것처럼 쓴 상태**다.

## 결정

**이 저장소의 문제는 전부 개발 fixture다.** 실제 학습에 쓰는 문제은행은 여기 두지 않는다.

`problem.yaml`에 축을 추가한다.

```yaml
source: DEV_FIXTURE   # 또는 CURATED
```

| | DEV_FIXTURE | CURATED |
| --- | --- | --- |
| 목적 | 제품 구조가 동작하는지 검증 | 실제 학습 |
| Test Case | 저장소에 공개 | 비공개 저장소 / DB |
| reference 풀이 | 저장소에 공개 | 비공개 |
| `hidden` 필드의 뜻 | **UI 노출 여부일 뿐** | UI 노출 + 실제 기밀 |

**공개 저장소에는 `DEV_FIXTURE`만 허용한다.** `check_problems.py`가 강제한다.
누군가 `CURATED` 문제를 커밋하면 CI가 막는다.

```text
Public repo (여기)              Private Problem Store (나중에)
├─ problem.yaml (statement 포함)  ├─ Hidden Test Input / expectedOutput
├─ SAMPLE / hidden case 전부      ├─ production reference solution
├─ reference.py / wrong.py        └─ 저작권 있는 외부 문제 메타
└─ 전부 DEV_FIXTURE
```

## 왜 fixture를 지우지 않는가

기밀이 걱정된다면 문제를 비공개로 옮기는 선택도 있다. 그러지 않는 이유가 있다.

**fixture가 없으면 하네스가 아무것도 검증하지 못한다.** [ADR-0007](0007-problems-are-verified-by-a-wrong-solution.md)의
`wrong.py` 대조, Judge의 판정 8종, 앞으로 붙일 Mastery 단위 테스트가 전부 실제 문제
데이터를 필요로 한다. 공개 저장소에서 CI가 도는 이상 그 데이터도 공개여야 한다.

그리고 이 저장소는 **제품이 아니라 제품의 설계를 보여주는 곳**이다. 문제 10개가
공개된 것이 손해가 아니라, 그 10개로 무엇을 검증하는지가 보이는 것이 이득이다.

## 이름을 바꾸지 않은 이유

`hidden` → `uiHidden` 같은 개명을 검토했지만 하지 않았다. `hidden`은 **학습 UI에서
사용자에게 보여줄지**를 뜻하는 필드이고, 그 의미 자체는 `CURATED` 문제에서도 그대로
유지된다. 달라지는 것은 "그 밖에 기밀성이 함께 보장되는가"이며, 그건 `source`가 답한다.

대신 `test-cases.schema.json`의 `hidden` 설명에 경고를 명시했다 — 이 저장소에서
`hidden`은 기밀성 보장이 아니다.

## 결과

**얻는 것**

- ADR-0006의 주장 범위가 정확해진다. 샌드박스 경계는 실제로 강제되고, 저장소 공개
  여부는 별개의 축이라는 것이 명시된다.
- `CURATED` 문제가 실수로 공개 저장소에 들어오는 것을 CI가 막는다.
- 나중에 문제은행을 붙일 때 어디에 두어야 하는지가 이미 정해져 있다.

**치르는 비용**

- 필드가 하나 늘고, 문제를 추가할 때마다 판단이 필요하다. 지금은 답이 하나뿐이라
  (`DEV_FIXTURE`) 부담이 없지만, `CURATED`가 생기면 두 저장소를 오가게 된다.
- 이 저장소의 10문제는 실서비스에 쓸 수 없다. 정답이 공개돼 있으니 학습 측정이
  성립하지 않는다. 문제은행은 처음부터 다시 만들어야 한다.

## 남은 것

- **비공개 문제 저장소의 형태를 아직 정하지 않았다.** DB 테이블(Addendum §104~106)이
  자연스럽지만, 그때도 `problem.schema.json` / `test-cases.schema.json`을 그대로
  쓸 수 있게 유지한다. 형식이 갈라지면 fixture로 검증한 것이 실제 문제에 적용되지 않는다.
- **외부 플랫폼 문제 연결**(PRD §154)은 또 다른 축이다. 문제 전문과 테스트케이스를
  저장하지 않고 ID·링크·태그만 다루는 경우이며, `source`에 값이 하나 더 필요해진다.

## 관련

- [ADR-0006](0006-expected-output-never-enters-sandbox.md) — 샌드박스 경계. 이 ADR이 그 주장 범위를 좁힌다
- [ADR-0007](0007-problems-are-verified-by-a-wrong-solution.md) — fixture가 필요한 이유
