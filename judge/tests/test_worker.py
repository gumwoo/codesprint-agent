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

import contextlib
import json
import os
import pathlib
import shutil
import sys
import tempfile

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


def dsn_for_tests() -> str:
    """**테스트 전용** 접속 정보. 없으면 실패한다.

    worker.dsn() 을 그대로 쓰면 안 된다. 이 테스트는 아래에서 스키마를 통째로 지우는데,
    worker.dsn() 은 실제 Worker 가 보는 DB 를 가리킨다 - CODESPRINT_DB_URL 을 개발
    DB 로 맞춰 둔 사람이 이 파일을 실행하면 그 DB 가 날아간다.

    기본값을 두지 않는 이유도 같다. "없으면 localhost/codesprint" 로 두면 그 이름의
    개발 DB 를 쓰는 사람이 그대로 당한다. 명시적으로 주게 한다.
    """
    url = os.environ.get("CODESPRINT_TEST_DB_URL")
    if url:
        return url
    host = os.environ.get("TEST_DB_HOST")
    name = os.environ.get("TEST_DB_NAME")
    if not host or not name:
        raise SystemExit(chr(10).join([
            "테스트 DB 를 지정해야 한다. 이 테스트는 스키마를 통째로 지운다.",
            "  CODESPRINT_TEST_DB_URL=postgresql://user:pw@host:5432/codesprint_test",
            "  또는 TEST_DB_HOST / TEST_DB_NAME"
            " (+ TEST_DB_PORT / TEST_DB_USER / TEST_DB_PASSWORD)",
            "운영이나 개발 DB 를 가리키지 않는지 확인한다.",
        ]))
    return (
        f"host={host} port={os.environ.get('TEST_DB_PORT', '5432')} dbname={name} "
        f"user={os.environ.get('TEST_DB_USER', 'codesprint')} "
        f"password={os.environ.get('TEST_DB_PASSWORD', 'codesprint')}"
    )


def migration_version(path: pathlib.Path) -> int:
    """`V10__x.sql` -> 10.

    **문자열로 정렬하지 않는다.** V10 이 생기는 순간 V1 보다 앞에 오고, 그러면
    아직 만들어지지 않은 테이블을 ALTER 하게 된다 - 실제로 그렇게 깨졌다.

    Flyway 는 숫자로 정렬한다. 하네스가 그것과 달라지면 여기서 도는 스키마가
    실물과 다른 스키마가 되고, 이 테스트가 지키려던 것("정본이 둘이 되지 않게")이
    그대로 무너진다.
    """
    return int(path.name[1:].split("__", 1)[0])


