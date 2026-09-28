# Java 채점 런타임(ADR-0045). 파이썬 이미지(judge/Dockerfile)와 같은 원칙이다. JDK 와 하네스를 돌릴
# python3 만 더한다.

# 사용자 프로그램을 띄우는 실행기(judge/runner/launch.c, ADR-0059). 여기서 굽고 실행 파일만 가져간다 -
# 컴파일러는 최종 이미지에 들어가지 않는다. 정적으로 링크해 최종 이미지의 libc 와 무관하게 돈다.
# 세 이미지(judge/Dockerfile · .cpp · .java)가 같은 줄로 굽는다.
FROM gcc:13-bookworm AS launcher
COPY judge/runner/launch.c /src/launch.c
RUN gcc -O2 -static -o /launch /src/launch.c

FROM eclipse-temurin:21-jdk-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 \
    && rm -rf /var/lib/apt/lists/*

# root 로 실행하지 않는다(Addendum 59). 파이썬 이미지와 같은 uid 다.
RUN useradd --create-home --uid 10001 runner

COPY judge/runner/harness.py /opt/judge/harness.py
COPY --from=launcher /launch /opt/judge/launch

# **언어는 이미지가 정한다.** 사용자 입력으로 받지 않는다.
ENV JUDGE_LANGUAGE=JAVA PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1

USER runner
WORKDIR /job

ENTRYPOINT ["python3", "/opt/judge/harness.py"]
