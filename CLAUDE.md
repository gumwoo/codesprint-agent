# CodeSprint Agent

코딩테스트 문제를 대신 풀어주는 AI가 아니라, **사용자가 무엇을 모르는지 찾아내고 그
Skill을 독립 풀이 가능한 상태까지 가장 짧은 경로로 만드는 학습 운영 Agent**다.

## 절대 규칙 — LLM과 시스템의 경계

근거: [ADR-0001](docs/adr/0001-llm-analyzes-system-decides.md),
[ADR-0002](docs/adr/0002-next-action-decided-by-rule-engine.md)

| LLM이 하는 것 | 시스템이 하는 것 |
| --- | --- |
| 개념 설명 · 힌트 · 문제 변형 | AC/WA 판정, 실행 시간, 메모리 (Judge) |
| 오답 원인 **후보** 도출 | Mistake **확정** 여부 (Rule) |
| 분석의 confidence | mastery / confidence **계산** |
| 코드 의미 분석 | Skill status 전환 |
| | 다음 학습 행동 (Decision Engine) |

**점수와 액션을 LLM에게 묻지 않는다.** 이 경계는 프롬프트가 아니라 스키마로 강제한다.
`contracts/*.llm.schema.json`에 `score` / `mastery` / `nextAction`이 들어가면 CI가 막는다.

**Reviewer는 실패 Test Case가 있을 때만 호출한다**([ADR-0004](docs/adr/0004-reviewer-invocation-requires-case-evidence.md)).
`ACCEPTED` / `COMPILE_ERROR` / `SYSTEM_ERROR`에서는 호출하지 않는다. 문법 오류는
시스템이 `SYNTAX_ERROR`를 결정론적으로 부여한다 — 그 code는 LLM enum에 없다.

대화 중에 이 저장소의 도메인을 다룰 때도 같은 규칙을 지킨다. mastery 값을 어림으로
말하지 않고, 산식(Addendum PART I)을 적용해 계산한다.

## 절대 규칙 — 커리큘럼은 문서가 아니라 데이터

`curriculum/*.yaml`은 실행되고 검증되는 파일이다. 문서가 아니다.

- Skill ID는 `UPPER_SNAKE_CASE` 하나뿐이다 ([ADR-0003](docs/adr/0003-skill-id-canonical-uppercase.md)).
  PRD 본문의 소문자 표기(`bfs_basic`)는 **폐기됐다.** 발견하면 변환한다.
- Skill / Mistake / Technique를 섞지 않는다.
  `GRID_BOUNDARY_CHECK`는 Skill, `BOUNDARY_CHECK`는 Mistake다.
- `skills.yaml`에는 **검증된 Skill만** 넣는다. 도메인 레지스트리(45개 알고리즘 도메인
  + Programming Foundations = 46개 entry)는 `domains.yaml`이 갖는다.
- `mistakes.yaml`의 `assigned_by`가 `REVIEWER`인 것만 LLM enum에 들어간다.
- **null을 허용하는 필드는 required여야 한다.** 생략은 "모른다", null은 "확인했고 없었다"다.

## 검사를 고칠 때

`tools/check_curriculum.py`에 새 불변식을 추가하면,
`tools/meta_test_curriculum.py`에 **그것을 깨뜨리는 케이스도 함께** 추가한다.

검사가 통과하는 것과 검사가 일하는 것은 다르다. 아무것도 안 하는 검사도 통과한다.

```bash
python tools/check_curriculum.py      # 데이터/계약이 맞는가
python tools/meta_test_curriculum.py  # 검사가 실제로 잡는가
```

## 사용자 제출 코드

`judge/submissions/` 아래는 **신뢰할 수 없는 입력**이다. 판정 대상이지 실행 대상이 아니다.
읽거나 실행하지 않는다. `.gitignore`와 `.claude/settings.json`이 이중으로 막는다.

`judge/fixtures/`는 다르다 — 우리가 만든 테스트 자산이라 읽고 고쳐도 된다.

## 샌드박스를 건드릴 때

`judge/run_submission.py`의 `DOCKER_LIMITS` / `MOUNT_MODE`에서 옵션을 빼면
`judge/tests/test_judge.py`가 실패한다. 각 옵션이 무엇을 막는지 주석으로 적혀 있으니
지우기 전에 읽는다. 새 제한을 추가하면 **그것을 뚫으려는 격리 케이스도 함께** 넣는다.

