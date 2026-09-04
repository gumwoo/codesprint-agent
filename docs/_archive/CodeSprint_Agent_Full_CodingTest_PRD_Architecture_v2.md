# CodeSprint Agent
## 전 범위 코딩테스트 AI 학습 에이전트 — 제품 기획 및 상세 설계서

---

# 0. 문서의 전제

이 문서는 특정 사용자의 과거 학습 이력, 익숙한 알고리즘, 선호 문제 유형을 전제로 하지 않는다.

서비스에 처음 가입한 사용자가 다음 상태 중 어디에 있든 사용할 수 있어야 한다.

- 코딩테스트를 처음 시작하는 사용자
- 문법만 아는 사용자
- 백준 Bronze/Silver 수준 사용자
- 프로그래머스 Lv.1~2 수준 사용자
- 백준 Gold 수준을 목표로 하는 사용자
- 기업 코딩테스트 직전 단기 대비 사용자
- 이미 많은 문제를 풀었지만 특정 유형에서 반복적으로 실패하는 사용자

따라서 제품의 학습 체계는 특정 세 유형에 한정하지 않고,
**취업 코딩테스트에서 실제로 접할 수 있는 전체 알고리즘/자료구조 영역을 하나의 Skill Graph로 관리한다.**

---

# 1. 프로젝트 정의

## 1.1 프로젝트명

**CodeSprint Agent**

### 한 줄 정의

> 사용자의 목표, 현재 실력, 풀이 과정, 코드, 오답 원인, 힌트 의존도, 풀이 시간, 시험일까지 남은 시간을 분석하여 **다음에 무엇을 공부하고 어떤 문제를 풀어야 하는지를 스스로 결정하는 AI 코딩테스트 학습 에이전트**

---

# 2. 해결하려는 문제

기존 코딩테스트 공부는 대부분 다음처럼 진행된다.

```text
알고리즘 목록 검색
→ 강의 선택
→ 문제 검색
→ 문제 풀이
→ 막힘
→ 블로그 검색
→ 정답 코드 확인
→ 비슷한 문제 검색
→ 며칠 후 다시 무엇을 할지 고민
```

이 과정에서 학습자는 반복적으로 다음 판단을 직접 해야 한다.

- 어떤 유형을 먼저 공부해야 하는가?
- 지금 수준에서 이 문제를 풀어도 되는가?
- 문제를 틀린 이유가 개념 부족인가, 구현 실수인가?
- 다음에는 같은 유형을 풀어야 하는가, 더 쉬운 문제를 풀어야 하는가?
- 언제 복습해야 하는가?
- 시험까지 시간이 얼마 없을 때 무엇을 버려야 하는가?
- 같은 실수를 반복하는 이유는 무엇인가?
- 개념을 안다고 생각하는데 실제로 독립 풀이가 가능한가?

CodeSprint Agent는 이 **학습 운영 판단 자체를 AI Agent와 규칙 엔진이 대신 수행**한다.

---

# 3. 제품의 핵심 원칙

## 3.1 문제 추천 서비스가 아니다

핵심은 다음 문제를 단순 추천하는 것이 아니다.

```text
학습 상태 파악
→ 부족한 Skill 결정
→ 필요한 설명 제공
→ 문제 선택
→ 풀이 과정 관찰
→ 오답 원인 분석
→ 숙련도 갱신
→ 다음 행동 결정
```

이 폐쇄 루프를 계속 반복한다.

이를 본 문서에서는 **Adaptive Learning Loop**라고 정의한다.

---

## 3.2 문제 수보다 Skill 획득 속도를 최적화한다

잘못된 KPI:

```text
오늘 20문제 풀이
```

제품이 추구하는 KPI:

```text
오늘 shortest_path_bfs Skill
독립 풀이 가능 상태 도달
```

---

## 3.3 LLM은 설명과 추론을 담당하고, 판정은 시스템이 담당한다

LLM에게 맡기지 않는 영역:

- 코드 실행
- Accepted / Wrong Answer 판정
- 시간 제한
- 메모리 제한
- 테스트케이스 실행 결과
- Skill 점수 산식
- 복습 날짜 기본 계산
- 동일 문제 중복 여부
- 학습 이벤트 기록

LLM이 담당하는 영역:

- 개념 설명
- 코드 의미 분석
- 오답 원인 후보 도출
- 단계별 힌트
- 문제 변형
- 학습 계획 설명
- 문제 난이도 조정 사유 설명

---

# 4. 전체 코딩테스트 커리큘럼

이 서비스는 아래 영역 전체를 Skill Graph에 포함한다.

난이도는 세 구간으로 구분한다.

- **CORE**: 대부분의 취업 코딩테스트 준비에 필수
- **INTERMEDIATE**: 중상 난도 기업/백준 Gold 진입에 중요
- **ADVANCED**: 고난도 코딩테스트 및 알고리즘 심화

---

# 5. Curriculum Domain Map

```text
00. Programming Foundations
01. Implementation / Simulation
02. Array / Matrix
03. String
04. Hash / Set / Map
05. Sorting
06. Stack / Queue / Deque
07. Heap / Priority Queue
08. Recursion
09. Brute Force / Enumeration
10. Backtracking
11. Two Pointer
12. Sliding Window
13. Prefix Sum / Difference Array
14. Binary Search / Parametric Search
15. Greedy
16. Mathematics
17. Number Theory
18. Combinatorics
19. Bit Manipulation / Bitmask
20. DFS
21. BFS
22. Graph Fundamentals
23. Shortest Path
24. Union Find
25. Minimum Spanning Tree
26. Topological Sort / DAG
27. Tree
28. Dynamic Programming
29. LIS / LCS / Sequence DP
30. Knapsack / Subset DP
31. Grid / State Space Search
32. Coordinate Compression
33. Monotonic Stack / Queue
34. Trie
35. String Matching
36. Segment Tree
37. Fenwick Tree
38. Sweep Line / Interval
39. Geometry
40. SCC / Articulation / Bridge
41. Network Flow / Matching
42. Advanced Shortest Path
43. Advanced DP
44. Advanced String
45. Mixed / Composite Problems
```

---

# 6. 00. Programming Foundations

등급: CORE

## Skills

```text
input_output
variable
condition
loop
function
list_basic
tuple_basic
dict_basic
set_basic
comprehension
lambda_basic
sorting_api
deque_api
heapq_api
counter_api
defaultdict_api
enumerate
zip
range
min_max_sum
string_split_join
integer_conversion
```

## Python 학습 포인트

```python
input()
map(int, input().split())
list(map(int, input().split()))
```

```python
for i in range(n):
    ...
```

```python
arr = [0] * n
grid = [[0] * m for _ in range(n)]
```

```python
from collections import deque, Counter, defaultdict
import heapq
```

## Agent 판정

문법 자체에서 반복 실패하면 알고리즘 설명으로 넘어가지 않는다.

예:

```text
IndexError 반복
→ array_indexing Skill 보강

split/mapping 오류 반복
→ input_parsing Skill 보강
```

---

# 7. 01. Implementation / Simulation

등급: CORE

## Skills

```text
simple_condition
state_update
simulation
direction_array
coordinate_move
boundary_check
matrix_rotation
matrix_flip
clockwise_counterclockwise
command_processing
case_handling
multi_condition
time_simulation
object_movement
collision_simulation
board_simulation
implementation_large
```

## 대표 패턴

### 방향 배열

```python
dx = [-1, 1, 0, 0]
dy = [0, 0, -1, 1]
```

### 상태 시뮬레이션

