# ADR-0033 · AC 는 Skill 사용을 증명하지 않는다

- 상태: 채택
- 날짜: 2026-09-15
- 정본 근거: Addendum §PYTHON_DEQUE_BASIC(설명을 이 ADR 로 고친다),
  [ADR-0007](0007-problems-are-verified-by-a-wrong-solution.md),
  [ADR-0009](0009-mastery-is-recomputed-from-evidence.md),
  [ADR-0032](0032-the-agent-drafts-the-system-adopts.md)

## 맥락

PR #43 검토에서 생성 문제 `P18_DEQUE_MOVING_SUM`(최근 K 개의 합)이
`PYTHON_DEQUE_BASIC` 을 재지 못한다는 지적이 나왔다. PRIMARY 0.7 로 매핑돼 있고 힌트도
`append / popleft` 를 가르치는데, K 칸 전 값을 인덱스로 빼는 풀이로 풀린다.

실제로 채점해서 확인했다.

| 문제 | deque 없이 쓴 풀이 | 판정 |
| --- | --- | --- |
| P18_DEQUE_MOVING_SUM | 인덱스로 K 칸 전 값을 뺀다 | `ACCEPTED` |
| **P01_QUEUE_BASIC** (사람이 씀) | list 에 앞 위치 번호를 둔다 | **`ACCEPTED`** |

**생성 파이프라인만의 문제가 아니었다.** 사람이 쓴 P01 도 같았다. 이 AC 는 그대로
Evidence 가 되고 mastery 가 올라간다(ADR-0009). 측정 오류다.

P18 은 채택 검사를 전부 통과했다. 교차 검증은 "정답이 맞는가" 를, 오답 검사는 "틀린
풀이가 걸리는가" 를 본다. **"Skill 없이 쓴 맞는 풀이가 걸리는가" 는 아무도 보지 않았다.**

## 결정

### 1. Skill 정의를 관측할 수 있는 행동으로 고친다

채점은 출력과 시간만 본다. `collections.deque 의 append / popleft 를 사용할 수 있다` 는
어떤 문제로도 확인할 수 없다 - 앞 위치 번호를 둔 list 는 언제나 같은 답을 같은 속도로 낸다.

```
전  collections.deque 의 append / popleft 를 사용할 수 있다.
후  먼저 들어온 값을 앞에서 꺼내고 뒤에 넣는 처리를, 줄이 길어져도 꺼낼 때마다
    원소를 옮기지 않는 방식(collections.deque 의 append / popleft)으로 시간 안에 한다.
```

앞 위치 번호를 둔 list 풀이는 이 정의를 만족한다. 받아들인다 - 재지 못하는 것을 잰다고
적는 것보다 낫다. Addendum 의 설명과 달라지는 부분이며 이 ADR 이 근거다.

### 2. 정답만으로 잴 수 없는 Skill 을 데이터에 적는다

`skills.yaml` 의 모든 Skill 에 `needs_skill_control` 을 둔다. 생략하면 CI 가 막는다 -
생략을 false 로 읽으면 잴 수 없는 Skill 이 조용히 대조 없이 문제를 얻는다.

지금 true 는 `PYTHON_DEQUE_BASIC` 하나다. **false 는 "정답이 곧 그 Skill 을 보여준다고
판단했다" 이지 검증된 사실이 아니다.** 판단이 틀린 Skill 이 나오면 true 로 바꾸고 대조
풀이를 붙인다.

### 3. 그 Skill 을 PRIMARY 로 갖는 문제는 대조 풀이가 걸려야 한다

`problems/<CODE>/skill_control.py` 는 **그 Skill 없이 같은 답을 내는 풀이**다.
`problem.yaml` 의 `skillControl` 에 무엇을 대신 썼는지 적는다(없으면 `null`, 필드는 필수).

```
check_problems   needs_skill_control 인 PRIMARY 에 skillControl 이 없으면 실패
                 skillControl 과 skill_control.py 가 짝이 안 맞으면 실패
verify_problems  skill_control.py 가 TIME_LIMIT 이고, 그 전 case 는 전부 ACCEPTED
```

**TIME_LIMIT 인 것만으로는 부족하다.** 처음부터 틀리는 풀이도 걸리기는 한다 - 그건 Skill
없는 풀이가 아니라 틀린 풀이라서 아무것도 보여주지 못한다. 대조군을 돌려 확인했다.

