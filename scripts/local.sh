#!/usr/bin/env bash
# 로컬에서 CodeSprint 를 끝까지 띄운다. 배포용이 아니다.
#
#   scripts/local.sh check     필요한 것이 다 있는가
#   scripts/local.sh db        PostgreSQL 을 띄운다 (compose.yaml)
#   scripts/local.sh build     샌드박스 이미지와 백엔드 jar 를 만든다
#   scripts/local.sh backend   백엔드 + 화면 (http://localhost:18080)  <- 터미널 하나
#   scripts/local.sh worker    Judge Worker                            <- 터미널 하나
#
# 백엔드와 Worker 는 **각자 한 터미널을 차지한다.** 둘 다 멈추지 않고 도는 프로세스라
# 한 스크립트가 백그라운드로 숨기면, 하나가 죽었을 때 화면은 "채점 중" 에서 멈추고
# 그 이유는 어디에도 보이지 않는다.
#
# Worker 가 없으면 제출은 영원히 PENDING 이다. 화면이 멈춘 것처럼 보이면 가장 먼저
# Worker 터미널을 본다.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DB_PORT="${CODESPRINT_DB_PORT:-55440}"
# 8080 을 쓰지 않는다. 개발 PC 에는 흔히 다른 서버가 이미 떠 있고, 실제로 그랬다 -
# 앱은 "포트 사용 중" 으로 죽고 브라우저는 엉뚱한 서버의 404 를 보여줬다.
APP_PORT="${PORT:-18080}"
JAR="$ROOT/backend/build/libs/codesprint-backend-0.1.0.jar"
IMAGE="codesprint-judge:py312"

say() { printf '%s\n' "$*"; }
die() { printf '[X] %s\n' "$*" >&2; exit 1; }

check() {
  local missing=0
  command -v docker >/dev/null || { say "[X] docker 가 없다"; missing=1; }
  docker info >/dev/null 2>&1 || { say "[X] Docker 엔진이 꺼져 있다 - Docker Desktop 을 켠다"; missing=1; }
  command -v java >/dev/null || { say "[X] java 가 없다 (17 이상)"; missing=1; }
  command -v python >/dev/null || { say "[X] python 이 없다 (3.12)"; missing=1; }
  python -c "import psycopg" 2>/dev/null \
    || { say "[X] psycopg 가 없다 - pip install -r requirements-dev.txt"; missing=1; }
  docker image inspect "$IMAGE" >/dev/null 2>&1 \
    || say "[!] 샌드박스 이미지가 없다 - scripts/local.sh build"
  [ -f "$JAR" ] || say "[!] 백엔드 jar 가 없다 - scripts/local.sh build"
  command -v claude >/dev/null \
    || say "[-] claude CLI 가 없다 - Reviewer 없이 돈다 (판정 · mastery · 다음 행동은 그대로)"
  # 출력을 먼저 받아 둔다. pipefail 아래서 `netstat | grep -q` 로 쓰면 grep 이 먼저
  # 끝날 때 netstat 이 SIGPIPE 로 죽어 조건 전체가 거짓이 된다 - 실제로 이 검사가
  # 사용 중인 8080 을 "비어 있다" 로 통과시켰다.
  local listening
  listening="$(netstat -ano 2>/dev/null || true)"
  if grep -qE "[:.]$APP_PORT[[:space:]].*LISTEN" <<<"$listening"; then
    say "[X] 포트 $APP_PORT 을 이미 다른 프로그램이 쓴다 - PORT=다른번호 scripts/local.sh backend"
    missing=1
  fi
  [ "$missing" = 0 ] && say "[OK] 필요한 것이 다 있다" || exit 1
}

db() {
  docker compose -f "$ROOT/compose.yaml" up -d --wait db
  say "[OK] PostgreSQL localhost:$DB_PORT"
}

build() {
  # 하네스는 이미지에 구워진다. 파일만 고치고 이미지를 두면 옛 하네스로 채점한다.
  docker build -q -t "$IMAGE" -f "$ROOT/judge/Dockerfile" "$ROOT"
  # gradle 을 호스트에 요구하지 않는다. wrapper jar 도 저장소에 두지 않는다(CI 와 같은 이유).
  MSYS_NO_PATHCONV=1 docker run --rm -v "$ROOT":/w -w /w/backend gradle:8.10.2-jdk17 \
    gradle bootJar --no-daemon -q
  say "[OK] $IMAGE · $(basename "$JAR")"
}

backend() {
  [ -f "$JAR" ] || die "jar 가 없다 - scripts/local.sh build"
  export DB_URL="jdbc:postgresql://localhost:$DB_PORT/codesprint"
  export CODESPRINT_REPO_ROOT="$ROOT"
  export CODESPRINT_PROBLEMS_DIR="$ROOT/problems"
  # Reviewer 는 기본으로 꺼져 있다. 켜려면 CODESPRINT_REVIEWER_ENABLED=true 로 부른다.
  export PORT="$APP_PORT"
  say "화면: http://localhost:$APP_PORT  (Reviewer: ${CODESPRINT_REVIEWER_ENABLED:-false})"
  exec java -jar "$JAR"
}

worker() {
  export CODESPRINT_DB_URL="postgresql://codesprint:codesprint@localhost:$DB_PORT/codesprint"
  # 출력을 모아 두지 않게 한다. 로그를 파일로 받으면 파이썬이 한참 쌓아 두었다
  # 내보내서, 채점이 도는데도 로그는 비어 있었다 - 멈춘 것처럼 보인다.
  export PYTHONUNBUFFERED=1
  cd "$ROOT"
  exec python judge/worker.py
}

case "${1:-}" in
  check) check ;;
  db) db ;;
  build) build ;;
  backend) backend ;;
  worker) worker ;;
  *) sed -n '2,15p' "$0"; exit 2 ;;
esac