```text
입력
→ 현재 상태
→ 명령 처리
→ 상태 변경
→ 결과
```

## 대표 문제 템플릿

- 로봇 이동
- 주사위 이동
- 게임판 시뮬레이션
- 문자열 명령 처리
- 시간 흐름 시뮬레이션
- 회전/이동
- 격자 내 충돌

---

# 8. 02. Array / Matrix

등급: CORE

## Skills

```text
array_traversal
matrix_traversal
indexing
row_column
transpose
matrix_sum
matrix_boundary
frequency_array
in_place_update
copy_vs_reference
multi_dimensional_array
```

## 반복 실수 분류

```text
INDEX_ERROR
ROW_COLUMN_SWAP
SHALLOW_COPY
BOUNDARY_ERROR
INITIALIZATION_ERROR
```

---

# 9. 03. String

등급: CORE

## Skills

```text
character_iteration
substring
split
join
replace
frequency
palindrome
anagram
string_sort
run_length
parsing
tokenization
pattern_basic
case_conversion
numeric_string
```

## INTERMEDIATE

```text
rolling_hash
kmp
z_algorithm
trie_search
```

---

# 10. 04. Hash / Set / Map

등급: CORE

## Skills

```text
existence_check
frequency_count
mapping
duplicate_detection
grouping
reverse_mapping
two_sum_hash
set_operation
intersection
union
difference
counter
defaultdict
```

## 핵심 판단

```text
"존재하는가?"
→ set

"몇 개인가?"
→ Counter / dict

"키로 값을 찾는가?"
→ dict
```

---

# 11. 05. Sorting

등급: CORE

## Skills

```text
ascending
descending
multi_key
custom_key
stable_sort
tuple_sort
coordinate_sort
string_sort
frequency_sort
sorting_then_scan
```

## Python

```python
arr.sort()
sorted(arr)
sorted(arr, key=lambda x: ...)
```

## Agent가 확인할 부분

- 정렬 후 해결 가능한 문제인지
- O(N log N)이 허용되는지
- key를 올바르게 구성했는지

---

# 12. 06. Stack / Queue / Deque

등급: CORE

## Stack Skills

```text
stack_basic
parentheses
undo
expression
next_greater_element
monotonic_stack
```

## Queue Skills

```text
queue_basic
fifo
process_simulation
bfs_queue
```

## Deque Skills

```text
both_end
rotation
sliding_window
```

---

# 13. 07. Heap / Priority Queue

등급: CORE / INTERMEDIATE

## Skills

```text
min_heap
max_heap
top_k
priority_processing
two_heap
median_stream
heap_greedy
dijkstra_heap
```

Python:

```python
heapq.heappush(heap, value)
heapq.heappop(heap)
```

Max Heap:

```python
heapq.heappush(heap, -value)
```

---

# 14. 08. Recursion

등급: CORE

## Skills

```text
base_case
recursive_call
call_stack
divide_and_conquer_basic
recursive_tree
```

## 반복 오류

```text
NO_BASE_CASE
WRONG_BASE_CASE
INFINITE_RECURSION
STATE_NOT_RESTORED
```

---

# 15. 09. Brute Force / Enumeration

등급: CORE

## Skills

```text
full_enumeration
nested_loop
candidate_generation
permutation
combination
product
subset
search_space_estimation
```

핵심 질문:

```text
N이 몇인가?
가능한 경우의 수가 몇인가?
전부 검사해도 시간 안에 끝나는가?
```

---

# 16. 10. Backtracking

등급: CORE / INTERMEDIATE

## Skills

```text
choose
explore
unchoose
visited_backtracking
permutation_backtracking
combination_backtracking
constraint_pruning
n_queen
board_search
```

표준 구조:

```python
def dfs(depth):
    if depth == target:
        return

    for candidate in candidates:
        if valid(candidate):
            choose(candidate)
            dfs(depth + 1)
            undo(candidate)
```

---

# 17. 11. Two Pointer

등급: CORE

## Skills

```text
left_right_pointer
sorted_two_pointer
pair_sum
interval_sum
merge_two_arrays
opposite_direction
same_direction
```

대표 조건:

```text
연속 구간
정렬된 배열
두 수의 합
양 끝에서 좁혀오기
```

---

# 18. 12. Sliding Window

등급: CORE / INTERMEDIATE

## Skills

```text
fixed_window
variable_window
window_frequency
window_distinct
minimum_window
maximum_window
```

구분:

```text
고정 길이
→ sliding window

조건 만족할 때 길이 변화
→ two pointer / variable sliding window
```

---

# 19. 13. Prefix Sum / Difference Array

등급: CORE

## Skills

```text
prefix_1d
range_sum
prefix_2d
matrix_range_sum
difference_array
range_update
cumulative_count
```

1차원:

```python
prefix[i + 1] = prefix[i] + arr[i]
```

구간합:

```python
prefix[r + 1] - prefix[l]
```

---

# 20. 14. Binary Search / Parametric Search

등급: CORE

## Skills

```text
binary_search_exact
lower_bound
upper_bound
count_range
binary_answer
parametric_search
feasibility_function
```

핵심 질문:

```text
정답 후보에 단조성이 있는가?
```

예:

```text
가능 가능 가능 불가능 불가능
```

이면 이분 탐색 가능.

---

# 21. 15. Greedy

등급: CORE

## Skills

```text
local_optimum
sorting_greedy
interval_scheduling
coin_greedy
resource_selection
deadline
priority_greedy
exchange_argument
```

Agent는 정답만 맞았다고 Greedy 숙련으로 판정하지 않는다.

확인 질문:

```text
왜 현재 선택이 이후 최적해를 망치지 않는가?
```

---

# 22. 16. Mathematics

등급: CORE

## Skills

```text
arithmetic
modular_arithmetic
gcd_lcm
formula
digit_math
coordinate_math
sequence
floor_ceiling
```

Python:

```python
import math

math.gcd(a, b)
```

---

# 23. 17. Number Theory

등급: INTERMEDIATE

## Skills

```text
prime_check
sieve
factorization
gcd
lcm
euclidean
modular_power
mod_inverse
divisor
euler_phi
```

---

# 24. 18. Combinatorics

등급: INTERMEDIATE

## Skills

```text
factorial
nCr
nPr
combination_mod
pascal_triangle
counting_principle
inclusion_exclusion_basic
```

---

# 25. 19. Bit Manipulation / Bitmask

등급: INTERMEDIATE

## Skills

```text
bit_and
bit_or
bit_xor
bit_shift
bit_test
bit_set
bit_clear
subset_bitmask
state_compression
bitmask_dp_basic
```

예:

```python
if mask & (1 << i):
    ...
```

---

# 26. 20. DFS

등급: CORE

## Skills

```text
dfs_recursive
dfs_iterative
visited
graph_dfs
grid_dfs
connected_component
area_size
cycle_detection_basic
path_search
component_count
```

## 세부 실수

```text
VISITED_TIMING
NO_VISITED
BOUNDARY_CHECK
RECURSION_DEPTH
STATE_LEAK
```

---

# 27. 21. BFS

등급: CORE

## Skills

```text
bfs_basic
queue
visited
distance
grid_bfs
graph_bfs
shortest_path_unweighted
multi_source_bfs
level_order
state_bfs
```

## 중요 변형

```text
일반 BFS
다중 시작점 BFS
상태를 포함한 BFS
벽 부수기 BFS
3차원 BFS
```

---

# 28. 22. Graph Fundamentals

등급: CORE

## Skills

