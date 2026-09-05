# ADR-0011 · Java는 애플리케이션, Python은 샌드박스와 하네스

- 상태: 채택
- 날짜: 2026-09-05
- 정본 근거: PRD §98 / Addendum §47, §66~68 / [ADR-0006](0006-expected-output-never-enters-sandbox.md)

## 맥락

백엔드를 세우면서 저장소에 언어가 둘이 됐다. 어디까지가 어느 쪽인지 정하지 않으면
다음 기능을 어느 쪽에 붙일지가 매번 즉흥 판단이 된다.

## 결정

```text
Java / Spring Boot                     Python
────────────────────                   ──────
API                                    Judge Worker (사용자 코드 실행)
영속성 (Evidence / user_skills)         커리큘럼·계약 검사 하네스
Mastery 산식 (production)              Mastery oracle (대조용)
Decision Engine                        문제 데이터 검증
Reviewer 오케스트레이션
학습 세션
```

경계는 **큐**다. Java가 제출을 저장하고 큐에 넣으면, Python Worker가 꺼내 컨테이너에서
실행하고 결과를 돌려준다(Addendum §67~68).

## 왜 Judge만 Python인가

**사용자 코드를 실행하는 프로그램은 컨테이너 안에서 돈다.** 그 안에 JVM을 띄울 이유가 없다.

- 이미지가 작아야 한다. 탈출 시도의 발판이 될 도구를 줄이기 위해서다.
- 채점 대상이 Python 3.12다(슬라이스 1). 러너가 같은 런타임이면 문법 확인
  (`compile()`)을 프로세스 안에서 할 수 있다.
- 하네스가 사용자 코드를 `import`하지 않고 별도 프로세스로 띄운다(ADR-0005). 이건
  언어와 무관하지만, 프로세스 관리와 `RLIMIT` 설정이 Python 쪽이 짧다.

Java/C++ 채점이 추가되면 이미지가 언어마다 갈리지만 **호스트 측 러너는 그대로다** —
`run_submission.py`는 언어를 모른다. 컨테이너를 만들고 프로토콜로 이야기할 뿐이다.

## 왜 검사 하네스는 Python인가

`tools/`는 저장소의 데이터와 계약을 검사한다. 애플리케이션이 아니라 CI 도구이며,
YAML·JSON을 다루고 1초 안에 끝나야 한다. Gradle 빌드를 거치면 그 조건이 깨진다.

## 왜 Mastery는 양쪽에 있는가

Java가 production이고 Python은 대조용 oracle이다. 근거와 접는 조건은
[ADR-0010](0010-java-implementation-is-checked-against-the-python-oracle.md)에 있다.

**이것만 의도적인 이중 구현이다.** 다른 것을 두 언어로 만들지 않는다.

## 결과

**얻는 것**

- 다음 기능을 어디에 붙일지가 정해져 있다. Decision Engine과 Reviewer 오케스트레이션은
  Java다 — Python으로 더 만들면 나중에 다시 옮겨야 한다.
- 샌드박스가 애플리케이션과 완전히 분리된다. Addendum §66이 요구한 것이기도 하다.

**치르는 비용**

- CI가 두 언어를 굽는다. 워크플로가 셋이 됐다(`curriculum` / `judge` / `backend`).
- 큐 프로토콜이 언어 경계를 넘는 계약이 된다. 지금은 없고, Judge Worker를 만들 때
  `contracts/`에 추가해야 한다.
- 개발자가 둘 다 알아야 한다. 포트폴리오 저장소에서는 이득이지만 팀에서는 비용이다.

## 아직 정하지 않은 것

**큐를 무엇으로 할 것인가.** Addendum §67은 Redis Stream을 권한다. 그때 Java 쪽
프로듀서와 Python 쪽 컨슈머가 같은 메시지 형식을 봐야 하므로, 그 형식도 `contracts/`에
둔다.

## 관련

- [ADR-0006](0006-expected-output-never-enters-sandbox.md) — 샌드박스 신뢰 경계
- [ADR-0010](0010-java-implementation-is-checked-against-the-python-oracle.md) — 이중 구현의 근거
