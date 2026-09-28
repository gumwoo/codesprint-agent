#!/usr/bin/env python3
"""Judge / Sandbox 통합 테스트.

두 종류를 함께 돌린다.

  판정     fixture 코드가 의도한 status 로 판정되는가
  격리     Addendum 87 의 보안 항목이 실제로 막히는가
  기밀성   채점 데이터(정답표)가 컨테이너 안으로 새지 않는가 (ADR-0006)

뒤의 둘이 이 파일의 존재 이유다. `--network none` 을 옵션에 적어두는 것과
**네트워크가 실제로 안 되는 것**은 다르다. 옵션을 지우거나 오타를 내도 채점은
정상으로 보이고, 아무도 모른 채 신뢰 경계가 사라진다.

    python judge/tests/test_judge.py
    python judge/tests/test_judge.py --build   # 이미지부터 다시 굽는다
"""
from __future__ import annotations

import argparse
import json
import pathlib
import subprocess
import sys
import tempfile
import threading
import time
import uuid

from jsonschema import Draft202012Validator

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
FIXTURES = ROOT / "judge" / "fixtures"
JOB = FIXTURES / "job-grid-area.json"
RUNNER = ROOT / "judge" / "run_submission.py"

sys.path.insert(0, str(ROOT / "judge"))
import run_submission  # noqa: E402

# 채점 결과는 계약을 지켜야 한다. 지키지 않으면 backend 가 받아 파싱할 때 터지거나,
# 더 나쁘게는 null 이어야 할 자리가 비어 있는 채로 흘러간다.
RESULT_SCHEMA = Draft202012Validator(
    json.loads((ROOT / "contracts" / "judge-result.schema.json").read_text(encoding="utf-8"))
)


# 제출 전 실행의 결과는 다른 계약이다 - case 에 출력이 실린다. 오랫동안 이 모양은
# 어떤 계약에도 대 보지 않았고, 그래서 계약이 그것을 설명하지 못한다는 것이
# 드러나지 않았다(ADR-0031).
RUN_RESULT_SCHEMA = Draft202012Validator(
    json.loads((ROOT / "contracts" / "run-judge-result.schema.json").read_text(encoding="utf-8"))
)


def contract_errors(result: dict) -> list[str]:
    return [f"{list(e.path)}: {e.message}" for e in RESULT_SCHEMA.iter_errors(result)]


def run_contract_errors(result: dict) -> list[str]:
    return [f"{list(e.path)}: {e.message}" for e in RUN_RESULT_SCHEMA.iter_errors(result)]


# -- 판정 -----------------------------------------------------------------
# (fixture, 기대 status, 실패 case 를 특정해야 하는가)
#
# 마지막 열이 중요하다. Reviewer 출력의 failedCaseRefs 는 minItems 1 이라,
# Reviewer 를 호출하는 판정에서 failedCaseId 가 null 이면 근거를 만들 수 없다.
# ADR-0004 가 계약으로 정한 것을 여기서 실제로 확인한다.
VERDICTS = [
    ("sol-accepted.py", "ACCEPTED", False),
    ("sol-wrong.py", "WRONG_ANSWER", True),
    ("sol-runtime-error.py", "RUNTIME_ERROR", True),
    ("sol-timeout.py", "TIME_LIMIT", True),
    ("sol-memory.py", "MEMORY_LIMIT", True),
    ("sol-output-flood.py", "OUTPUT_LIMIT", True),
    ("sol-syntax-error.py", "COMPILE_ERROR", False),
    # 경계 검사 누락은 IndexError 로 터지기도 하고, Python 의 음수 인덱싱 때문에
    # 조용히 틀린 답을 내기도 한다(curriculum/mistakes.yaml BOUNDARY_CHECK).
    # 이 fixture 는 후자다 - 크래시 없이 WA 가 된다.
    ("sol-boundary-missing.py", "WRONG_ANSWER", True),
    # 정답표를 찾아 그대로 출력하려는 제출. 컨테이너 안에 expectedOutput 이 없으므로
    # 찾지 못하고 빈 출력을 낸다(ADR-0006). 아래 CONFIDENTIALITY 가 더 강하게 검사한다.
    ("sol-answer-leak.py", "WRONG_ANSWER", True),
]

# -- 실패의 모양 (ADR-0015) -----------------------------------------------
# 첫 실패에서 멈추면 "무엇이 통과했는가" 를 알 수 없고, 그러면 Reviewer 주장을
# 뒷받침할 독립 근거를 만들 수 없다. 그래서 싼 실패에서는 끝까지 돌린다.
#
# 반대로 비싼 실패(제한에 걸릴 때까지 기다린 것)에서 계속 돌리면 무한 루프 하나가
# case 수만큼의 timeout 을 먹는다 - ADR-0005 가 조기 종료를 택한 이유이며 그 비용
# 특성은 지켜야 한다. **두 방향을 함께 본다.**
#
# (이름, 남은 case 를 계속 돌려야 하는가)
PROFILE = [
    ("sol-wrong.py", True),
    ("sol-runtime-error.py", True),
    ("sol-boundary-missing.py", True),
    ("sol-timeout.py", False),
    ("sol-memory.py", False),
    ("sol-output-flood.py", False),
]

# judge-result.schema.json 의 status 중 위에서 다루지 않는 것.
# SYSTEM_ERROR 는 사용자 코드로 재현할 수 없어 별도 경로로 확인한다(아래 main).
STATUS_COVERED_ELSEWHERE = {"SYSTEM_ERROR"}

# -- 격리 (Addendum 87) ---------------------------------------------------
# (이름, 사용자 코드, 이 코드가 성공하면 안 되는 이유)
ISOLATION = [
    (
        "네트워크 차단",
        "import socket\n"
        "socket.setdefaulttimeout(3)\n"
        "socket.create_connection(('1.1.1.1', 53))\n"
        "print('연결됨')\n",
        "외부로 데이터를 보내거나 도구를 받아올 수 있다",
    ),
    (
        "DNS 조회 차단",
        "import socket\nprint(socket.gethostbyname('example.com'))\n",
        "이름 해석만으로도 데이터를 밖으로 실어 보낼 수 있다",
    ),
    (
        "루트 파일시스템 쓰기 차단",
        "open('/evil', 'w').write('x')\nprint('썼다')\n",
        "이미지를 변조해 다음 제출의 채점에 영향을 줄 수 있다",
    ),
    (
        "채점 하네스 변조 차단",
        "open('/opt/judge/harness.py', 'a').write('\\n')\nprint('썼다')\n",
        "채점 로직 자체를 바꿔 판정을 조작할 수 있다",
    ),
    (
        "마운트 읽기 전용",
        "open('/job/job.json', 'w').write('{}')\nprint('썼다')\n",
        "Test Case 를 바꿔 오답을 정답으로 만들 수 있다",
    ),
    (
        "root 아님",
        "import os\nassert os.geteuid() == 0, '루트 아님'\nprint('루트다')\n",
        "컨테이너 탈출 시도의 난이도가 크게 낮아진다",
    ),
    (
        "fork bomb 제한",
        "import os\n"
        "for _ in range(500):\n"
        "    try:\n"
        "        if os.fork() == 0:\n"
        "            os._exit(0)\n"
        "    except OSError:\n"
        "        raise SystemExit('제한됨')\n"
        "print('전부 fork 됨')\n",
        "호스트의 프로세스 테이블을 고갈시킬 수 있다",
    ),
    (
        "tmpfs 실행 차단",
        "import os, stat, subprocess\n"
        "p = '/tmp/x.sh'\n"
        "open(p, 'w').write('#!/bin/sh\\necho hi\\n')\n"
        "os.chmod(p, stat.S_IRWXU)\n"
        "subprocess.run([p], check=True)\n"
        "print('실행됨')\n",
        "받아온 바이너리를 실행할 발판이 된다",
    ),
]


# 컨테이너 안을 훑어 채점 데이터가 새어 들어왔는지 보는 프로브들.
LEAK_PROBE_CASES = """
import glob, json
found = []
for p in glob.glob('/job/**/*', recursive=True) + glob.glob('/tmp/**/*', recursive=True):
    try:
        d = json.load(open(p))
    except Exception:
        continue
    if isinstance(d, dict) and 'cases' in d:
        found.append(p)
print('LEAK' if found else 'CLEAN')
"""

# 자기 자신(/job/solution.py)은 제외한다. 이 프로브의 소스에 'expectedOutput' 이라는
# 문자열이 들어 있어서, 빼지 않으면 자기를 읽고 LEAK 로 오탐한다. 실제로 그랬다.
# 사용자가 자기 제출 코드를 읽는 것은 유출이 아니다.
LEAK_PROBE_KEYS = """
import glob, os
needles = ('expectedOutput', '"cases"')
hay = ' '.join(f'{k}={v}' for k, v in os.environ.items())
for p in glob.glob('/job/**/*', recursive=True) + glob.glob('/tmp/**/*', recursive=True):
    if os.path.realpath(p) == os.path.realpath('/job/solution.py'):
        continue
    try:
        hay += open(p, errors='ignore').read()
    except Exception:
        pass
print('LEAK' if any(n in hay for n in needles) else 'CLEAN')
"""

LEAK_PROBE_LISTING = """
import os
print(','.join(sorted(os.listdir('/job'))))
"""


# -- 채점 데이터 기밀성 (ADR-0006) ---------------------------------------
# 실행 격리와 별개의 축이다. 코드가 갇혀 있어도 정답표를 읽을 수 있으면
# 알고리즘을 하나도 풀지 않고 전 case 를 AC 받을 수 있다. 실제로 그랬다.
#
# 프로브는 정답과 비교하지 않고 **사용자 출력 자체**를 확인한다.
CONFIDENTIALITY = [
    (
        "정답표가 컨테이너 안에 없다",
        LEAK_PROBE_CASES,
        "CLEAN",
    ),
    (
        "expectedOutput 이라는 키가 어디에도 없다",
        LEAK_PROBE_KEYS,
        "CLEAN",
    ),
    (
        "마운트에는 제출 코드만 있다",
        LEAK_PROBE_LISTING,
        "solution.py",
    ),
]