```text
vertex_edge
directed_undirected
weighted_unweighted
adjacency_list
adjacency_matrix
degree
connected_component
cycle
graph_traversal
```

자료구조:

```python
graph = [[] for _ in range(n)]
```

---

# 29. 23. Shortest Path

등급: INTERMEDIATE

## Skills

```text
bfs_shortest_path
dijkstra
bellman_ford
floyd_warshall
zero_one_bfs
multi_source_shortest
```

선택 기준:

```text
가중치 없음
→ BFS

가중치가 음수 없음
→ Dijkstra

음수 간선 가능
→ Bellman-Ford

모든 정점 쌍
→ Floyd-Warshall

가중치 0/1
→ 0-1 BFS
```

---

# 30. 24. Union Find

등급: INTERMEDIATE

## Skills

```text
find
union
path_compression
union_by_rank
connectivity
cycle_detection
```

---

# 31. 25. Minimum Spanning Tree

등급: INTERMEDIATE

## Skills

```text
mst_concept
kruskal
prim
union_find_mst
edge_sort
```

---

# 32. 26. Topological Sort / DAG

등급: INTERMEDIATE

## Skills

```text
indegree
topological_queue
dag
dependency
ordering
cycle_detection_dag
```

대표 문제:

- 선수 과목
- 작업 순서
- 빌드 순서
- 의존성

---

# 33. 27. Tree

등급: CORE / INTERMEDIATE

## Basic Skills

```text
tree_concept
root_parent_child
tree_dfs
tree_bfs
subtree
depth
leaf
```

## Intermediate Skills

```text
tree_diameter
lca
binary_tree
bst
tree_dp
```

---

# 34. 28. Dynamic Programming

등급: CORE / INTERMEDIATE

## 핵심 Skills

```text
dp_state
dp_transition
base_case
memoization
tabulation
one_dimensional_dp
two_dimensional_dp
grid_dp
counting_dp
minimum_cost_dp
maximum_value_dp
```

핵심 질문:

```text
큰 문제를 작은 문제로 나눌 수 있는가?
같은 작은 문제가 반복되는가?
상태를 무엇으로 정의할 것인가?
```

---

# 35. 29. LIS / LCS / Sequence DP

등급: INTERMEDIATE

## Skills

```text
lis_n2
lis_nlogn
lcs
edit_distance
sequence_matching
```

---

# 36. 30. Knapsack / Subset DP

등급: INTERMEDIATE

## Skills

```text
zero_one_knapsack
unbounded_knapsack
subset_sum
coin_change
partition
```

---

# 37. 31. Grid / State Space Search

등급: CORE / INTERMEDIATE

## Skills

```text
grid_traversal
state_encoding
position_plus_state
wall_break
key_door
multi_dimension
simulation_bfs
```

State 예:

```text
(x, y, wallBroken)
(x, y, keyMask)
(x, y, direction)
```

---

# 38. 32. Coordinate Compression

등급: INTERMEDIATE

## Skills

```text
unique_sort
rank_mapping
coordinate_remap
large_coordinate
```

---

# 39. 33. Monotonic Stack / Queue

등급: INTERMEDIATE

## Skills

```text
next_greater
previous_greater
histogram
stock_span
window_minimum
window_maximum
```

---

# 40. 34. Trie

등급: INTERMEDIATE

## Skills

```text
insert
search
prefix
autocomplete
dictionary_tree
```

---

# 41. 35. String Matching

등급: INTERMEDIATE / ADVANCED

## Skills

```text
naive_matching
kmp
prefix_function
z_algorithm
rolling_hash
```

---

# 42. 36. Segment Tree

등급: ADVANCED

## Skills

```text
segment_tree_build
range_query
point_update
range_min
range_max
range_sum
lazy_propagation
```

---

# 43. 37. Fenwick Tree

등급: ADVANCED

## Skills

```text
fenwick_prefix
fenwick_update
range_sum
inversion_count
```

---

# 44. 38. Sweep Line / Interval

등급: INTERMEDIATE / ADVANCED

## Skills

```text
interval_sort
interval_merge
event_point
sweep_line
overlap_count
```

---

# 45. 39. Geometry

등급: INTERMEDIATE / ADVANCED

## Skills

```text
distance
orientation
ccw
line_intersection
polygon_area
convex_hull
```

---

# 46. 40. SCC / Articulation / Bridge

등급: ADVANCED

## Skills

```text
scc
tarjan
kosaraju
articulation_point
bridge
```

---

# 47. 41. Network Flow / Matching

등급: ADVANCED

## Skills

```text
max_flow
ford_fulkerson
edmonds_karp
dinic
bipartite_matching
```

---

# 48. 42. Advanced Shortest Path

등급: ADVANCED

## Skills

```text
zero_one_bfs
multi_source
state_dijkstra
k_shortest_basic
johnson_basic
```

---

# 49. 43. Advanced DP

등급: ADVANCED

## Skills

```text
tree_dp
bitmask_dp
digit_dp
interval_dp
dag_dp
rerooting_dp
```

---

# 50. 44. Advanced String

등급: ADVANCED

## Skills

```text
suffix_array
suffix_automaton
aho_corasick
manacher
```

---

# 51. 45. Mixed / Composite Problems

실제 코딩테스트 문제는 하나의 태그로 끝나지 않는 경우가 많다.

예:

```text
Sorting + Greedy
BFS + State
Binary Search + Prefix Sum
Dijkstra + DP
Graph + Union Find
Simulation + Queue
Hash + Sliding Window
```

문제는 복수 Skill을 가진다.

예:

```json
{
  "problemId": 10012,
  "skills": [
    {
      "skill": "sliding_window",
      "weight": 0.6
    },
    {
      "skill": "frequency_count",
      "weight": 0.4
    }
  ]
}
```

---

# 52. Skill Graph 설계

Skill은 단순 목록이 아니라 선수 관계를 가진다.

예:

```text
queue_basic
   ↓
bfs_basic
   ↓
grid_bfs
   ↓
shortest_path_unweighted
   ↓
state_bfs
   ↓
zero_one_bfs
```

DP:

```text
recursion
   ↓
memoization
   ↓
dp_state
   ↓
one_dimensional_dp
   ↓
two_dimensional_dp
   ├── knapsack
   ├── lcs
   └── grid_dp
```

Graph:

```text
graph_basic
   ├── dfs
   ├── bfs
   ├── union_find
   ├── dijkstra
   └── topological_sort
```

---

# 53. Skill 데이터 모델

```text
skills

id
code
name
domain
tier
difficulty
description
parent_skill_id
is_active
```

선수 관계:

```text
skill_prerequisites

skill_id
prerequisite_skill_id
minimum_mastery
```

예:

```text
dijkstra
requires
priority_queue >= 0.65
graph_weighted >= 0.70
```

---

# 54. 사용자 실력 모델

사용자는 하나의 "알고리즘 점수"를 갖는 것이 아니라 Skill 단위 점수를 가진다.

```json
{
  "skillCode": "bfs_shortest_path",
  "concept": 0.82,
  "recognition": 0.76,
  "implementation": 0.68,
  "independentSolve": 0.52,
  "retention": 0.61,
  "speed": 0.57,
  "hintDependency": 0.21
}
```

---

# 55. Skill 평가 차원

## Concept

개념을 설명할 수 있는가?

## Recognition

문제를 보고 어떤 알고리즘을 사용해야 하는지 알아차리는가?

## Implementation

알고리즘을 코드로 구현할 수 있는가?

## Independent Solve

