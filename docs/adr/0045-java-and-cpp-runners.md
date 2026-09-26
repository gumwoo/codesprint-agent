# ADR-0045 · Java · C++ 채점 - 언어는 이미지에 굽고, 언어에 매인 Skill 은 그 언어의 제출만 잰다

- 상태: 채택
- 날짜: 2026-09-26
- 정본 근거: PRD §67(코드 실행 시스템), §164(Judge - Java Runner · C++ Runner),
  [ADR-0005](0005-judge-stops-at-first-failure.md), [ADR-0006](0006-expected-output-never-enters-sandbox.md),
  [ADR-0009](0009-mastery-is-recomputed-from-evidence.md), [ADR-0033](0033-an-accepted-answer-does-not-prove-the-skill.md)

## 맥락

PRD §67 은 "Skill Graph 와 제품 설계는 특정 언어에 종속되지 않는다" 고 하고, §164 체크리스트에 Java Runner ·
C++ Runner 가 있다. 지금까지 채점은 Python 3.12 하나였다. 언어를 늘리는 데서 정할 것은 셋이다 - 컴파일러를
누가 고르는가, 컴파일은 어디서 하는가, 그리고 **다른 언어로 낸 AC 가 무엇의 증거인가.**

## 결정

### 1. 언어마다 이미지 하나, 언어는 이미지에 굽는다

| language | 이미지 | 파일 | 실행 |
| --- | --- | --- | --- |
| `PYTHON` | `codesprint-judge:py312` | `solution.py` | `python` |
| `CPP` | `codesprint-judge:cpp` (gcc:13-bookworm) | `solution.cpp` | `g++ -O2 -std=gnu++17` → `/build/main` |
| `JAVA` | `codesprint-judge:java21` (eclipse-temurin:21-jdk-jammy) | `Main.java` | `javac` → `java -cp /build Main` |

하네스는 `JUDGE_LANGUAGE`(이미지의 `ENV`)로 제 언어를 안다. 사용자가 보낸 값으로 컴파일러나 명령을 고르지
않는다 - 요청의 `language` 는 **어느 이미지를 띄울지**만 고르고, 표에 없는 값은 API 에서 400 이다. 한 이미지에
세 언어를 넣으면 이미지가 커지는 것보다, 하네스가 입력으로 실행 명령을 고르는 자리가 생기는 것이 문제다.

### 2. 컴파일은 /build 에서, /tmp 는 그대로 noexec

제출이 쓸 수 있는 `/tmp` 는 noexec 로 남긴다. 컴파일 산출물만 exec 인 tmpfs `/build`(64MB)에 둔다. 컴파일은
20 초 · 출력 파일 64MB 로 막고, 실패하면 `COMPILE_ERROR` 다(stderr 는 기존과 같이 경로를 지운다).

### 3. 메모리는 case 마다 잰다

`RUSAGE_CHILDREN` 은 컴파일러까지 센다 - g++ 가 약 190MB 를 써서 모든 C++ 제출의 memoryKb 가 그 값이 됐다.
case 마다 `os.wait4` 로 그 프로세스의 `ru_maxrss` 를 읽는다.

### 4. JVM 의 출력 폭주는 OUTPUT_LIMIT

JVM 은 SIGXFSZ 를 무시하고 계속 쓰려다 시간 초과로 죽는다. 그대로 두면 출력 폭주가 TIME_LIMIT 로 보인다.
시간 초과로 죽었는데 stdout 이 상한까지 찼으면 `OUTPUT_LIMIT` 로 판정한다. `OutOfMemoryError` ·
`std::bad_alloc` 은 `MEMORY_LIMIT` 이다.

### 5. 언어에 매인 Skill 은 그 언어의 제출만 잰다

`skills.yaml` 의 `language` 가 정해진 Skill(지금 `PYTHON_LIST_BASIC`, `PYTHON_DEQUE_BASIC`)은 그 언어의
관측만 받는다. Java 로 푼 AC 는 Python list 를 다룬 증거가 아니다(ADR-0033 과 같은 선 - 정답은 Skill 사용을
증명하지 않는다).

- **PRIMARY** 가 다른 언어에 매인 문제(P01 · P11 · P19)는 제출도 실행도 400 이다. 받아 두고 Evidence 만
  빼면 그 제출은 아무것도 재지 않는데, 사용자는 푼 것으로 안다.
- **SECONDARY** 만 매인 문제(16 링크)는 받는다. 그 링크의 Evidence 만 남기지 않는다.

`LanguageTest` 가 둘을 보고, 대조로 Python 제출이 그 Skill 의 Evidence 를 남기는지 본다. 두 fix 를
되돌리면 두 테스트가 실패하는 것을 확인했다.

### 6. 격리 · 기밀성 검사는 언어마다 다시 건다

`test_judge.py` 가 판정 14 · 격리 16 · 기밀성 4 case 를 C++ 과 Java 에 건다. 격리 case 는 전부 제한을 걷은
대조군이 있다(제한이 없어도 실패하면 그 case 는 아무것도 보지 않는 것이다). 정답은 여전히 컨테이너에 들어가지
않는다(ADR-0006).

## 남는 위험

- **시간 제한은 언어 구분 없이 같다.** 문제의 `timeLimitMs` 는 Python reference 로 정했다. Java 는 case 마다
  JVM 을 새로 띄운다 - 작은 입력 4 case 에서 가장 느린 case 가 73~80ms(3 회 실측, 약 35MB)였다. 큰 입력에서
  Java · C++ reference 를 돌려 본 적은 없다. 언어별 배수를 두지 않은 것은 근거가 될 실측이 없어서다.
- **skill control(ADR-0033)은 Python 에서만 검증된다.** `skill_control.py` 는 Python 풀이다. C++ 은 같은
  시간 제한에서 훨씬 빠르므로, Python 에서 TIME_LIMIT 가 나는 "Skill 없는 풀이" 가 C++ 에서는 AC 가 날 수
  있다. 그 AC 는 PRIMARY Skill 의 Evidence 가 된다 - C++ 제출이 재는 것은 Python 제출보다 약하다.
  언어별 control 을 두거나, 언어별 시간 제한을 두는 것은 실측이 쌓인 뒤의 일이다.
- 화면은 Java · C++ 에 강조 모드를 붙이지 않는다(외부 스크립트를 늘리지 않는다, ADR-0017).
- PRD §67 의 JavaScript · Kotlin 은 하지 않았다. 같은 방식(이미지 하나 + 표 한 줄 + 판정 · 격리 case)으로 붙는다.
