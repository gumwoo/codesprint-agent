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

// 조회가 몇 번 연속으로 실패해야 사용자에게 알리는가.
//
// 한 번 실패했다고 관찰을 그만두지 않는다. 서버를 재시작하는 동안에도 job 은 큐에
// 남아 있고, 돌아오면 결과가 온다 - 여기서 포기하면 접수된 제출을 화면이 놓친다.
const UNREACHABLE_AFTER = 3;

const $ = (id) => document.getElementById(id);
const text = (value) => (value === null || value === undefined ? "-" : String(value));

let currentProblem = null;
// 풀이 시간의 기준. 문제를 연 순간부터 잰다 - 서버는 알 방법이 없다.
let openedAt = Date.now();

// **지금 화면이 보고 있는 제출.** 이것이 없으면 두 폴링이 같은 자리에 쓴다 -
// A 를 내고 곧바로 B 를 내면, 늦게 도착한 A 가 B 의 결과를 덮어 사용자는 B 를
// 냈는데 A 의 판정을 본다. 반대로 폴링 중에 다른 문제를 열면 결과가 갈 곳을 잃는다.
let activeSubmissionId = null;

let editor = null;

function sourceCode() {
  return editor ? editor.getValue() : $("sourceCode").value;
}

function setSourceCode(value) {
  if (editor) {
    editor.setValue(value);
  } else {
    $("sourceCode").value = value;
  }
}

/**
 * CodeMirror 가 왔으면 붙이고, 안 왔으면 textarea 로 남는다.
 *
 * CDN 이 막혔거나 오프라인이면 스크립트가 오지 않는다. 그때 화면이 안 뜨는 것은
 * 받아들일 수 없다 - 에디터는 편의지 이 화면의 목적이 아니다.
 */
function attachEditor() {
  if (typeof window.CodeMirror !== "function") {
    $("footNote").textContent =
        "코드 편집기를 불러오지 못했다. 평범한 입력창으로 계속 쓸 수 있다.";
    return;
  }
  editor = window.CodeMirror.fromTextArea($("sourceCode"), {
    mode: "python",
    theme: "material-darker",
    lineNumbers: true,
    indentUnit: 4,
    tabSize: 4,
    // Tab 으로 들여쓰고 싶지 포커스를 옮기고 싶지 않다.
    extraKeys: { Tab: (cm) => cm.execCommand("indentMore") },
  });
}

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

function showPicker() {
  showLeft("picker");
  refreshDiagnostic();
  $("crumbProblem").textContent = "고르는 중";
  $("problemMeta").textContent = "";
  $("submitButton").disabled = true;
  $("runButton").disabled = true;
}

/** 왼쪽 패널에서 하나만 보인다. 탭 표시도 같이 옮긴다. */
function showLeft(bodyId) {
  for (const id of ["picker", "statementBody", "skillsBody"]) {
    $(id).hidden = id !== bodyId;
  }
  $("tabSkills").classList.toggle("active", bodyId === "skillsBody");
  $("tabProblem").classList.toggle("active", bodyId !== "skillsBody");
}

/**
 * 내 Skill 상태.
 *
 * **여기서 아무것도 계산하지 않는다.** mastery 도 status 도 서버가 정한 것을 옮긴다
 * (ADR-0001). 화면이 다시 조합하기 시작하면 서버가 정한 것과 갈리고, 사용자가 보는
 * 쪽이 이긴다.
 */
