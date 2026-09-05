# ADR-0012 · 커리큘럼은 저장소의 한 곳에서 빌드 시점에 실린다

- 상태: 채택
- 날짜: 2026-09-05
- 정본 근거: [ADR-0003](0003-skill-id-canonical-uppercase.md), [ADR-0011](0011-language-boundary.md) / PRD §101~102

## 맥락

Decision Engine 이 커리큘럼 데이터를 읽어야 한다.

- **선수 관계**(`prerequisites.yaml`) — `LOCKED` / `READY` 판정
- **자동 드릴 대상**(`mistakes.yaml`의 `auto_drill` / `target_skill`) — `MICRO_DRILL`

지금까지 이 데이터는 Python 하네스만 읽었다. Java 백엔드가 읽으려면 어딘가에서 가져와야
한다.

## 선택지

**A. `backend/src/main/resources/`에 복사본을 둔다**

가장 쉽다. 그리고 **정본이 둘이 된다.** `curriculum/`을 고친 사람이 `backend/` 쪽을
잊으면 조용히 갈라지고, 갈라졌을 때 Python CI는 통과하는데 Java 동작만 달라진다.
이 저장소가 계속 막아온 종류의 문제다.

**B. DB 테이블로 옮기고 seed 한다** (PRD §101~102의 방향)

운영에서 커리큘럼을 바꾸려면 결국 이쪽이다. 다만 지금 하면 seed 스크립트, 마이그레이션,
YAML↔DB 동기화 검사가 함께 필요하다. 슬라이스 1에서 그만큼의 값을 얻지 못한다 —
커리큘럼은 8개 Skill이고 바뀌면 어차피 CI를 거친다.

**C. 빌드 시점에 저장소의 한 곳에서 가져온다**

```kotlin
tasks.named<ProcessResources>("processResources") {
    from(rootProject.projectDir.parentFile.resolve("curriculum")) { into("curriculum") }
}
```

정본은 `curriculum/*.yaml` 하나. git에 복사본이 없다. 커리큘럼이 바뀌면 jar가 다시 구워진다.

## 결정

**C를 택한다.**

`CurriculumCatalog`가 클래스패스에서 `curriculum/*.yaml`을 읽는다. 파일이 없으면
즉시 예외를 던진다 — 커리큘럼 없이 뜨면 Decision Engine이 모든 Skill을 잠긴 것으로 본다.

## Java는 커리큘럼을 검증하지 않는다

순환 선수 관계, dangling 참조, weight 합, `auto_drill` 대상의 실재성은 전부
`tools/check_curriculum.py`와 `tools/check_problems.py`가 CI에서 막는다.

**같은 검사를 두 언어로 두지 않는다.** 둘이 갈라졌을 때 어느 쪽이 맞는지 아무도 모르고,
검사를 고칠 때마다 두 곳을 만져야 한다. Java는 읽기만 하고, 읽지 못하면 실패한다.

## 자동 드릴 대상을 코드에 적지 않는다

Addendum §43의 pseudocode는 이렇게 되어 있다.

```text
elif confirmedMistake == BOUNDARY_CHECK:
    MICRO_DRILL(GRID_BOUNDARY_CHECK)
```

이대로 옮기면 `mistakes.yaml`의 `target_skill`을 바꿔도 코드가 따라가지 않는다.
`catalog.autoDrillTarget(mistake)`로 데이터에서 가져온다. `check_problems.py`가 그 Skill을
PRIMARY로 갖는 `MICRO_DRILL` 문제의 존재까지 확인하므로, 데이터 → 코드 → 문제가 한 줄로 이어진다.

## 결과

**얻는 것**

- 커리큘럼의 정본이 하나다. Python과 Java가 같은 파일을 본다.
- 커리큘럼을 고치면 CI가 검증하고, 그 결과가 백엔드에도 그대로 반영된다.
- `backend.yml`의 경로 필터에 `curriculum/**`을 넣었다 — 데이터가 바뀌면 백엔드 테스트도 돈다.

**치르는 비용**

- **운영 중 커리큘럼을 바꾸려면 배포해야 한다.** 슬라이스 1에서는 문제가 아니지만
  (커리큘럼 변경 = 코드 변경과 같은 무게), Skill이 수백 개가 되고 운영자가 직접 편집하게
  되면 B로 옮겨야 한다.
- Gradle이 저장소 루트 밖(`../curriculum`)을 본다. 백엔드 디렉터리만 떼어내면 빌드가 깨진다.
  지금은 모노레포라 문제없다.
- jar에 커리큘럼이 들어간다. `DEV_FIXTURE` 데이터라 공개돼도 무방하다(ADR-0008).

## 언제 B로 옮기는가

- 커리큘럼을 배포 없이 바꿔야 할 때
- Skill이 수백 개가 되어 YAML 한 파일로 관리하기 어려울 때
- 사용자별로 활성 Skill 집합이 달라질 때(PRD §129의 목표별 범위)

그때도 `curriculum/*.yaml`은 남긴다 — CI 검증과 리뷰 대상이 되는 것은 파일이지 DB 행이 아니다.
DB는 그것을 seed 한 결과가 된다.

## 관련

- [ADR-0003](0003-skill-id-canonical-uppercase.md) — Skill code가 전 영역에서 같은 값인 이유
- [ADR-0011](0011-language-boundary.md) — 어느 쪽이 무엇을 맡는가
- [ADR-0002](0002-next-action-decided-by-rule-engine.md) — 이 데이터를 쓰는 Decision Engine