힌트 없이 해결 가능한가?

## Retention

며칠 뒤에도 해결 가능한가?

## Speed

제한 시간 내 해결 가능한가?

---

# 56. 초기 진단 시스템

신규 사용자는 과거 학습 기록이 없다고 가정한다.

입력:

```text
사용 언어
목표 플랫폼
목표 난이도
시험 날짜
하루 가능 시간
```

그 후 진단 문제를 제공한다.

---

# 57. Adaptive Diagnostic Test

처음부터 모든 유형 문제를 하나씩 45개 주지 않는다.

진단은 분기형으로 동작한다.

예:

```text
Implementation 성공
→ Array/String 중간 난도

Hash 성공
→ Hash 응용

DFS 실패
→ DFS 기초 확인

BFS 성공
→ shortest path 확인
```

약 15~25문제 내에서 주요 영역 수준을 추정한다.

---

# 58. 진단 영역

최소 다음 영역은 초기 수준을 추정한다.

```text
Programming
Implementation
Array/String
Hash
Sorting
Stack/Queue
Brute Force
Two Pointer
Binary Search
Greedy
DFS/BFS
Graph
DP
Math
```

상위 목표 사용자에게 추가:

```text
Shortest Path
Union Find
MST
Topological Sort
Tree
Advanced DP
Data Structure
```

---

# 59. 학습 단계 모델

각 Skill은 다음 상태를 가진다.

```text
LOCKED
READY
LEARNING
PRACTICING
MASTERED
REVIEW_DUE
WEAKENED
```

예:

```text
bfs_basic       MASTERED
grid_bfs        MASTERED
shortest_path   PRACTICING
state_bfs       LOCKED
```

---

# 60. 학습 세션 구조

한 Skill 세션은 다음으로 구성된다.

```text
1. Recall
2. Micro Concept
3. Guided Example
4. Basic Practice
5. Variant Practice
6. Independent Problem
7. Review
```

항상 모든 단계를 거칠 필요는 없다.

Agent가 이미 숙련된 단계는 생략한다.

---

# 61. Micro Concept

개념 설명은 기본적으로 3~7분 분량이다.

예:

```text
Binary Search

판단 기준은 한 가지입니다.

정답 후보 x에 대해
"isPossible(x)"가 단조성을 가지면
정답 자체를 이분 탐색할 수 있습니다.
```

긴 이론 강의보다 문제 해결에 필요한 핵심만 먼저 제공한다.

---

# 62. 문제 난이도 모델

문제 난이도를 하나의 숫자로만 관리하지 않는다.

```json
{
  "conceptDifficulty": 2,
  "implementationDifficulty": 4,
  "searchSpaceDifficulty": 3,
  "edgeCaseDifficulty": 4,
  "requiredSkills": 2
}
```

같은 BFS라도:

```text
개념은 쉬운데 구현이 복잡한 문제
개념은 어려운데 코드는 짧은 문제
```

를 구분할 수 있다.

---

# 63. Problem Template System

생성 문제는 템플릿을 가진다.

예:

```text
GRID_CONNECTED_COMPONENT

parameters:
- rows
- columns
- obstacleRatio
- adjacency
- requiredOutput
```

변형:

```text
영역 개수
영역 크기
최대 영역
특정 조건 영역
벽 제거 후 영역
```

---

# 64. 문제 소스 전략

## A. Curated

직접 검수한 자체 문제.

## B. Public Metadata Linked

외부 플랫폼 문제의 링크/메타데이터만 관리.

저작권이 있는 문제 전문을 무단 저장하지 않는다.

## C. Template Generated

검증된 템플릿으로 생성.

## D. LLM Generated

특정 약점용 미니 문제.

---

# 65. LLM 생성 문제 검증

```text
Problem Generator
↓
Reference Solution
↓
Independent Solver
↓
Test Generator
↓
Code Runner
↓
Constraint Validator
↓
Ambiguity Checker
↓
Publish
```

두 솔루션의 출력이 불일치하면 사용자에게 제공하지 않는다.

---

# 66. Test Case 구성

각 문제는 최소 다음 케이스를 가진다.

```text
sample
minimum
maximum
boundary
duplicate
empty-like
single element
all same
sorted
reverse sorted
random
adversarial
```

유형에 따라 자동 확장한다.

---

# 67. 코드 실행 시스템

지원 언어는 확장 가능하게 설계한다.

```text
Python
Java
C++
JavaScript
Kotlin
```

초기 배포에서 한 언어만 먼저 활성화할 수 있지만,
**Skill Graph와 제품 설계 자체는 특정 언어에 종속되지 않는다.**

---

# 68. Judge Architecture

```text
Submission API
↓
Queue
↓
Sandbox Runner
↓
Compile
↓
Execute Test Cases
↓
Judge
↓
Result Store
↓
Reviewer Agent
```

상태:

```text
ACCEPTED
WRONG_ANSWER
TIME_LIMIT
MEMORY_LIMIT
RUNTIME_ERROR
COMPILE_ERROR
```

---

# 69. Sandbox 요구사항

```text
Network disabled
CPU limit
Memory limit
Process limit
Filesystem restriction
Timeout
Read-only runtime
Ephemeral container
```

---

# 70. 오답 원인 Taxonomy

```text
SYNTAX_ERROR
INPUT_PARSE
OUTPUT_FORMAT
ALGORITHM_SELECTION
PROBLEM_UNDERSTANDING
DATA_STRUCTURE_SELECTION
STATE_DEFINITION
TRANSITION_ERROR
BASE_CASE
BOUNDARY_CHECK
INDEX_ERROR
VISITED_TIMING
VISITED_MISSING
INITIALIZATION
LOOP_CONDITION
TERMINATION
OFF_BY_ONE
DUPLICATE_PROCESSING
SORT_KEY
INTEGER_OVERFLOW
FLOAT_PRECISION
TIME_COMPLEXITY
MEMORY_COMPLEXITY
RECURSION_DEPTH
STATE_RESTORE
PRUNING
GRAPH_DIRECTION
EDGE_WEIGHT
DISTANCE_UPDATE
DP_ORDER
DP_TRANSITION
GREEDY_PROOF
BINARY_SEARCH_BOUND
HEAP_DIRECTION
UNION_FIND_PARENT
IMPLEMENTATION_MISC
```

---

# 71. Reviewer Agent 출력

```json
{
  "primaryMistake": "BINARY_SEARCH_BOUND",
  "secondaryMistakes": [
    "OFF_BY_ONE"
  ],
  "algorithmSelection": "CORRECT",
  "conceptIssue": false,
  "implementationIssue": true,
  "confidence": 0.93,
  "affectedSkills": [
    "binary_search_exact"
  ],
  "recommendedAction": "MICRO_DRILL"
}
```

---

# 72. Micro Drill

사용자가 특정 실수를 반복하면 긴 문제 대신 2~5분짜리 문제를 준다.

예:

```text
OFF_BY_ONE 반복
→ 배열 경계 미니 문제 3개

VISITED_TIMING 반복
→ BFS 방문 시점 비교 문제

BINARY_SEARCH_BOUND 반복
→ lower/upper bound 미니 문제
```

---

# 73. Hint System

6단계 기본 구조:

```text
H1 문제 관찰 포인트
H2 알고리즘 범주
H3 자료구조 / 상태
H4 핵심 전이
H5 의사코드
H6 전체 풀이
```

예:

```text
H1
"연속 구간"이라는 표현을 주목하세요.

H2
Two Pointer를 고려하세요.

H3
left, right와 현재 합을 관리하세요.

H4
합이 너무 크면 left를 증가시킵니다.
```