async function showSkills() {
  showLeft("skillsBody");
  const userId = Number($("userId").value);
  const rows = $("skillRows");
  if (!userId) {
    $("skillsNote").textContent = "사용자를 먼저 만든다.";
    rows.replaceChildren();
    return;
  }

  try {
    const [catalog, map] = await Promise.all([
      getJson("/api/skills"),
      getJson(`/api/users/${userId}/skills`),
    ]);
    // 이름과 선수 관계는 커리큘럼에서, 점수는 상태에서 온다. 둘을 code 로 잇는다.
    const defined = new Map(catalog.skills.map((skill) => [skill.code, skill]));

    // 진단과 같은 이유로, 쓰기 전에 확인한다. 사용자가 바뀌면 이 답은 남의 것이다.
    if (!stillCurrent(userId)) {
      return;
    }

    rows.replaceChildren();
    for (const state of map.skills) {
      const skill = defined.get(state.skillCode) || {};
      const tr = document.createElement("tr");

      const name = document.createElement("td");
      const title = document.createElement("div");
      title.textContent = skill.name || state.skillCode;
      const code = document.createElement("div");
      code.className = "code what";
      code.textContent = state.skillCode;
      name.append(title, code);

      const status = document.createElement("td");
      status.textContent = state.status;
      status.className = `st-${state.status}`;
      if (state.status === "LOCKED" && (skill.requires || []).length) {
        const why = document.createElement("div");
        why.className = "what";
        // 왜 잠겼는지 말해 준다. 잠긴 것만 보여주면 사용자가 할 수 있는 일이 없다.
        why.textContent = "먼저: " + skill.requires.map((r) => r.skillCode).join(", ");
        status.append(why);
      }

      const mastery = document.createElement("td");
      mastery.className = "num";
      // null 은 "아직 안 봤다" 다. 0 으로 적으면 "보았고 못한다" 가 된다.
      mastery.textContent = state.mastery === null ? "–" : state.mastery.toFixed(2);

      const evidence = document.createElement("td");
      evidence.className = "num";
      evidence.textContent = state.evidenceCount;

      tr.append(name, status, mastery, evidence);
      rows.append(tr);
    }
    $("skillsNote").textContent =
        "제출할 때마다 다시 계산된다. – 는 아직 근거가 없다는 뜻이고 0 과 다르다.";
  } catch (error) {
    if (!stillCurrent(userId)) {
      return;
    }
    $("skillsNote").textContent = `상태를 불러오지 못했다: ${error.message}`;
    rows.replaceChildren();
  }
}

/**
 * 초기 진단.
 *
 * **여기서 무엇을 물을지 고르지 않는다.** 서버가 정한 Skill 과 문제를 그대로
 * 보여준다(ADR-0018). 화면이 선수 그래프를 다시 걸으면 서버가 정한 순서와 갈리고,
 * 사용자가 보는 쪽이 이긴다.
 */
/**
 * 사용자가 바뀌었다. <b>보고 있는 것 전부</b>를 그 사람 것으로 다시 읽는다.
 *
 * <p>한 곳에 모아 둔 이유가 있다. 처음에는 사용자를 만드는 쪽과 id 를 직접 고치는
 * 쪽이 따로 처리했는데, 만드는 쪽에만 Skill 표 갱신을 넣어 두어서 <b>id 를 직접
 * 고치면 진단은 새 사용자 것이고 Skill 표는 이전 사용자 것</b>으로 남았다.
 * 늦게 온 응답을 막는 것(stillCurrent)과는 다른 문제다 - 그쪽은 덮어쓰기를 막고,
 * 이쪽은 아예 다시 읽지 않는 것이다.
 */
function switchedUser() {
  // **보고 있는 것 전부**에는 채점 결과도 들어간다. 판정 · 분석 · 다음 행동은 전부
  // 그 사용자에 대한 것이라, 남겨 두면 새 사용자의 화면에 남의 결과가 붙어 있다.
  // 폴링도 끊는다 - 살려 두면 이전 사용자의 결과가 **나중에 도착해서** 그려진다.
  cancelActivePolling();
  resetResultUi("제출하면 여기에 판정과 다음 행동이 나온다.");
  // "제출하는 중…" 같은 진행 문구도 이전 사용자의 것이다.
  $("footNote").textContent = "";

  remember($("userId").value);
  refreshDiagnostic();
  if (!$("skillsBody").hidden) {
    showSkills();
  }
}

/**
 * 이 응답이 아직 화면에 쓸 것인가. 그 사이 사용자가 바뀌었으면 버린다.
 *
 * <p><b>규칙: 지금 화면의 사용자와 관계없는 비동기 결과는 화면에 아무것도 쓰지
 * 않는다.</b> 아무것도 다. 남의 것이라는 안내조차 쓰지 않는다 - 그러려면 다른
 * 사용자의 id 를 화면에 적어야 하고, 인증이 붙으면 그건 남의 정보다.
 */
function stillCurrent(userId) {
  return Number($("userId").value) === userId;
}

/** 이 사용자의 진행 상황을 버튼 옆에 적는다. 화면이 넘어갔으면 적지 않는다. */
function reportTo(userId, message) {
  if (stillCurrent(userId)) {
    $("footNote").textContent = message;
  }
}

