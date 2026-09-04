# 채점 런타임. 사용자 코드가 도는 유일한 장소다.
#
# 이 이미지에 무엇을 넣지 "않는가" 가 더 중요하다 - 네트워크 도구, 패키지 관리자,
# 셸 유틸리티가 있으면 탈출 시도의 발판이 된다. slim 을 쓰고 아무것도 더하지 않는다.
FROM python:3.12-slim

# root 로 실행하지 않는다(Addendum 59). uid 를 고정해 호스트 쪽 파일 소유권도 예측 가능하게.
RUN useradd --create-home --uid 10001 runner

# 하네스는 이미지에 굽는다. /job 마운트에 두면 사용자가 바꿔치기할 수 있는 자리에
# 채점 로직이 놓인다 - 마운트는 read-only 지만, 신뢰 경계를 마운트에 의존하지 않는다.
# 빌드 컨텍스트는 저장소 루트다(.dockerignore 가 하네스 하나만 남긴다).
COPY judge/runner/harness.py /opt/judge/harness.py

# /job 은 read-only 로 마운트된다. 파이썬이 그 옆에 __pycache__ 를 쓰려다
# 실패하면, 그 실패가 채점 결과로 둔갑한다.
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1

USER runner
WORKDIR /job

ENTRYPOINT ["python3", "/opt/judge/harness.py"]
