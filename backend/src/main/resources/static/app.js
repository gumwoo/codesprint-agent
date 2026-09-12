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

/**
 * 화면이 지금 보고 있는 실행. 제출의 activeSubmissionId 와 같은 역할이다.
 *
 * 없으면 먼저 낸 실행이 나중에 끝나면서 뒤에 낸 결과를 덮는다 - 코드를 고쳐
 * 다시 돌렸는데 이전 코드의 출력이 나타난다.
 */
let activeRunId = null;

/**
 * 접수(제출·실행)는 조회와 규칙이 다르다.
 *
 * 조회는 claimView 하나로 끝나지만, 접수에는 **두 구간**이 있다.
 *
 *   요청 구간   POST 를 보내고 202 를 기다린다      claimView 로 지킨다
 *   관찰 구간   채점이 끝날 때까지 결과를 지켜본다   activeRunId / activeSubmissionId
 *
 * **두 구간을 한 표로 묶을 수 없다.** 새 요청을 시작할 때 앞의 *요청*은 버려도 되지만
 * 앞의 *관찰*은 살려야 한다 - 새 요청이 거절되면 앞 결과를 다시 볼 방법이 없다.
 * 실제로 그렇게 묶었다가 되돌렸다(PR #25).
 *
 * 그래서 관찰의 주인은 따로 둔다. 인수인계는 202 를 받은 쪽이 dropActive* 로 하고,
 * 문제나 사용자가 바뀌면 cancelActive* 가 자리를 비운다.
 */


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
  const mine = claimView("problemList");
  const data = await getJson("/api/problems");
  if (!mine()) {
    return;
  }
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

/**
 * 지금 버튼이 살아 있어도 되는 화면인가.
 *
 * <p>버튼을 잠그는 곳은 {@link showPicker} 하나뿐이다. 그래서 <b>목록이 보이는지</b>만
 * 본다 - {@code statementBody} 가 보이는지로 보면 "내 Skill" 탭을 열어 둔 채 요청이
 * 끝났을 때 잠긴 채로 남는다. 그 탭은 버튼을 잠근 적이 없는데도.
 *
 * <p>표(claimView)만으로는 부족하다. 목록으로 돌아가는 것은 요청을
 * 무효화하지 않으므로 - 접수된 채점은 그대로 관찰한다 - 번호가 그대로다.
 */
function onAProblemScreen() {
  return $("picker").hidden;
}

function showPicker() {
  showLeft("picker");
  refreshDiagnostic();
  refreshReviews();
  $("crumbProblem").textContent = "고르는 중";
  $("problemMeta").textContent = "";
  $("submitButton").disabled = true;
  $("runButton").disabled = true;
}

