# Backend

CodeSprint Agent 애플리케이션. Spring Boot 3 · Java 17 · PostgreSQL · Flyway.

**사용자 코드를 실행하지 않는다.** 그것은 별도 Python Judge Worker 의 일이고 여기서는
큐로만 이야기한다 - 근거: [ADR-0011](../docs/adr/0011-language-boundary.md).

```text
learning/domain        Mastery 산식 · Decision Engine · 선수 관계 판정
learning/persistence   Evidence 저장소
curriculum/            커리큘럼 로더 (빌드 시점에 ../curriculum 을 굽는다)
resources/db/migration Flyway. 스키마의 정본
```

## 두 계산을 나눠 뒀다

```text
MasteryCalculator      Evidence  -> UNASSESSED / LEARNING / PRACTICING / MASTERED / WEAKENED
PrerequisiteEvaluator  선수 관계 -> LOCKED / READY
```

Evidence 만 봐서는 "앞 Skill 을 충분히 했는가" 를 알 수 없기 때문이다(ADR-0009).
둘을 합치는 규칙은 `PrerequisiteEvaluator.resolve` 에 있고, **이미 배우기 시작한 Skill 은
잠그지 않는다** - 다른 문제의 SECONDARY Skill 로 Evidence 가 쌓여 선수 조건보다 먼저
진도가 나가는 경우가 있는데, 그때 "잠김" 으로 되돌리면 사용자는 자기가 푼 것이 사라진
것으로 본다.

## 커리큘럼은 복사본을 두지 않는다

`backend/src/main/resources` 에 커리큘럼 YAML 을 복사해 넣지 않는다. 정본이 둘이 되면
`curriculum/` 을 고친 사람이 이쪽을 잊었을 때 조용히 갈라진다. Gradle 이 빌드 시점에
`../curriculum` 에서 가져온다([ADR-0012](../docs/adr/0012-curriculum-is-packaged-from-one-source.md)).

**Java 는 커리큘럼을 검증하지 않는다.** 순환 선수 관계, dangling 참조, `auto_drill` 대상의
실재성은 `tools/check_curriculum.py` 와 `tools/check_problems.py` 가 막는다. 같은 검사를
두 언어로 두면 갈라졌을 때 어느 쪽이 맞는지 알 수 없다.

**자동 드릴 대상을 코드에 적지 않는다.** Addendum 43 의 pseudocode 는
`MICRO_DRILL(GRID_BOUNDARY_CHECK)` 처럼 되어 있지만, 그대로 옮기면 `mistakes.yaml` 을
고쳐도 코드가 따라가지 않는다. `catalog.autoDrillTarget(mistake)` 로 데이터에서 가져온다.

## 실행

```bash
docker run -d --name cs-db -e POSTGRES_DB=codesprint -e POSTGRES_USER=codesprint \
  -e POSTGRES_PASSWORD=codesprint -p 5432:5432 postgres:16-alpine

cd backend && gradle bootRun
```

## 테스트

```bash
cd backend && gradle test
```

Testcontainers 가 PostgreSQL 을 띄운다. **Docker 가 없으면 skip 이 아니라 실패한다** -
조용히 건너뛰면 DB 검증이 사라진 줄 아무도 모른 채 CI 가 초록불을 낸다.

Docker-in-Docker 가 막히는 환경(Windows 의 Docker Desktop 안에서 Gradle 컨테이너를
띄우는 경우)에서는 외부 DB 를 줄 수 있다.

```bash
TEST_DB_URL=jdbc:postgresql://localhost:55432/codesprint gradle test
```

## Mastery 산식은 Python oracle 과 대조된다

`MasteryCalculator` 는 `learning/mastery.py` 의 이식본이다. 두 구현이 **같은 golden
fixture**(`tests/golden/`)를 읽고 같은 값을 내야 하며 CI 가 대조한다
([ADR-0010](../docs/adr/0010-java-implementation-is-checked-against-the-python-oracle.md)).

산식을 고치면 세 곳을 함께 고친다.

```bash
# 1. learning/mastery.py 또는 learning/evidence.py
# 2. backend/.../MasteryCalculator.java
# 3. golden 재생성
python tools/gen_mastery_golden.py --write
```