---

# 74. 힌트 의존도

같은 AC라도 평가가 다르다.

```text
힌트 없이 AC
→ 높은 독립 풀이 점수

H2 후 AC
→ 중간

H5 후 AC
→ 구현 연습 성공, 독립 풀이 미확보

정답 확인 후 재작성
→ 학습 성공 가능, Mastery 판정 금지
```

---

# 75. 다음 행동 Decision Engine

가능 Action:

```text
CONTINUE
HARDER
EASIER
MICRO_DRILL
REVIEW_CONCEPT
RETRY_VARIANT
CHANGE_SKILL
UNLOCK_NEXT
SCHEDULE_REVIEW
MOCK_TEST
END_SESSION
```

---

# 76. Decision 예시

```text
최근 동일 Skill:
AC / AC / AC
힌트 없음
풀이 시간 목표 이내

→ HARDER
```

```text
WA / WA
동일 오류: boundary_check

→ MICRO_DRILL
```

```text
알고리즘 자체를 잘못 선택

→ REVIEW_CONCEPT
```

```text
개념 문제 성공
구현만 반복 실패

→ RETRY_VARIANT
```

---

# 77. 숙련도 갱신

개념적 산식:

```text
Mastery =
0.15 Concept
+ 0.20 Recognition
+ 0.25 Implementation
+ 0.25 IndependentSolve
+ 0.10 Retention
+ 0.05 Speed
```

실제 서비스에서는 사용자 데이터로 가중치를 조정한다.

---

# 78. Mastered 조건 예시

```text
Mastery >= 0.80

AND

최근 독립 풀이 3문제 중 2문제 이상 성공

AND

최소 1회 복습 성공
```

정답 코드 확인 직후 맞힌 문제는 Mastered 판정에 사용하지 않는다.

---

# 79. Spaced Repetition

기본:

```text
첫 성공
→ 1일

복습 성공
→ 3일

재성공
→ 7일

안정
→ 14일

장기 유지
→ 30일
```

실패하면 간격을 축소한다.

---

# 80. Daily Planner Agent

입력:

```text
시험일까지 남은 날짜
하루 공부 가능 시간
Skill 상태
복습 예정 Skill
목표 난이도
최근 성과
```

출력:

```json
{
  "totalMinutes": 120,
  "blocks": [
    {
      "type": "REVIEW",
      "skill": "binary_search",
      "minutes": 20
    },
    {
      "type": "LEARN",
      "skill": "dijkstra",
      "minutes": 45
    },
    {
      "type": "PRACTICE",
      "skill": "greedy",
      "minutes": 35
    },
    {
      "type": "MIXED",
      "minutes": 20
    }
  ]
}
```

---

# 81. 학습 우선순위 계산

개념적 Priority:

```text
Priority =
GoalImportance
× Weakness
× Learnability
× ExamFrequency
× RetentionNeed
÷ EstimatedStudyCost
```

---

# 82. 시험 직전 모드

시험 D-7 이하부터 전략을 변경할 수 있다.

일반 모드:

```text
장기 Skill 성장
```

시험 모드:

```text
점수 상승 기대값 최대화
```

---

# 83. Learning ROI

```text
ROI =
ExpectedScoreGain
/
ExpectedMinutes
```

예:

```text
Hash 응용
30분 → +5 기대

Segment Tree 신규
240분 → +3 기대
```

시험 D-2라면 Hash를 우선한다.

---

# 84. Mock Test Mode

문제 유형을 알려주지 않는다.

```text
Problem A
Problem B
Problem C
Problem D
```

Agent는 시험 중 힌트를 제공하지 않는다.

시험 종료 후:

```text
문제 선택 전략
시간 배분
버린 문제
실수 유형
알고리즘 인식
```

까지 분석한다.

---

# 85. 시간 관리 평가

```text
문제 읽기 시간
첫 코드 작성 시간
첫 실행 시간
첫 실패 이후 수정 시간
최종 제출 시간
```

Agent 피드백 예:

```text
알고리즘은 4분 내에 올바르게 선택했지만,
구현에 21분이 소요되었습니다.

현재 병목은 개념이 아니라 구현 속도입니다.
```

---

# 86. 문제 선택 능력

실전에서는 모든 문제를 풀 필요가 없다.

Mock Test에서 다음을 학습한다.

```text
쉽고 확실한 문제 먼저
너무 오래 막히면 넘기기
남은 시간에 재도전
```

Agent가 이를 별도 Skill로 관리할 수 있다.

```text
exam_problem_selection
exam_time_management
```

---

# 87. Agent 구성

```text
Learning Orchestrator
├── Diagnostic Agent
├── Planner Agent
├── Tutor Agent
├── Problem Agent
├── Hint Agent
├── Reviewer Agent
├── Evaluator Agent
├── Review Scheduler
└── Exam Strategy Agent
```

---

# 88. Diagnostic Agent

역할:

```text
신규 사용자의 초기 수준 추정
필요한 다음 진단 문제 결정
진단 종료 시점 결정
```

---

# 89. Planner Agent

역할:

```text
오늘의 학습 목표
시간 배분
새로운 Skill vs 복습 비율
```

---

# 90. Tutor Agent

역할:

```text
Micro Concept
예제
질문형 설명
사용자 수준에 맞는 표현
```

---

# 91. Problem Agent

역할:

```text
문제 선택
문제 생성
난이도 변경
변형 문제 생성
```

---

# 92. Reviewer Agent

역할:

```text
코드 의미 분석
실패 원인 분류
시간복잡도 분석
개념 문제와 구현 문제 구분
```

---

# 93. Evaluator Agent

LLM 결과 + 시스템 이벤트를 종합한다.

```text
Judge 결과
Hint 사용량
풀이 시간
재시도
Reviewer 결과
```

을 받아 Skill State를 갱신한다.

---

# 94. Review Scheduler

복습 시기를 결정한다.

---

# 95. Exam Strategy Agent

시험일이 가까워졌을 때:

```text
새 유형 학습 여부
복습 우선순위
Mock Test 빈도
문제 포기 전략
```

을 결정한다.

---

# 96. Orchestration 원칙

모든 역할을 실제 별도 LLM 인스턴스로 만들 필요는 없다.

1차 구현:

```text
Orchestrator
+
하나의 LLM
+
Role Prompt
+
Rule Engine
```

고도화:

```text
Planner Model
Reviewer Model
Generator Model
```

분리 가능.

---

# 97. 시스템 아키텍처

```text
Web / Mobile
     ↓
API Gateway
     ↓
Learning API
     ↓
Learning Orchestrator
 ┌────┼──────────────┐
 ↓    ↓              ↓
Rule  LLM Gateway    Judge
 ↓    ↓              ↓
PostgreSQL          Sandbox
 ↓
Redis
```

---

# 98. Backend 권장 구성

```text
Spring Boot
Java 21 또는 17
PostgreSQL
Redis
Docker
```

모듈 예:

```text
user
curriculum
problem
submission
judge
learning
agent
analytics
exam
```

---

# 99. Frontend

```text
Next.js
React
TypeScript
Monaco Editor
```

핵심 화면:

```text
Dashboard
Skill Map
Learning Session
Problem Solver
Review
Mock Test
Analytics
```

---

# 100. DB — User

```sql
users
-----
id
email
nickname
preferred_language
target_platform
target_level
exam_date
daily_minutes
created_at
```

---

# 101. DB — Skill

