#!/usr/bin/env python3
"""Judge Worker 의 큐 동작을 실물 PostgreSQL 로 검증한다.

    python judge/tests/test_worker.py

여기서 확인하는 것은 **판정 자체가 아니라 큐다**. 샌드박스 격리와 판정 정확도는
test_judge.py 가 본다. 이쪽은 ADR-0013 이 큐에 요구한 것들을 확인한다.

  - 두 Worker 가 같은 job 을 집지 않는다
  - Worker 가 죽어도 job 이 영원히 RUNNING 으로 남지 않는다
  - 계속 실패하는 job 이 큐를 영원히 막지 않는다
  - Worker 는 학습 상태를 건드리지 않는다

인메모리로 흉내 내지 않는다. ``FOR UPDATE SKIP LOCKED`` 는 PostgreSQL 의 동작이고,
그걸 흉내 낸 것으로 검증하면 아무것도 검증하지 않는 것과 같다.
"""
from __future__ import annotations

import json
import os
import pathlib
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(ROOT))

from judge import worker  # noqa: E402

failures: list[str] = []


def check(name: str, ok: bool, detail: str = "") -> None:
    print(f"[{'O' if ok else 'X'}] {name}" + (f" — {detail}" if detail and not ok else ""))
    if not ok:
        failures.append(f"{name}: {detail}")


MIGRATIONS = ROOT / "backend" / "src" / "main" / "resources" / "db" / "migration"


def migrate(conn) -> None:
    """백엔드와 **같은 마이그레이션**으로 스키마를 만든다.

    테이블을 여기서 따로 정의하면 정본이 둘이 된다. 컬럼이 갈라져도 이 테스트는
    계속 통과하고, 정작 실물에서 Worker 가 깨진다.
    """
    with conn.cursor() as cur:
        for path in sorted(MIGRATIONS.glob("V*.sql")):
            cur.execute(path.read_text(encoding="utf-8"))
    conn.commit()


def reset(conn) -> None:
    with conn.cursor() as cur:
        cur.execute("TRUNCATE judge_jobs, skill_evidence, user_skills, submissions,"
                    " problems, users RESTART IDENTITY CASCADE")
    conn.commit()


def seed_job(conn, *, source_code: str = "print(1)", problem: str = "P01_QUEUE_BASIC",
             language: str = "PYTHON") -> int:
    """제출 하나와 그에 딸린 job 하나를 만든다."""
    with conn.cursor() as cur:
        cur.execute("INSERT INTO users (email, nickname) VALUES (%s, %s) RETURNING id",
                    (f"w{os.urandom(4).hex()}@codesprint.dev", "worker-test"))
        user_id = cur.fetchone()[0]
        cur.execute("INSERT INTO problems (code, source) VALUES (%s, 'DEV_FIXTURE')"
                    " ON CONFLICT (code) DO UPDATE SET source = EXCLUDED.source RETURNING id",
                    (problem,))
        problem_id = cur.fetchone()[0]
        cur.execute(
            "INSERT INTO submissions (user_id, problem_id, language, status)"
            " VALUES (%s, %s, %s, 'QUEUED') RETURNING id",
            (user_id, problem_id, language))
        submission_id = cur.fetchone()[0]
        cur.execute(
            "INSERT INTO judge_jobs (submission_id, problem_code, language, source_code)"
            " VALUES (%s, %s, %s, %s) RETURNING id",
            (submission_id, problem, language, source_code))
        job_id = cur.fetchone()[0]
    conn.commit()
    return job_id


def row(conn, job_id: int) -> dict:
    with conn.cursor() as cur:
        cur.execute("SELECT status, attempts, result, failure_reason, lease_expires_at,"
                    " applied_at FROM judge_jobs WHERE id = %s", (job_id,))
        status, attempts, result, reason, lease, applied = cur.fetchone()
    return {"status": status, "attempts": attempts, "result": result,
            "failureReason": reason, "lease": lease, "appliedAt": applied}


# -- 1. 배정 -------------------------------------------------------------

def test_claims_once(conn) -> None:
    """두 Worker 가 같은 job 을 집으면 같은 제출이 두 번 채점된다."""
    reset(conn)
    job_id = seed_job(conn)

    first = worker.claim(conn)
    check("job 을 집어온다", first is not None and first["jobId"] == job_id)
    check("집으면 RUNNING 이 된다", row(conn, job_id)["status"] == "RUNNING")
    check("시도 횟수가 는다", row(conn, job_id)["attempts"] == 1)

    # 리스가 살아 있는 동안에는 아무도 못 집는다.
    check("이미 배정된 job 은 다시 집히지 않는다", worker.claim(conn) is None)


def test_expired_lease_is_reclaimed(conn) -> None:
    """Worker 가 죽으면 그 job 은 영원히 RUNNING 으로 남는다 - 리스가 그걸 푼다."""
    reset(conn)
    job_id = seed_job(conn)
    worker.claim(conn)

    # Worker 가 죽었다고 하자. 리스만 과거로 돌린다.
    with conn.cursor() as cur:
        cur.execute("UPDATE judge_jobs SET lease_expires_at = now() - interval '1 minute'"
                    " WHERE id = %s", (job_id,))
    conn.commit()

    again = worker.claim(conn)
    check("리스가 만료되면 다시 집힌다", again is not None and again["jobId"] == job_id)
    check("다시 집으면 시도 횟수가 또 는다", row(conn, job_id)["attempts"] == 2)


