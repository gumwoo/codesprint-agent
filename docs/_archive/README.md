# Archive — 원본 정본 문서

여기 있는 두 문서가 **현재 정본**이다. `docs/00-product/` 이하로 분해하기 전까지는
이 파일들을 기준으로 판단한다.

| 파일 | 내용 |
| --- | --- |
| `CodeSprint_Agent_Full_CodingTest_PRD_Architecture_v2.md` | 제품 정의, 45개 도메인 커리큘럼, Agent 구성, DB/API 설계 (§1~168) |
| `CodeSprint_Agent_Implementation_Spec_Addendum.md` | Mastery 산식, Skill Catalog, 첫 Vertical Slice, Sandbox (§1~91) |

분해 후에도 **삭제하지 않는다.** 변환 과정에서 의미가 바뀌었는지 대조할 근거가 필요하다.

## 이미 정정된 것

분해를 기다리지 않고 확정한 사항이다. 원본 본문은 고치지 않았으므로 충돌 시 ADR 이 이긴다.

| 원본 | 정정 | 근거 |
| --- | --- | --- |
| PRD 본문의 소문자 Skill ID (`bfs_basic`) | `UPPER_SNAKE_CASE` (`BFS_BASIC`) | [ADR-0003](../adr/0003-skill-id-canonical-uppercase.md) |
| PRD §71 Reviewer 출력의 `recommendedAction` | 폐기. Decision Engine 이 결정 | [ADR-0002](../adr/0002-next-action-decided-by-rule-engine.md) |