# -- Java · C++ (ADR-0045) ------------------------------------------------
# 언어를 더해도 신뢰 경계는 하나다. 파이썬에서 막은 것을 같은 옵션이 다른 런타임에서도 막는지, 대조군과
# 함께 **그 언어로** 다시 본다 - 옵션은 컨테이너에 걸리지만, 뚫는 수단은 언어마다 다르다.

# (언어, fixture, 기대 status, 실패 case 를 특정해야 하는가)
LANG_VERDICTS = [
    *[("CPP", f"cpp/{name}", status, case) for name, status, case in [
        ("accepted.cpp", "ACCEPTED", False),
        ("wrong.cpp", "WRONG_ANSWER", True),
        ("runtime_error.cpp", "RUNTIME_ERROR", True),
        ("timeout.cpp", "TIME_LIMIT", True),
        ("memory.cpp", "MEMORY_LIMIT", True),
        ("output_flood.cpp", "OUTPUT_LIMIT", True),
        ("compile_error.cpp", "COMPILE_ERROR", False),
    ]],
    *[("JAVA", f"java/{name}", status, case) for name, status, case in [
        ("Accepted.java", "ACCEPTED", False),
        ("Wrong.java", "WRONG_ANSWER", True),
        ("RuntimeError.java", "RUNTIME_ERROR", True),
        ("Timeout.java", "TIME_LIMIT", True),
        ("Memory.java", "MEMORY_LIMIT", True),
        # 스레드를 pids 상한보다 많이 만들면 JVM 은 OutOfMemoryError("unable to create native thread")로
        # 죽는다. 메모리가 아니라 프로세스 수 상한이다 - MEMORY_LIMIT 로 부르면 안 된다(대조: Memory.java).
        ("ThreadBomb.java", "RUNTIME_ERROR", True),
        # JVM 은 SIGXFSZ 를 무시한다. 쓰기 실패를 삼키고 계속 돌면 시간 제한에 걸리는데, 그래도
        # 출력 상한을 채웠으면 OUTPUT_LIMIT 이어야 한다 - "느리다" 로 읽히면 안 된다.
        ("OutputFlood.java", "OUTPUT_LIMIT", True),
        ("CompileError.java", "COMPILE_ERROR", False),
    ]],
]

_CPP_HEAD = ("#include <bits/stdc++.h>\n#include <unistd.h>\n#include <netdb.h>\n"
             "#include <sys/socket.h>\n#include <sys/stat.h>\n#include <arpa/inet.h>\n"
             "using namespace std;\n")

# (언어, 이름, 사용자 코드, 이 코드가 성공하면 안 되는 이유). 실패는 0 이 아닌 종료 코드다.
LANG_ISOLATION = [
    ("CPP", "네트워크 차단", _CPP_HEAD + (
        "int main(){int s=socket(AF_INET,SOCK_STREAM,0);sockaddr_in a{};a.sin_family=AF_INET;"
        "a.sin_port=htons(53);inet_pton(AF_INET,\"1.1.1.1\",&a.sin_addr);timeval tv{3,0};"
        "setsockopt(s,SOL_SOCKET,SO_SNDTIMEO,&tv,sizeof tv);"
        "if(connect(s,(sockaddr*)&a,sizeof a)!=0)return 1;puts(\"connected\");}\n"),
     "외부로 데이터를 보내거나 도구를 받아올 수 있다"),
    ("CPP", "DNS 조회 차단", _CPP_HEAD + (
        "int main(){addrinfo*r=nullptr;if(getaddrinfo(\"example.com\",nullptr,nullptr,&r)!=0)"
        "return 1;puts(\"resolved\");}\n"),
     "이름 해석만으로도 데이터를 밖으로 실어 보낼 수 있다"),
    ("CPP", "루트 파일시스템 쓰기 차단", _CPP_HEAD + (
        "int main(){FILE*f=fopen(\"/evil\",\"w\");if(!f)return 1;fputs(\"x\",f);puts(\"w\");}\n"),
     "이미지를 변조해 다음 제출의 채점에 영향을 줄 수 있다"),
    ("CPP", "채점 하네스 변조 차단", _CPP_HEAD + (
        "int main(){FILE*f=fopen(\"/opt/judge/harness.py\",\"a\");if(!f)return 1;puts(\"w\");}\n"),
     "채점 로직 자체를 바꿔 판정을 조작할 수 있다"),
    ("CPP", "마운트 읽기 전용", _CPP_HEAD + (
        "int main(){FILE*f=fopen(\"/job/job.json\",\"w\");if(!f)return 1;puts(\"w\");}\n"),
     "Test Case 를 바꿔 오답을 정답으로 만들 수 있다"),
    ("CPP", "root 아님", _CPP_HEAD + "int main(){if(geteuid()!=0)return 1;puts(\"root\");}\n",
     "컨테이너 탈출 시도의 난이도가 크게 낮아진다"),
    ("CPP", "fork bomb 제한", _CPP_HEAD + (
        "int main(){for(int i=0;i<500;i++){pid_t p=fork();if(p<0)return 1;if(p==0)_exit(0);}"
        "puts(\"forked\");}\n"),
     "호스트의 프로세스 테이블을 고갈시킬 수 있다"),
    ("CPP", "tmpfs 실행 차단", _CPP_HEAD + (
        "int main(){FILE*f=fopen(\"/tmp/x.sh\",\"w\");if(!f)return 1;"
        "fputs(\"#!/bin/sh\\necho hi\\n\",f);fclose(f);chmod(\"/tmp/x.sh\",0700);"
        "if(system(\"/tmp/x.sh\")!=0)return 1;puts(\"ran\");}\n"),
     "받아온 바이너리를 실행할 발판이 된다"),
    ("JAVA", "네트워크 차단", (
        "import java.net.*;\npublic class Main{public static void main(String[] a)throws Exception{"
        "Socket s=new Socket();s.connect(new InetSocketAddress(\"1.1.1.1\",53),3000);"
        "System.out.println(\"connected\");}}\n"),
     "외부로 데이터를 보내거나 도구를 받아올 수 있다"),
    ("JAVA", "DNS 조회 차단", (
        "import java.net.*;\npublic class Main{public static void main(String[] a)throws Exception{"
        "System.out.println(InetAddress.getByName(\"example.com\"));}}\n"),
     "이름 해석만으로도 데이터를 밖으로 실어 보낼 수 있다"),
    ("JAVA", "루트 파일시스템 쓰기 차단", (
        "import java.io.*;\npublic class Main{public static void main(String[] a)throws Exception{"
        "new FileWriter(\"/evil\").close();System.out.println(\"w\");}}\n"),
     "이미지를 변조해 다음 제출의 채점에 영향을 줄 수 있다"),
    ("JAVA", "채점 하네스 변조 차단", (
        "import java.io.*;\npublic class Main{public static void main(String[] a)throws Exception{"
        "new FileWriter(\"/opt/judge/harness.py\",true).close();System.out.println(\"w\");}}\n"),
     "채점 로직 자체를 바꿔 판정을 조작할 수 있다"),
    ("JAVA", "마운트 읽기 전용", (
        "import java.io.*;\npublic class Main{public static void main(String[] a)throws Exception{"
        "new FileWriter(\"/job/job.json\").close();System.out.println(\"w\");}}\n"),
     "Test Case 를 바꿔 오답을 정답으로 만들 수 있다"),
    ("JAVA", "root 아님", (
        "public class Main{public static void main(String[] a){"
        "if(new com.sun.security.auth.module.UnixSystem().getUid()!=0)System.exit(1);"
        "System.out.println(\"root\");}}\n"),
     "컨테이너 탈출 시도의 난이도가 크게 낮아진다"),
    ("JAVA", "프로세스 폭주 제한", (
        "public class Main{public static void main(String[] a)throws Exception{"
        "for(int i=0;i<200;i++)new ProcessBuilder(\"/bin/sleep\",\"1\").start();"
        "System.out.println(\"spawned\");}}\n"),
     "호스트의 프로세스 테이블을 고갈시킬 수 있다"),
    ("JAVA", "tmpfs 실행 차단", (
        "import java.io.*;\npublic class Main{public static void main(String[] a)throws Exception{"
        "File f=new File(\"/tmp/x.sh\");try(FileWriter w=new FileWriter(f)){w.write(\"#!/bin/sh\\necho hi\\n\");}"
        "f.setExecutable(true);if(new ProcessBuilder(\"/tmp/x.sh\").start().waitFor()!=0)System.exit(1);"
        "System.out.println(\"ran\");}}\n"),
     "받아온 바이너리를 실행할 발판이 된다"),
]

# (언어, 이름, 프로브 코드, 기대 출력). 파이썬 프로브와 같은 것을 본다 - 마운트에는 제출 코드만 있고,
# 환경 변수에 정답표의 흔적이 없다.
LANG_CONFIDENTIALITY = [
    ("CPP", "마운트에는 제출 코드만 있다", _CPP_HEAD + (
        "#include <dirent.h>\nint main(){vector<string> n;DIR*d=opendir(\"/job\");"
        "while(auto e=readdir(d)){string s=e->d_name;if(s!=\".\"&&s!=\"..\")n.push_back(s);}"
        "sort(n.begin(),n.end());string o;for(auto&x:n)o+=(o.empty()?\"\":\",\")+x;puts(o.c_str());}\n"),
     "solution.cpp"),
    ("CPP", "환경 변수에 정답표가 없다", _CPP_HEAD + (
        "extern char**environ;int main(){for(char**e=environ;*e;e++){string s=*e;"
        "if(s.find(\"expectedOutput\")!=string::npos||s.find(\"\\\"cases\\\"\")!=string::npos)"
        "{puts(\"LEAK\");return 0;}}puts(\"CLEAN\");}\n"),
     "CLEAN"),
    ("JAVA", "마운트에는 제출 코드만 있다", (
        "import java.io.*;import java.util.*;\npublic class Main{public static void main(String[] a){"
        "String[] n=new File(\"/job\").list();Arrays.sort(n);System.out.println(String.join(\",\",n));}}\n"),
     "Main.java"),
    ("JAVA", "환경 변수에 정답표가 없다", (
        "public class Main{public static void main(String[] a){String e=System.getenv().toString();"
        "System.out.println(e.contains(\"expectedOutput\")||e.contains(\"\\\"cases\\\"\")?\"LEAK\":\"CLEAN\");}}\n"),
     "CLEAN"),
]