def test_exhausted_job_is_failed(conn) -> None:
    """상한을 넘긴 job 을 그냥 두면 아무도 집지 않는 채로 남는다.

    사용자에게는 제출이 영영 PENDING 으로 보인다 - 실패했다는 사실조차 전달되지 않는다.
    """
    reset(conn)
    job_id = seed_job(conn)
    with conn.cursor() as cur:
        cur.execute("UPDATE judge_jobs SET attempts = %s,"
                    " lease_expires_at = now() - interval '1 minute' WHERE id = %s",
                    (worker.MAX_ATTEMPTS, job_id))
    conn.commit()

    check("상한을 넘기면 더 집지 않는다", worker.claim(conn) is None)

    worker.reap_exhausted(conn)
    after = row(conn, job_id)
    check("포기한 job 은 FAILED 로 끝난다", after["status"] == "FAILED")
    check("왜 실패했는지 남는다", bool(after["failureReason"]),
          f"failureReason={after['failureReason']}")


# -- 2. 채점 -------------------------------------------------------------

def test_accepted_submission(conn) -> None:
    """정답이 실제로 ACCEPTED 를 받는다. Docker 가 필요하다."""
    reset(conn)
    reference = (ROOT / "problems" / "P01_QUEUE_BASIC" / "reference.py").read_text(
        encoding="utf-8")
    job_id = seed_job(conn, source_code=reference)

    worker.drain(conn)
    after = row(conn, job_id)
    check("채점이 끝나면 DONE 이다", after["status"] == "DONE", f"status={after['status']}")
    result = after["result"] if isinstance(after["result"], dict) else json.loads(
        after["result"] or "{}")
    check("정답은 ACCEPTED 다", result.get("status") == "ACCEPTED", f"result={result}")
    check("리스를 놓는다", after["lease"] is None)


def test_wrong_submission(conn) -> None:
    """오답도 판정으로 돌아온다 - 실패가 아니다."""
    reset(conn)
    wrong = (ROOT / "problems" / "P01_QUEUE_BASIC" / "wrong.py").read_text(encoding="utf-8")
    job_id = seed_job(conn, source_code=wrong)

    worker.drain(conn)
    after = row(conn, job_id)
    result = after["result"] if isinstance(after["result"], dict) else json.loads(
        after["result"] or "{}")
    check("오답도 DONE 으로 끝난다", after["status"] == "DONE")
    check("판정은 ACCEPTED 가 아니다", result.get("status") != "ACCEPTED", f"result={result}")


def test_missing_problem_fails(conn) -> None:
    """Test Case 가 없으면 채점할 수 없다. 조용히 통과시키면 안 된다."""
    reset(conn)
    job_id = seed_job(conn, problem="NO_SUCH_PROBLEM")

    worker.drain(conn)
    after = row(conn, job_id)
    check("채점하지 못하면 FAILED 다", after["status"] == "FAILED")
    check("결과는 비어 있다", after["result"] is None)
    check("이유가 남는다", bool(after["failureReason"]))


# -- 3. 경계 -------------------------------------------------------------

def test_worker_does_not_touch_learning_state(conn) -> None:
    """Worker 는 판정만 만든다.

    Evidence / mastery / 다음 행동은 Java 가 결과를 반영할 때 만든다(ADR-0011).
    두 언어가 같은 테이블을 고치기 시작하면 어느 쪽이 정본인지 알 수 없게 된다.
    """
    reset(conn)
    reference = (ROOT / "problems" / "P01_QUEUE_BASIC" / "reference.py").read_text(
        encoding="utf-8")
    job_id = seed_job(conn, source_code=reference)
    worker.drain(conn)

    with conn.cursor() as cur:
        cur.execute("SELECT count(*) FROM skill_evidence")
        evidence = cur.fetchone()[0]
        cur.execute("SELECT count(*) FROM user_skills")
        skills = cur.fetchone()[0]
        cur.execute("SELECT status FROM submissions")
        submission_status = cur.fetchone()[0]

    check("Evidence 를 만들지 않는다", evidence == 0, f"{evidence}건")
    check("user_skills 를 건드리지 않는다", skills == 0, f"{skills}건")
    # 제출의 판정도 Java 가 반영할 때 붙인다. Worker 가 미리 바꾸면 반영 전에
    # 사용자가 결과를 보게 되고, 그때 다음 행동은 아직 없다.
    check("제출 행의 판정도 건드리지 않는다", submission_status == "QUEUED",
          f"status={submission_status}")
    check("반영 표시는 Java 의 몫이다", row(conn, job_id)["appliedAt"] is None)


def main() -> int:
    try:
        import psycopg
    except ImportError:
        print("[FAIL] psycopg 가 없다. pip install -r requirements-dev.txt")
        return 1

    try:
        conn = psycopg.connect(worker.dsn())
    except Exception as e:  # noqa: BLE001 - 접속 실패 원인을 그대로 보여준다
        # 건너뛰지 않는다. 조용히 skip 하면 큐 검증이 사라진 줄 아무도 모른다.
        print(f"[FAIL] DB 에 접속하지 못했다: {e}")
        print("       CODESPRINT_DB_URL 또는 DB_HOST/DB_NAME/DB_USER/DB_PASSWORD 를 확인한다.")
        return 1

    with conn:
        migrate(conn)
        for fn in (test_claims_once, test_expired_lease_is_reclaimed,
                   test_exhausted_job_is_failed, test_accepted_submission,
                   test_wrong_submission, test_missing_problem_fails,
                   test_worker_does_not_touch_learning_state):
            print(f"\n== {fn.__name__} ==")
            fn(conn)

    if failures:
        print(f"\n[FAIL] {len(failures)}건 실패")
        for f in failures:
            print("  - " + f)
        return 1
    print("\n[OK] Worker 큐 테스트 전부 통과")
    return 0


if __name__ == "__main__":
    sys.exit(main())
