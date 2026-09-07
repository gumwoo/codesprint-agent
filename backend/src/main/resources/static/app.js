// CodeSprint Agent 슬라이스 1 화면. 정본: docs/adr/0017-the-web-client-has-no-build-step.md
//
// **이 파일은 판단하지 않는다.** 판정 · mastery · 다음 행동은 전부 서버가 정해서
// 내려준다(ADR-0001, ADR-0002). 여기서 status 를 보고 문구를 고르는 것까지가 전부이며,
// 점수를 계산하거나 무엇을 할지 정하는 코드가 여기 생기면 경계가 무너진다.
//
// 채점은 요청 안에서 끝나지 않으므로(ADR-0013) 제출 뒤에는 폴링한다.

"use strict";

const POLL_INTERVAL_MS = 1000;

// 오래 걸린다고 **관찰을 포기하지 않는다.**
//
// 처음에는 2분 한도를 두고 넘으면 멈췄는데, 서버는 그보다 훨씬 오래 걸릴 수 있다 -
// 재시도까지 세면 최악이 14분쯤 된다(Worker 의 timeout · backoff · MAX_ATTEMPTS 와
// Reviewer timeout 을 합쳐서). 그 숫자를 화면에 옮겨 적으면 UI 가 Worker 내부값에
// 묶이고, 그 값이 바뀔 때마다 또 갈린다.
//
// job 은 큐에 살아 있는데 화면이 임의의 시간 때문에 포기할 이유가 없다. 대신
// **오래 걸린다고 말해 주고** 폴링 간격을 늘린다 - 기다리는 것과 방치하는 것은 다르다.
const POLL_SLOW_AFTER_MS = 120000;
const POLL_SLOW_INTERVAL_MS = 5000;

const $ = (id) => document.getElementById(id);
const text = (value) => (value === null || value === undefined ? "-" : String(value));

let currentProblem = null;
// 풀이 시간의 기준. 문제를 연 순간부터 잰다 - 서버는 알 방법이 없다.
let openedAt = Date.now();

async function getJson(url) {
  const response = await fetch(url);
  if (!response.ok) {
    throw new Error(`${url} -> ${response.status}`);
  }
  return response.json();
}

async function loadProblems() {
  const data = await getJson("/api/problems");
  const list = $("problemList");
  list.replaceChildren();
  for (const problem of data.problems) {
    const item = document.createElement("li");
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = `${problem.code} · ${problem.title}`;
    button.addEventListener("click", () => openProblem(problem.code));
    const tag = document.createElement("span");
    tag.className = "tag";
    tag.textContent = `${problem.kind} · ${problem.primarySkill}`;
    item.append(button, tag);
    list.append(item);
  }
}

async function openProblem(code) {
  currentProblem = await getJson(`/api/problems/${code}`);
  $("problemTitle").textContent = `${currentProblem.code} · ${currentProblem.title}`;
  $("problemMeta").textContent =
      `${currentProblem.kind} · 시간 ${currentProblem.timeLimitMs}ms · `
      + `메모리 ${currentProblem.memoryLimitMb}MB · `
      + `기대 풀이 ${text(currentProblem.expectedSolveSeconds)}초`;
  $("statement").textContent = currentProblem.statement;

  const samples = $("samples");
  samples.replaceChildren();
  // 서버가 hidden case 를 내려주지 않는다. 여기서 거르지 않는 이유는, 거를 것이
  // 있다고 믿는 순간 유출 경로가 화면 쪽으로 옮겨오기 때문이다.
  currentProblem.samples.forEach((sample, index) => {
    const block = document.createElement("div");
    block.className = "sample";
    block.innerHTML = `<h4>예시 ${index + 1}</h4>`;
    const input = document.createElement("pre");
    input.textContent = sample.input;
    const output = document.createElement("pre");
    output.textContent = sample.expectedOutput;
    block.append(input, output);
    samples.append(block);
  });

  openedAt = Date.now();
  $("workspace").hidden = false;
  $("result").hidden = true;
  $("submitNote").textContent = "";
  window.scrollTo({ top: 0, behavior: "smooth" });
}

async function submit() {
  if (!currentProblem) {
    return;
  }
  const button = $("submitButton");
  button.disabled = true;
  $("submitNote").textContent = "제출하는 중…";

  const startedAt = Date.now();
  try {
    const response = await fetch(`/api/problems/${currentProblem.code}/submit`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        userId: Number($("userId").value),
        language: "PYTHON",
        sourceCode: $("sourceCode").value,
        hintLevel: Number($("hintLevel").value),
        solutionViewed: $("solutionViewed").checked,
        // 풀이 시간은 화면이 잰다. 서버가 알 방법이 없다.
        solveSeconds: Math.max(1, Math.round((Date.now() - openedAt) / 1000)),
      }),
    });
    if (!response.ok) {
      // 서버가 거절한 이유를 그대로 보여준다. "제출 실패" 로 덮으면 무엇이
      // 잘못됐는지 알 수 없다.
      $("submitNote").textContent = `제출이 거절됐다 (${response.status}): `
          + (await response.text());
      return;
    }
    const accepted = await response.json();
    $("submitNote").textContent = "";
    await waitForResult(accepted.submissionId, startedAt);
  } catch (error) {
    $("submitNote").textContent = `제출하지 못했다: ${error.message}`;
  } finally {
    button.disabled = false;
  }
}