# -- 동시 채점 (ADR-0053) ---------------------------------------------------
# 격리는 사용자 코드를 가두는 것만이 아니다. **한 컨테이너 안의 일이 옆 컨테이너의 판정을 바꾸면
# 안 된다.** Judge Worker 가 둘이거나 테스트가 겹치면 채점은 동시에 돈다.
#
# 하네스가 자식에게 RLIMIT_NPROC(64)을 걸던 때, 스레드를 많이 쥔 제출 하나가 도는 동안 옆 컨테이너의
# 정상 Java 제출이 JVM 을 띄우지 못해 COMPILE_ERROR 가 났다(4/4). RLIMIT_NPROC 은 컨테이너가 아니라
# 커널 전체에서 **uid 별로** 세고, 채점 컨테이너는 전부 uid 10001 이다. 제출 하나씩 보는 검사로는
# 보이지 않는다 - 혼자 돌리면 늘 ACCEPTED 였다.
#
# 쥐는 쪽: 컨테이너의 --pids-limit 까지 프로세스를 채우고 붙잡는다. 폭주를 막는 상한이 사라져도
# 호스트를 채우지 않도록 fork 횟수는 200 에서 멈춘다.
CONCURRENT_HOG = (
    "import os, time\n"
    "held = 0\n"
    "for _ in range(200):\n"
    "    try:\n"
    "        pid = os.fork()\n"
    "    except OSError:\n"
    "        break\n"
    "    if pid == 0:\n"
    "        time.sleep(%d)\n"
    "        os._exit(0)\n"
    "    held += 1\n"
    "time.sleep(%d)\n"
    "print(held)\n"
)
# 쥐는 시간의 상한. 옆 채점이 끝나면 컨테이너를 지워 바로 놓게 한다 - 고정 시간만 쥐게 두면 기계가
# 바쁜 날 옆 채점보다 먼저 놓는다. 20 초 · 30 초로 두었을 때 실제로 C++ 채점 중에 놓았다.
CONCURRENT_HOLD_S = 90
# 쥐는 쪽 컨테이너의 프로세스 수가 여기 닿아야 "채웠다" 로 본다. --pids-limit 64 에서 하네스와
# 사용자 프로세스를 합쳐 64 까지 찬다.
CONCURRENT_FULL_PIDS = 60
# 옆에서 채점할 정상 제출. 둘 다 컴파일러와 런타임이 스레드 · 프로세스를 띄운다 - 파이썬의
# sol-accepted.py 는 fork 하지 않아 uid 상한이 있어도 통과하므로 여기 넣으면 아무것도 보지 않는다.
CONCURRENT_VICTIMS = [("JAVA", "java/Accepted.java"), ("CPP", "cpp/accepted.cpp")]


def _container_pids(names: set[str]) -> dict[str, int]:
    """컨테이너별 프로세스 수(cgroup pids.current). docker stats 가 읽어 준다."""
    if not names:
        return {}
    proc = subprocess.run(
        ["docker", "stats", "--no-stream", "--format", "{{.Name}} {{.PIDs}}", *sorted(names)],
        capture_output=True, text=True, errors="replace",
    )
    counts = {}
    for line in proc.stdout.splitlines():
        parts = line.split()
        if len(parts) == 2 and parts[1].isdigit():
            counts[parts[0]] = int(parts[1])
    return counts


def check_concurrent() -> list[str]:
    """프로세스를 가득 쥔 제출 옆에서 정상 제출이 제 판정을 받는가.

    **지연이 아니라 게이트로 순서를 쥔다.** 쥐는 쪽 컨테이너가 실제로 가득 찬 것을 docker stats 로
    본 뒤에 옆 채점을 시작하고, 끝난 뒤에도 아직 쥐고 있는지 다시 본다. 그러지 않으면 쥐기 전에
    (또는 놓은 뒤에) 채점한 날 이 검사는 조용히 통과한다.

    **대조군이 있다.** 같은 순간에 uid 로 세는 상한(`--ulimit nproc=64`)을 건 컨테이너는 채점에
    실패해야 한다. 성공하면 쥐는 쪽이 uid 10001 에 압력을 주지 못한 것이고, 그때 이 검사는 무엇이
    uid 로 세어지든 통과한다.
    """
    problems: list[str] = []
    hog_job = {"problemId": 0, "timeLimitMs": (CONCURRENT_HOLD_S + 4) * 1000,
               "cases": [{"id": 1, "input": "", "expectedOutput": "held"}]}
    hog_code = CONCURRENT_HOG % (CONCURRENT_HOLD_S + 2, CONCURRENT_HOLD_S)
    # 쥐는 쪽 컨테이너를 라벨로 찾는다. 다른 채점(겹쳐 도는 테스트)의 컨테이너를 잘못 집어 지우면 안 된다.
    # 라벨은 격리에 아무것도 더하거나 빼지 않는다.
    label = f"codesprint-concurrent-hog={uuid.uuid4().hex[:12]}"
    limits = run_submission.DOCKER_LIMITS
    # 쥐는 쪽은 일부러 오래 돈다. 마지막 방어선(SUBMISSION_HARD_TIMEOUT_S)에 먼저 걸려 놓지 않게
    # 이 검사 동안만 늘린다 - 옆 채점도 같은 값을 읽지만, 그쪽에는 상한일 뿐 판정을 바꾸지 않는다.
    original_timeout = run_submission.SUBMISSION_HARD_TIMEOUT_S
    run_submission.SUBMISSION_HARD_TIMEOUT_S = CONCURRENT_HOLD_S + 20
    run_submission.DOCKER_LIMITS = [*limits, "--label", label]
    holder: dict = {}
    hog = threading.Thread(target=lambda: holder.update(result=judge_with_job(hog_code, hog_job)))
    hog.start()
    hog_name = None
    try:
        # 게이트: 쥐는 쪽 컨테이너가 뜨고, 가득 찰 때까지 기다린다.
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline and hog.is_alive():
            if hog_name is None:
                found = subprocess.run(
                    ["docker", "ps", "--filter", f"label={label}", "--format", "{{.Names}}"],
                    capture_output=True, text=True, errors="replace").stdout.split()
                if found:
                    hog_name = found[0]
                    # 컨테이너가 떴으면 명령은 이미 만들어졌다. 옆 채점은 원래 옵션으로 돈다.
                    run_submission.DOCKER_LIMITS = limits
            if (hog_name is not None
                    and _container_pids({hog_name}).get(hog_name, 0) >= CONCURRENT_FULL_PIDS):
                break
            time.sleep(0.3)
        else:
            return [f"[VACUOUS] 쥐는 쪽 컨테이너({hog_name})가 프로세스 {CONCURRENT_FULL_PIDS}개에 닿지 "
                    f"않았다 - 옆 채점에 압력이 없으면 이 검사는 아무것도 보지 않는다"]

        # 대조군: uid 로 세는 상한이면 지금 이 순간 막혀야 한다.
        try:
            run_submission.DOCKER_LIMITS = [*limits, "--ulimit", "nproc=64:64"]
            control = run_submission.run(FIXTURES / "sol-accepted.py", JOB)
        finally:
            run_submission.DOCKER_LIMITS = limits
        if control["status"] == "ACCEPTED":
            problems.append("[VACUOUS] uid 로 세는 상한(--ulimit nproc=64)을 건 채점도 ACCEPTED 다 - "
                            "쥐는 쪽이 uid 10001 에 압력을 주지 못했다")
        else:
            print(f"[O] 대조: 같은 순간 uid 로 세는 상한(nproc=64)을 건 채점 -> {control['status']}")

        for language, fixture in CONCURRENT_VICTIMS:
            result = run_submission.run(FIXTURES / fixture, JOB, language=language)
            still = _container_pids({hog_name}).get(hog_name, 0)
            if still < CONCURRENT_FULL_PIDS:
                problems.append(f"[VACUOUS] {fixture} 를 채점하는 사이 쥐는 쪽이 놓았다 "
                                f"(프로세스 {still}개) - 동시에 돌았다고 말할 수 없다")
            elif result["status"] != "ACCEPTED":
                problems.append(f"{fixture}: 옆 컨테이너가 프로세스를 쥐고 있는 동안 {result['status']} "
                                f"- 혼자면 ACCEPTED 다. 다른 제출이 판정을 바꿨다 "
                                f"({(result.get('stderr') or '').strip()[:120]})")
            else:
                print(f"[O] {fixture} -> 옆 컨테이너 프로세스 {still}개인 동안 ACCEPTED")
    finally:
        run_submission.DOCKER_LIMITS = limits
        if hog_name is not None:
            # 옆 채점이 끝났으니 놓게 한다. 쥐는 쪽의 판정은 보지 않는다.
            run_submission._force_remove(hog_name)
        hog.join()
        run_submission.SUBMISSION_HARD_TIMEOUT_S = original_timeout
    return problems


# -- case 사이에 남는 프로세스 ---------------------------------------------------
# case 는 서로 독립이어야 한다. 앞 case 가 남긴 것이 뒤 case 의 판정을 바꾸면 **실패가 엉뚱한 case 에
# 붙는다** - failedCaseId 와 실패의 모양(ADR-0015)이 원인이 아닌 case 를 가리킨다.
#
# 하네스는 컨테이너의 PID 1 인데 제 자식만 기다렸다. fork bomb case 가 끝나면 그 자손 61 개가 좀비로
# --pids-limit 64 를 차지한 채 남아, 다음 case 는 스레드 4 개도 못 만들어 RUNTIME_ERROR 가 났다(혼자
# 돌리면 ACCEPTED). 살아 남은 자손도 다음 case 로 그대로 넘어갔다.
#
# 제출 하나가 입력에 따라 둘로 갈린다. "leave" 는 오래 사는 자손과 곧 끝나는 자손으로 자리를 채우고
# 끝나며, 끝나기 직전 자기 말고 남아 있는 프로세스 수를 적는다. 그 밖의 입력은 스레드 8 개로 풀고,
# 자기와 PID 1 말고 남아 있는 것이 있는지 적는다.
LEFTOVER_CODE = """\
import os, sys, threading, time
def others():
    zombie = alive = 0
    for d in os.listdir('/proc'):
        if not d.isdigit() or int(d) in (1, os.getpid()):
            continue
        try:
            stat = open(f'/proc/{d}/stat').read()
        except OSError:
            continue
        if stat[stat.rindex(')') + 2] == 'Z':
            zombie += 1
        else:
            alive += 1
    return zombie, alive
if sys.stdin.read().strip() == 'leave':
    for i in range(200):
        try:
            pid = os.fork()
        except OSError:
            break
        if pid == 0:
            if i < %d:
                time.sleep(60)
            os._exit(0)
    print('left', sum(others()))
else:
    done = []
    workers = [threading.Thread(target=done.append, args=(1,)) for _ in range(8)]
    for w in workers:
        w.start()
    for w in workers:
        w.join()
    zombie, alive = others()
    print(f'ok {len(done)}' if (zombie, alive) == (0, 0) else f'left zombie={zombie} alive={alive}')
"""
# 오래 사는(60 초) 자손 수. 나머지는 곧바로 끝나 좀비가 된다 - 두 종류를 다 남긴다.
LEFTOVER_SURVIVORS = 10
# "leave" 가 끝나기 직전 남아 있어야 하는 프로세스 수. --pids-limit 64 에서 하네스와 자기를 빼면 62 까지
# 찬다(ADR-0056 전에는 타이머 스레드 자리도 빠져 61). 여기 못 미치면 뒤 case 에 줄 압력이 없다.
LEFTOVER_MIN = 55


