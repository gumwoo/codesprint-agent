#!/usr/bin/env python3
"""Judge Worker. 큐에서 제출을 꺼내 샌드박스에서 채점하고 결과를 다시 큐에 쓴다.

    python judge/worker.py --once          # 큐를 한 번 비우고 끝낸다 (테스트/CI)
    python judge/worker.py                 # 계속 돈다

정본 근거: ADR-0013, ADR-0011, Addendum 47 / 67~68.

── 왜 여기가 Python 인가 ────────────────────────────────────────────────
사용자 코드를 실행하는 프로그램은 컨테이너 안에서 돈다. 그 안에 JVM 을 띄울 이유가
없다(ADR-0011). Java 는 학습 상태(Evidence / mastery / 다음 행동)를 소유하고, 이쪽은
**판정만** 만든다.

── 이 Worker 가 하지 않는 것 ───────────────────────────────────────────
- Evidence 를 만들지 않는다. mastery 를 건드리지 않는다. 다음 행동을 정하지 않는다.
  그건 전부 Java 가 결과를 반영할 때 한다. 두 언어가 같은 테이블을 고치기 시작하면
  어느 쪽이 정본인지 알 수 없게 된다.
- 정답을 컨테이너에 넣지 않는다. cases.json 은 **호스트에서** 읽고, 비교도 호스트가
  한다(ADR-0006). run_submission.py 가 그 경계를 지킨다.
"""
from __future__ import annotations

import argparse
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import time

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = pathlib.Path(__file__).resolve().parent.parent

# job 을 집어간 뒤 이 시간 안에 끝내지 못하면 다른 Worker 가 가져갈 수 있다.
# run_submission.py 안쪽 timeout 보다 넉넉해야 한다 - 그러지 않으면 정상적으로
# 채점 중인 job 을 다른 Worker 가 중복으로 집는다.
LEASE_SECONDS = 300

# 몇 번까지 다시 시도하는가. 상한이 없으면 계속 죽는 job 하나가 큐를 영원히 막는다.
MAX_ATTEMPTS = 3

POLL_SECONDS = 1.0


def dsn() -> str:
    """접속 정보. 백엔드와 같은 DB 를 본다 - 큐가 곧 경계다."""
    url = os.environ.get("CODESPRINT_DB_URL")
    if url:
        return url
    return (
        f"host={os.environ.get('DB_HOST', 'localhost')} "
        f"port={os.environ.get('DB_PORT', '5432')} "
        f"dbname={os.environ.get('DB_NAME', 'codesprint')} "
        f"user={os.environ.get('DB_USER', 'codesprint')} "
        f"password={os.environ.get('DB_PASSWORD', 'codesprint')}"
    )


def claim(conn) -> dict | None:
    """job 하나를 집어온다. 계약: contracts/judge-job.schema.json.

    ``FOR UPDATE SKIP LOCKED`` 를 쓴다. 두 Worker 가 같은 job 을 집지 않으면서도
    서로를 기다리지 않는다 - 잠긴 행은 건너뛴다.

    리스가 만료된 RUNNING 도 다시 집는다. Worker 가 죽으면 그 job 이 영원히
    RUNNING 으로 남기 때문이다.
    """
    with conn.cursor() as cur:
        cur.execute(
            """
            WITH claimed AS (
                SELECT id FROM judge_jobs
                 WHERE (status = 'QUEUED'
                        OR (status = 'RUNNING' AND lease_expires_at < now()))
                   AND attempts < %s
                 ORDER BY id
                 FOR UPDATE SKIP LOCKED
                 LIMIT 1
            )
            UPDATE judge_jobs j
               SET status = 'RUNNING',
                   attempts = j.attempts + 1,
                   lease_expires_at = now() + make_interval(secs => %s),
                   updated_at = now()
              FROM claimed
             WHERE j.id = claimed.id
         RETURNING j.id, j.submission_id, j.problem_code, j.language,
                   j.source_code, j.attempts
            """,
            (MAX_ATTEMPTS, LEASE_SECONDS),
        )
        row = cur.fetchone()
    conn.commit()
    if row is None:
        return None
    return {
        "jobId": row[0],
        "submissionId": row[1],
        "problemCode": row[2],
        "language": row[3],
        "sourceCode": row[4],
        "attempts": row[5],
    }


def finish(conn, job_id: int, result: dict | None, failure_reason: str | None) -> None:
    """결과를 큐에 쓴다. 학습 상태는 건드리지 않는다 - 그건 Java 가 한다."""
    with conn.cursor() as cur:
        cur.execute(
            """
            UPDATE judge_jobs
               SET status = %s,
                   result = %s,
                   failure_reason = %s,
                   lease_expires_at = NULL,
                   updated_at = now()
             WHERE id = %s
            """,
            (
                "DONE" if result is not None else "FAILED",
                json.dumps(result, ensure_ascii=False) if result is not None else None,
                failure_reason,
                job_id,
            ),
        )
    conn.commit()