| P01 의 skill_control.py 를 | verify_problems |
| --- | --- |
| list `pop(0)` 풀이 (그대로) | `[O]` 대조 풀이 TIME_LIMIT |
| 앞 위치 번호를 둔 list 풀이로 바꾸면 | `[X]` ACCEPTED - 재지 못한다 `[VACUOUS]` |
| 오답(wrong.py)으로 바꾸면 | `[X]` TIME_LIMIT 이 아니라 WRONG_ANSWER |

### 4. 생성에서는 채택 단계로 막는다

초안 계약에 `skillControl`(대조 풀이 · 큰 입력 생성기)을 둔다(프롬프트 `problem-v2`).
교차 검증 뒤에 **Skill 측정** 단계를 넣는다.

```
계약 → 참조 → 중복 → 입력 생성기 → 교차 검증 → Skill 측정 → 문제 데이터 검사 → 실제 채점
```

```
같은 답      작은 입력 전부에서 정답과 같다
정답은 빠름   큰 입력에서 정답이 1000ms 안에 끝난다 (제한의 절반)
대조는 느림   큰 입력에서 대조 풀이가 TIME_LIMIT
```

통과하면 큰 입력이 숨은 `MAXIMUM` case 로 **맨 뒤에** 들어간다 - 대조 풀이가 거기서
시간 초과하고 그 앞은 전부 맞혀야 하기 때문이다.

`meta_test_adoption.py` 에 일곱 케이스를 더했다. 잴 수 없는 Skill 을 보조로 두면 `참조` 에서 막힌다. 대조를 갖춘 큐 초안은 채택되고, 대조 풀이가
없거나 · 시간 안에 끝나거나(P18 의 모양) · 그냥 틀리거나 · 정답이 큰 입력에서 느리거나 ·
큰 입력 생성기가 비면 **Skill 측정** 단계에서 막힌다. 뒤 두 케이스는 **사유까지** 본다 -
빈 입력 검사를 꺼도 정답이 빈 입력에서 터져 같은 단계에 걸렸다(대조군으로 확인).

### 5. 잴 수 없는 Skill 은 PRIMARY 로만 붙는다

SECONDARY 도 관측값은 PRIMARY 와 같은 alpha 로 EMA 에 들어가고, weight 는 confidence 에만
곱해진다. 대조 풀이는 PRIMARY 에만 붙으므로, **SECONDARY 로 붙으면 대조 없이 그 Skill 의
Evidence 가 쌓인다.** P14 · P16 이 `PYTHON_DEQUE_BASIC` 을 SECONDARY 로 가졌고 `list.pop(0)`
BFS 가 둘 다 AC 였다(검증 에이전트가 찾았다). `check_problems` 가 막고, 채택 검사는 `참조`
단계에서 막으며, 생성기 프롬프트는 그런 Skill 을 보조 후보로 보여주지 않는다.

## 결과

- **P01** 을 고쳤다. 넣은 값으로 줄을 400000 칸 채운 뒤 **넣기와 꺼내기를 번갈아** 90000 번
  하는 큰 case 하나를 맨 뒤에 넣었다.

  검증 에이전트가 세 번 뚫었다. 전부 "원소를 옮기는" 풀이가 시간 안에 들어온 것이다.

  | 시도 | 뚫은 풀이 |
  | --- | --- |
  | 처음 줄 1..M 만 길다 | 처음 줄은 번호만 세고 넣은 값만 list 에 두고 `pop(0)` |
  | push 를 몰아 넣고 pop 을 몰아 꺼낸다 | 넣기↔꺼내기가 바뀔 때만 list 를 뒤집는다 - 한 번만 옮긴다 |
  | 값을 한 자리로 줄였다(출력 상한 때문에) | `bytearray` 의 `pop(0)` - 원소가 1 바이트라 옮겨도 빠르다 |

  그래서 세 가지를 함께 정했다. 길이는 **넣은 값**으로 만들고, 꺼낼 때마다 옮기는 풀이가
  **매번** 옮기게 하고, 값은 **2 바이트 배열에 담기지 않게** 여섯 자리로 둔다. 출력 상한(1MB)
  때문에 pop 은 90000 번까지다(값 ≤ 10^9 이면 한 줄 최대 11 바이트).

  | 풀이 (채점 샌드박스 그대로, 큰 case 만 제한을 15000ms 로 늘려 두 번 실측 - 검증 에이전트) | 시간 |
  | --- | --- |
  | 정답 (deque) | 516ms / 409ms |
  | `array('i')` 의 `pop(0)` | 10160ms / 8451ms |
  | list `pop(0)` (skill_control.py) | 15000ms 에서 끊김 |

  실제 채점은 `--cpus 0.5` 다(`DOCKER_LIMITS`). 처음에 `--cpus=1` 로 잰 값(`array('i')` 3971ms)은
  여유를 작게 적은 것이었다.

  채점에서는 `pop(0)` · `insert(0)` · 뒤집기 · `array('i')` 풀이 모두 TIME_LIMIT, 앞 위치 번호
  풀이는 ACCEPTED. `cases.json` 이 7.5MB 가 됐다 - Worker 가 호스트에서 읽으므로(judge-job
  계약) 큐 행에는 들어가지 않는다

  정의상 받아들이는 풀이도 확인됐다(검증 에이전트): 앞 위치 번호, 두 스택 큐, 주기적으로
  앞부분을 한 번에 지우는 list. 셋 다 꺼낼 때마다 옮기지 않는다
  `heapq` 에 (들어온 순번, 값) 을 넣는 풀이도 ACCEPTED 다. 꺼낼 때 O(log n) 개만 옮기므로 "꺼낼
  때마다 전체를 옮기지 않는다" 에 들어간다고 본다