def check_leftovers() -> list[str]:
    """앞 case 가 남긴 프로세스가 뒤 case 의 판정을 바꾸지 않는가.

    **상태로 게이트한다.** 첫 case 가 실제로 자리를 채웠는지는 그 case 가 적은 수로 확인한다 - 못
    채웠으면 뒤 case 가 통과해도 아무것도 본 것이 아니므로 [VACUOUS] 로 실패한다. 오래 사는 자손은
    60 초를 자므로 뒤 case 가 도는 동안 스스로 사라지지 않는다. 시간을 맞춰 기다리는 곳이 없다.

    출력을 봐야 하므로 공개 case 로 두고 제출 전 실행(samples_only)으로 돌린다 - 그 경로도 같은
    하네스의 run_case 를 탄다.
    """
    job = {"problemId": 0, "timeLimitMs": 5000, "cases": [
        {"id": 1, "input": "leave", "expectedOutput": "left"},
        *[{"id": i, "input": "check", "expectedOutput": "ok 8"} for i in (2, 3, 4)],
    ]}
    result = judge_samples_only(LEFTOVER_CODE % LEFTOVER_SURVIVORS, job)
    cases = {c["id"]: c for c in result["cases"]}
    if 1 not in cases:
        # 하네스가 도중에 죽으면 호스트는 case 결과를 버리고 SYSTEM_ERROR 하나만 돌려준다.
        return [f"채점이 무너졌다 - {result['status']} {(result.get('stderr') or '')[:160]}"]
    first = (cases[1].get("stdout") or "").split()
    if len(first) != 2 or first[0] != "left" or not first[1].isdigit() or int(first[1]) < LEFTOVER_MIN:
        return [f"[VACUOUS] 첫 case 가 프로세스를 {LEFTOVER_MIN}개 이상 남기지 못했다 ({first}) - "
                f"뒤 case 에 줄 압력이 없으면 이 검사는 아무것도 보지 않는다"]
    problems = []
    for case_id in (2, 3, 4):
        case = cases.get(case_id)
        if case is None:
            problems.append(f"case {case_id} 가 돌지 않았다 - {result['status']} "
                            f"{(result.get('stderr') or '')[:160]}")
        elif case["status"] != "ACCEPTED":
            detail = ((case.get("stderr") or "").strip().splitlines() or [""])[-1]
            problems.append(f"case {case_id}: 앞 case 가 프로세스 {first[1]}개를 남긴 뒤 {case['status']} "
                            f"- 혼자면 ACCEPTED 다 ({(case.get('stdout') or '').strip()} {detail[:120]})")
    if not problems:
        print(f"[O] 앞 case 가 프로세스 {first[1]}개를 남겨도 뒤 case 3개가 스레드 8개로 ACCEPTED, "
              f"남은 프로세스 0")
    return problems


# -- 시간 제한은 자리를 쓰지 않는다 · 자식을 거두는 곳은 하나다 (ADR-0056) -------------------------
# 하네스는 자식을 띄운 직후 타이머 스레드를 만들었다. 자식이 그보다 먼저 --pids-limit 64 를 다 채우면
# 하네스가 `can't start new thread` 로 죽고, 사용자 코드가 SYSTEM_ERROR(우리 잘못)가 된다. 그리고 그 타이머가
# 부른 proc.kill() 은 poll() 로 자식을 먼저 거둘 수 있어, 자식이 hard limit 과 같은 순간에 끝나면 메인 스레드의
# os.wait4 가 ChildProcessError 로 하네스를 죽였다.
#
# 둘 다 경주라 그대로는 재현되지 않는다 - 곧바로 자리를 채우는 C++ 제출 300 case 에서 0 번, hard limit 근처에서
# 끝나는 제출 480 case 에서 0 번이었다. 그래서 **하네스를 그 순서로 세워 두는 게이트**를 채점 이미지 위에 따로
# 굽는다. 게이트는 시간이 아니라 상태로 연다.
#
#   pids  자식을 띄운 직후 컨테이너의 태스크 수가 pids.max 에 닿을 때까지 하네스를 세운다. 닿으면 /tmp/gate-open
#         을 만들고 놓는다. 그 뒤 하네스가 태스크를 하나라도 새로 만들면 죽는다.
#         실행기로 띄우는 하네스(ADR-0059)에서는 **실행기를 거둔 뒤에** 세운다. Popen 직후에 세우면 아직 거두지
#         않은 실행기가 한 자리를 쥐고 있다가, 게이트가 열린 뒤 _spawn 이 거두면서 자리가 하나 빈다 - 그 자리로
#         하네스가 태스크를 만들어도 죽지 않아 이 검사가 회귀를 놓친다(PR #74 검토에서 변이로 확인).
#   reap  자식을 거두기 직전, 자식이 끝나 좀비가 된 뒤에도 거두지 않고 hard limit 을 넘겨 둔다. 다른 누가 먼저
#         거두면(옛 하네스의 타이머가 부른 poll) 거기서 놓는다.
#
# 게이트는 채점 이미지에 들어가지 않는다 - 이 검사가 채점 이미지를 FROM 으로 따로 굽고 끝나면 지운다.
GATE_SOURCE = '''\
import importlib.util, os, subprocess, sys, time

spec = importlib.util.spec_from_file_location("harness", "/opt/judge/harness.py")
harness = importlib.util.module_from_spec(spec)
spec.loader.exec_module(harness)

MODE = os.environ["JUDGE_TEST_GATE"]
RUN = harness.LANGUAGES[harness.LANGUAGE]["run"]
OPEN = "/tmp/gate-open"
run_pids = set()
launcher_pids = set()


def cgroup(name):
    with open("/sys/fs/cgroup/" + name) as f:
        return f.read().strip()


class GatedPopen(subprocess.Popen):
    def __init__(self, args, *rest, **kw):
        # 사용자 프로그램은 실행기를 거쳐 뜬다([LAUNCHER, fd, *RUN], ADR-0059). 그 전 하네스는 RUN 을 직접 띄웠다.
        run = list(args)[-len(RUN):] == RUN
        if run and os.path.exists(OPEN):
            os.unlink(OPEN)
        super().__init__(args, *rest, **kw)
        if not run:
            return
        if list(args) != RUN:
            # 실행기다. 게이트는 그것을 거둔 뒤(gated_wait4)에 세운다.
            launcher_pids.add(self.pid)
            return
        run_pids.add(self.pid)
        if MODE == "pids":
            hold_until_full()


def hold_until_full():
    """태스크 수가 pids.max 에 닿을 때까지 하네스를 세우고, 닿으면 게이트를 연다."""
    limit = cgroup("pids.max")
    deadline = time.monotonic() + 15
    while limit.isdigit() and time.monotonic() < deadline:
        if int(cgroup("pids.current")) >= int(limit):
            open(OPEN, "w").close()
            break
        time.sleep(0.01)


real_wait4 = os.wait4
real_pidfd_open = os.pidfd_open


def recording_pidfd_open(pid, *rest):
    # 시간 제한을 거는 대상이 사용자 프로그램이다. 실행기를 거치면 Popen 의 pid 는 실행기의 것이라 여기서 안다.
    run_pids.add(pid)
    return real_pidfd_open(pid, *rest)


def gated_wait4(pid, options):
    if MODE == "pids" and pid in launcher_pids:
        result = real_wait4(pid, options)
        hold_until_full()
        return result
    if MODE == "reap" and pid in run_pids:
        deadline = time.monotonic() + %(hold)d
        while time.monotonic() < deadline and os.path.exists("/proc/%%d" %% pid):
            time.sleep(0.01)
    return real_wait4(pid, options)


subprocess.Popen = GatedPopen
os.wait4 = gated_wait4
os.pidfd_open = recording_pidfd_open
sys.exit(harness.main())
'''
# reap 게이트가 자식을 거두지 않고 두는 시간. 아래 job 의 hard limit(0.2 초 + 0.5 초)보다 넉넉히 길다. 옛 하네스
# 에서는 타이머가 먼저 거둬 가므로 여기까지 기다리지 않는다.
GATE_REAP_HOLD_S = 4

# 자식을 띄우자마자 --pids-limit 을 다 채우고 쥔다. 게이트가 열린 것을 본 뒤에야 입력을 읽는다 - 게이트가 열렸다는
# 것은 태스크 수가 pids.max 에 닿은 채로 하네스가 다음 줄로 넘어갔다는 뜻이다.
#
# **게이트가 열릴 때까지 fork 를 되풀이한다.** 처음 실패한 곳에서 멈추면, 그 뒤 비는 자리(하네스가 실행기를 거두는
# 것 등)를 다시 채우지 않아 하네스가 그 자리로 태스크를 만들어도 죽지 않는다.
GATE_FILL_CODE = """\
import os, sys, time
held = 0
deadline = time.monotonic() + 20
while not os.path.exists('/tmp/gate-open') and time.monotonic() < deadline:
    try:
        pid = os.fork()
    except OSError:
        time.sleep(0.002)
        continue
    if pid == 0:
        time.sleep(60)
        os._exit(0)
    held += 1
if not os.path.exists('/tmp/gate-open'):
    print('nogate', held)
    raise SystemExit(0)
if sys.stdin.read().strip() == 'spin':
    while True:
        pass
print('gated')
"""