def reap_exhausted(conn) -> int:
    """시도 횟수를 다 쓴 job 을 끝낸다.

    이게 없으면 그런 job 은 리스가 만료된 RUNNING 으로 영원히 남는다. claim 의
    조건(``attempts < MAX_ATTEMPTS``)에 걸려 아무도 집지 않으므로, 사용자에게는
    제출이 영영 PENDING 으로 보인다 - 실패했다는 사실조차 전달되지 않는다.

    FAILED 로 끝내면 Java 가 SYSTEM_ERROR 로 반영한다. 우리 잘못이므로 학습 경로를
    바꾸지 않되, 사용자는 "채점하지 못했다" 를 보게 된다.
    """
    with conn.cursor() as cur:
        cur.execute(
            """
            UPDATE judge_jobs
               SET status = 'FAILED',
                   failure_reason = %s,
                   lease_expires_at = NULL,
                   updated_at = now()
             WHERE status IN ('QUEUED', 'RUNNING')
               AND attempts >= %s
               AND (lease_expires_at IS NULL OR lease_expires_at < now())
            """,
            (f"{MAX_ATTEMPTS}회 시도했지만 끝내지 못했다", MAX_ATTEMPTS),
        )
        reaped = cur.rowcount
    conn.commit()
    return reaped


def run_job(job: dict) -> tuple[dict | None, str | None]:
    """샌드박스에서 채점한다.

    돌려주는 값이 (None, 이유) 면 **채점 자체가 불가능했다**는 뜻이다. Java 는 그것을
    SYSTEM_ERROR 로 반영한다 - 우리 잘못이므로 사용자 점수를 건드리지 않는다.
    """
    # 계약이 PYTHON 만 허용하고 DB CHECK 도 막지만, 여기서도 확인한다. 무엇이든
    # solution.py 로 써서 Python 으로 돌리므로, 다른 언어가 새어 들어오면
    # language 와 실제 판정이 어긋난 기록이 남는다.
    if job["language"] != "PYTHON":
        return None, f"지원하지 않는 언어다: {job['language']}"

    cases = ROOT / "problems" / job["problemCode"] / "cases.json"
    if not cases.exists():
        return None, f"Test Case 파일이 없다: {cases}"

    workdir = pathlib.Path(tempfile.mkdtemp(prefix="codesprint-worker-"))
    try:
        solution = workdir / "solution.py"
        # 사용자 코드다. 읽지 않고 그대로 넘긴다.
        solution.write_text(job["sourceCode"], encoding="utf-8")

        proc = subprocess.run(
            [sys.executable, str(ROOT / "judge" / "run_submission.py"),
             str(solution), str(cases)],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
        )
        if proc.returncode != 0:
            return None, f"하네스가 실패했다 (exit={proc.returncode}): {proc.stderr[:500]}"
        try:
            return json.loads(proc.stdout), None
        except json.JSONDecodeError as e:
            return None, f"판정을 읽지 못했다: {e} / {proc.stdout[:300]}"
    finally:
        for path in sorted(workdir.rglob("*"), reverse=True):
            path.unlink(missing_ok=True)
        workdir.rmdir()


def drain(conn) -> int:
    """큐가 빌 때까지 처리한다. 처리한 개수를 돌려준다."""
    done = reap_exhausted(conn)
    while True:
        job = claim(conn)
        if job is None:
            return done

        result, reason = run_job(job)
        finish(conn, job["jobId"], result, reason)
        status = result["status"] if result else f"FAILED({reason})"
        print(f"[job {job['jobId']}] {job['problemCode']} -> {status}")
        done += 1


def main() -> int:
    parser = argparse.ArgumentParser(description="채점 큐를 처리한다")
    parser.add_argument("--once", action="store_true",
                        help="큐를 한 번 비우고 끝낸다")
    args = parser.parse_args()

    import psycopg  # 여기서 import 한다 - 백엔드 테스트는 이 모듈을 부르지 않는다

    with psycopg.connect(dsn()) as conn:
        if args.once:
            print(f"[OK] {drain(conn)}건 처리")
            return 0
        print("Judge Worker 시작. Ctrl+C 로 멈춘다.")
        while True:
            if drain(conn) == 0:
                time.sleep(POLL_SECONDS)


if __name__ == "__main__":
    sys.exit(main())