/** 왼쪽 패널에서 하나만 보인다. 탭 표시도 같이 옮긴다. */
function showLeft(bodyId) {
  // **문제 화면을 떠나면 진행 중인 이동을 놓는다.** 떠나는 경로마다 따로 적으면
  // 하나씩 빠뜨린다 - 실제로 목록은 놓았는데 "내 Skill" 탭은 놓지 않아서,
  // 느리게 오던 문제가 도착해 사용자를 그 문제로 끌고 갔다.
  if (bodyId !== "statementBody") {
    invalidateView("problem");
  }
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
  const mine = claimView("skills");
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

    // 진단과 같은 이유로, 쓰기 전에 확인한다.
    if (!mine()) {
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
    if (!mine()) {
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
  cancelActiveRun();
  resetResultUi("제출하면 여기에 판정과 다음 행동이 나온다.");
  // "제출하는 중…" 같은 진행 문구도 이전 사용자의 것이다.
  $("footNote").textContent = "";

  remember($("userId").value);
  refreshDiagnostic();
  refreshReviews();
  if (!$("skillsBody").hidden) {
    showSkills();
  }
}

// -- 화면 조각의 소유권 ------------------------------------------------
//
// **이 파일에서 지금까지 나온 결함은 전부 한 종류였다** - 늦게 온 응답이 최신
// 화면을 덮는다. 다섯 번 나왔고 다섯 번 다 리뷰에서 잡혔다.
//
// 원인은 "확인" 이 둘로 나뉘어 있었다는 것이다. 사용자가 바뀌었는가, 그리고 더
// 새 요청이 있는가. 따로 두면 **한쪽만 확인하는 코드**를 쓰게 된다 - 실제로
// 복습·진단·Skill 표가 전부 앞엣것만 보고 있었다.
//
// 그래서 하나로 묶는다. 시작할 때 표를 받고, 화면에 쓰기 전에 그 표로 묻는다.
// 반쪽만 묻는 것이 불가능해진다.

/** 화면 조각별로 마지막에 표를 가져간 사람. */
const viewOwners = {};

/**
 * 이 화면 조각을 지금부터 내가 그린다. **앞의 요청은 여기서 무효가 된다.**
 *
 * 조각마다 따로 센다 - 복습 조회가 진단을 무효화하면 관계없는 두 화면이 엮인다.
 *
 * @return 아직 내 것인지 묻는 함수. **화면에 쓰기 전에 부른다.**
 */
function claimView(name) {
  const token = (viewOwners[name] = (viewOwners[name] || 0) + 1);
  const userId = Number($("userId").value);
  return () => viewOwners[name] === token && Number($("userId").value) === userId;
}

/**
 * 진행 중인 것을 무효화한다. 화면이 다른 것을 보게 됐을 때 쓴다.
 *
 * claimView 와 달리 새 주인을 세우지 않는다 - 아무도 그리지 않는 상태다.
 */
function invalidateView(name) {
  viewOwners[name] = (viewOwners[name] || 0) + 1;
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

/**
 * 예약된 복습.
 *
 * **만기 여부를 여기서 정하지 않는다.** 서버가 `due` 를 내려준다 - 시각 비교를
 * 브라우저에서 하면 그쪽 시계가 학습 기록을 정하게 된다(ADR-0021).
 */
/**
 * 시각을 사람이 읽는 형태로. <b>표시만이다.</b>
 *
 * 브라우저의 지역 시간을 쓰는 것은 읽으라고 주는 값이기 때문이고, **만기 판단은
 * 여전히 서버의 `due` 다** - 브라우저 시계가 학습 기록을 정하면 안 된다(ADR-0021).
 */
function when(isoText) {
  const at = new Date(isoText);
  return Number.isNaN(at.getTime()) ? isoText : at.toLocaleString();
}

async function refreshReviews() {
  const card = $("reviewCard");
  const userId = Number($("userId").value);
  const mine = claimView("reviews");
  if (!userId) {
    card.hidden = true;
    return;
  }

  let view;
  try {
    view = await getJson(`/api/users/${userId}/reviews`);
  } catch (error) {
    // **여기에도 확인이 필요하다.** 오래된 요청이 늦게 실패하면서 최신 카드를
    // 조용히 감추면, 복습이 잡혀 있는데 없는 것으로 보인다.
    if (mine()) {
      card.hidden = true;
    }
    return;
  }
  if (!mine()) {
    return;
  }

  if (!view.reviews.length) {
    card.hidden = true;
    return;
  }
  card.hidden = false;

  const due = view.reviews.filter((review) => review.due);
  const button = $("reviewStart");
  if (!due.length) {
    // 잡혀 있지만 아직 때가 아니다. **간격이 지나야 의미가 있다** - 지금 풀면
    // 그건 복습이 아니라 그냥 한 번 더 푸는 것이다.
    const next = view.reviews[0];
    card.classList.add("done");
    $("reviewWhen").textContent = `${next.intervalDays}일 간격`;
    $("reviewNote").textContent = `${next.skillCode} — 다음 복습 ${when(next.dueAt)}`;
    return;
  }

  card.classList.remove("done");
  const first = due[0];
  $("reviewWhen").textContent = due.length > 1 ? `${due.length}개 밀림` : "지금";
  $("reviewNote").textContent =
      `${first.skillCode} — 시간이 지난 뒤에도 되는지 확인한다`;

  if (!first.problem) {
    // 줄 문제가 없다. 조용히 감추면 사용자는 복습이 없는 것으로 읽는다.
    card.classList.add("done");
    $("reviewNote").textContent = `${first.skillCode} — 줄 복습 문제가 없다`;
    return;
  }
  button.textContent = `${first.skillCode} 복습하기 — ${first.problem.code}`;
  button.onclick = () => openProblem(first.problem.code);
}

async function refreshDiagnostic() {
  const box = $("diagnostic");
  const userId = Number($("userId").value);
  const mine = claimView("diagnostic");
  if (!userId) {
    box.hidden = true;
    return;
  }

  let step;
  try {
    step = await getJson(`/api/users/${userId}/diagnostic`);
  } catch (error) {
    if (!mine()) {
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
  if (!mine()) {
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
  // **문제를 빠르게 두 번 고르면 늦게 온 응답이 이긴다.** 마지막에 누른 것이
  // 아니라 먼저 누른 문제가 열린다 - 이 검사가 그것을 찾았다(ADR-0023).
  const mine = claimView("problem");
  const opened = await getJson(`/api/problems/${code}`);
  if (!mine()) {
    return;
  }
  currentProblem = opened;
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
  // 실행도 함께 놓는다 - P01 에서 돌린 결과가 P02 로 넘어간 뒤 나타나면
  // 그 출력이 지금 문제의 것으로 읽힌다.
  cancelActivePolling();
  cancelActiveRun();
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
  invalidateView("submit");
  activeSubmissionId = null;
}

/** 인수인계용. 번호는 올리지 않는다 - {@link cancelActiveRun} 의 설명과 같다. */
function dropActiveSubmission() {
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
  // 요청 구간을 지킨다. 202 를 기다리는 동안 다른 문제를 열거나 사용자를 바꾸면
  // 그 제출의 판정과 다음 행동이 **다른 화면에** 나타난다.
  const mine = claimView("submit");
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
    //
    // 다만 내 번호일 때만 푼다 - 문제 목록으로 돌아가 잠긴 버튼을 늦게 끝난
    // 요청이 다시 열면, 열어 둔 문제가 없는데 제출할 수 있게 된다.
    if (mine() && onAProblemScreen()) {
      button.disabled = false;
    }
  }

  // 접수된 뒤에 화면이 옮겨 갔으면 이 제출은 이 화면 것이 아니다. 서버에서는
  // 그대로 채점되고, 그 사용자로 돌아오면 Skill 상태에 반영돼 있다.
  if (!mine()) {
    return;
  }

  // 여기서부터가 화면이 보는 제출이다. 앞의 것은 이제 놓는다.
  // 실행도 놓는다 - 답을 낸 뒤에 시험 삼아 돌린 결과가 뒤늦게 나타나면,
  // 사용자는 그것을 이번 제출의 결과로 읽는다.
  dropActiveSubmission();
  cancelActiveRun();
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
  // **POST 를 보내기 전에 표를 받는다.** 202 를 기다리는 동안 문제나 사용자가
  // 바뀌거나 다른 실행이 시작될 수 있고, 그때 이 응답은 남의 화면 것이 된다.
  const mine = claimView("run");
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
    // 접수 시도가 끝나면 버튼을 푼다. 제출과 같은 이유다 - 관찰은 한도 없이
    // 이어지므로, 그 뒤에 풀면 Worker 가 죽어 있을 때 버튼이 영영 잠긴다.
    //
    // **내 번호일 때만 푼다.** 문제 목록으로 돌아가 버튼이 잠긴 뒤에 늦게 끝난
    // 요청이 그것을 다시 열면, 열어 둔 문제가 없는데 실행할 수 있게 된다.
    if (mine() && onAProblemScreen()) {
      button.disabled = false;
    }
  }

  if (!mine()) {
    return;
  }
  // 여기서부터가 화면이 보는 실행이다. 앞의 것은 이제 놓는다.
  dropActiveRun();
  await waitForRun(accepted.runId, runningUserId);
}

/** 보고 있던 실행을 놓는다. 그 폴러는 다음 응답에서 스스로 멈춘다. */
function cancelActiveRun() {
  // 진행 중인 POST 도 함께 버린다. 번호를 올려 두면 그 요청의 202 가 뒤늦게
  // 도착해도 자기 번호가 이미 지났음을 알고 물러난다.
  invalidateView("run");
  dropActiveRun();
}

/**
 * 보고 있던 실행만 놓는다. <b>번호는 올리지 않는다</b> - 인수인계할 때 쓰며,
 * 올리면 인수받으려는 요청 자신의 번호가 무효가 된다.
 */
function dropActiveRun() {
  activeRunId = null;
  $("runOutput").replaceChildren();
}

/**
 * 실행 결과를 기다린다.
 *
 * <b>제출 폴링과 같은 규칙을 쓴다.</b> 처음에는 5분 뒤 포기하고 GET 한 번 실패하면
 * 끝냈는데, 같은 큐와 같은 Worker 를 쓰면서 실행에만 다른 규칙을 둘 이유가 없다 -
 * job 은 큐에 남아 있고, 서버가 잠깐 내려갔다고 사라지지 않는다.
 */
async function waitForRun(runId, runningUserId) {
  activeRunId = runId;
  const box = $("runOutput");
  box.replaceChildren();
  box.append(note("실행 중…"));

  const startedAt = Date.now();
  let warned = false;
  let failures = 0;
  for (;;) {
    let view = null;
    let failure = null;
    try {
      view = await getJson(`/api/runs/${runId}?userId=${runningUserId}`);
    } catch (error) {
      failure = error;
    }

    // **화면을 만지기 전에 확인한다.** 기다리는 동안 다른 실행이 접수됐거나,
    // 문제나 사용자가 바뀌었을 수 있다. 그러면 이 폴러는 남의 화면에 쓰는 것이 된다.
    // **번호는 여기서 보지 않는다.** 번호는 202 이전의 요청을 거르는 용도이고,
    // 이미 접수된 관찰의 생존 조건에 넣으면 새 실행을 누른 순간 앞의 관찰이
    // 끊긴다 - 그 새 실행이 거절되면 앞 결과를 다시 볼 방법이 없다.
    //
    // 인수인계는 202 를 받은 쪽이 dropActiveRun() 으로 한다. 문제나 사용자가
    // 바뀌면 cancelActiveRun() 이 activeRunId 를 비우므로 그쪽도 여기서 걸린다.
    if (activeRunId !== runId || !stillCurrent(runningUserId)) {
      return;
    }

    if (failure) {
      // 한 번 실패했다고 포기하지 않는다. 접수된 실행은 사라지지 않는다.
      failures += 1;
      if (failures >= UNREACHABLE_AFTER) {
        box.replaceChildren(note(`실행 결과를 가져오지 못하고 있다: ${failure.message}`));
      }
    } else {
      failures = 0;
      if (view.status === "DONE" || view.status === "FAILED") {
        reportTo(runningUserId, "");
        renderRun(view, box);
        return;
      }
    }

    const waited = Date.now() - startedAt;
    if (!warned && waited >= POLL_SLOW_AFTER_MS) {
      warned = true;
      box.replaceChildren(note("실행이 오래 걸리고 있다. 계속 기다린다."));
    }
    const slowly = warned || failures >= UNREACHABLE_AFTER;
    await new Promise((resolve) => setTimeout(
        resolve, slowly ? POLL_SLOW_INTERVAL_MS : POLL_INTERVAL_MS));
  }
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
    // 실행과 같은 이유로 번호를 보지 않는다. 새 제출을 눌렀다가 거절됐을 때
    // 앞 제출의 관찰이 끊기면 안 된다 - 앞의 것은 그대로 채점되고 있다.
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
  // 진단과 복습도 같이 움직인다 - 방금 낸 것이 다음 질문과 일정을 바꾼다.
  refreshDiagnostic();
  refreshReviews();
}

function heading(label) {
  const h = document.createElement("h3");
  h.textContent = label;
  return h;
}

async function goToNextProblem(submissionId) {
  // **문제 화면의 주인은 하나다.** "다음 문제" 도 결국 문제 화면으로 가는 길이라,
  // 따로 통을 두면 늦게 온 "다음 문제" 가 사용자가 방금 직접 고른 문제를 덮는다 -
  // 이번 PR 이 openProblem 에서 막은 것과 같은 결함이 이름만 달라 새어 나갔다.
  const mine = claimView("problem");
  const response = await fetch(`/api/submissions/${submissionId}/next-problem`);
  if (!mine()) {
    return;
  }
  if (!response.ok) {
    $("nextAction").append(note("줄 문제를 아직 고르지 못했다."));
    return;
  }
  const next = await response.json();
  if (!mine()) {
    return;
  }
  if (!next.problem) {
    // 화면이 action을 해석하지 않는다. 서버가 실제 자료를 주었는지만 본다.
    // 무엇을 할지 다시 결정하면 ADR-0002의 경계가 화면으로 새어 나온다.
    if (next.concept) {
      renderConcept(next.concept);
      $("goNext").hidden = true;
      return;
    }
    // 자료가 없는 것과 아직 정해지지 않은 것은 다르다. 서버가 이유를 준다.
    $("nextAction").append(note(next.reason));
    return;
  }
  await openProblem(next.problem.code);
}

function renderConcept(concept) {
  const action = $("nextAction");
  // **결정 요약을 지우지 않는다.** 자료는 무엇을 보는지 말하지만, 왜 보는지
  // ("같은 문제 3회 실패 - 개념부터 다시 본다")는 그 줄에만 있다. 지우면
  // 사용자는 갑자기 나타난 개념 설명이 자기 실패와 무슨 상관인지 알 수 없다.
  //
  // 이미 그린 자료는 걷어낸다 - 두 번 부르면 같은 설명이 두 번 쌓인다.
  const drawn = action.querySelector(".concept");
  if (drawn) {
    drawn.remove();
  }

  const box = document.createElement("div");
  box.className = "concept";
  box.append(heading(`${concept.skillCode} 개념 복습`));

  const title = document.createElement("p");
  title.className = "what";
  title.textContent = concept.title;
  const summary = document.createElement("p");
  summary.textContent = concept.summary;

  const points = document.createElement("ul");
  points.className = "concept-points";
  for (const point of concept.keyPoints) {
    const item = document.createElement("li");
    item.textContent = point;
    points.append(item);
  }

  const exampleTitle = heading("예시");
  const example = document.createElement("pre");
  example.textContent = concept.example;
  const check = document.createElement("p");
  check.className = "concept-check";
  check.textContent = `스스로 확인: ${concept.selfCheck}`;
  box.append(title, summary, points, exampleTitle, example, check);
  action.append(box);
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
  // **여기가 소유권이 가장 큰 변경이다.** 사용자를 바꾸면 화면 전체가 따라간다.
  // 두 번 누르면 늦게 온 응답이 나중에 만든 사용자를 덮어쓰고, 그 사이 사용자가
  // id 를 직접 고쳤어도 덮는다.
  const mine = claimView("user");
  const response = await fetch("/api/users", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ nickname: "로컬 사용자" }),
  });
  if (!response.ok) {
    if (mine()) {
      $("footNote").textContent = `사용자를 만들지 못했다 (${response.status})`;
    }
    return;
  }
  const created = await response.json();
  if (!mine()) {
    return;
  }
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
refreshReviews();
loadProblems().catch((error) => {
  $("problemList").textContent = `문제 목록을 불러오지 못했다: ${error.message}`;
});