격리 케이스에는 대조군이 있다. 제한을 걷어냈을 때도 실패하면 그 테스트는 아무것도
검증하지 못하는 것이라 실패로 처리된다.

**정답(`expectedOutput`)을 컨테이너 안으로 넣지 않는다**([ADR-0006](docs/adr/0006-expected-output-never-enters-sandbox.md)).
read-only 마운트는 수정을 막을 뿐 읽기를 막지 않는다. 하네스는 실행만 하고,
정답 비교는 호스트가 한다. 격리(쓰기)와 기밀성(읽기)은 다른 축이므로 따로 검사한다.

## 문제를 추가할 때

`problems/<CODE>/` 에 네 파일을 함께 넣는다 — `problem.yaml`, `cases.json`,
`reference.py`, **`wrong.py`**.

`wrong.py` 는 그 문제에서 실제로 자주 나오는 실수를 담은 오답이며, CI 가 그것이
**실제로 걸리는지** 확인한다([ADR-0007](docs/adr/0007-problems-are-verified-by-a-wrong-solution.md)).
정답이 통과하는 것만 보면 아무것도 거르지 못하는 Test Case 집합도 통과한다.

`negativeControl` 에 **무엇을 심었고 어떤 판정이 나와야 하는지**를 적는다.
"실패했는가" 가 아니라 "의도한 이유로 실패했는가" 를 확인한다.

`auto_drill` 이 켜진 Mistake 는 그 `target_skill` 을 PRIMARY 로 갖는 `MICRO_DRILL`
문제가 반드시 있어야 한다. 없으면 Decision Engine 이 갈 곳 없는 액션을 낸다.

**이 저장소의 문제는 전부 `source: DEV_FIXTURE` 다**([ADR-0008](docs/adr/0008-public-repo-holds-fixtures-not-the-problem-bank.md)).
공개 저장소이므로 Test Case 와 정답이 그대로 보인다 — `hidden` 은 UI 노출 여부일 뿐
기밀성 보장이 아니다. 실서비스 문제은행(`CURATED`)은 여기 두지 않으며 CI 가 막는다.

```bash
python tools/check_problems.py      # 참조 무결성 (Docker 불필요)
python tools/meta_test_problems.py  # 그 검사가 실제로 잡는가
python tools/verify_problems.py     # 실제 채점 (Docker 필요)
```

## 점수를 계산할 때

`learning/` 은 Addendum PART I 을 그대로 옮긴 것이다. 값을 임의로 고치지 않는다 —
힌트 단계별 관측값, EMA alpha, confidence 가중치가 전부 정본 문서에 있다.

**mastery 는 저장된 값이 아니라 Evidence 로부터 재계산되는 파생값이다**
([ADR-0009](docs/adr/0009-mastery-is-recomputed-from-evidence.md)).
EMA 는 되돌릴 수 없으므로, 저장된 값이 정본이면 산식을 고쳐도 과거에 적용할 수 없다.

세 가지를 구분한다 — `null`(아직 안 봤다) / `0.0`(보았고 못한다) /
`mastery` vs `confidence`(얼마나 잘하는가 vs 그 판단을 얼마나 믿는가).

```bash
python learning/tests/test_mastery.py
```

## 산식을 고칠 때는 세 곳을 함께 고친다

Mastery 는 두 번 구현돼 있다 — `learning/`(Python oracle, 실행 가능한 명세)과
`backend/`(Java production). 둘이 같은 golden fixture 를 읽고 같은 값을 내야 한다
([ADR-0010](docs/adr/0010-java-implementation-is-checked-against-the-python-oracle.md)).

```bash
# 1. learning/mastery.py 또는 learning/evidence.py
# 2. backend/src/main/java/.../MasteryCalculator.java (계산)
#    backend/src/main/java/.../SubmissionEvidenceFactory.java (관측 -> Evidence)
# 3. python tools/gen_mastery_golden.py --write
#    python tools/gen_evidence_golden.py --write
```

golden 이 둘인 이유는 고정하는 것이 다르기 때문이다. `tests/golden/` 은 **이미
만들어진 Evidence 로부터의 계산**을, `tests/golden/evidence/` 는 **제출을 Evidence 로
옮기는 매핑**을 고정한다. 앞단이 갈리면 계산이 아무리 정확해도 다른 값이 나온다.

