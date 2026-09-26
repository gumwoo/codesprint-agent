# CodeSprint Agent

> 코딩테스트 문제를 대신 풀어주는 AI가 아니라, 사용자가 **무엇을 모르는지 찾아내고
> 그 Skill을 독립 풀이 가능한 상태까지 가장 짧은 경로로 만드는** 학습 운영 Agent.

기존에는 학습자가 직접 하던 판단 — 무엇을 공부할지, 지금 이 문제를 풀어도 되는지,
틀린 이유가 개념인지 구현인지, 언제 복습할지, 시험이 임박했을 때 무엇을 버릴지 —
을 Agent와 Rule Engine이 대신 수행한다.

## Adaptive Learning Loop

```text
진단 → Skill 상태 → Planner → 개념/문제 → 사용자 코드
  ↑                                            ↓
  └── 다음 학습 행동 ← Decision ← Mastery ← Reviewer ← Judge
```

## 설계의 중심 — LLM은 분석하고, 시스템이 판정한다

| LLM | 시스템 |
| --- | --- |
| 개념 설명 · 힌트 · 문제 변형 | AC/WA 판정, 실행 시간, 메모리 (Judge) |
| 오답 원인 **후보** 도출 | Mistake **확정** 여부 (Rule) |
| 분석의 confidence | mastery / confidence **계산** |
| 코드 의미 분석 | 다음 학습 행동 (Decision Engine) |

이 경계를 **프롬프트가 아니라 스키마로 강제한다.** `contracts/*.llm.schema.json`에는
`score` / `mastery` / `nextAction` 필드가 없다. 물어보지 않으므로 만들어낼 수 없다.

근거: [ADR-0001](docs/adr/0001-llm-analyzes-system-decides.md) ·
[ADR-0002](docs/adr/0002-next-action-decided-by-rule-engine.md)

## 저장소 구조

```text
curriculum/    Skill Graph — 문서가 아니라 CI가 검증하는 데이터
contracts/     API · LLM · Judge 계약 (JSON Schema) — 전부 contracts/README.md 표에 있다
judge/         사용자 코드를 실행하는 샌드박스와 채점 하네스 + Judge Worker
problems/      검증된 문제 96개 (전부 개발 fixture — ADR-0008)
learning/      Mastery 산식의 실행 가능한 명세 (Python oracle)
backend/       Spring Boot · PostgreSQL · Decision Engine · API · 화면(static/)
reviewer/      Reviewer 프롬프트 (파일 이름이 버전이다)
e2e/           실제 브라우저로 보는 화면 비동기 순서 검사 (Playwright)
tests/         golden fixture + Reviewer 평가 케이스
tools/         계약 · 문제 데이터 검사 + 메타테스트
scripts/       내 PC 에서 끝까지 띄우는 스크립트 (+ 문제 초안 생성 · 채택)
generator/     문제 초안 생성기 프롬프트 (파일 이름이 버전이다)
generated/     생성된 초안 · 채택 기록 · 거절 사유
docs/adr/      결정과 그 이유
docs/_archive/ 원본 PRD / Implementation Spec (현재 정본)
```

## 내 PC 에서 실행

배포하지 않는다. 만든 사람이 자기 PC 에서 돌린다.

**필요한 것:** Docker Desktop(켜진 상태) · Java 17+ · Python 3.12+ ·
`pip install -r requirements-dev.txt`. Claude CLI 는 선택이다 — 없으면 오답 분석만
빠지고 판정 · mastery · 다음 행동은 그대로 돈다.

```bash
scripts/local.sh check      # 필요한 것이 다 있는가 (포트 충돌도 본다)
scripts/local.sh db         # PostgreSQL (localhost:55440)
scripts/local.sh build      # 샌드박스 이미지 + 백엔드 jar
scripts/local.sh backend    # 터미널 1 — 화면 http://localhost:18080
scripts/local.sh worker     # 터미널 2 — 채점
```

**떠야 하는 것이 넷이고, 한 스크립트가 숨기지 않는다.**

| | 어디서 | 없으면 |
| --- | --- | --- |
| PostgreSQL | 컨테이너 (`compose.yaml`) | 백엔드가 뜨지 않는다 |
| 백엔드 + 화면 | 호스트 `java -jar` | — |
| Judge Worker | 호스트 `python judge/worker.py` | **제출이 영원히 "채점 중" 이다** |
| Reviewer (선택) | 호스트 `claude` CLI | 오답 원인 분석만 빠진다 |