async function waitForResult(submissionId, startedAt) {
  $("result").hidden = false;
  $("goNext").hidden = true;
  $("state").textContent = "채점 중…";
  $("judge").replaceChildren();
  $("review").replaceChildren();
  $("nextAction").replaceChildren();

  let warned = false;
  for (;;) {
    const view = await getJson(`/api/submissions/${submissionId}`);
    if (view.state !== "PENDING") {
      render(submissionId, view);
      return;
    }

    const waited = Date.now() - startedAt;
    if (!warned && waited >= POLL_SLOW_AFTER_MS) {
      warned = true;
      // Worker 가 떠 있지 않으면 여기 온다. 그것은 사용자 잘못이 아니므로
      // 무엇을 확인해야 하는지 알려준다. 기다리는 것 자체는 계속한다.
      $("state").textContent =
          "평소보다 오래 걸리고 있다. Judge Worker 가 떠 있는지 확인한다 - "
          + "제출은 큐에 남아 있고, 끝나면 여기에 나타난다.";
    }
    await new Promise((resolve) => setTimeout(
        resolve, warned ? POLL_SLOW_INTERVAL_MS : POLL_INTERVAL_MS));
  }
}

function render(submissionId, view) {
  const result = view.result;
  $("state").textContent = `판정 ${result.judge.status}`;

  const judge = $("judge");
  judge.replaceChildren();
  const rows = [
    ["통과", `${result.judge.passed} / ${result.judge.total}`],
    ["실행 시간", `${text(result.judge.executionMs)} ms`],
    ["메모리", `${text(result.judge.memoryKb)} KB`],
    ["첫 실패 case", text(result.judge.failedCaseId)],
  ];
  for (const [label, value] of rows) {
    const dt = document.createElement("dt");
    dt.textContent = label;
    const dd = document.createElement("dd");
    dd.textContent = value;
    judge.append(dt, dd);
  }
  if (result.judge.stderr) {
    const pre = document.createElement("pre");
    pre.className = "stderr";
    pre.textContent = result.judge.stderr;
    judge.append(pre);
  }

  // 분석은 없을 수 있다. Reviewer 를 부르지 않았거나, 불렀는데 못 쓰는 답이
  // 왔거나(ADR-0014). 사용자가 할 수 있는 일이 같으므로 구분해 보여주지 않는다.
  const review = $("review");
  if (result.review) {
    review.innerHTML = "<h3>오답 원인 분석</h3>";
    const line = document.createElement("p");
    line.textContent = `${result.review.primaryMistake} · ${result.review.status}`
        + ` (confidence ${result.review.confidence})`;
    const why = document.createElement("p");
    why.className = "note";
    why.textContent = result.review.explanation;
    review.append(line, why);
  }

  const action = $("nextAction");
  action.innerHTML = "<h3>다음</h3>";
  const what = document.createElement("p");
  what.textContent = result.nextAction.targetSkill
      ? `${result.nextAction.type} · ${result.nextAction.targetSkill}`
      : result.nextAction.type;
  const why = document.createElement("p");
  why.className = "note";
  why.textContent = result.nextAction.reason;
  action.append(what, why);

  const goNext = $("goNext");
  goNext.hidden = false;
  goNext.onclick = () => goToNextProblem(submissionId);
}

async function goToNextProblem(submissionId) {
  const response = await fetch(`/api/submissions/${submissionId}/next-problem`);
  if (!response.ok) {
    $("nextAction").append(note("줄 문제를 아직 고르지 못했다."));
    return;
  }
  const next = await response.json();
  if (!next.problem) {
    // 문제가 없는 것과 아직 정해지지 않은 것은 다르다. 서버가 이유를 준다.
    $("nextAction").append(note(next.reason));
    return;
  }
  await openProblem(next.problem.code);
}

function note(message) {
  const p = document.createElement("p");
  p.className = "note";
  p.textContent = message;
  return p;
}

// 사용자를 만들 수 있어야 한다. 인증이 없어서 화면이 id 를 직접 보내는데(ADR-0017),
// 새 DB 에는 그 id 가 하나도 없어 아무것도 시작할 수 없었다.
async function createUser() {
  const response = await fetch("/api/users", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ nickname: "로컬 사용자" }),
  });
  if (!response.ok) {
    $("submitNote").textContent = `사용자를 만들지 못했다 (${response.status})`;
    return;
  }
  const created = await response.json();
  $("userId").value = created.userId;
  remember(created.userId);
}

// 브라우저에만 기억한다. 서버에는 세션이 없다 - 있는 척하면 인증이 붙었을 때
// 무엇이 진짜 로그인인지 알 수 없게 된다.
function remember(userId) {
  try {
    localStorage.setItem("codesprint.userId", String(userId));
  } catch (error) {
    // 저장을 막아 둔 브라우저도 있다. 그때는 이번 세션에만 유지된다.
  }
}

function restore() {
  try {
    const saved = localStorage.getItem("codesprint.userId");
    if (saved) {
      $("userId").value = saved;
    }
  } catch (error) {
    // 위와 같다.
  }
}

$("createUser").addEventListener("click", createUser);
$("userId").addEventListener("change", () => remember($("userId").value));
restore();
$("submitButton").addEventListener("click", submit);
loadProblems().catch((error) => {
  $("problemList").textContent = `문제 목록을 불러오지 못했다: ${error.message}`;
});