async function refreshDiagnostic() {
  const box = $("diagnostic");
  const userId = Number($("userId").value);
  if (!userId) {
    box.hidden = true;
    return;
  }

  let step;
  try {
    step = await getJson(`/api/users/${userId}/diagnostic`);
  } catch (error) {
    if (!stillCurrent(userId)) {
      return;
    }
    // 진단을 못 읽어도 문제 목록은 그대로 쓸 수 있다. 조용히 감추지 않고 말해 준다.
    box.hidden = false;
    box.classList.add("done");
    $("diagProgress").textContent = "";
    $("diagReason").textContent = `진단을 불러오지 못했다: ${error.message}`;
    return;
  }

  // **응답을 받은 뒤, 화면에 쓰기 전에** 확인한다. 사용자를 새로 만들면 두 요청이
  // 겹치고, 늦게 온 옛 사용자의 답이 새 사용자의 화면을 덮어쓴다 - 실제로 그렇게
  // 아무것도 안 한 사용자에게 "1 / 8 확인됨" 이 떴다.
  if (!stillCurrent(userId)) {
    return;
  }

  box.hidden = false;
  box.classList.toggle("done", step.done || !step.problem);
  $("diagProgress").textContent = `${step.assessed} / ${step.total}`;
  // 끝났을 때도 **서버가 준 이유**를 쓴다. 화면이 "끝났다" 만 말하면, Skill 지도에
  // mastery 없이 남아 있는 Skill 이 모순처럼 보인다 - 재 본 것과 묻지 않기로 한 것을
  // 구분해 주는 문장이 서버에서 온다.
  $("diagReason").textContent = step.done
      ? `진단이 끝났다 — ${step.reason}. 여기서부터는 제출할 때마다 다음 할 일을 정해서 준다.`
      : step.reason;

  if (step.done || !step.problem) {
    return;
  }
  const button = $("diagStart");
  button.textContent = `${step.targetSkill} 확인하기 — ${step.problem.code}`;
  button.onclick = () => openProblem(step.problem.code);
}

async function openProblem(code) {
  currentProblem = await getJson(`/api/problems/${code}`);
  $("problemTitle").textContent = currentProblem.title;
  $("crumbProblem").textContent = currentProblem.code;
  $("problemMeta").textContent =
      `${currentProblem.kind} · ${currentProblem.timeLimitMs}ms · `
      + `${currentProblem.memoryLimitMb}MB · 기대 `
      + `${text(currentProblem.expectedSolveSeconds)}초`;
  $("statement").textContent = currentProblem.statement;

  const samples = $("samples");
  samples.replaceChildren();
  // 서버가 hidden case 를 내려주지 않는다. 여기서 거르지 않는 이유는, 거를 것이
  // 있다고 믿는 순간 유출 경로가 화면 쪽으로 옮겨오기 때문이다.
  currentProblem.samples.forEach((sample, index) => {
    const block = document.createElement("div");
    block.className = "sample";
    const title = document.createElement("h4");
    title.textContent = `예시 ${index + 1}`;
    const input = document.createElement("pre");
    input.textContent = sample.input;
    const output = document.createElement("pre");
    output.textContent = sample.expectedOutput;
    block.append(title, input, output);
    samples.append(block);
  });

  showLeft("statementBody");
  $("submitButton").disabled = false;
  $("runButton").disabled = false;
  setSourceCode("");
  // 문제를 옮기는 것은 진짜로 그만 보는 것이다. 여기서는 놓는다.
  cancelActivePolling();
  resetResultUi("제출하면 여기에 판정과 다음 행동이 나온다.");
  $("footNote").textContent = "";
  openedAt = Date.now();
}

/**
 * 보고 있던 제출을 놓는다. 그 폴링은 다음 응답에서 스스로 멈춘다.
 *
 * <b>결과 영역을 지우는 것과 따로 둔다.</b> 하나로 묶었더니 새 제출이 거절됐을 때도
 * 이미 관찰을 그만둔 뒤였다 - 앞 제출은 실제로 채점되고 있는데 그것을 기다리는
 * 폴러가 사라졌고, 제출 이력 화면이 없어 다시 볼 방법도 없었다.
 */
function cancelActivePolling() {
  activeSubmissionId = null;
}