```sql
skills
------
id
code
name
domain
tier
difficulty
description
parent_skill_id
```

---

# 102. DB — Prerequisite

```sql
skill_prerequisites
-------------------
skill_id
prerequisite_skill_id
minimum_mastery
```

---

# 103. DB — User Skill

```sql
user_skills
-----------
user_id
skill_id
concept_score
recognition_score
implementation_score
independent_score
retention_score
speed_score
mastery_score
status
last_studied_at
next_review_at
```

---

# 104. DB — Problem

```sql
problems
--------
id
title
statement
source_type
difficulty
time_limit_ms
memory_limit_mb
language_independent
validation_status
created_at
```

---

# 105. DB — Problem Skill

```sql
problem_skills
--------------
problem_id
skill_id
weight
role
```

role:

```text
PRIMARY
SECONDARY
PREREQUISITE
```

---

# 106. DB — Test Case

```sql
test_cases
----------
id
problem_id
input
expected_output
type
is_hidden
```

---

# 107. DB — Submission

```sql
submissions
-----------
id
user_id
problem_id
language
code
status
execution_ms
memory_kb
attempt_no
created_at
```

---

# 108. DB — Mistake

```sql
submission_mistakes
-------------------
id
submission_id
mistake_type
severity
confidence
description
line_from
line_to
```

---

# 109. DB — Hint

```sql
hint_usage
----------
id
user_id
problem_id
submission_id
hint_level
created_at
```

---

# 110. DB — Learning Session

```sql
learning_sessions
-----------------
id
user_id
mode
started_at
ended_at
planned_minutes
actual_minutes
```

mode:

```text
DIAGNOSTIC
DAILY
REVIEW
EXAM
MOCK
FREE
```

---

# 111. DB — Learning Event

```sql
learning_events
---------------
id
user_id
session_id
event_type
skill_id
problem_id
metadata_json
occurred_at
```

---

# 112. 이벤트 목록

```text
SESSION_STARTED
CONCEPT_OPENED
PROBLEM_OPENED
CODE_RUN
SUBMITTED
ACCEPTED
FAILED
HINT_OPENED
SOLUTION_OPENED
REVIEW_COMPLETED
SKILL_MASTERED
SKILL_WEAKENED
MOCK_STARTED
MOCK_FINISHED
```

---

# 113. API — Onboarding

```http
POST /api/onboarding
```

```json
{
  "language": "PYTHON",
  "targetPlatform": "GENERAL",
  "targetLevel": "JOB_TEST_INTERMEDIATE",
  "examDate": "2026-10-01",
  "dailyMinutes": 90
}
```

---

# 114. API — Diagnostic

```http
POST /api/diagnostics/start
GET  /api/diagnostics/{id}/next
POST /api/diagnostics/{id}/answer
GET  /api/diagnostics/{id}/result
```

---

# 115. API — Daily Plan

```http
GET /api/learning/today
```

---

# 116. API — Session

```http
POST /api/learning/sessions
GET  /api/learning/sessions/{id}/next-action
```

---

# 117. API — Submission

```http
POST /api/problems/{id}/run
POST /api/problems/{id}/submit
```

---

# 118. API — Hint

```http
POST /api/problems/{id}/hints/{level}
```

---

# 119. API — Skill Map

```http
GET /api/users/me/skills
```

---

# 120. API — Reviews

```http
GET /api/reviews/due
POST /api/reviews/{id}/complete
```

---

# 121. Dashboard

예:

```text
시험 D-21

오늘 90분
[복습] Binary Search 20분
[학습] Dijkstra 35분
[연습] Greedy 25분
[혼합] 10분

Mastered Skills 37 / 126
```

---

# 122. 전체 Skill Map UI

```text
Graph
├─ DFS          87%
├─ BFS          82%
├─ Dijkstra     43%
├─ MST          12%
└─ Topological  55%

DP
├─ Basic DP     71%
├─ Knapsack     35%
├─ LIS          22%
└─ Tree DP       0%
```

---

# 123. 오답 분석 UI

```text
최근 50개 제출

OFF_BY_ONE            8
BOUNDARY_CHECK        6
ALGORITHM_SELECTION   4
DP_TRANSITION         3
TIME_COMPLEXITY       2
```

---

# 124. 사용자에게 보여주는 피드백

잘못된 피드백:

```text
틀렸습니다.
정답은 BFS입니다.
```

권장:

```text
알고리즘 선택은 맞았습니다.

실패 원인은 BFS 자체가 아니라
방문 처리를 queue에서 꺼낼 때 수행한 것입니다.

같은 좌표가 queue에 여러 번 들어갈 수 있어
불필요한 탐색이 증가합니다.

다음 문제에서는 visited를
queue에 넣는 시점에 처리하는 연습만 합니다.
```

---

# 125. 학습 콘텐츠 단위

서비스의 최소 콘텐츠 단위는 "문제"가 아니다.

```text
Skill
Concept Card
Example
Problem
Variant
Micro Drill
Review Problem
Assessment
```

---

# 126. CORE 취업 코테 필수 Skill Set

일반적인 취업 코딩테스트 대비 기본 세트:

```text
Programming
Implementation
Array
String
Hash
Sorting
Stack
Queue
Heap
Recursion
Brute Force
Backtracking
Two Pointer
Sliding Window
Prefix Sum
Binary Search
Greedy
Math
DFS
BFS
Graph
Basic DP
Basic Tree
```

---

# 127. INTERMEDIATE Skill Set

```text
Dijkstra
Floyd-Warshall
Union Find
MST
Topological Sort
LIS
LCS
Knapsack
Coordinate Compression
Monotonic Stack
Trie
KMP
0-1 BFS
Tree Diameter
LCA
```

---

# 128. ADVANCED Skill Set

```text
Segment Tree
Fenwick Tree
SCC
Articulation Point
Bridge
Network Flow
Bipartite Matching
Bitmask DP
Digit DP
Tree DP
Interval DP
Convex Hull
Suffix Array
Aho-Corasick
```

---

# 129. 목표별 권장 범위

## 입문

```text
Programming
Implementation
Array/String
Hash
Sorting
Stack/Queue
Brute Force
```

## 일반 취업 코테

```text
CORE 전체
+
Binary Search
Greedy
DFS/BFS
Basic DP
Heap
```

## 상위 기업 / 백준 Gold

```text
CORE
+
INTERMEDIATE 대부분
```

## 알고리즘 심화

```text
ADVANCED 포함
```

사용자가 어떤 목표를 선택하더라도
**전체 커리큘럼 데이터는 존재하고 목표에 따라 활성 범위만 달라진다.**

---

# 130. 학습 순서의 원칙

고정 순서만 강제하지 않는다.

기본 경로:

```text
Programming
↓
Implementation / Array / String
↓
Hash / Sorting / Stack / Queue
↓
Brute Force / Recursion
↓
DFS / BFS
↓
Two Pointer / Prefix Sum
↓
Binary Search / Greedy
↓
Basic DP
↓
Graph Advanced
↓
Intermediate Structures
↓
Advanced
```

진단 결과에 따라 건너뛸 수 있다.

---

# 131. 문제 생성 규칙

생성 문제마다 반드시 정의:

```text
target_skill
secondary_skills
difficulty
expected_time
constraints
reference_complexity
common_mistakes
test_strategy
```

---

# 132. 문제 메타데이터 예

```json
{
  "targetSkill": "binary_search_parametric",
  "secondarySkills": [
    "sorting"
  ],
  "difficulty": 3,
  "expectedMinutes": 18,
  "referenceComplexity": "O(N log M)",
  "commonMistakes": [
    "BINARY_SEARCH_BOUND",
    "OFF_BY_ONE"
  ]
}
```