백엔드와 Worker 가 컨테이너가 아닌 이유는 둘 다 호스트의 무언가를 불러야 해서다 —
Reviewer 는 로그인된 `claude` CLI 를, Worker 는 채점 샌드박스를 띄울 `docker` 를.
그리고 둘은 **각자 한 터미널을 차지한다.** 백그라운드로 숨기면 하나가 죽었을 때
화면은 멈춘 것처럼 보이고 이유는 어디에도 남지 않는다.

Reviewer 까지 켜려면:

```bash
CODESPRINT_REVIEWER_ENABLED=true scripts/local.sh backend
```

포트는 `PORT=` 와 `CODESPRINT_DB_PORT=` 로 바꾼다. 8080 · 5432 를 쓰지 않는 이유는
개발 PC 에 흔히 다른 서버가 이미 떠 있기 때문이다 — 실제로 부딪혔다.

## 문제 만들기 — 에이전트가 초안을, 시스템이 채택을

문제는 LLM 이 **초안**으로 만들고, 채택 검사를 통과한 것만 문제은행에 들어간다
([ADR-0032](docs/adr/0032-the-agent-drafts-the-system-adopts.md)).

```bash
scripts/local.sh generate PYTHON_LIST_BASIC 2      # 초안 2개 (Claude CLI)
scripts/local.sh adopt generated/drafts/*.json     # 채택 검사 (Docker)
```

**기대 출력은 LLM 에게 받지 않는다.** 시스템이 정답을 실행해서 만든다. 그러면 "정답이
통과한다" 는 아무것도 증명하지 못하므로, 초안은 **다른 방식의 풀이 둘**을 내고 무작위
입력 30개와 경계 입력 전부에서 **답이 같아야** 채택된다.

```text
계약 → 참조 → 중복 → 입력 생성기 → 교차 검증 → Skill 측정 → 문제 데이터 검사 → 실제 채점
```

**AC 는 Skill 사용을 증명하지 않는다**([ADR-0033](docs/adr/0033-an-accepted-answer-does-not-prove-the-skill.md)).
deque 를 몰라도 AC 가 나는 문제가 채택됐다가 철회됐다. 정답만으로 잴 수 없는 Skill 이면
그 Skill 없이 같은 답을 내는 풀이가 큰 입력에서 시간 초과해야 채택된다.

뒤의 두 단계는 사람이 쓴 문제에 쓰던 검사 그대로다. 거절된 초안도 단계와 사유와 함께
`generated/rejected/` 에 남는다. 채택된 문제는 PR 로 들어오고 사람이 마지막에 본다.

`tools/meta_test_adoption.py` 가 고정 초안을 일부러 망가뜨려 각 결함이 **제 단계에서**
막히는지 CI 에서 본다. 모델은 부르지 않는다.

## 검증

```bash
pip install -r requirements-dev.txt
python tools/check_curriculum.py      # 데이터/계약이 맞는가
python tools/meta_test_curriculum.py  # 검사가 실제로 잡는가
```

CI 도 같은 파일을 설치한다. 로컬과 CI 가 다른 의존성으로 검증하면 하네스 결과의
근거가 흔들리므로, 의존성을 한 곳에만 둔다 — **CI 워크플로가 이 파일을 쓰는지도 검사한다.**

두 번째가 있는 이유: **검사가 통과하는 것과 검사가 일하는 것은 다르다.**
아무것도 안 하는 검사도 통과한다. 그래서 계약을 일부러 망가뜨린 뒤 검사가 실제로
실패하는지 확인한다. 여기서 "검사가 놓침"이 나오면 데이터가 아니라 **하네스가 깨진 것**이다.

현재 커리큘럼 · 계약 32건 + 문제 데이터 27건, 총 59개 위반 케이스를 차단한다.

같은 논리가 문제 데이터에도 적용된다. **정답이 통과하는 것과 오답이 걸리는 것은
다르다** — 아무것도 거르지 못하는 Test Case 집합도 정답은 통과시킨다. 그래서 문제마다
그 문제에서 자주 나오는 실수를 담은 `wrong.py` 를 두고, 그것이 실제로 걸리는지
확인한다([ADR-0007](docs/adr/0007-problems-are-verified-by-a-wrong-solution.md)).
그것도 "실패했는가" 가 아니라 **"의도한 이유로 실패했는가"** 를 본다 — 심어둔 실수와
드러나야 할 판정을 `negativeControl` 에 데이터로 적어두고 대조한다.

저장소의 문제 96개는 전부 **개발 fixture**다. Test Case 와 정답이 공개돼 있으므로
실서비스 문제은행은 여기 두지 않는다([ADR-0008](docs/adr/0008-public-repo-holds-fixtures-not-the-problem-bank.md)).