function resetResultUi(note) {
  const state = $("state");
  state.textContent = "";
  state.className = "meta";
  $("submitNote").textContent = note;
  $("submitNote").hidden = false;
  $("judge").replaceChildren();
  $("review").replaceChildren();
  $("nextAction").replaceChildren();
  $("goNext").hidden = true;
}

async function submit() {
  if (!currentProblem) {
    return;
  }
  const button = $("submitButton");
  button.disabled = true;
  // **접수되기 전에는 화면을 건드리지 않는다.** 거절될 수 있고, 그때 앞 제출은
  // 그대로 채점되고 있다. 진행 상황은 버튼 옆에 적는다.
  $("footNote").textContent = "제출하는 중…";

  const startedAt = Date.now();
  // 누구의 제출인지 여기서 고정한다. 응답을 기다리는 동안 사용자가 바뀔 수 있고,
  // 그때 body 와 화면이 다른 사람을 가리키면 안 된다.
  const submittingUserId = Number($("userId").value);
  // **접수와 관찰을 나눠서 다룬다.** 한 try 로 묶으면 폴링이 한 번 실패했을 때도
  // "제출하지 못했다" 가 뜬다 - 제출은 됐는데 문구가 틀리고, 더 나쁘게는 그 자리에서
  // 루프가 끝나 접수된 제출을 화면이 놓친다.
  let accepted;
  try {
    const response = await fetch(`/api/problems/${currentProblem.code}/submit`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        userId: submittingUserId,
        language: "PYTHON",
        sourceCode: sourceCode(),
        // 화면은 힌트 사용량을 신고하지 않는다. 서버도 0 / false 만 받는다 -
        // 힌트가 생기면 서버가 내주면서 기록하고, 제출은 그 기록을 쓴다.
        hintLevel: 0,
        solutionViewed: false,
        // 풀이 시간은 화면이 잰다. 서버가 알 방법이 없다.
        solveSeconds: Math.max(1, Math.round((Date.now() - openedAt) / 1000)),
      }),
    });
    if (!response.ok) {
      // 서버가 거절한 이유를 그대로 보여준다. "제출 실패" 로 덮으면 무엇이
      // 잘못됐는지 알 수 없다. 앞 제출의 폴링은 건드리지 않는다.
      reportTo(submittingUserId, `제출이 거절됐다 (${response.status}): `
          + (await response.text()));
      return;
    }
    accepted = await response.json();
  } catch (error) {
    // 여기까지 못 왔으면 접수되지 않은 것이다. 앞 제출은 그대로 둔다.
    reportTo(submittingUserId, `제출하지 못했다: ${error.message}`);
    return;
  } finally {
    // **접수 시도가 끝나면 버튼을 푼다.** 폴링은 관찰일 뿐이고 한도 없이 이어지므로
    // (ADR-0017), 그 뒤에 풀면 Worker 가 죽어 있을 때 버튼이 영영 잠긴다.
    button.disabled = false;
  }

  // 접수된 뒤에 사용자가 바뀌었으면 이 제출은 이 화면 것이 아니다. 서버에서는
  // 그대로 채점되고, 그 사용자로 돌아오면 Skill 상태에 반영돼 있다.
  if (!stillCurrent(submittingUserId)) {
    return;
  }

  // 여기서부터가 화면이 보는 제출이다. 앞의 것은 이제 놓는다.
  cancelActivePolling();
  resetResultUi("채점 중…");
  $("footNote").textContent = "";
  await waitForResult(accepted.submissionId, startedAt);
}

/**
 * 제출 전 실행. **제출이 아니다**(ADR-0020).
 *
 * 공개 예제만 돌고 Evidence 도 mastery 도 다음 행동도 만들지 않는다. 그래서 결과를
 * 그 자리에 보여주고 버린다 - 판정 패널의 상태(state)를 건드리지 않는다.
 */
async function runSamples() {
  if (!currentProblem) {
    return;
  }
  const button = $("runButton");
  const runningUserId = Number($("userId").value);
  button.disabled = true;
  reportTo(runningUserId, "실행하는 중…");

  let accepted;
  try {
    const response = await fetch(`/api/problems/${currentProblem.code}/run`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        userId: runningUserId,
        language: "PYTHON",
        sourceCode: sourceCode(),
      }),
    });
    if (!response.ok) {
      reportTo(runningUserId, `실행이 거절됐다 (${response.status}): `
          + (await response.text()));
      return;
    }
    accepted = await response.json();
  } catch (error) {
    reportTo(runningUserId, `실행하지 못했다: ${error.message}`);
    return;
  } finally {
    button.disabled = false;
  }

  if (!stillCurrent(runningUserId)) {
    return;
  }
  await waitForRun(accepted.runId, runningUserId);
}

