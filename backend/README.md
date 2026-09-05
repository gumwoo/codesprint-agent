# Backend

CodeSprint Agent 애플리케이션. Spring Boot 3 · Java 17 · PostgreSQL · Flyway.

**사용자 코드를 실행하지 않는다.** 그것은 별도 Python Judge Worker 의 일이고 여기서는
큐로만 이야기한다 - 근거: [ADR-0011](../docs/adr/0011-language-boundary.md).

```text
learning/domain        Mastery 산식 (Addendum PART I 의 production 구현)
learning/persistence   Evidence 저장소
resources/db/migration Flyway. 스키마의 정본
```

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
- Decision Engine (ADR-0002, Addendum 43) - 다음 단계
- Reviewer 오케스트레이션
- Judge Worker 와 큐 (Addendum 67~69). 언어 경계를 넘는 메시지 계약이 필요하다
- Expand-Contract 마이그레이션 규율. 무중단 배포를 전제하기 전에 정해야 한다