def _gated_image(mode: str) -> str:
    """지금 쓰는 파이썬 채점 이미지 위에 게이트를 얹은 시험용 이미지를 굽는다."""
    base = run_submission.LANGUAGES["PYTHON"][0]
    repo, tag = base.rsplit(":", 1)
    image = f"{repo}-gate:{tag}-{mode}"
    with tempfile.TemporaryDirectory() as d:
        ctx = pathlib.Path(d)
        (ctx / "gate.py").write_text(GATE_SOURCE % {"hold": GATE_REAP_HOLD_S},
                                     encoding="utf-8", newline="\n")
        (ctx / "Dockerfile").write_text(
            f"FROM {base}\n"
            "COPY gate.py /opt/judge/gate.py\n"
            f"ENV JUDGE_TEST_GATE={mode}\n"
            'ENTRYPOINT ["python3", "/opt/judge/gate.py"]\n', encoding="utf-8", newline="\n")
        build = subprocess.run(["docker", "build", "-q", "-t", image, str(ctx)],
                               capture_output=True, text=True, errors="replace")
    if build.returncode != 0:
        raise RuntimeError(f"게이트 이미지를 굽지 못했다 ({image}): {build.stderr[-300:]}")
    return image


def _with_image(image: str, run):
    """파이썬 제출을 잠시 다른 이미지로 채점한다."""
    original = run_submission.LANGUAGES["PYTHON"]
    run_submission.LANGUAGES["PYTHON"] = (image, *original[1:])
    try:
        return run()
    finally:
        run_submission.LANGUAGES["PYTHON"] = original


def check_timer_needs_no_task() -> list[str]:
    """자식이 곧바로 --pids-limit 을 다 채워도 그 제출이 제 판정을 받는가.

    게이트가 태스크 수가 pids.max 에 닿은 것을 본 뒤에야 하네스를 놓는다. 제출은 게이트가 열린 것을 확인하고
    'gated' 를 쓴다 - 게이트가 열리지 않았으면 'nogate' 를 쓰고, 그때 이 검사는 [VACUOUS] 로 실패한다.
    """
    image = _gated_image("pids")
    problems = []
    try:
        for mode, expected in (("exit", "ACCEPTED"), ("spin", "TIME_LIMIT")):
            job = {"problemId": 0, "timeLimitMs": 2000,
                   "cases": [{"id": 1, "input": mode, "expectedOutput": "gated"}]}
            result = _with_image(image, lambda: judge_samples_only(GATE_FILL_CODE, job))
            case = (result.get("cases") or [{}])[0]
            stdout = (case.get("stdout") or "").strip()
            if result["status"] == "SYSTEM_ERROR":
                problems.append(f"{mode}: 자식이 --pids-limit 을 다 채운 뒤 SYSTEM_ERROR - 시간 제한이 새 태스크를 "
                                f"요구한다 ({(result.get('stderr') or '')[:120]})")
            elif stdout.startswith("nogate"):
                problems.append(f"[VACUOUS] {mode}: 게이트가 열리지 않았다({stdout}) - 태스크 수가 pids.max 에 "
                                f"닿지 않았으면 이 검사는 아무것도 보지 않는다")
            elif result["status"] != expected:
                problems.append(f"{mode}: {expected} 를 기대했는데 {result['status']} ({stdout[:60]})")
            else:
                print(f"[O] 자식이 --pids-limit 을 다 채운 채 {mode} -> {result['status']}")
    finally:
        subprocess.run(["docker", "rmi", "-f", image], capture_output=True)
    return problems


def check_single_reaper() -> list[str]:
    """자식이 hard limit 과 같은 순간에 끝나도 하네스가 죽지 않는가.

    게이트가 끝난 자식을 거두지 않고 hard limit 을 넘겨 둔다. 그 사이 다른 누가 거두면 하네스의 os.wait4 가
    ChildProcessError 로 죽어 SYSTEM_ERROR 가 된다. 시간 제한이 그 사이 울렸으면 TIME_LIMIT 이어야 한다 -
    ACCEPTED 면 게이트가 붙잡은 동안 시간 제한이 울리지 않은 것이라 [VACUOUS] 다.
    """
    image = _gated_image("reap")
    try:
        job = {"problemId": 0, "timeLimitMs": 200,
               "cases": [{"id": 1, "input": "", "expectedOutput": "x"}]}
        result = _with_image(image, lambda: judge_with_job("print('x')\n", job))
    finally:
        subprocess.run(["docker", "rmi", "-f", image], capture_output=True)
    if result["status"] == "SYSTEM_ERROR":
        return [f"끝난 자식을 hard limit 까지 거두지 않고 두면 SYSTEM_ERROR - os.wait4 가 아닌 곳이 먼저 거뒀다 "
                f"({(result.get('stderr') or '')[:120]})"]
    if result["status"] == "ACCEPTED":
        return ["[VACUOUS] 게이트가 자식을 붙잡은 동안 시간 제한이 울리지 않았다 - 경주를 만들지 못했다"]
    if result["status"] != "TIME_LIMIT":
        return [f"TIME_LIMIT 을 기대했는데 {result['status']}"]
    print("[O] 끝난 자식을 hard limit 넘어까지 두어도 하네스가 거둔다 -> TIME_LIMIT")
    return []


# -- memoryKb 는 사용자 프로그램 자신의 것이다 (ADR-0059) ----------------------------------------------
# 하네스가 사용자 프로그램을 직접 fork 하던 때 memoryKb(wait4 의 ru_maxrss)는 max(하네스의 사본, 프로그램) 이었다 -
# 커널이 exec 때 사본의 최고 RSS 를 maxrss 에 남긴다. 자기 최고 RSS 가 약 3.1MB 인 C++ 풀이가 9.4MB 로, 4.5MB 입력을
# 받으면 23MB 로 보였다(ADR-0056 D). 프로그램이 스스로 읽은 VmHWM(exec 뒤의 주소 공간만 센다)과 대 본다.
MEMORY_PROBE_CPP = r"""#include <cstdio>
#include <cstring>
int main() {
    static char buf[1 << 16];
    while (fread(buf, 1, sizeof buf, stdin) > 0) {}
    FILE* f = fopen("/proc/self/status", "r");
    char line[256];
    while (fgets(line, sizeof line, f))
        if (!strncmp(line, "VmHWM:", 6)) { long v; sscanf(line + 6, "%ld", &v); printf("%ld\n", v); }
}
"""
MEMORY_PROBE_PY = """import sys
sys.stdin.read()
for line in open('/proc/self/status'):
    if line.startswith('VmHWM:'):
        print(line.split()[1])
"""
# memoryKb 와 VmHWM 의 차이로 허용하는 폭. 실측 차이는 +170~+400KB 다 - 프로그램이 VmHWM 을 읽은 뒤 출력하고 끝나는
# 동안 쓴 것과 커널 RSS 카운터의 근사. 하네스의 사본은 가장 작은 이미지에서도 6MB 를 넘으므로 섞이면 여기서 걸린다.
# 아래쪽도 본다 - 실행기(약 640KB)나 다른 것을 재고 있으면 VmHWM 보다 한참 작다.
MEMORY_SLACK_KB = 1024
# 하네스는 case 입력을 쥔 채 fork 했다 - 입력이 크면 그 사본까지 memoryKb 가 됐다. 이 프로그램은 조금씩 읽고 버린다.
MEMORY_BIG_INPUT = "".join(f"{i} {i * 7919 % 1000003}\n" for i in range(330_000))


def check_memory_is_the_programs_own() -> list[str]:
    """memoryKb 가 사용자 프로그램 자신의 최고 RSS 인가 - 하네스(와 그가 쥔 입력)가 섞이지 않는가."""
    problems = []
    for label, language, code, case_input in (
            ("C++ 작은 풀이", "CPP", MEMORY_PROBE_CPP, "1 2\n"),
            (f"C++ 큰 입력({len(MEMORY_BIG_INPUT) / 1e6:.1f}MB)", "CPP", MEMORY_PROBE_CPP, MEMORY_BIG_INPUT),
            ("Python 작은 풀이", "PYTHON", MEMORY_PROBE_PY, "1 2\n")):
        with tempfile.TemporaryDirectory() as d:
            base = pathlib.Path(d)
            source = base / _source_name(language)
            source.write_text(code, encoding="utf-8", newline="")
            (base / "job.json").write_text(json.dumps({
                "problemId": 0, "timeLimitMs": 5000,
                "cases": [{"id": 1, "input": case_input, "expectedOutput": "", "hidden": False}],
            }), encoding="utf-8", newline="")
            result = run_submission.run(source, base / "job.json", samples_only=True, language=language)
        stdout = ((result.get("cases") or [{}])[0].get("stdout") or "").strip()
        if not stdout.isdigit():
            problems.append(f"[VACUOUS] {label}: 프로그램이 VmHWM 을 적지 못했다({result['status']}, "
                            f"{stdout[:60]!r}) - 대 볼 값이 없다")
            continue
        hwm, memory = int(stdout), result["memoryKb"]
        if memory is None or not hwm - MEMORY_SLACK_KB // 2 <= memory <= hwm + MEMORY_SLACK_KB:
            problems.append(f"{label}: memoryKb {memory} 가 프로그램 자신의 최고 RSS(VmHWM {hwm})에서 너무 멀다 "
                            f"(허용 -{MEMORY_SLACK_KB // 2}~+{MEMORY_SLACK_KB}KB) - 프로그램 밖의 메모리를 재고 있다")
            continue
        print(f"[O] {label}: memoryKb {memory} / 프로그램의 VmHWM {hwm} ({memory - hwm:+d}KB)")
    return problems


# 컨테이너가 하네스보다 먼저 stderr 에 쓰는 양. 파이프 버퍼(Linux 64KB)와 docker 가 중간에 쥐는 버퍼를 넉넉히 넘긴다 -
# 옛 run_submission 에서 사용자 코드가 /proc/1/fd/2 로 4MB 를 쓰자 채점이 끝나지 않았다(Docker Desktop 실측).
NOISE_BYTES = 16 * 1024 * 1024
NOISE_SOURCE = '''\
import os, runpy
chunk = b"n" * 65536
for _ in range(%(chunks)d):
    os.write(2, chunk)
runpy.run_path("/opt/judge/harness.py", run_name="__main__")
'''
# 옛 코드에서는 감시 타이머의 `docker rm -f` 도 막혀 채점이 스스로 끝나지 않는다. 기다리는 상한은 이 검사가 쥔다.
NOISE_HARD_TIMEOUT_S = 10
NOISE_WAIT_S = NOISE_HARD_TIMEOUT_S + 30


