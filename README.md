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
contracts/     LLM 요청/응답 + Judge 판정 계약 (JSON Schema)
judge/         사용자 코드를 실행하는 샌드박스와 채점 하네스
problems/      슬라이스 1 의 검증된 문제 10개 (전부 개발 fixture — ADR-0008)
learning/      Mastery 산식의 실행 가능한 명세 (Python oracle)
backend/       Spring Boot · PostgreSQL · Mastery / Decision Engine
tests/golden/  두 구현을 대조하는 golden fixture
tools/         계약 검사 + 메타테스트
docs/adr/      결정과 그 이유
docs/_archive/ 원본 PRD / Implementation Spec (현재 정본)
```

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

현재 계약 27건 + 문제 데이터 15건, 총 42개 위반 케이스를 차단한다.

같은 논리가 문제 데이터에도 적용된다. **정답이 통과하는 것과 오답이 걸리는 것은
다르다** — 아무것도 거르지 못하는 Test Case 집합도 정답은 통과시킨다. 그래서 문제마다
그 문제에서 자주 나오는 실수를 담은 `wrong.py` 를 두고, 그것이 실제로 걸리는지
확인한다([ADR-0007](docs/adr/0007-problems-are-verified-by-a-wrong-solution.md)).
그것도 "실패했는가" 가 아니라 **"의도한 이유로 실패했는가"** 를 본다 — 심어둔 실수와
드러나야 할 판정을 `negativeControl` 에 데이터로 적어두고 대조한다.

저장소의 문제 10개는 전부 **개발 fixture**다. Test Case 와 정답이 공개돼 있으므로
실서비스 문제은행은 여기 두지 않는다([ADR-0008](docs/adr/0008-public-repo-holds-fixtures-not-the-problem-bank.md)).

Judge 는 같은 논리를 격리에 적용한다. `--network none` 을 **적어두는 것**과 네트워크가
**실제로 안 되는 것**은 다르므로, 제한을 걸고 한 번 / 걷어내고 한 번 돌려 그 실패가
격리 덕분인지 확인한다.

```bash
docker build -t codesprint-judge:py312 -f judge/Dockerfile .
python judge/tests/test_judge.py          # 판정 9 + 격리 8 + 기밀성 3
python tools/verify_problems.py           # 문제 10개를 실제로 채점
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

첫 Vertical Slice 착수 전. 존재하는 것은 커리큘럼 데이터, LLM 계약, 그리고
그 둘을 지키는 하네스다.

| | 상태 |
| --- | --- |
| Skill Catalog (8개) + 도메인 레지스트리 (46개) | 완료 |
| LLM 계약 + 검사 하네스 | 완료 |
| Judge / Sandbox (Python 3.12) | 완료 |
| 문제 · Test Case 10개 | 완료 |
| Mastery / Evidence 산식 (Python oracle) | 완료 |
| 백엔드 기반 · Evidence 영속성 · Java 산식 | 완료 |
| Judge Worker / 큐 | 미착수 |
| Decision Engine · 선수 관계 판정 | 완료 |
| API / Frontend | 미착수 |
| Reviewer 평가 하네스 | 슬라이스 1 이후 (로깅은 지금부터) |

슬라이스 1 범위는 Python 3.12 + BFS Grid 계열 8개 Skill + Mistake 2종 자동 드릴이다.
도메인 레지스트리는 **45개 알고리즘 도메인 + Programming Foundations 1개 = 총 46개
Registry Entry**다. `curriculum/domains.yaml`에 골격으로 전부 등록돼 있고, 검증된 Skill만
`skills.yaml`로 승격한다.
