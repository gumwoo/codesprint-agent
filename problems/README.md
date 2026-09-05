# Problems

Vertical Slice 1 의 문제 집합. 생성형이 아니라 **검증된 고정 문제**다(Addendum 40).

> ⚠️ **이 10개는 전부 개발 fixture(`source: DEV_FIXTURE`)다.**
> Test Case 와 정답 풀이가 공개 저장소에 그대로 들어 있다. `hidden: true` 는 학습 UI 에서
> 보여줄지를 뜻할 뿐 기밀성 보장이 아니다.
> 실제 학습에 쓰는 문제은행(`CURATED`)은 여기 두지 않는다 —
> [ADR-0008](../docs/adr/0008-public-repo-holds-fixtures-not-the-problem-bank.md).
> 공개 저장소에 `CURATED` 문제가 들어오면 CI 가 막는다.

```text
P01_QUEUE_BASIC              deque 로 명령 처리
P02_GRID_TRAVERSAL           출발점에서 도달 가능한 칸 세기
P03_CONNECTED_COMPONENT      영역의 개수
P04_AREA_SIZE                가장 큰 영역의 크기
P05_SHORTEST_PATH            격자 최단거리
P06_BOUNDARY_DRILL_A         경계 검사 드릴 (MICRO_DRILL)
P07_BOUNDARY_DRILL_B         경계 검사 드릴 (MICRO_DRILL)
P08_VISITED_TIMING_DRILL     방문 시점 드릴 (MICRO_DRILL)
P09_BFS_VARIANT_A            8방향 최단거리
P10_BFS_REVIEW               가장 먼 칸까지의 거리 (REVIEW)
```

## 파일

| | |
| --- | --- |
| `problem.yaml` | 메타 + Skill 매핑 + 자주 나오는 Mistake. 형식은 `contracts/problem.schema.json` |
| `cases.json` | Test Case. 형식은 `contracts/test-cases.schema.json` |
| `reference.py` | 정답. CI 가 실제로 채점해 ACCEPTED 를 확인한다 |
| `wrong.py` | **일부러 틀린 풀이.** 아래 참고 |

`problem.yaml` 의 `negativeControl` 이 `wrong.py` 가 무엇을 틀리게 했고 그 결과가
어떤 판정이어야 하는지를 적는다.

```yaml
negativeControl:
  mistake: VISITED_TIMING       # 심어둔 실수 (mistakes.yaml 의 code)
  expectedStatus: WRONG_ANSWER  # 그 실수가 드러나야 하는 판정
```

## wrong.py 가 있는 이유

reference 만 검사하면 **모든 출력을 통과시키는 Test Case 집합**도 통과한다.
아무것도 거르지 못하는 문제가 초록불을 받는다.

```text
reference ACCEPTED              → 정답이 정답으로 판정되는가
wrong 이 expectedStatus 로 걸림 → 이 Test Case 가 오답을 실제로 거르는가   ← 이쪽이 더 중요하다
```

**"실패했는가" 가 아니라 "의도한 이유로 실패했는가" 를 본다.** 처음에는 ACCEPTED 만
아니면 통과시켰는데, 그러면 누가 `wrong.py` 의 문법을 깨뜨려 `COMPILE_ERROR` 가 나도
검증이 통과한다. 실제로 확인했다. 계약 하네스의 메타테스트가 exit code 만 보지 않고
기대 메시지까지 대조하는 것과 같은 이유다.

틀린 풀이가 통과한다는 것은 그 문제가 Skill 을 측정하지 못한다는 뜻이고,
측정하지 못하는 문제로 쌓은 Evidence 는 mastery 를 오염시킨다.

`wrong.py` 는 아무렇게나 틀린 코드가 아니라 **그 문제에서 실제로 자주 나오는 실수**다.
무엇을 틀리게 했는지 파일 첫 줄 주석에 적는다. 계약 하네스의 메타테스트,
Judge 의 격리 대조군과 같은 논리다.

## MICRO_DRILL 과 자동 드릴 착지점

`curriculum/mistakes.yaml` 에서 `auto_drill: true` 인 Mistake 는 그 `target_skill` 을
PRIMARY 로 갖는 `MICRO_DRILL` 문제가 **반드시 하나 이상** 있어야 한다.
없으면 Decision Engine 이 `MICRO_DRILL` 을 고르고도 줄 문제가 없다 -
런타임에는 "다음 행동이 없음" 으로 나타나고, 원인이 데이터에 있다는 것을 알기 어렵다.

CI 가 이 짝을 검사한다.

```text
BOUNDARY_CHECK  -> GRID_BOUNDARY_CHECK       -> P06, P07
VISITED_TIMING  -> BFS_VISITED_MANAGEMENT    -> P08
```

## P08 을 설계할 때 알게 된 것

평범한 격자 BFS 에서 **방문 시점 오류는 보통 답을 바꾸지 않는다.** 같은 칸이 큐에
여러 번 들어가 느려질 뿐, 최단거리 자체는 맞게 나온다. 처음 만든 `wrong.py` 가
정답과 한 번도 갈리지 않아서 알게 됐다(랜덤 격자 20만 개 대조).

그래서 P08 은 **꺼내는 횟수가 답에 직접 반영되도록** 설계했다. 거리 합을 pop 할 때마다
누적하면 중복만큼 합이 커진다. 드릴이 노리는 실수를 실제로 드러내려면 문제 쪽에서
그 차이를 관측 가능하게 만들어야 한다.

`curriculum/mistakes.yaml` 의 `VISITED_TIMING` 설명도 이 사실에 맞춰 읽어야 한다 -
"탐색량이 불어난다" 가 주된 증상이고, 답이 틀리는 것은 문제 형태에 달렸다.

## 검증

```bash
python tools/check_problems.py      # 참조 무결성 / Test Case 형식 (Docker 불필요)
python tools/meta_test_problems.py  # 그 검사가 실제로 잡는가
python tools/verify_problems.py     # 실제 채점 (Docker 필요)
```

## 오답 라벨 분포

`negativeControl.mistake` 를 모아 보면 지금 이렇다.

```text
BOUNDARY_CHECK        P06, P07
VISITED_TIMING        P08
OUTPUT_FORMAT         P01
IMPLEMENTATION_MISC   P02, P03, P04, P05, P09, P10   ← 6/10
```

절반 이상이 `IMPLEMENTATION_MISC` 다. 슬라이스 1 taxonomy 8종으로는 "도달 여부를
따지지 않았다", "큐 대신 스택을 썼다", "대각선을 빠뜨렸다" 같은 오답에 이름을 붙일 수
없기 때문이다. `curriculum/mistakes.yaml` 의 `IMPLEMENTATION_MISC` 설명이 예고한
"이 항목의 비율이 높으면 taxonomy 를 넓혀야 한다는 신호" 가 데이터로 나타난 것이다.

`check_problems.py` 가 이 비율을 출력한다. 실패로 처리하지는 않는다 - 슬라이스 1 은
자동 액션을 2종으로 묶어두기로 했고(Addendum 42), taxonomy 확장은 실제 사용자 제출
분포를 보고 결정할 일이다. 지금 넓히면 근거 없이 넓히는 것이 된다.

## 아직 없는 것

- 문제 본문의 모호성 검사 (Addendum 133 Problem Validator)
- 독립 검증 풀이(Alternative Solution) — 지금은 reference 하나뿐이라
  reference 가 틀리면 expectedOutput 도 함께 틀린다
- 생성형 문제와 변형 (Addendum 63~65)