---

# 133. Problem Validator

검사:

```text
문제 설명 모호성
Input Format 일치
Output Format 일치
Constraint 적합성
Reference Solution 성공
Alternative Solution 검증
Hidden Test 생성
시간복잡도 적합
```

---

# 134. AI 안전장치

LLM 출력은 schema validation을 통과해야 한다.

예:

```json
{
  "mistakeType": "BOUNDARY_CHECK",
  "confidence": 0.91,
  "explanation": "...",
  "nextAction": "MICRO_DRILL"
}
```

허용되지 않는 mistakeType이면 저장하지 않는다.

---

# 135. Prompt Versioning

```text
reviewer-v1
reviewer-v2
problem-generator-v1
hint-v3
planner-v2
```

각 결과에 prompt version을 저장한다.

---

# 136. 평가 데이터셋

Agent 품질도 테스트해야 한다.

## Reviewer Dataset

```text
문제
사용자 코드
실제 오류 유형
```

목표:

```text
Mistake Classification Accuracy
```

## Problem Generator Dataset

```text
유효 문제 비율
Reference Solution 성공률
모호성 발생률
```

---

# 137. 핵심 제품 Metric

```text
Time To Mastery
Independent Solve Rate
Hint Dependency
Repeated Mistake Rate
Review Retention
Skill Completion Rate
Exam Mock Improvement
```

---

# 138. Time To Mastery

정의:

```text
Skill을 LEARNING 상태에서 MASTERED까지
올리는 데 걸린 실제 학습 시간
```

이 서비스가 가장 최적화해야 하는 핵심 지표다.

---

# 139. 반복 실수 감소율

예:

```text
OFF_BY_ONE

Week 1: 24%
Week 3: 9%
```

이 자체가 학습 효과를 보여준다.

---

# 140. Agentic Learning Loop

최종 핵심 구조:

```text
Goal
 ↓
Diagnostic
 ↓
Skill State
 ↓
Planner
 ↓
Concept / Problem
 ↓
User Code
 ↓
Judge
 ↓
Reviewer
 ↓
Evaluator
 ↓
Skill State Update
 ↓
Decision Engine
 ↓
Next Learning Action
 └─────────────────↺
```

---

# 141. 구현 우선순위

전체 코테 유형은 처음부터 Curriculum DB에 설계한다.

기능 개발은 다음 순서가 현실적이다.

```text
1. Curriculum / Skill Graph
2. Problem / Test Case
3. Code Runner / Judge
4. Submission
5. User Skill State
6. Reviewer
7. Mistake Tracking
8. Decision Engine
9. Hint System
10. Daily Planner
11. Review Scheduler
12. Diagnostic
13. Mock Test
14. Problem Generator
15. Exam Strategy
```

여기서 중요한 점:

> **기능을 순차적으로 개발하는 것과 학습 유형을 세 개만 지원하는 것은 다른 이야기다.**

제품 데이터 모델과 커리큘럼은 처음부터 전체 유형을 수용하도록 설계한다.

---

# 142. 첫 번째 Vertical Slice

첫 구현은 모든 기능을 얕게 만드는 대신
하나의 완전한 루프를 만든다.

```text
사용자 진단
↓
Skill 선택
↓
문제
↓
코드 실행
↓
판정
↓
오답 분석
↓
Skill 갱신
↓
다음 문제
```

이 루프를 만들고 동일 구조에 모든 Skill을 연결한다.

---

# 143. 제품이 실제로 공부가 되기 위한 조건

이 서비스가 실패하는 가장 쉬운 방법은
"AI가 문제를 많이 만들어주는 서비스"가 되는 것이다.

반드시 지켜야 하는 조건:

1. 문제마다 Target Skill이 존재한다.
2. 사용자의 실패 원인을 저장한다.
3. 다음 문제 선택에 이전 실패가 반영된다.
4. 정답을 본 문제는 Mastery로 처리하지 않는다.
5. 복습 성공 전에는 완전 숙련으로 처리하지 않는다.
6. 쉬운 문제를 성공했다고 무조건 난이도를 올리지 않는다.
7. 개념 오류와 구현 오류를 구분한다.
8. 실전에서는 유형명을 숨긴다.
9. 시험까지 남은 시간이 적으면 학습 전략을 바꾼다.
10. 모든 학습 행동이 Skill Graph와 연결된다.

---

# 144. 실제 학습 예시 — 신규 사용자

```text
목표:
일반 기업 코딩테스트

진단 결과:
Implementation 0.74
Hash           0.61
Stack          0.42
BFS            0.21
Binary Search  0.15
DP             0.08
```

Agent:

```text
오늘 80분

Stack/Queue   20분
BFS Basic     35분
Implementation Review 10분
Mixed Practice 15분
```

---

# 145. 실제 학습 예시 — 중급 사용자

```text
DFS/BFS Mastered
Greedy 0.65
Binary Search 0.71
Dijkstra 0.31
DP 0.59
```

Agent:

```text
Dijkstra 학습
Greedy 변형
DP 복습
```

---

# 146. 실제 학습 예시 — 시험 D-3

```text
시험까지 3일
하루 120분
```

Agent:

```text
신규 Segment Tree 제외
Dijkstra 유지
Binary Search 오답 교정
DFS/BFS Mixed
1회 Mock Test
```

---

# 147. 코드 면접 연계

문제 해결 후 선택적으로 Agent가 묻는다.

```text
왜 BFS를 사용했나요?
DFS로도 가능한가요?
시간복잡도는?
visited를 언제 처리했나요?
메모리 복잡도는?
```

이를 통해 단순 AC보다 깊은 이해를 확인한다.

---

# 148. Explain Back

사용자가 개념을 직접 설명하게 할 수 있다.

예:

```text
"왜 이 문제에서 Binary Search가 가능한지
두 문장으로 설명해보세요."
```

설명을 Evaluator가 분석한다.

---

# 149. 정답 암기 방지

같은 패턴의 숫자만 바꾼 문제를 반복하지 않는다.

변형 축:

```text
문제 서술
입력 구조
출력 요구
제약 조건
자료 표현
필요한 Secondary Skill
```

---

# 150. Anti-Overhelp

사용자가 바로 정답을 요청해도 학습 모드 설정에 따라:

```text
학습 모드:
Hint 우선

자유 모드:
전체 해설 허용
```

사용자가 선택할 수 있다.

---

# 151. 학습 모드

```text
GUIDED
NORMAL
STRICT
EXAM
FREE
```

## GUIDED

초심자 중심.

## NORMAL

기본.

## STRICT

힌트 최소화.

## EXAM

유형/힌트 숨김.

## FREE

자유 질문.

---

# 152. 사용자 설정

```text
선호 언어
목표 플랫폼
목표 난이도
시험 날짜
일일 학습 시간
설명 스타일
힌트 강도
학습 모드
```

---

# 153. 플랫폼 종속성 제거

Skill Graph는 백준/프로그래머스/LeetCode 어느 플랫폼에도 종속되지 않는다.

```text
Skill
↕
Problem Adapter
↕
Platform
```

---

# 154. 외부 문제 연결 시 저작권 원칙

저장 가능:

```text
문제 ID
제목
링크
난이도
태그
개인 학습 기록
```

주의:

```text
문제 전문
테스트케이스
공식 해설
```

은 각 플랫폼 정책을 확인하여 처리한다.

---