/** 실행 결과를 기다린다. 제출 폴링과 섞이지 않게 따로 둔다. */
async function waitForRun(runId, runningUserId) {
  const box = $("review");
  for (let tries = 0; tries < 300; tries += 1) {
    let view = null;
    try {
      view = await getJson(`/api/runs/${runId}?userId=${runningUserId}`);
    } catch (error) {
      if (!stillCurrent(runningUserId)) {
        return;
      }
      reportTo(runningUserId, `실행 결과를 가져오지 못했다: ${error.message}`);
      return;
    }
    if (!stillCurrent(runningUserId)) {
      return;
    }

    if (view.status === "DONE" || view.status === "FAILED") {
      reportTo(runningUserId, "");
      renderRun(view, box);
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  reportTo(runningUserId, "실행이 오래 걸리고 있다.");
}

function renderRun(view, box) {
  box.replaceChildren();
  if (!view.judged) {
    box.append(heading("실행"), note(view.failureReason || "실행하지 못했다."));
    return;
  }
  const judged = view.judged;
  box.append(heading("실행 — 공개 예제"));
  box.append(note(`${judged.passed} / ${judged.total} 통과 · 점수에 반영되지 않는다`));

  const table = document.createElement("table");
  table.className = "runcases";
  const head = document.createElement("tr");
  for (const label of ["입력", "기대", "실제", "결과"]) {
    const th = document.createElement("th");
    th.textContent = label;
    head.append(th);
  }
  table.append(head);

  for (const item of judged.cases) {
    const tr = document.createElement("tr");
    for (const value of [item.input, item.expectedOutput, item.stdout]) {
      const td = document.createElement("td");
      const pre = document.createElement("pre");
      pre.textContent = value;
      td.append(pre);
      tr.append(td);
    }
    const status = document.createElement("td");
    status.textContent = item.status;
    tr.append(status);
    table.append(tr);
  }
  box.append(table);

  if (judged.stderr) {
    box.append(heading("stderr"));
    const pre = document.createElement("pre");
    pre.className = "statement";
    pre.textContent = judged.stderr;
    box.append(pre);
  }
}

async function waitForResult(submissionId, startedAt) {
  activeSubmissionId = submissionId;
  $("submitNote").textContent = "채점 중…";
  $("state").textContent = "채점 중";

  let warned = false;
  let failures = 0;
  for (;;) {
    let view = null;
    let failure = null;
    try {
      view = await getJson(`/api/submissions/${submissionId}`);
    } catch (error) {
      failure = error;
    }

    // **화면을 만지기 전에 확인한다.** 기다리는 동안 다른 제출이 접수됐을 수 있고,
    // 그러면 이 폴러는 남의 화면에 쓰는 것이 된다 - 실제로 그랬다. 버려진 폴러가
    // "결과를 가져오지 못하고 있다" 를 새 제출의 화면에 남겼다.
    //
    // 루프 맨 앞에서만 보면 부족하다. await 는 여기서 일어난다.
    if (activeSubmissionId !== submissionId) {
      return;
    }

    if (failure) {
      // **한 번 실패했다고 포기하지 않는다.** 서버를 재시작하는 동안에도 job 은
      // 큐에 남아 있고, 돌아오면 결과가 온다.
      failures += 1;
      if (failures >= UNREACHABLE_AFTER) {
        $("submitNote").textContent =
            `결과를 가져오지 못하고 있다 (${failure.message}). 제출은 접수됐으므로 `
            + "서버가 돌아오면 여기에 나타난다.";
      }
    } else if (failures) {
      // 돌아왔다. 알리던 문구를 원래대로 되돌린다.
      failures = 0;
      $("submitNote").textContent = warned ? slowNote() : "채점 중…";
    }

    if (view && view.state !== "PENDING") {
      render(submissionId, view);
      return;
    }

    const waited = Date.now() - startedAt;
    if (!warned && waited >= POLL_SLOW_AFTER_MS) {
      warned = true;
      // Worker 가 떠 있지 않으면 여기 온다. 그것은 사용자 잘못이 아니므로
      // 무엇을 확인해야 하는지 알려준다. 기다리는 것 자체는 계속한다.
      if (!failures) {
        $("submitNote").textContent = slowNote();
      }
    }
    // 서버가 답하지 않는 동안에는 천천히 두드린다.
    const slowly = warned || failures >= UNREACHABLE_AFTER;
    await new Promise((resolve) => setTimeout(
        resolve, slowly ? POLL_SLOW_INTERVAL_MS : POLL_INTERVAL_MS));
  }
}

function slowNote() {
  return "평소보다 오래 걸리고 있다. Judge Worker 가 떠 있는지 확인한다 - "
      + "제출은 큐에 남아 있고, 끝나면 여기에 나타난다.";
}

function render(submissionId, view) {
  const result = view.result;
  const passed = result.judge.status === "ACCEPTED";

  $("submitNote").hidden = true;
  const state = $("state");
  state.textContent = result.judge.status;
  state.className = passed ? "meta verdict-ok" : "meta verdict-bad";

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
  review.replaceChildren();
  if (result.review) {
    review.append(heading("오답 원인"));
    const line = document.createElement("p");
    line.className = "what";
    line.textContent = `${result.review.primaryMistake} · ${result.review.status}`
        + ` (confidence ${result.review.confidence})`;
    review.append(line, note(result.review.explanation));
  }

  const action = $("nextAction");
  action.replaceChildren();
  action.append(heading("다음"));
  const what = document.createElement("p");
  what.className = "what";
  what.textContent = result.nextAction.targetSkill
      ? `${result.nextAction.type} · ${result.nextAction.targetSkill}`
      : result.nextAction.type;
  action.append(what, note(result.nextAction.reason));

  const goNext = $("goNext");
  goNext.hidden = false;
  goNext.onclick = () => goToNextProblem(submissionId);

  // 채점이 반영되면 Skill 상태가 달라진다. 그 화면을 보고 있었다면 다시 읽는다 -
  // 옛 값을 그대로 두면 방금 푼 것이 반영되지 않은 것처럼 보인다.
  if (!$("skillsBody").hidden) {
    showSkills();
  }
  // 진단도 같이 움직인다 - 방금 낸 것이 다음 질문을 바꾼다.
  refreshDiagnostic();
}

function heading(label) {
  const h = document.createElement("h3");
  h.textContent = label;
  return h;
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
    $("footNote").textContent = `사용자를 만들지 못했다 (${response.status})`;
    return;
  }
  const created = await response.json();
  $("userId").value = created.userId;

  // 값을 코드로 바꾸면 change 가 뜨지 않는다. 직접 부른다.
  switchedUser();
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

/** 가운데 핸들로 좌우 너비를 조절한다. */
function attachGutter() {
  const split = document.querySelector(".split");
  const gutter = $("gutter");
  let dragging = false;

  gutter.addEventListener("pointerdown", (event) => {
    dragging = true;
    gutter.setPointerCapture(event.pointerId);
  });
  gutter.addEventListener("pointerup", () => {
    dragging = false;
  });
  gutter.addEventListener("pointermove", (event) => {
    if (!dragging) {
      return;
    }
    // 양쪽 다 너무 좁아지지 않게 막는다. 좁은 쪽이 쓸모없어지면 나누는 의미가 없다.
    const ratio = Math.min(0.72, Math.max(0.2, event.clientX / split.clientWidth));
    document.documentElement.style.setProperty("--left", `${ratio * 100}%`);
    if (editor) {
      editor.refresh();
    }
  });
}

attachEditor();
attachGutter();
$("createUser").addEventListener("click", createUser);
$("userId").addEventListener("change", switchedUser);
$("toProblems").addEventListener("click", showPicker);
$("tabProblem").addEventListener("click", () => {
  // 열어 둔 문제가 있으면 그리로, 없으면 목록으로 돌아간다.
  showLeft(currentProblem ? "statementBody" : "picker");
});
$("tabSkills").addEventListener("click", showSkills);
$("submitButton").addEventListener("click", submit);
$("runButton").addEventListener("click", runSamples);
restore();
refreshDiagnostic();
loadProblems().catch((error) => {
  $("problemList").textContent = `문제 목록을 불러오지 못했다: ${error.message}`;
});