- **P16** 은 `(거리, 번호)` 로 정렬하는 풀이가 AC 였다(검증 에이전트). 모든 case 에서 부모
  처리 순서와 번호 순서가 같았다. 둘이 갈리는 반례와 큰 case(N=1000)를 넣었고, 채택기가
  같은 무작위 입력을 두 번 남기던 것을 고쳐 **서로 다르고 큰 것**을 남기게 했다
- **P18** 을 철회했다. 거절 기록에 단계 `Skill 측정` 으로 남겼다. 번호는 다시 쓰지 않는다
- 새 deque 문제를 `problem-v2` 로 다시 생성했다(초안 2, 실제 Claude CLI). **채택 0.**

  | 초안 | 결과 |
  | --- | --- |
  | 1 | `Skill 측정` 거절 - 정답이 큰 입력에서 OUTPUT_LIMIT |
  | 2 | 채택 검사 전부 통과(P20 라운드 로빈 종료 순서) → **`사람 검토` 거절** |

  2 는 종료 순서가 `(필요 시간, 번호)` 정렬과 같다. 큐 없이 정렬만 하는 풀이가 실제 채점에서
  `ACCEPTED` 였다. Skill 측정 단계는 초안이 낸 대조 풀이(`pop(0)`)가 걸리는 것까지만 확인했다.
  그래서 deque Skill 의 문제는 다시 P01 하나다. 파일럿 전과 같고, FailingJourneyTest 가
  이미 이 경우를 걷는다(ADR-0030)

## 남는 위험

- **대조 풀이는 초안이 고른 하나뿐이다.** 단계가 확인하는 것은 "그 풀이는 걸린다" 이지
  "Skill 없이 풀 방법이 없다" 가 아니다. 모든 지름길을 기계로 찾을 수는 없다. 재생성에서
  실제로 났다 - 정렬 지름길이 있는 문제가 단계를 통과했다(위 결과). **사람의 PR 검토가
  마지막 방어선인 이유가 두 번째로 확인됐다.** 이 단계는 알려진 모양(P18 처럼 초안
  스스로 효율적인 대조를 내거나, 대조를 빼는 경우)을 막는 바닥이지 보장이 아니다
- **시간으로만 가른다.** 기계가 몇 배 빠르면 `pop(0)` 풀이가 제한 안에 들어올 수 있다.
  P01 에서 가장 가까운 것은 `array('i')` 의 `pop(0)` 으로 채점 조건에서 제한의 4.2~5.1배다.
  기계가 그만큼 빠르면 들어올 수 있다
- **큰 case 의 기대 출력은 정답 하나로 만든다.** 작은 입력에서 막은 "자기 답과 자기 비교" 가
  큰 case 에는 남는다. 완전탐색은 대조 풀이처럼 느려서 큰 입력에서 돌릴 수 없다
- 앞 위치 번호를 둔 list 는 deque 를 몰라도 통과한다. 정의를 그렇게 고쳤기 때문이고,
  "deque 라는 이름을 아는가" 는 이 채점으로 재지 않는다
- `needs_skill_control: false` 인 7개 Skill 은 판단이지 검증이 아니다
- 큰 case 는 저장소와 채점 시간을 늘린다