한쪽만 고치면 CI 가 막는다. golden 재생성은 기본 동작이 아니라 `--write` 를 붙여야 한다 —
값이 조용히 따라 바뀌면 golden 이 아무것도 고정하지 못한다.

**커리큘럼 복사본을 만들지 않는다**([ADR-0012](docs/adr/0012-curriculum-is-packaged-from-one-source.md)).
백엔드는 빌드 시점에 `curriculum/` 을 가져오며, 자동 드릴 대상 같은 값을 코드에 적지 않고
데이터에서 읽는다. Java 는 커리큘럼을 검증하지 않는다 — 그건 `tools/` 의 몫이다.

**언어 경계는 ADR-0011 이다.** Java 는 애플리케이션(API·영속성·산식·Decision),
Python 은 샌드박스와 하네스. 다음 기능을 Python 으로 더 만들지 않는다.

## 현재 상태

Vertical Slice 1 진행 중. 커리큘럼 데이터, 계약, 하네스, Judge/Sandbox, 검증된 문제
10개, Mastery 산식, 백엔드(Spring Boot · PostgreSQL · Flyway), Decision Engine,
제출 API, Judge Worker + 큐, 그리고 Reviewer 오케스트레이션까지 있다.
**LLM 어댑터와 프론트는 아직 없다** - `ReviewerPort` 구현이 없으면 분석 없이 진행한다.

제출 하나가 지나는 길:

```
POST /api/problems/{code}/submit   접수하고 큐에 넣는다 (202)
judge/worker.py                    꺼내 샌드박스에서 채점하고 결과를 큐에 쓴다
JudgeResultPoller                  Evidence -> mastery 재계산 -> 다음 행동
GET  /api/submissions/{id}         결과를 확인한다
```

**채점은 요청 안에서 하지 않는다**([ADR-0013](docs/adr/0013-judging-happens-outside-the-request.md)).
큐의 정본은 `judge_jobs` 테이블이고, 그 행 하나가 언어 경계다 - Java 가 쓰고 Python 이
읽는다. 컬럼을 늘릴 때는 `contracts/judge-job.schema.json` 과 Worker 를 함께 고친다.

**Worker 는 학습 상태를 건드리지 않는다.** Evidence · mastery · 다음 행동은 전부 Java 가
결과를 반영할 때 만든다. 두 언어가 같은 테이블을 고치면 어느 쪽이 정본인지 알 수 없다.

## Reviewer 를 다룰 때

**Reviewer 가 낸 Mistake 는 확정되기 전까지 주장일 뿐이다**
([ADR-0014](docs/adr/0014-reviewer-output-is-a-claim-until-judge-evidence-agrees.md)).
확정은 시스템이 하고, 그 규칙은 Addendum 19·21 이다.

```
confidence < 0.60          LOGGED_ONLY   기록만
0.60 ~ 0.80                POSSIBLE      자동 드릴 금지
0.80 ~ 0.90                PROBABLE
A. c >= 0.90 + Judge 가 실패시킨 case 인용   CONFIRMED
B. c >= 0.80 + 최근 3문제에서 2회 이상       CONFIRMED
```

**확신만으로 확정하지 않는다.** confidence 는 LLM 이 스스로 매기므로 그것만 보면
순환이다. 그리고 검증(`ReviewerOutputValidator`)을 통과하지 못한 분석은 **통째로
버린다** - 부분적으로 맞는 절반을 살리려다 잘못된 절반이 학습 경로에 들어간다.

확정되지 않은 탐지도 지우지 않는다. 재발(§21-B)을 세려면 남아 있어야 하고, 나중에
Reviewer 정확도를 재는 라벨이 된다.

슬라이스 1 범위: Python 3.12 + BFS Grid 계열 8개 Skill + Mistake 2종 자동 드릴.
정본은 [docs/_archive/](docs/_archive/) 의 Addendum PART III.

## 문서

- `docs/adr/` — 결정과 그 이유. **새 결정을 하면 여기에 남긴다.**
- `docs/_archive/` — 원본 PRD/Addendum. 분해 전까지 여기가 정본이다.
- `curriculum/README.md` — 데이터 파일과 정본 문서의 매핑
