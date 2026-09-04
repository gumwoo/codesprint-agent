# Curriculum

Skill Graph 의 **실행 가능한 정본**이다. 문서가 아니라 CI 가 검증하는 데이터이므로
`docs/` 바깥에 둔다.

| 파일 | 정본 문서 | 쓰이는 곳 |
| --- | --- | --- |
| [domains.yaml](domains.yaml) | PRD §4~51 | 46개 도메인 골격. Skill 의 `domain` 참조 대상 |
| [skills.yaml](skills.yaml) | Addendum §31~34 | **검증된 Skill 만.** 첫 슬라이스 8개 |
| [prerequisites.yaml](prerequisites.yaml) | Addendum §33 | 학습 경로 잠금 해제 조건 |
| [mistakes.yaml](mistakes.yaml) | Addendum §37, §42 | 오답 taxonomy + 자동 드릴 연결 |

## 왜 domains 와 skills 를 나눴는가

두 정본이 서로 다른 것을 요구한다.

```text
PRD §167 원칙 1     전체 커리큘럼은 처음부터 설계한다
Addendum §31        첫 구현에서 사용하는 Skill 은 아래 8개로 고정한다
```

둘 다 맞다. 전자는 **데이터 모델이 45개 도메인을 수용해야 한다**는 뜻이고,
후자는 **검증되지 않은 Skill 을 활성화하지 말라**는 뜻이다.

그래서 골격(`domains.yaml`, 46개)과 실체(`skills.yaml`, 8개)를 분리했다.
도메인의 `active` 플래그가 둘의 동기화를 표시하며, CI 가 거짓말을 막는다.
Skill 이 없는데 `active: true` 이거나 그 반대이면 실패한다.

## 규칙

1. **문서와 데이터는 함께 바꾼다.** 한쪽만 바꾸면 정본이 둘로 갈라진다.
2. **code 는 한번 Production 에 쓰이면 변경 금지**(Addendum §35).
   `deprecated: true` + `replacement` 로 대체한다.
3. **Skill 과 Mistake 를 섞지 않는다**(§30).
   `GRID_BOUNDARY_CHECK` 는 Skill, `BOUNDARY_CHECK` 는 Mistake 다.
   CI 가 이름 충돌을 막는다.
4. **`auto_drill` 을 함부로 늘리지 않는다.**
   Mistake 오분류는 라벨 하나로 끝나지만, 자동 액션은 사용자의 학습 시간을
   엉뚱한 드릴에 쓰게 만든다. Reviewer 정확도가 측정된 뒤 하나씩 연다.
5. **`mistakes.yaml` 을 고치면 `contracts/reviewer-output.llm.schema.json` 의
   `$defs.mistakeCode.enum` 도 함께 고친다.** CI 가 동기화를 검증한다.
   단, 대조 대상은 `assigned_by: REVIEWER` 인 것만이다.

6. **새 Mistake 를 넣을 때 `assigned_by` 를 먼저 정한다**(ADR-0004).
   `SYSTEM` 이면 Judge 결과만으로 결정론적으로 부여되며 LLM enum 에 넣지 않는다.
   `REVIEWER` 이면 실패 Test Case 를 근거로 분류되므로, 그 Mistake 가 나타나는
   Judge 상태에 **실패 case 가 특정되는지** 확인해야 한다. 특정되지 않으면
   `failedCaseRefs` 의 minItems: 1 을 만족시킬 수 없다.

## 검증

```bash
python tools/check_curriculum.py      # 데이터/계약이 맞는가
python tools/meta_test_curriculum.py  # 검사가 실제로 잡는가
```

## 미해결

- `GRID_COORDINATE ← PYTHON_LIST_BASIC` 은 Addendum §33 을 그대로 옮긴 것이다.
  언어 독립 Skill 이 언어 종속 Skill 을 요구하는 형태라 PRD §67 과 긴장이 있다.
  Java/C++ 추가 시점에 재검토한다.