# 155. Company Preparation

기업별 모드는 "실제 비공개 문제 복제"가 아니라
공개적으로 알려진 평가 범위와 일반적 스타일을 바탕으로 구성한다.

예:

```text
시간 제한
문제 수
권장 Skill 비중
Mock Test 구성
```

---

# 156. 추천 엔진 초기 구현

초기에는 ML이 없어도 된다.

Rule 기반:

```text
if repeatedMistake >= 2:
    MICRO_DRILL

elif independentScore < 0.5:
    SAME_LEVEL_VARIANT

elif mastery >= 0.8:
    UNLOCK_NEXT

elif reviewDue:
    REVIEW
```

---

# 157. 추천 엔진 고도화

데이터 축적 후:

```text
Knowledge Tracing
Item Response Theory
Contextual Bandit
Learning-to-Rank
```

등을 적용할 수 있다.

---

# 158. Knowledge Tracing 확장

사용자 Skill 이해도를 시간에 따라 추적한다.

입력:

```text
문제 난이도
정답 여부
힌트
시간
Skill
```

출력:

```text
P(skill mastered)
```

---

# 159. 개인화에서 중요한 점

개인화는 "말투"가 아니다.

진짜 개인화:

```text
어떤 Skill을
어떤 순서로
어떤 난이도로
언제 다시
어떤 형태로
학습시키는가
```

이다.

---

# 160. 프로젝트의 기술적 가치

이 프로젝트에서 보여줄 수 있는 것:

```text
Agent Orchestration
LLM Structured Output
Adaptive Recommendation
Skill Graph
Code Sandbox
Online Judge
Learning Analytics
Event Driven Data
Prompt Evaluation
Generated Content Validation
Spaced Repetition
```

---

# 161. 프로젝트의 AX 가치

기존에는 사용자가 직접 수행하던 업무:

```text
공부할 유형 선택
강의 검색
문제 검색
난이도 판단
오답 분석
복습 일정 관리
시험 전략 조정
```

을 Agent가 수행한다.

따라서 단순 AI 기능 추가가 아니라
**학습 프로세스 자체의 운영 방식을 AI 중심으로 재설계한 AX 서비스**로 설명할 수 있다.

---

# 162. 최종 제품 정의

CodeSprint Agent는

> 코딩테스트 문제를 대신 풀어주는 AI가 아니라,
> 사용자가 **코딩테스트 전체 유형을 체계적으로 습득할 수 있도록 학습 경로를 운영하는 AI Agent**다.

사용자가 처음 시작하더라도 전체 Skill Graph에서 진단을 시작하고,

```text
진단
→ 학습
→ 문제
→ 실행
→ 판정
→ 오답 분석
→ 보강
→ 변형
→ 복습
→ 실전
```

의 순환을 통해 목표 수준까지 도달하게 한다.

---

# 163. 가장 중요한 문장

> **많이 풀게 하지 않는다.  
> 무엇을 모르는지 찾아내고, 그 Skill을 가장 짧은 경로로 독립 풀이 가능한 상태까지 만든다.**

---

# 164. 구현 시작 체크리스트

## Curriculum

- [ ] 전체 Domain 등록
- [ ] 전체 Skill ID 정의
- [ ] Skill Tier 정의
- [ ] 선수관계 정의
- [ ] 목표별 활성 Skill Set 정의

## Problem

- [ ] Problem Schema
- [ ] Problem-Skill Mapping
- [ ] Test Case Schema
- [ ] Template Schema
- [ ] Validator

## Judge

- [ ] Sandbox
- [ ] Python Runner
- [ ] Java Runner
- [ ] C++ Runner
- [ ] Timeout
- [ ] Memory Limit

## Learning

- [ ] User Skill
- [ ] Mastery Calculation
- [ ] Hint Tracking
- [ ] Mistake Tracking
- [ ] Review Schedule

## Agent

- [ ] Diagnostic
- [ ] Planner
- [ ] Tutor
- [ ] Reviewer
- [ ] Evaluator
- [ ] Problem Generator
- [ ] Exam Strategy

## UX

- [ ] Onboarding
- [ ] Diagnostic
- [ ] Dashboard
- [ ] Skill Map
- [ ] Solver
- [ ] Review
- [ ] Mock Test
- [ ] Analytics

---

# 165. 권장 초기 Repository 구조

```text
codesprint/
├── backend/
│   ├── user/
│   ├── curriculum/
│   ├── problem/
│   ├── judge/
│   ├── submission/
│   ├── learning/
│   ├── agent/
│   ├── review/
│   ├── exam/
│   └── analytics/
│
├── frontend/
│   ├── dashboard/
│   ├── skill-map/
│   ├── learning/
│   ├── solver/
│   ├── mock-test/
│   └── analytics/
│
├── runner/
│   ├── python/
│   ├── java/
│   └── cpp/
│
├── prompts/
│   ├── diagnostic/
│   ├── tutor/
│   ├── reviewer/
│   ├── generator/
│   └── planner/
│
├── curriculum/
│   ├── skills.yaml
│   ├── prerequisites.yaml
│   └── target-profiles.yaml
│
└── docs/
    └── architecture/
```

---

# 166. Curriculum YAML 예시

```yaml
skills:
  - code: bfs_basic
    domain: BFS
    tier: CORE
    prerequisites:
      - queue_basic
      - visited_basic

  - code: bfs_shortest_path
    domain: BFS
    tier: CORE
    prerequisites:
      - bfs_basic

  - code: zero_one_bfs
    domain: SHORTEST_PATH
    tier: INTERMEDIATE
    prerequisites:
      - bfs_shortest_path
      - deque_basic
```

---

# 167. 최종 구현 원칙

1. **전체 커리큘럼은 처음부터 설계한다.**
2. 실제 개발 기능은 Vertical Slice 방식으로 단계적으로 만든다.
3. 특정 세 알고리즘에 제품 전체를 종속시키지 않는다.
4. 신규 사용자의 과거 지식을 가정하지 않는다.
5. 모든 문제는 Skill Graph와 연결한다.
6. 모든 오답은 가능한 한 원인으로 분해한다.
7. 모든 학습 세션은 다음 행동을 생성한다.
8. 복습 없는 Mastery는 인정하지 않는다.
9. 실전 모드에서는 유형명을 숨긴다.
10. 최종 목표는 **독립 풀이 능력**이다.

---

# 168. 결론

이 서비스는 Python 문제 몇 개를 제공하는 튜터가 아니다.

또한 Implementation, Hash, DFS/BFS만 반복하는 학습기도 아니다.

기초 문법부터 구현, 문자열, 해시, 정렬, 완전탐색, 백트래킹,
투포인터, 누적합, 이분탐색, Greedy, DFS/BFS, Graph, DP,
Shortest Path, MST, Tree, 고급 자료구조와 심화 알고리즘까지
**전체 코딩테스트 Skill Graph를 하나의 학습 시스템으로 통합**한다.

그 위에서 Agent가 사용자별로 필요한 영역만 선택하고,
불필요한 학습은 건너뛰며,
부족한 부분은 더 잘게 쪼개서 다시 학습시킨다.

제품의 핵심 경쟁력은 문제의 개수가 아니라 다음 세 가지다.

```text
1. 정확한 Skill 진단
2. 정확한 오답 원인 분석
3. 정확한 다음 학습 행동 결정
```

이 세 가지가 제대로 연결되면,
CodeSprint Agent는 실제 코딩테스트 공부에 사용할 수 있는
**Adaptive AI Learning System**이 된다.