Judge 는 같은 논리를 격리에 적용한다. `--network none` 을 **적어두는 것**과 네트워크가
**실제로 안 되는 것**은 다르므로, 제한을 걸고 한 번 / 걷어내고 한 번 돌려 그 실패가
격리 덕분인지 확인한다.

```bash
docker build -t codesprint-judge:py312 -f judge/Dockerfile .
python judge/tests/test_judge.py          # 판정 9 + 격리 8 + 기밀성 3
python tools/verify_problems.py           # 문제 96개를 실제로 채점
python learning/tests/test_mastery.py     # Mastery 산식 (Python oracle)
python tools/gen_mastery_golden.py        # golden 이 oracle 과 일치하는가
cd backend && gradle test                 # Java 구현이 oracle 과 같은 값을 내는가
```

Mastery 는 두 번 구현돼 있다. `learning/` 은 Addendum PART I 의 **실행 가능한 명세**이고
`backend/` 가 production 이다. 둘이 **같은 golden fixture 를 읽고 같은 값을 내는지**
CI 가 대조한다([ADR-0010](docs/adr/0010-java-implementation-is-checked-against-the-python-oracle.md)) —
문서를 읽고 옮긴 것이 맞는지 기계가 확인한다.

격리(실행이 갇혀 있는가)와 기밀성(채점 데이터가 새지 않는가)은 다른 축이다.
정답표가 컨테이너 안에 있으면 코드가 갇혀 있어도 그것을 읽어 되뱉을 수 있으므로,
**정답은 신뢰 경계를 넘지 않는다**([ADR-0006](docs/adr/0006-expected-output-never-enters-sandbox.md)).

## 현재 상태

**슬라이스 1 의 필수 흐름(Addendum §38)이 끝까지 돈다** — 진단에서 출발해 문제를 풀고,
실제 샌드박스가 채점하고, 판정 · mastery · 다음 행동을 거쳐 다음 문제로 간다.
에이전트가 가리키는 대로만 걸어 `MASTERED` 에 닿는 것을 테스트가 본다
([ADR-0028](docs/adr/0028-the-guided-path-is-walked-end-to-end.md),
[ADR-0030](docs/adr/0030-the-agent-does-not-repeat-itself.md)).

| | 상태 |
| --- | --- |
| Skill Catalog (78개, 도메인 46개 전부 활성) + 도메인 레지스트리 (46개) + 학습 트랙 4개 | 완료 (ADR-0034 W1~W9) |
| 계약 + 검사 하네스 + 메타테스트 | 완료 |
| Judge / Sandbox (Python 3.12 · Java 21 · C++17, ADR-0045) + Judge Worker / 큐 | 완료 |
| 문제 · Test Case 116개 + 단계별 힌트 사다리 | 완료 |
| 문제 템플릿 - 가족과 변형 축, 숫자만 바꾼 변형 금지 (ADR-0051) | 완료 |
| Mastery / Evidence 산식 (Python oracle ↔ Java) | 완료 |
| 초기 진단 · Decision Engine · 선수 관계 | 완료 |
| 간격 복습 · 개념 자료 · 단계별 힌트 | 완료 |
| API + 화면 (빌드 도구 없음) + 브라우저 E2E | 완료 |
| Reviewer + 실제 모델 평가 (25건 · 오확정 0) | 완료 — 기본은 꺼져 있다 |
| 내 PC 실행 경로 (`scripts/local.sh`) | 완료 |
| 오늘의 계획 · 시험 직전 모드 (ADR-0038) | 완료 |
| 모의 시험 · 학습 모드 5종 (ADR-0043) | 완료 |
| 자유 질문 Tutor (ADR-0044) | 완료 — 기본은 꺼져 있다 |
| 학습 분석 · 시험 전략(모의 시험 빈도 · 포기 기준) · Evaluator 매핑 (ADR-0049) | 완료 |

슬라이스 1 범위는 Python 3.12 + BFS Grid 계열 8개 Skill + Mistake 2종 자동 드릴이었다. 지금은
PRD 의 웨이브(W1~W9, [ADR-0034](docs/adr/0034-the-road-to-the-full-prd.md))를 모두 마쳐 도메인 마흔여섯이 전부
켜져 있고 Skill 은 일흔여덟이다. 사용자의 목표(학습 트랙)가 그중 켜지는 범위를 정한다.
도메인 레지스트리는 **45개 알고리즘 도메인 + Programming Foundations 1개 = 총 46개
Registry Entry**다. `curriculum/domains.yaml`에 골격으로 전부 등록돼 있고, 검증된 Skill만
`skills.yaml`로 승격한다.