def migrate(conn) -> None:
    """백엔드와 **같은 마이그레이션**으로 스키마를 만든다.

    테이블을 여기서 따로 정의하면 정본이 둘이 된다. 컬럼이 갈라져도 이 테스트는
    계속 통과하고, 정작 실물에서 Worker 가 깨진다.
    """
    with conn.cursor() as cur:
        # 매번 처음부터 만든다. 이어서 돌리면 두 번째 실행이 DuplicateTable 로 죽고,
        # 그러면 "테스트 DB 를 새로 띄웠을 때만 도는" 테스트가 된다.
        cur.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public")
        for path in sorted(MIGRATIONS.glob("V*.sql"), key=migration_version):
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
        cur.execute("INSERT INTO users (email, nickname, track) VALUES (%s, %s, 'JOB')"
                    " RETURNING id",
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


def expire_lease(conn, job_id: int) -> None:
    """리스를 과거로 돌린다. Worker 가 죽었거나 backoff 가 끝난 상황을 만든다."""
    with conn.cursor() as cur:
        cur.execute("UPDATE judge_jobs SET lease_expires_at = now() - interval '1 minute'"
                    " WHERE id = %s", (job_id,))
    conn.commit()


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


def test_stale_worker_cannot_overwrite(conn) -> None:
    """리스가 만료된 뒤 살아난 Worker 가 남의 판정을 덮어쓰면 안 된다.

    리스는 "다른 Worker 가 다시 가져갈 수 있다" 만 보장한다. 이전 Worker 가 나중에
    결과를 쓰는 것은 막지 못하므로, attempts 를 fencing token 으로 쓴다.
    """
    reset(conn)
    job_id = seed_job(conn)

    a = worker.claim(conn)                      # Worker A: attempts = 1
    with conn.cursor() as cur:                  # A 가 멈춘 사이 리스가 만료된다
        cur.execute("UPDATE judge_jobs SET lease_expires_at = now() - interval '1 minute'"
                    " WHERE id = %s", (job_id,))
    conn.commit()
    b = worker.claim(conn)                      # Worker B: attempts = 2

    wrote_b = worker.finish(conn, job_id, b["attempts"],
                            {"status": "ACCEPTED", "passed": 5, "total": 5}, None)
    check("현재 주인은 결과를 쓴다", wrote_b)

    # A 가 뒤늦게 살아나 자기 판정을 들고 온다.
    wrote_a = worker.finish(conn, job_id, a["attempts"],
                            {"status": "WRONG_ANSWER", "passed": 0, "total": 5}, None)
    check("뒤늦은 Worker 의 쓰기는 거부된다", not wrote_a)

    after = row(conn, job_id)
    result = after["result"] if isinstance(after["result"], dict) else json.loads(
        after["result"] or "{}")
    check("판정이 덮어써지지 않는다", result.get("status") == "ACCEPTED",
          f"result={result}")


def test_stale_worker_cannot_revive_failed_job(conn) -> None:
    """상한을 넘겨 거둔 job 을 뒤늦은 Worker 가 DONE 으로 되살리면 안 된다.

    attempts 만 비교하면 이 경우가 통과한다 - 거둘 때 attempts 는 그대로이기 때문이다.
    status = 'RUNNING' 조건이 그것을 막는다.
    """
    reset(conn)
    job_id = seed_job(conn)
    claimed = worker.claim(conn)

    with conn.cursor() as cur:
        cur.execute("UPDATE judge_jobs SET attempts = %s,"
                    " lease_expires_at = now() - interval '1 minute' WHERE id = %s",
                    (worker.MAX_ATTEMPTS, job_id))
    conn.commit()
    worker.reap_exhausted(conn)
    check("거둔 job 은 FAILED 다", row(conn, job_id)["status"] == "FAILED")

    # 거둘 때 attempts 를 바꾸지 않았으므로, 그 값을 든 Worker 가 돌아올 수 있다.
    revived = worker.finish(conn, job_id, worker.MAX_ATTEMPTS,
                            {"status": "ACCEPTED", "passed": 5, "total": 5}, None)
    check("거둔 job 은 되살아나지 않는다", not revived)
    check("FAILED 로 남는다", row(conn, job_id)["status"] == "FAILED")
    check("claim 한 적이 있어도 마찬가지다", claimed is not None)


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


# P01 의 Java 풀이. **언어가 이미지를 고르는지** 보려고 쓴다(ADR-0045).
JAVA_P01 = """import java.io.*;
import java.util.*;
public class Main {
    public static void main(String[] args) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        StringTokenizer first = new StringTokenizer(in.readLine());
        int m = Integer.parseInt(first.nextToken()), n = Integer.parseInt(first.nextToken());
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int i = 1; i <= m; i++) q.add(i);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < n; i++) {
            StringTokenizer t = new StringTokenizer(in.readLine());
            if (t.nextToken().equals("push")) q.add(Integer.parseInt(t.nextToken()));
            else out.append(q.isEmpty() ? -1 : q.poll()).append('\\n');
        }
        System.out.print(out);
    }
}
"""


def test_language_picks_the_image(conn) -> None:
    """job 의 language 가 채점 이미지를 고른다(ADR-0045). Docker 와 Java 이미지가 필요하다.

    **대조군이 있다.** 같은 코드를 PYTHON 으로 넣으면 컴파일 오류여야 한다 - 그래야 ACCEPTED 가
    "Java 로 돌았기 때문" 이라고 말할 수 있다. 언어를 무시하고 늘 같은 이미지로 돌면 둘 다 같은 판정이 된다.
    """
    reset(conn)
    java = seed_job(conn, source_code=JAVA_P01, language="JAVA")
    as_python = seed_job(conn, source_code=JAVA_P01, language="PYTHON")

    worker.drain(conn)
    results = {}
    for name, job_id in (("JAVA", java), ("PYTHON", as_python)):
        after = row(conn, job_id)
        results[name] = after["result"] if isinstance(after["result"], dict) else json.loads(
            after["result"] or "{}")
    check("Java 로 낸 Java 풀이는 ACCEPTED 다", results["JAVA"].get("status") == "ACCEPTED",
          f"result={results['JAVA']}")
    check("대조: 같은 코드를 PYTHON 으로 내면 COMPILE_ERROR 다",
          results["PYTHON"].get("status") == "COMPILE_ERROR", f"result={results['PYTHON']}")


# -- 제한은 문제의 것이다 (ADR-0058) ------------------------------------
# Worker 가 cases.json 만 넘기던 때, 모든 문제가 기본값 2000ms · 전역 256m 로 채점됐다. P111 은 화면에
# 1000ms 라고 보이는데 1721ms 가 걸린 제출이 ACCEPTED 였다. 검증(verify_problems)은 problem.yaml 의 값으로
# 돌아서 그 차이를 보지 못했다.
#
# **제한만 다른 문제 사본 둘**을 같은 코드로 채점한다. 판정이 갈리면 제한이 문제에서 왔다는 뜻이다 -
# 전역 값 하나로 돌면 둘은 같은 판정이 된다. 사본을 쓰는 이유는 실제 문제의 제한이 바뀌어도 이 검사가
# 제 뜻을 잃지 않게 하기 위해서다.

BASE_PROBLEM = "P01_QUEUE_BASIC"

# 풀이 앞에 붙일 대기. 두 시간 제한(1000 / 2000ms) 사이에 오도록 잡는다 - 인터프리터 기동이 --cpus 0.5
# 에서 수백 ms 라, 1.5 초면 느슨한 쪽(2000ms)의 여유가 100ms 안팎이었다(실측 1898ms).
SLEEP_S = 1.1

# 메모리를 실제로 쓰는 할당(페이지를 채운다 - bytearray(n) 은 0 페이지라 RSS 로 잡히지 않을 수 있다).
# 느슨한 쪽(256m)에는 들어가고 빡빡한 쪽(64m)에는 들어가지 않는 크기다.
ALLOC_MB = 100


@contextlib.contextmanager
def problem_copies(**variants: dict):
    """``BASE_PROBLEM`` 의 사본을 만들고 Worker 가 그 디렉터리를 보게 한다.

    ``variants`` 는 {사본 code: problem.yaml 에 덮어쓸 값}. cases.json 은 그대로 둔다.
    """
    import yaml

    base = ROOT / "problems" / BASE_PROBLEM
    original = worker.PROBLEMS
    with tempfile.TemporaryDirectory(prefix="codesprint-limits-") as tmp:
        root = pathlib.Path(tmp)
        for code, overrides in variants.items():
            target = root / code
            target.mkdir()
            shutil.copyfile(base / "cases.json", target / "cases.json")
            problem = yaml.safe_load((base / "problem.yaml").read_text(encoding="utf-8"))
            problem.update(code=code, **overrides)
            (target / "problem.yaml").write_text(
                yaml.safe_dump(problem, allow_unicode=True), encoding="utf-8")
        worker.PROBLEMS = root
        try:
            yield
        finally:
            worker.PROBLEMS = original


def seed_run_job(conn, *, source_code: str, problem: str) -> int:
    """제출 전 실행(kind=RUN) 하나. 제출 행을 가리키지 않는다(V7)."""
    with conn.cursor() as cur:
        cur.execute("INSERT INTO users (email, nickname, track) VALUES (%s, %s, 'JOB')"
                    " RETURNING id",
                    (f"r{os.urandom(4).hex()}@codesprint.dev", "worker-test"))
        user_id = cur.fetchone()[0]
        cur.execute(
            "INSERT INTO judge_jobs (kind, user_id, problem_code, language, source_code)"
            " VALUES ('RUN', %s, %s, 'PYTHON', %s) RETURNING id",
            (user_id, problem, source_code))
        job_id = cur.fetchone()[0]
    conn.commit()
    return job_id


def result_of(conn, job_id: int) -> dict:
    after = row(conn, job_id)
    return after["result"] if isinstance(after["result"], dict) else json.loads(
        after["result"] or "{}")


def test_time_limit_comes_from_the_problem(conn) -> None:
    """같은 느린 정답이 1000ms 문제에서는 TIME_LIMIT, 2000ms 문제에서는 ACCEPTED 다.

    제출(SUBMIT)과 제출 전 실행(RUN)을 함께 본다 - 모의 시험의 제출 · 실행도 같은 두 종류의 job 이다.
    """
    reset(conn)
    reference = (ROOT / "problems" / BASE_PROBLEM / "reference.py").read_text(encoding="utf-8")
    slow = f"import time\ntime.sleep({SLEEP_S})\n" + reference

    with problem_copies(LIMIT_TIGHT_TIME={"timeLimitMs": 1000},
                        LIMIT_LOOSE_TIME={"timeLimitMs": 2000}):
        tight = seed_job(conn, source_code=slow, problem="LIMIT_TIGHT_TIME")
        loose = seed_job(conn, source_code=slow, problem="LIMIT_LOOSE_TIME")
        tight_run = seed_run_job(conn, source_code=slow, problem="LIMIT_TIGHT_TIME")
        worker.drain(conn)

    tight_result = result_of(conn, tight)
    loose_result = result_of(conn, loose)
    run_result = result_of(conn, tight_run)
    check("timeLimitMs 1000 문제에서는 TIME_LIMIT 다",
          tight_result.get("status") == "TIME_LIMIT", f"result={tight_result}")
    # 옛 기본값(2000)으로 걸렸다면 2000ms 를 넘겨야 TIME_LIMIT 다. 그보다 짧게 걸렸다는 것이 1000 으로 돌았다는 뜻이다.
    # 하한은 1000 을 포함한다 - hard limit(제한 + 500ms)에 걸려 죽으면 하네스는 executionMs 를 제한값 그대로 보고한다
    # (느린 러너에서 기동 + sleep 이 1.5 초를 넘으면 그렇다, 검증 에이전트).
    check("2000ms 전에 걸렸다 - 전역 기본값이 아니라 문제의 값으로",
          1000 <= (tight_result.get("executionMs") or 0) < 2000,
          f"executionMs={tight_result.get('executionMs')}")
    check("대조: 같은 코드가 timeLimitMs 2000 문제에서는 ACCEPTED 다",
          loose_result.get("status") == "ACCEPTED", f"result={loose_result}")
    # 대조가 성립하려면 느슨한 쪽이 실제로 1000ms 를 넘겨야 한다 - 아니면 SLEEP_S 가 두 제한 사이에 있지 않다.
    check("대조가 성립한다: 느슨한 쪽도 1000ms 를 넘겨 돌았다 [VACUOUS 아님]",
          (loose_result.get("executionMs") or 0) > 1000,
          f"executionMs={loose_result.get('executionMs')}")
    check("제출 전 실행도 문제의 제한으로 돈다", run_result.get("status") == "TIME_LIMIT",
          f"result={run_result}")


def test_memory_limit_comes_from_the_problem(conn) -> None:
    """같은 코드가 memoryLimitMb 64 문제에서는 MEMORY_LIMIT, 256 문제에서는 ACCEPTED 다."""
    reset(conn)
    reference = (ROOT / "problems" / BASE_PROBLEM / "reference.py").read_text(encoding="utf-8")
    hungry = f"held = b'x' * ({ALLOC_MB} * 1024 * 1024)\n" + reference

    with problem_copies(LIMIT_TIGHT_MEMORY={"memoryLimitMb": 64},
                        LIMIT_LOOSE_MEMORY={"memoryLimitMb": 256}):
        tight = seed_job(conn, source_code=hungry, problem="LIMIT_TIGHT_MEMORY")
        loose = seed_job(conn, source_code=hungry, problem="LIMIT_LOOSE_MEMORY")
        worker.drain(conn)

    tight_result = result_of(conn, tight)
    loose_result = result_of(conn, loose)
    check("memoryLimitMb 64 문제에서는 MEMORY_LIMIT 다",
          tight_result.get("status") == "MEMORY_LIMIT", f"result={tight_result}")
    check("대조: 같은 코드가 memoryLimitMb 256 문제에서는 ACCEPTED 다",
          loose_result.get("status") == "ACCEPTED", f"result={loose_result}")
    check("대조가 성립한다: 느슨한 쪽이 실제로 64MB 를 넘게 썼다 [VACUOUS 아님]",
          (loose_result.get("memoryKb") or 0) > 64 * 1024,
          f"memoryKb={loose_result.get('memoryKb')}")


def test_limits_are_not_defaulted(conn) -> None:
    """제한이 없거나 천장을 넘는 문제는 **채점하지 않는다.** 기본값이나 천장으로 바꿔 돌리지 않는다.

    결함이 "없으면 2000" 이라는 조용한 기본값이었다. 그리고 천장(run_submission.MEMORY_CEILING_MB)으로
    낮춰 돌리면 화면이 보여 주는 값과 채점이 쓰는 값이 다시 갈린다.
    """
    import run_submission

    reset(conn)
    over = run_submission.MEMORY_CEILING_MB * 2
    with problem_copies(LIMIT_MISSING={"timeLimitMs": None},
                        LIMIT_OVER_CEILING={"memoryLimitMb": over}):
        missing = seed_job(conn, problem="LIMIT_MISSING")
        too_big = seed_job(conn, problem="LIMIT_OVER_CEILING")
        worker.drain(conn)

    missing_row = row(conn, missing)
    too_big_row = row(conn, too_big)
    check("timeLimitMs 가 없으면 판정을 내지 않는다",
          missing_row["result"] is None and "timeLimitMs" in (missing_row["failureReason"] or ""),
          f"row={missing_row}")
    check("천장을 넘는 memoryLimitMb 는 판정을 내지 않는다",
          too_big_row["status"] != "DONE" and "memoryLimitMb" in (too_big_row["failureReason"] or ""),
          f"row={too_big_row}")


# -- 판정은 로캘과 상관없이 UTF-8 로 온다 ---------------------------------
# run_submission.py 는 판정 JSON 을 로캘 인코딩으로 쓰고, 하네스 출력도 로캘로 읽었다. Worker 는 UTF-8 로 읽는다.
# Windows(cp949)에서 한글 이유가 깨진 채 DB 에 남았고, 사용자 출력에 한글 · 이모지가 있으면 print 가 죽어
# 평범한 제출이 재시도 끝에 FAILED 가 됐다.
#
# Linux CI 의 로캘은 UTF-8 이라 그대로 두면 이 결함이 보이지 않는다. **자식의 로캘을 UTF-8 이 아니게** 만든다.
# 둘 다 필요하다 - LC_ALL=C 만 주면 Python 이 C 로캘에서 UTF-8 모드를 켜고(PEP 540), PYTHONUTF8=0 만 주면
# 러너의 로캘(C.UTF-8)을 그대로 쓴다. 둘을 함께 주면 ascii 다(python:3.12 이미지에서 확인). LC_ALL 이 있으면
# C.UTF-8 로의 강제 변환(PEP 538)은 일어나지 않는다. Windows 에서는 LC_ALL 이 뜻이 없지만 로캘이 원래 cp949 다.
NON_UTF8_CHILD = {"LC_ALL": "C", "PYTHONUTF8": "0"}


@contextlib.contextmanager
def non_utf8_child_locale():
    """Worker 가 띄우는 run_submission.py 가 UTF-8 이 아닌 로캘로 돌게 한다. Worker 는 환경을 그대로 물려준다."""
    names = [*NON_UTF8_CHILD, "PYTHONIOENCODING"]  # PYTHONIOENCODING 이 있으면 stdout 쪽을 가린다
    saved = {name: os.environ.get(name) for name in names}
    os.environ.pop("PYTHONIOENCODING", None)
    os.environ.update(NON_UTF8_CHILD)
    try:
        yield
    finally:
        for name, value in saved.items():
            if value is None:
                os.environ.pop(name, None)
            else:
                os.environ[name] = value


def test_verdict_text_survives_a_non_utf8_locale(conn) -> None:
    """한글 이유와 사용자 출력의 한글 · 이모지가 DB 에 그대로 남는다. 이모지를 찍는 제출도 평범한 판정을 받는다."""
    import subprocess

    import run_submission

    # 대조: 그 환경이 정말 UTF-8 이 아닌가. UTF-8 이면 아래 검사는 고치기 전에도 통과한다.
    with non_utf8_child_locale():
        probe = subprocess.run(
            [sys.executable, "-c",
             "import locale, sys; print(locale.getpreferredencoding(False), sys.stdout.encoding)"],
            capture_output=True, text=True, encoding="utf-8", errors="replace")
    encodings = probe.stdout.split()
    check("대조가 성립한다: 자식의 로캘 · stdout 이 UTF-8 이 아니다 [VACUOUS 아님]",
          len(encodings) == 2 and not any(e.lower().replace("-", "") in ("utf8", "cp65001")
                                          for e in encodings),
          f"encodings={encodings}")

    reset(conn)
    over = run_submission.MEMORY_CEILING_MB * 2
    # 기대하는 이유는 같은 함수를 **프로세스 안에서** 불러 얻는다 - 인코딩 경계를 지나지 않은 원문이다.
    # 천장 검사가 docker 보다 먼저라 컨테이너는 뜨지 않는다.
    with tempfile.TemporaryDirectory(prefix="codesprint-utf8-") as tmp:
        job_path = pathlib.Path(tmp) / "job.json"
        job_path.write_text(json.dumps({"memoryLimitMb": over, "cases": [{"id": 1, "input": ""}]}),
                            encoding="utf-8")
        expected_reason = run_submission.run(job_path, job_path)["stderr"]

    shout = "한글 \U0001F600"  # 한글 + cp949 에 없는 이모지
    with problem_copies(TEXT_OVER_CEILING={"memoryLimitMb": over}, TEXT_BASE={}):
        too_big = seed_job(conn, problem="TEXT_OVER_CEILING")
        printed = seed_run_job(conn, source_code=f"print({shout!r})\n", problem="TEXT_BASE")
        crashed = seed_job(conn, problem="TEXT_BASE", source_code=(
            f"import sys\nprint({shout!r}, file=sys.stderr)\nraise SystemExit(1)\n"))
        with non_utf8_child_locale():
            worker.drain(conn)

    too_big_row = row(conn, too_big)
    # 이유 문구가 ASCII 로만 바뀌면 이 검사는 아무것도 보지 않는다 - 한글이 들어 있어야 뜻이 있다.
    check("대조가 성립한다: 기대하는 이유에 한글이 있다 [VACUOUS 아님]",
          any("가" <= ch <= "힣" for ch in expected_reason or ""),
          f"기대={expected_reason!r}")
    check("한글 이유가 깨지지 않고 DB 에 남는다",
          too_big_row["failureReason"] == expected_reason,
          f"기대={expected_reason!r} / 실제={too_big_row['failureReason']!r}")

    printed_row = row(conn, printed)
    printed_result = result_of(conn, printed)
    stdout = ((printed_result.get("cases") or [{}])[0]).get("stdout")
    check("한글 · 이모지를 찍는 실행이 평범한 판정을 받는다 (SYSTEM_ERROR · 재시도가 아니다)",
          printed_row["status"] == "DONE" and printed_row["attempts"] == 1
          and printed_result.get("status") == "WRONG_ANSWER",
          f"row={printed_row}")
    check("실행 결과의 출력이 깨지지 않는다", stdout == shout + "\n", f"stdout={stdout!r}")

    crashed_row = row(conn, crashed)
    crashed_result = result_of(conn, crashed)
    check("stderr 에 이모지를 찍고 죽는 제출은 RUNTIME_ERROR 다",
          crashed_row["status"] == "DONE" and crashed_result.get("status") == "RUNTIME_ERROR",
          f"row={crashed_row}")
    check("그 stderr 가 깨지지 않는다", shout in (crashed_result.get("stderr") or ""),
          f"stderr={crashed_result.get('stderr')!r}")


def test_infra_failure_is_retried(conn) -> None:
    """감지된 인프라 장애는 첫 시도에서 끝나면 안 된다.

    ADR-0013 이 큐를 도입한 이유 중 하나가 "실패한 채점을 다시 시도할 수 있다" 다.
    그런데 run_submission.py 는 Docker 를 못 찾아도 SYSTEM_ERROR JSON 을 정상
    출력하고 exit 0 으로 끝난다 - 가르지 않으면 Worker 에게는 "결과가 있다" 로 보여
    곧바로 DONE 이 된다. 그러면 상한 3회는 Worker 가 죽은 경우에만 쓰인다.

    Test Case 파일이 없는 job 으로 그 경로를 만든다 - run_job 이 (None, 이유)를 낸다.
    """
    reset(conn)
    job_id = seed_job(conn, problem="NO_SUCH_PROBLEM")

    worker.drain(conn)
    after = row(conn, job_id)
    check("첫 실패로 끝내지 않는다", after["status"] == "RUNNING",
          f"status={after['status']}")
    check("이유는 남긴다", bool(after["failureReason"]))
    check("backoff 동안은 다시 집히지 않는다", worker.claim(conn) is None)

    # 상한까지 시도한다. backoff 를 기다리는 대신 리스를 과거로 돌린다.
    for _ in range(worker.MAX_ATTEMPTS - 1):
        expire_lease(conn, job_id)
        worker.drain(conn)

    check("상한만큼 시도한다", row(conn, job_id)["attempts"] == worker.MAX_ATTEMPTS,
          f"attempts={row(conn, job_id)['attempts']}")

    expire_lease(conn, job_id)
    worker.drain(conn)
    final = row(conn, job_id)
    check("상한을 다 쓰면 FAILED 로 끝난다", final["status"] == "FAILED",
          f"status={final['status']}")
    check("마지막 실패 이유가 보존된다", "cases.json" in (final["failureReason"] or ""),
          f"failureReason={final['failureReason']}")


def test_user_failure_is_not_retried(conn) -> None:
    """오답은 다시 돌려도 같은 판정이다. 재시도는 낭비다."""
    reset(conn)
    wrong = (ROOT / "problems" / "P01_QUEUE_BASIC" / "wrong.py").read_text(encoding="utf-8")
    job_id = seed_job(conn, source_code=wrong)

    worker.drain(conn)
    after = row(conn, job_id)
    check("오답은 한 번에 끝난다", after["status"] == "DONE")
    check("시도 횟수도 한 번이다", after["attempts"] == 1, f"attempts={after['attempts']}")


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


def test_migrations_run_in_flyway_order(conn) -> None:
    """마이그레이션을 **숫자 순서**로 돌리는가.

    DB 가 필요 없는 검사지만 여기 둔다 - 깨지는 곳이 여기이기 때문이다.

    V9 까지는 문자열 정렬과 숫자 정렬이 우연히 같았다. V10 이 생기자 갈라졌고,
    아직 만들어지지 않은 테이블을 ALTER 하다 죽었다. **우연히 맞던 것이 언제
    틀리기 시작하는지는 그때가 되어야 보인다.**
    """
    names = [p.name for p in sorted(MIGRATIONS.glob("V*.sql"), key=migration_version)]
    versions = [migration_version(MIGRATIONS / name) for name in names]

    check("마이그레이션이 숫자 순서로 돈다", versions == sorted(versions), str(names))
    check("버전이 중복되지 않는다", len(set(versions)) == len(versions), str(names))

    # 문자열 정렬과 갈라지는 순간을 실제로 확인한다. 같아지면 이 검사는 그
    # 이후로 아무것도 잡지 못하므로, 그 사실을 드러내 둔다.
    as_text = [p.name for p in sorted(MIGRATIONS.glob("V*.sql"))]
    if as_text == names:
        print("   (지금은 문자열 정렬과 결과가 같다 - V10 이상이 사라지면 이 검사는 무력하다)")
    else:
        print(f"   문자열 정렬이라면 {as_text[0]} 이 먼저 온다 - 숫자 정렬은 {names[0]}")


def main() -> int:
    try:
        import psycopg
    except ImportError:
        print("[FAIL] psycopg 가 없다. pip install -r requirements-dev.txt")
        return 1

    try:
        conn = psycopg.connect(dsn_for_tests())
    except Exception as e:  # noqa: BLE001 - 접속 실패 원인을 그대로 보여준다
        # 건너뛰지 않는다. 조용히 skip 하면 큐 검증이 사라진 줄 아무도 모른다.
        print(f"[FAIL] 테스트 DB 에 접속하지 못했다: {e}")
        print("       CODESPRINT_TEST_DB_URL 또는 TEST_DB_HOST / TEST_DB_NAME 을 확인한다.")
        return 1

    with conn:
        migrate(conn)
        for fn in (test_migrations_run_in_flyway_order,
                   test_claims_once, test_expired_lease_is_reclaimed,
                   test_exhausted_job_is_failed, test_stale_worker_cannot_overwrite,
                   test_stale_worker_cannot_revive_failed_job, test_accepted_submission,
                   test_wrong_submission, test_language_picks_the_image,
                   test_time_limit_comes_from_the_problem,
                   test_memory_limit_comes_from_the_problem,
                   test_limits_are_not_defaulted,
                   test_verdict_text_survives_a_non_utf8_locale,
                   test_infra_failure_is_retried,
                   test_user_failure_is_not_retried,
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