def _noisy_image() -> str:
    """하네스를 띄우기 전에 컨테이너 stderr 로 NOISE_BYTES 를 쓰는 시험용 이미지. 채점 이미지에는 들어가지 않는다."""
    base = run_submission.LANGUAGES["PYTHON"][0]
    repo, tag = base.rsplit(":", 1)
    image = f"{repo}-noisy:{tag}"
    with tempfile.TemporaryDirectory() as d:
        ctx = pathlib.Path(d)
        (ctx / "noisy.py").write_text(NOISE_SOURCE % {"chunks": NOISE_BYTES // 65536},
                                      encoding="utf-8", newline="\n")
        (ctx / "Dockerfile").write_text(
            f"FROM {base}\n"
            "COPY noisy.py /opt/judge/noisy.py\n"
            'ENTRYPOINT ["python3", "/opt/judge/noisy.py"]\n', encoding="utf-8", newline="\n")
        build = subprocess.run(["docker", "build", "-q", "-t", image, str(ctx)],
                               capture_output=True, text=True, errors="replace")
    if build.returncode != 0:
        raise RuntimeError(f"시험용 이미지를 굽지 못했다 ({image}): {build.stderr[-300:]}")
    return image


def check_noisy_stderr() -> list[str]:
    """docker CLI 가 stderr 로 많이 써도 채점이 끝나는가.

    run_submission 은 프로토콜로 stdout 만 읽는다. stderr 를 파이프로 받아 두고 읽지 않으면 CLI 가 버퍼를 채운
    순간 멈추고, 같은 연결로 오는 stdout 도 멈춘다. 대조군: 같은 이미지를 docker 로 직접 띄워 stderr 를 전부 읽으면
    NOISE_BYTES 가 실제로 CLI 의 stderr 에 도착해야 한다 - 아니면 이 검사는 아무것도 보지 않는다([VACUOUS]).
    """
    image = _noisy_image()
    try:
        control = subprocess.run(["docker", "run", "--rm", "-i", "--network", "none", image],
                                 input=b"", capture_output=True, timeout=120)
        if len(control.stderr) < NOISE_BYTES:
            return [f"[VACUOUS] 컨테이너가 쓴 stderr 가 CLI 에 {len(control.stderr)} 바이트만 왔다 "
                    f"({NOISE_BYTES} 를 기대) - 막힐 만큼 쓰지 못했다"]

        box: dict = {}
        job = {"problemId": 0, "timeLimitMs": 2000,
               "cases": [{"id": 1, "input": "", "expectedOutput": "quiet"}]}
        original_timeout = run_submission.SUBMISSION_HARD_TIMEOUT_S

        original_language = run_submission.LANGUAGES["PYTHON"]

        def judge_noisy() -> None:
            box["result"] = judge_with_job("print('quiet')\n", job)

        # 설정은 이 스레드에서 바꾸고 되돌린다. 채점 스레드가 돌아오지 않아도 뒤의 검사는 원래 설정으로 돈다.
        run_submission.SUBMISSION_HARD_TIMEOUT_S = NOISE_HARD_TIMEOUT_S
        run_submission.LANGUAGES["PYTHON"] = (image, *original_language[1:])
        try:
            # 옛 코드는 여기서 끝나지 않는다. 데몬 스레드로 돌려 상한을 넘기면 실패로 적고 넘어간다 - 막힌 CLI 는
            # 이 프로세스가 끝나 파이프가 닫힐 때 풀린다.
            worker = threading.Thread(target=judge_noisy, daemon=True)
            started = time.monotonic()
            worker.start()
            worker.join(NOISE_WAIT_S)
        finally:
            run_submission.SUBMISSION_HARD_TIMEOUT_S = original_timeout
            run_submission.LANGUAGES["PYTHON"] = original_language
        if worker.is_alive():
            return [f"컨테이너가 stderr 로 {NOISE_BYTES // 1024 // 1024}MB 를 쓰자 {NOISE_WAIT_S}초 안에 채점이 "
                    f"끝나지 않았다 - hard timeout({NOISE_HARD_TIMEOUT_S}초)도 풀지 못했다 (stderr 파이프를 읽지 않는다)"]
        result = box.get("result") or {}
        if result.get("status") != "ACCEPTED":
            return [f"컨테이너 stderr 가 많을 때 ACCEPTED 를 기대했는데 {result.get('status')} "
                    f"({(result.get('stderr') or '')[:120]})"]
        print(f"[O] 컨테이너가 stderr 로 {NOISE_BYTES // 1024 // 1024}MB 를 써도 "
              f"{time.monotonic() - started:.1f}초 만에 ACCEPTED (대조군: CLI 가 {len(control.stderr)} 바이트를 냈다)")
        return []
    finally:
        subprocess.run(["docker", "rmi", "-f", image], capture_output=True)


def _source_name(language: str) -> str:
    return run_submission.LANGUAGES[language][1]


def judge(code: str, language: str = "PYTHON") -> dict:
    """임시 제출을 만들어 채점한다."""
    with tempfile.TemporaryDirectory() as d:
        path = pathlib.Path(d) / _source_name(language)
        path.write_text(code, encoding="utf-8", newline="")
        return run_submission.run(path, JOB, language=language)


def judge_with_job(code: str, job: dict) -> dict:
    """job 을 직접 지정해 채점한다."""
    with tempfile.TemporaryDirectory() as d:
        base = pathlib.Path(d)
        (base / "solution.py").write_text(code, encoding="utf-8", newline="")
        (base / "job.json").write_text(
            json.dumps(job, ensure_ascii=False), encoding="utf-8", newline="")
        return run_submission.run(base / "solution.py", base / "job.json")


def judge_samples_only(code: str, job: dict) -> dict:
    """공개 case 만 돌린다 (제출 전 실행, ADR-0020)."""
    with tempfile.TemporaryDirectory() as d:
        base = pathlib.Path(d)
        (base / "solution.py").write_text(code, encoding="utf-8", newline="")
        (base / "job.json").write_text(
            json.dumps(job, ensure_ascii=False), encoding="utf-8", newline="")
        return run_submission.run(base / "solution.py", base / "job.json",
                                  samples_only=True)


def check_samples_only() -> list[str]:
    """제출 전 실행이 숨은 case 를 돌리지 않는가.

    **대조군이 있다.** 같은 job 을 플래그 없이 돌렸을 때 숨은 case 가 실제로
    돌아야 한다 - 그러지 않으면 이 검사는 "원래 하나뿐인 job" 을 보고 통과한다.
    """
    problems = []
    job = {
        "cases": [
            {"id": 1, "input": "1\n", "expectedOutput": "1\n"},
            {"id": 2, "input": "2\n", "expectedOutput": "2\n", "hidden": True},
            {"id": 3, "input": "3\n", "expectedOutput": "3\n", "hidden": True},
        ]
    }
    code = "import sys\nprint(sys.stdin.read().strip())\n"

    run_only = judge_samples_only(code, job)
    if run_only["total"] != 1:
        problems.append(f"공개 case 는 1개인데 {run_only['total']}개를 돌렸다")
    if [c["id"] for c in run_only["cases"]] != [1]:
        problems.append(f"숨은 case 가 돌았다: {[c['id'] for c in run_only['cases']]}")

    # 출력은 공개 case 에서만 돌려준다. 나눠 두면 "숨은 case 의 출력" 조합이 생긴다.
    if "stdout" not in run_only["cases"][0]:
        problems.append("실행인데 출력이 없다 - 그러면 돌려 볼 이유가 없다")

    # 실행 결과는 실행 계약을 지킨다. 여기서 대 보지 않았던 탓에 계약이 이 모양을
    # 설명하지 못한다는 것이 한참 동안 드러나지 않았다.
    for error in run_contract_errors(run_only):
        problems.append(f"실행 결과가 run-judge-result.schema.json 을 어긴다: {error}")

    # 대조군: 플래그가 없으면 전부 돈다.
    full = judge_with_job(code, job)
    if full["total"] != 3:
        problems.append(f"[대조군 실패] 제출 채점이 3개를 돌려야 하는데 {full['total']}개다")
    if any("stdout" in c for c in full["cases"]):
        problems.append("[대조군 실패] 제출 채점 결과에 출력이 실려 있다")
    # 두 계약이 서로를 대신하지 않는가. 출력이 실린 실행 결과가 제출 채점 계약을
    # 통과하면, 제출 결과에 출력이 새어도 계약이 막지 못한다.
    if not contract_errors(run_only):
        problems.append("[대조군 실패] 출력이 실린 실행 결과가 제출 채점 계약을 통과한다")
    for error in contract_errors(full):
        problems.append(f"제출 채점 결과가 judge-result.schema.json 을 어긴다: {error}")
    return problems


def _judge_containers() -> set[str]:
    """지금 살아 있는 채점 컨테이너 이름들."""
    proc = subprocess.run(
        ["docker", "ps", "-a", "--filter", "name=codesprint-judge-", "--format", "{{.Names}}"],
        capture_output=True, text=True, errors="replace",
    )
    return {n for n in proc.stdout.split() if n}


def judge_probe(code: str, expected_stdout: str, language: str = "PYTHON") -> str | None:
    """프로브 코드를 돌리고 **사용자 출력 자체**를 돌려준다.

    판정이 아니라 출력을 봐야 한다. 채점 결과는 ACCEPTED/WRONG_ANSWER 로만 말하므로
    "무엇이 보였는가" 를 알 수 없다. expectedOutput 을 프로브가 낼 값으로 두고
    1-case job 을 만들어, ACCEPTED 면 그 값이 나온 것으로 읽는다.

    이 job 의 expectedOutput 은 프로브의 기대 출력("CLEAN" 등)이라 정답표가 아니다 -
    프로브가 그걸 읽어도 의미가 없다.
    """
    with tempfile.TemporaryDirectory() as d:
        base = pathlib.Path(d)
        source = base / _source_name(language)
        source.write_text(code, encoding="utf-8", newline="")
        job = {
            "problemId": 0,
            "timeLimitMs": 5000,
            "cases": [{"id": 1, "input": "", "expectedOutput": expected_stdout}],
        }
        (base / "job.json").write_text(json.dumps(job, ensure_ascii=False), encoding="utf-8", newline="")
        result = run_submission.run(source, base / "job.json", language=language)

    if result["status"] == "ACCEPTED":
        return expected_stdout
    if result["status"] == "WRONG_ANSWER":
        return "(기대와 다른 출력)"
    return None


def judge_unrestricted(code: str, language: str = "PYTHON") -> dict:
    """격리 옵션을 **걷어내고** 같은 코드를 돌린다. 대조군이다.

    격리 테스트가 통과하는 것만으로는 부족하다. 사용자 코드에 오타가 있어도
    RUNTIME_ERROR 가 나므로 막힌 것처럼 보인다 - 아무것도 검증하지 않는 테스트가
    초록불을 내는 상태다.

    그래서 제한 없이 한 번 더 돌려 **그때는 실행에 성공하는지** 확인한다.
    제한이 있을 때만 실패해야 그 실패가 격리 덕분이라고 말할 수 있다.
    """
    limits, mount = run_submission.DOCKER_LIMITS, run_submission.MOUNT_MODE
    try:
        run_submission.DOCKER_LIMITS = ["--memory", "512m", "--user", "root"]
        # 마운트 읽기 전용은 DOCKER_LIMITS 가 아니라 -v 의 :ro 가 막는다.
        # 이것도 함께 뒤집지 않으면 대조군에서도 막혀 "제한을 걷어내도 실패한다" 로
        # 오판한다. 실제로 그렇게 나왔고, 대조군이 그것을 잡아줬다.
        run_submission.MOUNT_MODE = "rw"
        return judge(code, language)
    finally:
        run_submission.DOCKER_LIMITS, run_submission.MOUNT_MODE = limits, mount


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--build", action="store_true", help="이미지를 다시 빌드한다")
    args = parser.parse_args()

    if args.build:
        print("이미지 빌드 중...")
        for image, dockerfile in [(run_submission.IMAGE, "judge/Dockerfile"),
                                  (run_submission.LANGUAGES["CPP"][0], "judge/Dockerfile.cpp"),
                                  (run_submission.LANGUAGES["JAVA"][0], "judge/Dockerfile.java")]:
            build = subprocess.run(
                ["docker", "build", "-q", "-t", image, "-f", dockerfile, "."],
                cwd=ROOT, capture_output=True, text=True,
            )
            if build.returncode != 0:
                print(f"[X] 이미지 빌드 실패 ({image}):\n" + build.stderr[-800:])
                return 1

    failed = 0

    print("== 제출 전 실행 ==")
    samples = check_samples_only()
    for problem in samples:
        print(f"[X] {problem}")
    failed += len(samples)
    if not samples:
        print("[O] 공개 case 만 돌고, 출력은 그때만 실린다")

    print("== 판정 ==")
    for name, expected, needs_case in VERDICTS:
        result = judge((FIXTURES / name).read_text(encoding="utf-8"))

        violations = contract_errors(result)
        if violations:
            failed += 1
            print(f"[X] {name}: 결과가 judge-result.schema.json 을 어긴다")
            for v in violations[:3]:
                print(f"    {v}")
            continue

        actual = result["status"]
        if actual != expected:
            failed += 1
            print(f"[X] {name}: {expected} 를 기대했는데 {actual}")
            print(f"    {json.dumps(result, ensure_ascii=False)[:200]}")
            continue
        # Reviewer 를 호출하는 판정은 근거가 될 case 를 반드시 특정해야 한다(ADR-0004).
        if needs_case and result["failedCaseId"] is None:
            failed += 1
            print(f"[X] {name}: {actual} 인데 failedCaseId 가 null 이다 "
                  f"(Reviewer 가 failedCaseRefs 를 채울 수 없다)")
            continue
        if not needs_case and result["failedCaseId"] is not None:
            failed += 1
            print(f"[X] {name}: {actual} 인데 failedCaseId 가 있다")
            continue
        print(f"[O] {name} -> {actual}")

    print("\n== 실패의 모양 (ADR-0015) ==")
    total_cases = len(json.loads(JOB.read_text(encoding="utf-8"))["cases"])
    for name, run_all in PROFILE:
        result = judge((FIXTURES / name).read_text(encoding="utf-8"))
        executed = result["cases"]
        if result["status"] == "SYSTEM_ERROR":
            failed += 1
            print(f"[X] {name}: 채점 자체가 실패했다 - {result.get('stderr')}")
            continue

        if run_all and len(executed) != total_cases:
            failed += 1
            print(f"[X] {name}: {len(executed)}/{total_cases} case 에서 멈췄다 "
                  f"- 통과한 case 를 알 수 없으면 독립 근거를 만들 수 없다")
            continue
        if not run_all and len(executed) >= total_cases:
            failed += 1
            print(f"[X] {name}: {result['status']} 인데 끝까지 돌았다 "
                  f"- 무한 루프 하나가 case 수만큼의 timeout 을 먹는다")
            continue

        # 판정과 근거는 **첫** 실패가 정한다. 뒤의 case 가 이 값을 덮으면
        # Reviewer 에게 주는 case 가 제출마다 흔들린다.
        first_failed = next((c for c in executed if c["status"] != "ACCEPTED"), None)
        if first_failed is None:
            failed += 1
            print(f"[X] {name}: 실패한 case 가 하나도 없다")
            continue
        if (result["status"], result["failedCaseId"]) != (
                first_failed["status"], first_failed["id"]):
            failed += 1
            print(f"[X] {name}: 판정이 첫 실패({first_failed['status']} "
                  f"case {first_failed['id']})와 다르다 - "
                  f"{result['status']} case {result['failedCaseId']}")
            continue

        passed_ids = [c["id"] for c in executed if c["status"] == "ACCEPTED"]
        print(f"[O] {name} -> 실행 {len(executed)}/{total_cases}, "
              f"통과 {passed_ids}, 첫 실패 case {result['failedCaseId']}")

    print("\n== 진단 예산 (ADR-0015) ==")
    # 싼 실패라고 case 가 빠른 것은 아니다. 제한 직전까지 돌다 WA 가 나는 case 가
    # 이어지면 최악은 여전히 `case 수 x timeLimit` 이고, 그러면 hard timeout 에 걸려
    # **사용자 코드가 느린 것이 SYSTEM_ERROR(우리 잘못)로 둔갑한다.**
    slow = (FIXTURES / "sol-slow-wrong.py").read_text(encoding="utf-8")
    original_budget = run_submission.DIAGNOSTIC_BUDGET_MS
    try:
        run_submission.DIAGNOSTIC_BUDGET_MS = 500
        limited = judge(slow)
    finally:
        run_submission.DIAGNOSTIC_BUDGET_MS = original_budget
    full = judge(slow)

    if limited["status"] != "WRONG_ANSWER" or full["status"] != "WRONG_ANSWER":
        failed += 1
        print(f"[X] 진단 예산: 판정이 WRONG_ANSWER 가 아니다 "
              f"({limited['status']} / {full['status']})")
    elif len(limited["cases"]) >= len(full["cases"]):
        # 대조군: 예산을 넉넉히 주면 더 돌아야 한다. 양쪽이 같으면 이 테스트는
        # 예산이 아니라 다른 이유로 멈춘 것을 보고 있는 것이다.
        failed += 1
        print(f"[X] 진단 예산: 예산을 줄여도 실행한 case 가 줄지 않았다 "
              f"({len(limited['cases'])} vs {len(full['cases'])}) [VACUOUS]")
    elif limited["failedCaseId"] != full["failedCaseId"]:
        failed += 1
        print(f"[X] 진단 예산: 예산이 판정 근거를 바꿨다 "
              f"(case {limited['failedCaseId']} vs {full['failedCaseId']})")
    else:
        print(f"[O] 진단 예산 -> 예산 500ms 에서 {len(limited['cases'])}개, "
              f"기본 예산에서 {len(full['cases'])}개 실행, 판정은 그대로")

    print("\n== stderr sanitize (Addendum 63) ==")
    # 이 값은 이제 화면까지 간다(PR #21). 컨테이너 안의 절대 경로가 그대로 나가면
    # 마운트 위치와 하네스 구조가 사용자에게 보인다 - 사용자가 고칠 수 있는 정보도
    # 아니고, 우리 쪽 구조를 알려 주는 것뿐이다.
    crash = judge((FIXTURES / "sol-runtime-error.py").read_text(encoding="utf-8"))
    stderr = crash.get("stderr") or ""
    if not stderr.strip():
        failed += 1
        print("[X] stderr: RUNTIME_ERROR 인데 stderr 가 비어 있다 - 원인을 알 수 없다")
    else:
        leaked = [path for path in ("/job/", "/opt/", "/tmp/", "/usr/") if path in stderr]
        if leaked:
            failed += 1
            print(f"[X] stderr: 컨테이너 경로가 그대로 나간다 {leaked}")
            print(f"    {stderr.strip().splitlines()[:3]}")
        elif "solution.py" not in stderr:
            # 경로를 지우다 파일명까지 지우면 어느 줄인지 짚을 수 없다.
            failed += 1
            print(f"[X] stderr: 파일명이 남지 않았다 - {stderr.strip().splitlines()[:2]}")
        else:
            print(f"[O] stderr -> 경로 없이 파일명만 남는다 "
                  f"({stderr.strip().splitlines()[-1][:50]})")

    print("\n== 격리 (Addendum 87) ==")
    for name, code, why in ISOLATION:
        result = judge(code)
        # SYSTEM_ERROR 는 우리 인프라가 고장난 것이지 격리가 뚫린 것이 아니다.
        # 둘을 같은 메시지로 보고하면 원인을 엉뚱한 곳에서 찾게 된다.
        if result["status"] == "SYSTEM_ERROR":
            failed += 1
            print(f"[X] {name}: 채점 자체가 실패했다 (격리와 무관) - {result.get('stderr')}")
            continue
        # 격리가 동작하면 사용자 코드는 실행에 실패한다.
        if result["status"] != "RUNTIME_ERROR":
            failed += 1
            print(f"[X] {name}: 막히지 않았다 ({result['status']}) - {why}")
            continue

        # 대조군: 제한을 걷어내면 같은 코드가 실행에 성공해야 한다.
        # 여기서도 RUNTIME_ERROR 면 위의 실패는 격리 때문이 아니라 코드가 원래
        # 잘못된 것이다 - 아무것도 검증하지 못하는 테스트다.
        control = judge_unrestricted(code)
        if control["status"] == "RUNTIME_ERROR":
            failed += 1
            print(f"[X] {name}: 제한을 걷어내도 실패한다 - 이 테스트는 격리를 "
                  f"검증하지 못한다 [VACUOUS]")
            print(f"    {(control.get('stderr') or '').strip().splitlines()[-1:]}")
            continue
        print(f"[O] {name} -> 제한 있음 RUNTIME_ERROR / 제한 없음 {control['status']}")

    print("\n== 채점 데이터 기밀성 (ADR-0006) ==")
    for name, code, expected_stdout in CONFIDENTIALITY:
        seen = judge_probe(code, expected_stdout)
        if seen is None:
            failed += 1
            print(f"[X] {name}: 프로브가 실행되지 않았다")
            continue
        if seen != expected_stdout:
            failed += 1
            print(f"[X] {name}: '{expected_stdout}' 를 기대했는데 '{seen}' [정답표 유출]")
            continue
        print(f"[O] {name} -> {seen}")

    print("\n== Java · C++ 판정 (ADR-0045) ==")
    for language, fixture, expected, needs_case in LANG_VERDICTS:
        result = run_submission.run(FIXTURES / fixture, JOB, language=language)
        violations = contract_errors(result)
        if violations:
            failed += 1
            print(f"[X] {fixture}: 결과가 judge-result.schema.json 을 어긴다 - {violations[:2]}")
            continue
        if result["status"] != expected:
            failed += 1
            print(f"[X] {fixture}: {expected} 를 기대했는데 {result['status']} "
                  f"- {(result.get('stderr') or '')[:120]}")
            continue
        if needs_case != (result["failedCaseId"] is not None):
            failed += 1
            print(f"[X] {fixture}: {expected} 의 failedCaseId 가 {result['failedCaseId']}")
            continue
        if expected == "ACCEPTED" and (result["memoryKb"] or 0) > 100 * 1024:
            # 메모리는 사용자 코드만 잰다. 컴파일러(g++ 는 약 190MB)가 섞이면 작은 풀이가 거대해 보인다.
            failed += 1
            print(f"[X] {fixture}: memoryKb {result['memoryKb']} - 컴파일러의 메모리가 섞였다")
            continue
        print(f"[O] {fixture} -> {result['status']} (memoryKb {result['memoryKb']})")

    for language, fixture in [("CPP", "cpp/compile_error.cpp"), ("JAVA", "java/CompileError.java")]:
        stderr = run_submission.run(FIXTURES / fixture, JOB, language=language).get("stderr") or ""
        if "/job/" in stderr or "/build/" in stderr or "/opt/" in stderr:
            failed += 1
            print(f"[X] {fixture}: 컴파일 오류에 컨테이너 경로가 그대로 나간다")
        elif _source_name(language) not in stderr:
            failed += 1
            print(f"[X] {fixture}: 컴파일 오류에 파일 이름이 없다 - 어디를 고칠지 알 수 없다")
        else:
            print(f"[O] {fixture} -> 경로 없이 {_source_name(language)} 만 남는다")

    print("\n== Java · C++ 격리 (ADR-0045) ==")
    for language, name, code, why in LANG_ISOLATION:
        result = judge(code, language)
        if result["status"] == "SYSTEM_ERROR":
            failed += 1
            print(f"[X] {language} {name}: 채점 자체가 실패했다 - {result.get('stderr')}")
            continue
        if result["status"] != "RUNTIME_ERROR":
            failed += 1
            print(f"[X] {language} {name}: 막히지 않았다 ({result['status']}) - {why}")
            continue
        control = judge_unrestricted(code, language)
        if control["status"] in ("RUNTIME_ERROR", "COMPILE_ERROR", "SYSTEM_ERROR"):
            failed += 1
            print(f"[X] {language} {name}: 제한을 걷어내도 {control['status']} - 이 테스트는 격리를 "
                  f"검증하지 못한다 [VACUOUS] {(control.get('stderr') or '')[:120]}")
            continue
        print(f"[O] {language} {name} -> 제한 있음 RUNTIME_ERROR / 제한 없음 {control['status']}")

    print("\n== Java · C++ 채점 데이터 기밀성 (ADR-0006) ==")
    for language, name, code, expected_stdout in LANG_CONFIDENTIALITY:
        seen = judge_probe(code, expected_stdout, language)
        if seen != expected_stdout:
            failed += 1
            print(f"[X] {language} {name}: '{expected_stdout}' 를 기대했는데 '{seen}'")
            continue
        print(f"[O] {language} {name} -> {seen}")

    print("\n== 동시 채점 - 옆 컨테이너가 판정을 바꾸지 않는다 (ADR-0053) ==")
    concurrent = check_concurrent()
    for problem in concurrent:
        print(f"[X] {problem}")
    failed += len(concurrent)

    print("\n== case 사이에 남는 프로세스 ==")
    leftovers = check_leftovers()
    for problem in leftovers:
        print(f"[X] {problem}")
    failed += len(leftovers)

    print("\n== 시간 제한은 자리를 쓰지 않는다 (ADR-0056) ==")
    no_task = check_timer_needs_no_task()
    for problem in no_task:
        print(f"[X] {problem}")
    failed += len(no_task)

    print("\n== 자식을 거두는 곳은 하나다 (ADR-0056) ==")
    reaper = check_single_reaper()
    for problem in reaper:
        print(f"[X] {problem}")
    failed += len(reaper)

    print("\n== memoryKb 는 사용자 프로그램 자신의 것이다 (ADR-0059) ==")
    own_memory = check_memory_is_the_programs_own()
    for problem in own_memory:
        print(f"[X] {problem}")
    failed += len(own_memory)

    print("\n== docker CLI 의 stderr 가 채점을 막지 않는다 ==")
    noisy = check_noisy_stderr()
    for problem in noisy:
        print(f"[X] {problem}")
    failed += len(noisy)

    print("\n== SYSTEM_ERROR 경로 ==")
    # 사용자 코드로는 재현할 수 없다. 우리 인프라가 고장난 상황을 직접 만든다.
    broken = run_submission.run(FIXTURES / "sol-accepted.py", FIXTURES / "does-not-exist.json")
    if broken["status"] != "SYSTEM_ERROR":
        failed += 1
        print(f"[X] job 이 없으면 SYSTEM_ERROR 여야 하는데 {broken['status']}")
    elif contract_errors(broken):
        failed += 1
        print("[X] SYSTEM_ERROR 결과가 계약을 어긴다")
    else:
        print("[O] job 을 읽지 못하면 -> SYSTEM_ERROR")

    print("\n== hard timeout 후 컨테이너 회수 ==")
    # --rm 은 컨테이너가 스스로 종료했을 때만 지워준다. hard timeout 으로 docker CLI 를
    # 끊으면 컨테이너는 계속 돌 수 있고, 그러면 CPU/메모리를 계속 먹는다.
    # 짧은 hard timeout 을 걸고 무한 루프를 돌려 잔존 컨테이너가 없는지 확인한다.
    before = _judge_containers()
    original_timeout = run_submission.SUBMISSION_HARD_TIMEOUT_S
    try:
        run_submission.SUBMISSION_HARD_TIMEOUT_S = 3
        # case timeout(60s)이 hard timeout(3s)보다 훨씬 길어야 하네스가 붙잡혀 있다.
        stuck = judge_with_job(
            "while True:\n    pass\n",
            {"problemId": 0, "timeLimitMs": 60000,
             "cases": [{"id": 1, "input": "", "expectedOutput": "x"}]},
        )
    finally:
        run_submission.SUBMISSION_HARD_TIMEOUT_S = original_timeout

    if stuck["status"] != "SYSTEM_ERROR":
        failed += 1
        print(f"[X] hard timeout 인데 {stuck['status']} 가 나왔다")
    else:
        leftover = _judge_containers() - before
        if leftover:
            failed += 1
            print(f"[X] 컨테이너가 남았다: {sorted(leftover)}")
            for name in leftover:
                subprocess.run(["docker", "rm", "-f", name], capture_output=True)
        else:
            print("[O] hard timeout -> SYSTEM_ERROR, 컨테이너 잔존 없음")

    print("\n== status 커버리지 ==")
    # 판정 fixture 개수와 status 종류 수는 다르다(WRONG_ANSWER 가 여러 번 나온다).
    # "8종 전부 검증한다" 고 말하려면 근거가 있어야 한다.
    all_status = set(
        json.loads((ROOT / "contracts" / "judge-result.schema.json").read_text(encoding="utf-8"))
        ["properties"]["status"]["enum"]
    )
    tested = ({expected for _, expected, _ in VERDICTS}
              | {expected for _, _, expected, _ in LANG_VERDICTS} | STATUS_COVERED_ELSEWHERE)
    missing = sorted(all_status - tested)
    if missing:
        failed += 1
        print(f"[X] 한 번도 검사하지 않는 status: {missing}")
    else:
        print(f"[O] judge-result.schema.json 의 status {len(all_status)}종 전부 검사한다")

    if failed:
        print(f"\n[FAIL] {failed}건 실패")
        return 1
    print(f"\n[OK] 판정 {len(VERDICTS)}건 · 실패의 모양 {len(PROFILE)}건 · "
          f"격리 {len(ISOLATION)}건 · 기밀성 {len(CONFIDENTIALITY)}건 · "
          f"Java · C++ 판정 {len(LANG_VERDICTS)}건 · 격리 {len(LANG_ISOLATION)}건 · "
          f"기밀성 {len(LANG_CONFIDENTIALITY)}건 · "
          f"동시 채점 {len(CONCURRENT_VICTIMS)}건 · case 사이 잔존 프로세스 · 시간 제한 게이트 2건 · memoryKb 3건 · stderr 폭주 · 컨테이너 회수 · stderr sanitize · status 8종 커버 — 모두 통과")
    return 0


if __name__ == "__main__":
    sys.exit(main())