한쪽만 고치면 CI 가 막는다. 실제로 `Dimension.INDEPENDENT` 가중치를 0.25 -> 0.30 으로
바꿔봤더니 golden 17건 중 12건이 실패했다.

**반올림을 맞춰야 했다.** Python 의 `round()` 는 banker's rounding 이라
`BigDecimal.setScale(4, HALF_EVEN)` 으로 맞췄다. ADR-0009 에 "Java 로 옮길 때 부동소수
반올림 때문에 값이 갈릴 수 있다" 고 적어둔 리스크가 실제로 나타난 지점이다.

## 스키마는 Flyway 가 정본이다

`spring.jpa.hibernate.ddl-auto` 는 `validate` 다. Hibernate 에게 스키마 생성을 맡기면
마이그레이션이 정본이 아니게 되고, 운영에서 어떤 DDL 이 돌았는지 아무도 모르게 된다.

마이그레이션에 들어간 제약은 전부 이유가 있다.

| 제약 | 막는 것 |
| --- | --- |
| `UNIQUE(user_id, source_event_id, skill_code)` | Worker 재시도가 EMA 를 두 번 적용하는 것 |
| `CHECK` 관측값 최소 하나 | 측정한 게 없는데 confidence 만 오르는 Evidence |
| `CHECK` status / evidence_type 목록 | 오타 난 값이 조용히 저장되는 것 |
| `TIMESTAMPTZ` | 시간대 없는 시각. EMA 정렬이 틀어진다 |
| `observed_* / source_confidence BETWEEN 0 AND 1`, `weight >= 0` | 정본에 범위 밖 값이 영구 저장되는 것 |
| mastery ↔ status 정합 | 재계산이 어긋난 캐시 |

`mastery` 와 `status` 의 관계를 **양방향 동치로 쓰지 않는다.** `LOCKED` 와 `READY` 는
Evidence 가 아니라 선수 관계에서 나오는 상태라(ADR-0009), 신규 사용자의 잠긴 Skill 은
mastery 가 `NULL` 인 채로 `LOCKED` 다. 동치로 묶으면 그 정상 상태를 저장할 수 없다.
반대로 mastery 가 있는데 `LOCKED` 인 것은 가능하다 - 다른 문제의 SECONDARY Skill 로
Evidence 가 쌓였는데 선수 조건은 아직 못 채운 경우다.

## Evidence 는 append-only 이고, 그것을 세 층이 강제한다

처음에는 "엔티티에 setter 를 두지 않았다" 를 근거로 삼았는데 **그건 근거가 되지 못한다.**
평범한 코드에서 필드를 바꾸기 어렵게 할 뿐 UPDATE 나 DELETE 를 막지 못한다.
실제로 확인했더니 `UPDATE 1` / `DELETE 1` 이 그대로 됐다.

| 층 | 막는 것 |
| --- | --- |
| 엔티티 | setter 없음. 코드에서 필드 변경 |
| Repository | `JpaRepository` 를 상속하지 않는다. `delete*` API 자체가 없다 |
| DB 트리거 | `skill_evidence_append_only`. UPDATE / DELETE 를 거부한다 |

Evidence 하나가 사라지면 과거를 다시 접었을 때 다른 mastery 가 나오고, 그러면 다른
status 와 다른 Decision 으로 이어진다(ADR-0009). `user_skills` 는 캐시라 다시 만들면
되지만 이쪽은 복원할 방법이 없다.

⚠️ **사용자 데이터 삭제(탈퇴 등)는 이 트리거를 우회해야 한다.** 권한을 가진 경로에서
`ALTER TABLE skill_evidence DISABLE TRIGGER skill_evidence_append_only` 를 쓰고 다시
켠다. 그 경로를 만들 때 별도 감사 기록을 남긴다 - 아직 없다.

## 아직 없는 것

- API 컨트롤러. 지금은 도메인과 영속성뿐이다
- Decision Engine 을 실제로 부르는 서비스 계층. 규칙은 있고 호출부가 없다
- Reviewer 오케스트레이션
- Judge Worker 와 큐 (Addendum 67~69). 언어 경계를 넘는 메시지 계약이 필요하다
- Expand-Contract 마이그레이션 규율. 무중단 배포를 전제하기 전에 정해야 한다
