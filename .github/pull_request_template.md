<!--
PR 본문은 아래 7개 섹션을 모두 채운다. 해당 없으면 "해당 없음"으로 명시(섹션 삭제 금지).
파일 나열이 아니라 "동작/의도" 중심으로.
-->

## 배경 / 목적 (Why)
<!-- 이 변경이 왜 필요한지 1~3줄. 문제 상황·의사결정 근거. -->

## 변경 사항 (What)
<!-- 사용자/동작 관점 핵심 변경 3~5개. 파일 나열 X. -->
-

## 구현 노트 (How)
<!-- 눈여겨볼 설계 결정·트레이드오프. 리뷰어가 헷갈릴 지점 선제 설명. -->
-

## 테스트 / 검증
- [ ] `python tools/check_curriculum.py`
- [ ] `python tools/meta_test_curriculum.py`
- [ ] 불변식을 추가했다면 그것을 깨뜨리는 메타테스트도 함께 추가했다
- [ ] `curriculum/mistakes.yaml`을 고쳤다면 `contracts/reviewer-output.llm.schema.json`의 enum도 함께 고쳤다
<!-- 실행하지 않은 항목은 체크하지 말고 그 이유를 아래에 쓴다. 수동 확인 시나리오도 여기. -->

## 스크린샷 / 데모
<!-- UI 변경 시 Before / After. 없으면 "해당 없음". -->
해당 없음

## 영향 범위 & 리스크
<!-- 건드린 경계(curriculum/contracts/tools/backend/frontend), 회귀 가능 지점, 롤백 방법.
     계약(contracts/)이나 Skill code를 건드렸다면 반드시 적는다.
     Production 데이터에 쓰인 Skill code 변경은 ADR-0003에 따라 금지다. -->

## 관련
<!-- 관련 ADR, docs 링크, 후속 TODO. -->
