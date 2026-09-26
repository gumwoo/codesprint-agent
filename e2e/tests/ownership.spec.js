const { test, expect } = require("@playwright/test");
const { gate, releaseAndSettle, stubApi, problem, finished, accepted, hint,
  conceptFor, fulfill } = require("../fixtures/api");

/**
 * 화면의 비동기 소유권 회귀. 정본: ADR-0023, ADR-0025.
 *
 * 여기 있는 여섯 개는 전부 **실제로 있었던 결함**이고, 전부 리뷰에서 사람이 찾았다.
 * 정적 검사(WebClientTest)는 여섯 중 하나도 잡지 못했다 - 소유권을 받기는 받았는데
 * 통을 잘못 나눴거나, 축이 모자랐거나, 떠날 때 놓지 않았기 때문이다.
 *
 * 각 테스트는 늦게 온 응답이 최신 화면을 덮지 않는지 본다. 순서는 게이트가 쥔다.
 */

/** 사용자 id 를 넣고 화면이 자리를 잡을 때까지 기다린다. */
async function asUser(page, id = "1") {
  await page.goto("/index.html");
  await page.locator("#problemList button").first().waitFor();
  await page.fill("#userId", id);
  await page.dispatchEvent("#userId", "change");
}

test("먼저 누른 문제가 늦게 도착해도 마지막에 고른 문제가 남는다", async ({ page }) => {
  // PR #30. openProblem 이 표를 받지 않아, P02 -> P03 으로 눌렀는데 P02 가 열렸다.
  const slow = gate();
  await stubApi(page);
  await page.route("**/api/problems/P02_GRID_TRAVERSAL?*", async (route) => {
    await slow.held;
    await fulfill(route, problem("P02_GRID_TRAVERSAL", "P02 제목"));
  });

  await asUser(page);
  await page.locator("#problemList button", { hasText: "P02" }).click();
  await page.locator("#problemList button", { hasText: "P03" }).click();
  await expect(page.locator("#crumbProblem")).toHaveText("P03_CONNECTED_COMPONENT");

  // 이제 P02 를 놓아 준다. 마지막에 고른 것은 P03 이므로 화면이 바뀌면 안 된다.
  await releaseAndSettle(page, slow, "/api/problems/P02_GRID_TRAVERSAL");
  await expect(page.locator("#crumbProblem")).toHaveText("P03_CONNECTED_COMPONENT");
});

test("문제를 기다리는 동안 목록으로 돌아가면 끌려가지 않는다", async ({ page }) => {
  // PR #30. showPicker 가 진행 중인 이동을 놓지 않아, 늦게 온 문제가 사용자를
  // 그 문제 화면으로 끌고 갔다.
  const slow = gate();
  await stubApi(page);
  await page.route("**/api/problems/P02_GRID_TRAVERSAL?*", async (route) => {
    await slow.held;
    await fulfill(route, problem("P02_GRID_TRAVERSAL", "P02 제목"));
  });

  await asUser(page);
  await page.locator("#problemList button", { hasText: "P02" }).click();
  await page.click("#toProblems");
  await expect(page.locator("#picker")).toBeVisible();

  await releaseAndSettle(page, slow, "/api/problems/P02_GRID_TRAVERSAL");
  await expect(page.locator("#picker")).toBeVisible();
  await expect(page.locator("#statementBody")).toBeHidden();
});

test("문제를 기다리는 동안 내 Skill 로 옮겨도 끌려가지 않는다", async ({ page }) => {
  // PR #30. 목록은 놓았는데 "내 Skill" 탭은 놓지 않았다 - 떠나는 경로마다 따로
  // 적으면 하나씩 빠뜨린다.
  const slow = gate();
  await stubApi(page);
  await page.route("**/api/problems/P02_GRID_TRAVERSAL?*", async (route) => {
    await slow.held;
    await fulfill(route, problem("P02_GRID_TRAVERSAL", "P02 제목"));
  });

  await asUser(page);
  await page.locator("#problemList button", { hasText: "P02" }).click();
  await page.click("#tabSkills");
  await expect(page.locator("#skillsBody")).toBeVisible();

  await releaseAndSettle(page, slow, "/api/problems/P02_GRID_TRAVERSAL");
  await expect(page.locator("#skillsBody")).toBeVisible();
  await expect(page.locator("#statementBody")).toBeHidden();
});

test("늦게 온 복습 조회가 지나간 '지금 복습하기' 를 되살리지 않는다", async ({ page }) => {
  // PR #29. 복습을 마쳐 일정이 밀렸는데, 늦게 온 due=true 가 버튼을 되살렸다.
  // 그 버튼을 누른 제출은 복습으로 세어지지 않는다 - 화면이 말한 것과 기록이 어긋난다.
  const due = {
    userId: 1, now: "2026-09-12T00:00:00Z",
    reviews: [{ skillCode: "BFS_GRID_TRAVERSAL", dueAt: "2026-09-11T00:00:00Z",
      due: true, intervalDays: 1,
      problem: { code: "P02_GRID_TRAVERSAL", title: "복습 문제" } }],
  };
  const notDue = {
    userId: 1, now: "2026-09-12T00:00:00Z",
    reviews: [{ skillCode: "BFS_GRID_TRAVERSAL", dueAt: "2026-09-15T00:00:00Z",
      due: false, intervalDays: 3, problem: null }],
  };

  const slow = gate();
  let seen = 0;
  await stubApi(page);
  await page.route("**/api/users/*/reviews", async (route) => {
    seen += 1;
    if (seen === 1) {
      await slow.held;                       // 첫 조회(만기)를 붙잡는다
      return fulfill(route, due);
    }
    return fulfill(route, notDue);            // 두 번째(만기 아님)가 먼저 도착한다
  });

  await asUser(page);
  // 첫 조회가 **실제로 출발했는지**를 세어서 기다린다. 시간으로 재면 아직 나가지도
  // 않은 요청을 두고 두 번째를 보내게 되어 경합이 성립하지 않는다.
  await expect.poll(() => seen).toBe(1);
  await page.evaluate(() => refreshReviews());   // 두 번째 조회
  await expect(page.locator("#reviewWhen")).toHaveText("3일 간격");

  await releaseAndSettle(page, slow, "/reviews");
  await expect(page.locator("#reviewWhen")).toHaveText("3일 간격");
  await expect(page.locator("#reviewStart")).toBeHidden();
});

test("늦게 온 사용자 생성이 나중에 만든 사용자를 덮지 않는다", async ({ page }) => {
  // PR #30. createUser 를 "화면 조각이 아니다" 로 예외에 뒀는데, 실은 소유권이
  // 가장 큰 변경이다 - 사용자를 바꾸면 화면 전체가 따라간다.
  const slow = gate();
  let made = 0;
  await stubApi(page);
  await page.route("**/api/users", async (route) => {
    made += 1;
    if (made === 1) {
      await slow.held;
      return fulfill(route, { userId: 11, nickname: "먼저", track: "JOB", dailyMinutes: null, examDate: null, learningMode: "NORMAL" });
    }
    return fulfill(route, { userId: 12, nickname: "나중", track: "JOB", dailyMinutes: null, examDate: null, learningMode: "NORMAL" });
  });

  await page.goto("/index.html");
  await page.locator("#problemList button").first().waitFor();
  await page.selectOption("#track", "JOB");
  await page.click("#createUser");
  await page.click("#createUser");
  await expect(page.locator("#userId")).toHaveValue("12");

  await releaseAndSettle(page, slow, "/api/users");
  await expect(page.locator("#userId")).toHaveValue("12");
});

test("개념 자료가 다른 제출의 결정 요약 아래에 붙지 않는다", async ({ page }) => {
  // PR #32. goToNextProblem 이 claimView("problem") 만 봤다. 그 통은 "어느 문제를
  // 보고 있는가" 를 지키지, "어느 제출의 결정을 보고 있는가" 는 지키지 않는다.
  //
  // 그러면 이 화면이 이으려던 "왜(3회 실패)" 와 "무엇(개념 자료)" 이 서로 다른
  // 제출에서 온다 - 고치려던 것의 정확히 반대다.
  const slow = gate();
  let submitted = 0;

  await stubApi(page);
  await page.route("**/api/problems/*/submit", async (route) => {
    submitted += 1;
    return fulfill(route, accepted(submitted), 202);
  });
  await page.route("**/api/submissions/1", (route) =>
      fulfill(route, finished(1, "REVIEW_CONCEPT", "BFS_GRID_TRAVERSAL",
          "같은 문제 3회 실패 - 개념부터 다시 본다")));
  await page.route("**/api/submissions/2", (route) =>
      fulfill(route, finished(2, "RETRY_VARIANT", "BFS_GRID_TRAVERSAL",
          "구현 연습이 더 필요하다")));
  await page.route("**/api/submissions/1/next-problem", async (route) => {
    await slow.held;
    return fulfill(route, { submissionId: 1, action: "REVIEW_CONCEPT",
      targetSkill: "BFS_GRID_TRAVERSAL", problem: null,
      concept: conceptFor("BFS_GRID_TRAVERSAL"), reason: "개념을 다시 확인한다" });
  });

  await asUser(page);
  await page.locator("#problemList button", { hasText: "P02" }).click();

  // 제출 1 의 결과가 뜨고, 거기서 "다음 단계 보기" 를 누른다.
  await page.click("#submitButton");
  await expect(page.locator("#nextAction")).toContainText("3회 실패");
  await page.click("#goNext");

  // 그 사이 제출 2 가 접수되어 결과 패널을 넘겨받는다.
  await page.click("#submitButton");
  await expect(page.locator("#nextAction")).toContainText("구현 연습이 더 필요하다");

  // 이제 제출 1 의 자료가 도착한다. 지금 화면은 제출 2 의 것이다.
  await releaseAndSettle(page, slow, "/api/submissions/1/next-problem");
  await expect(page.locator("#nextAction .concept")).toHaveCount(0);
  await expect(page.locator("#nextAction")).toContainText("구현 연습이 더 필요하다");
});

test("늦게 온 힌트가 다른 문제의 화면에 붙지 않는다", async ({ page }) => {
  // 힌트는 문제 화면의 것이다. P02 에서 힌트를 눌러 두고 P03 으로 옮기면, 늦게
  // 도착한 H1 이 P03 의 힌트인 것처럼 붙는다 - 그리고 그것은 기록과도 어긋난다.
  // 서버에 남은 것은 P02 의 H1 이기 때문이다.
  const slow = gate();
  await stubApi(page);
  await page.route("**/api/problems/P02_GRID_TRAVERSAL/hints/1", async (route) => {
    await slow.held;
    return fulfill(route, hint(1));
  });

  await asUser(page);
  await page.locator("#problemList button", { hasText: "P02" }).click();
  await page.click("#hintButton");

  // 목록으로 돌아가 다른 문제로 옮긴다. P02 는 이미 열렸으므로 목록이 숨어 있다.
  await page.click("#toProblems");
  await page.locator("#problemList button", { hasText: "P03" }).click();
  await expect(page.locator("#crumbProblem")).toHaveText("P03_CONNECTED_COMPONENT");

  await releaseAndSettle(page, slow, "/hints/1");
  await expect(page.locator("#hintList li")).toHaveCount(0);
  await expect(page.locator("#hintNote")).toHaveText("");
});

test("사용자를 바꾸면 띄워 둔 힌트가 남지 않는다", async ({ page }) => {
  // 힌트는 사용자별 기록이다. 남겨 두면 새 사용자는 그 문제에서 아무것도 보지
  // 않았는데 본 것처럼 보이고, 제출에 얼려지는 값과 어긋난다.
  await stubApi(page);

  await asUser(page, "1");
  await page.locator("#problemList button", { hasText: "P02" }).click();
  await page.click("#hintButton");
  await expect(page.locator("#hintList li")).toHaveCount(1);

  await page.fill("#userId", "2");
  await page.dispatchEvent("#userId", "change");
  await expect(page.locator("#hintList li")).toHaveCount(0);
});

test("다음 단계는 화면이 정하지 않고 서버가 준 값을 따른다", async ({ page }) => {
  // 이미 H3 까지 본 사용자. 화면은 0 부터 다시 보여주지만, **채점 기록에 남는
  // 값은 서버가 말한 3** 이다. 화면이 자기 계산으로 "H1" 이라고 적으면 사용자가
  // 보는 값과 mastery 에 들어가는 값이 갈린다(ADR-0027).
  await stubApi(page);
  await page.route("**/api/problems/P02_GRID_TRAVERSAL/hints/1", (route) =>
      fulfill(route, hint(1, 3)));

  await asUser(page);
  await page.locator("#problemList button", { hasText: "P02" }).click();
  await page.click("#hintButton");

  await expect(page.locator("#hintNote")).toHaveText("채점 기록에 남는 단계: H3 / H6");
  await expect(page.locator("#hintButton")).toHaveText("다음 힌트 (H2)");
});

test("전체 풀이는 두 번 눌러야 열린다", async ({ page }) => {
  // 전체 풀이는 되돌릴 수 없다. 열면 그 문제의 다음 제출이 독립 풀이로 세지 않는다.
  // 앞 단계 힌트를 연달아 누르다 손이 한 번 더 가는 것으로 끝나면 안 된다.
  const solutionRequests = [];
  page.on("request", (request) => {
    if (request.url().includes("/hints/6")) {
      solutionRequests.push(request.url());
    }
  });
  await stubApi(page);

  await asUser(page);
  await page.locator("#problemList button", { hasText: "P02" }).click();
  for (let level = 1; level <= 5; level++) {
    await page.click("#hintButton");
    await expect(page.locator("#hintList li")).toHaveCount(level);
  }
  await expect(page.locator("#hintButton")).toHaveText("전체 풀이 보기 (H6)");

  // 한 번 누르면 아무것도 열리지 않고, 무엇이 기록되는지 말한다.
  await page.click("#hintButton");
  await expect(page.locator("#hintButton")).toHaveText("한 번 더 누르면 전체 풀이를 연다");
  await expect(page.locator("#hintNote")).toContainText("독립 풀이로 세지 않는다");
  await expect(page.locator("#hintList li")).toHaveCount(5);
  expect(solutionRequests).toHaveLength(0);

  // 두 번째에 연다.
  await page.click("#hintButton");
  await expect(page.locator("#hintList li")).toHaveCount(6);
  await expect(page.locator("#hintList li pre")).toHaveCount(1);
  expect(solutionRequests).toHaveLength(1);
});

test("전체 풀이를 눌러 둔 채 문제를 옮기면 그 상태가 따라가지 않는다", async ({ page }) => {
  // 눌러 둔 상태가 남으면 다른 문제에서 한 번만 눌러도 전체 풀이가 열린다.
  await stubApi(page);

  await asUser(page);
  await page.locator("#problemList button", { hasText: "P02" }).click();
  for (let level = 1; level <= 5; level++) {
    await page.click("#hintButton");
    await expect(page.locator("#hintList li")).toHaveCount(level);
  }
  await page.click("#hintButton");
  await expect(page.locator("#hintButton")).toHaveText("한 번 더 누르면 전체 풀이를 연다");

  await page.click("#toProblems");
  await page.locator("#problemList button", { hasText: "P03" }).click();
  await expect(page.locator("#crumbProblem")).toHaveText("P03_CONNECTED_COMPONENT");
  await expect(page.locator("#hintButton")).toHaveText("힌트 보기");
});

test("늦게 온 이전 사용자의 목표가 지금 사용자의 목표를 덮지 않는다", async ({ page }) => {
  // ADR-0035. 사용자를 바꾸면 그 사람의 목표를 서버에서 읽는다. 1 번의 응답이 늦게
  // 오면 2 번 화면의 목표가 1 번 것으로 바뀌고, 그 상태로 목표를 바꾸면 남의 값이 적힌다.
  const slow = gate();
  await stubApi(page);
  await page.route("**/api/users/1", async (route) => {
    await slow.held;
    await fulfill(route, { userId: 1, nickname: "하나", track: "JOB", dailyMinutes: null, examDate: null, learningMode: "NORMAL" });
  });
  await page.route("**/api/users/2", (route) =>
    fulfill(route, { userId: 2, nickname: "둘", track: "INTRO", dailyMinutes: null, examDate: null, learningMode: "NORMAL" }));

  await asUser(page, "1");
  await page.fill("#userId", "2");
  await page.dispatchEvent("#userId", "change");
  await expect(page.locator("#track")).toHaveValue("INTRO");

  await releaseAndSettle(page, slow, "/api/users/1");
  await expect(page.locator("#track")).toHaveValue("INTRO");
});

test("목표를 고르지 않으면 새로 시작하지 않는다", async ({ page }) => {
  // ADR-0035 §4. 첫 트랙이 골라진 채로 두면 고르지 않은 사용자가 그 트랙으로 만들어진다.
  let created = 0;
  await stubApi(page);
  await page.route("**/api/users", (route) => {
    created += 1;
    return fulfill(route, { userId: 7, nickname: "x", track: "INTRO", dailyMinutes: null, examDate: null, learningMode: "NORMAL" });
  });

  await page.goto("/index.html");
  await page.locator("#track option[value=JOB]").waitFor({ state: "attached" });
  await expect(page.locator("#track")).toHaveValue("");
  await page.click("#createUser");
  await expect(page.locator("#footNote")).toHaveText("목표를 먼저 고른다");
  expect(created).toBe(0);
});

test("늦게 온 이전 사용자의 계획이 지금 사용자의 오늘 화면을 덮지 않는다", async ({ page }) => {
  // ADR-0038. 오늘 탭은 사용자 · 계획 · 오답 세 요청을 기다린다. 1 번의 계획이 늦게 오면
  // 2 번 화면에 1 번의 할 일이 그려진다.
  const slow = gate();
  const planFor = (userId, minutes) => ({
    userId, date: "2026-09-26", examInDays: null, totalMinutes: minutes, mode: "NORMAL",
    blocks: [], reason: `사용자 ${userId} 의 계획`, mastered: 0, total: 8,
  });
  await stubApi(page);
  await page.route("**/api/users/1/today", async (route) => {
    await slow.held;
    await fulfill(route, planFor(1, 60));
  });
  await page.route("**/api/users/2/today", (route) => fulfill(route, planFor(2, 90)));

  await asUser(page, "1");
  await page.click("#tabToday");
  await page.fill("#userId", "2");
  await page.dispatchEvent("#userId", "change");
  await expect(page.locator("#todayReason")).toHaveText("사용자 2 의 계획");

  await releaseAndSettle(page, slow, "/api/users/1/today");
  await expect(page.locator("#todayReason")).toHaveText("사용자 2 의 계획");
});

test("잘못 입력한 하루 시간은 저장하지 않는다 - 정하지 않음으로 바꾸지 않는다", async ({ page }) => {
  // ADR-0038 §4. number 칸에 잘못 쓰면 value 가 "" 가 되어, 그대로 보내면 null 로 저장됐다.
  let puts = 0;
  await stubApi(page);
  await page.route("**/api/users/1/settings", (route) => {
    puts += 1;
    return fulfill(route, { userId: 1, nickname: "x", track: "JOB", dailyMinutes: null,
      examDate: null, learningMode: "NORMAL" });
  });

  await asUser(page, "1");
  await page.click("#tabToday");
  await page.locator("#dailyMinutes").focus();
  await page.keyboard.type("6-0");
  await page.click("#saveSettings");
  await expect(page.locator("#settingsNote")).toHaveText("입력한 값을 읽지 못했다 - 저장하지 않았다");
  expect(puts).toBe(0);
});

test("늦게 온 이전 사용자의 시험이 지금 사용자의 시험 화면을 덮지 않는다", async ({ page }) => {
  // ADR-0043. 시험 탭은 가장 최근 시험을 읽어 그린다. 1 번의 시험이 늦게 오면 2 번 화면에
  // 1 번의 문제 라벨과 "시험 끝내기" 가 나타나고, 누르면 남의 시험을 끝낸다.
  const slow = gate();
  const { mockTest } = require("../fixtures/api");
  await stubApi(page);
  await page.route("**/api/users/1/mock-tests/latest", async (route) => {
    await slow.held;
    await fulfill(route, mockTest(11, "IN_PROGRESS", ["A", "B", "C"]));
  });
  // 2 번은 시험이 없다 - stubApi 가 모르는 경로에 404 를 준다.

  await asUser(page, "1");
  await page.click("#tabMock");
  await page.fill("#userId", "2");
  await page.dispatchEvent("#userId", "change");
  await expect(page.locator("#mockStart")).toBeVisible();

  await releaseAndSettle(page, slow, "/api/users/1/mock-tests/latest");
  await expect(page.locator("#mockStart")).toBeVisible();
  await expect(page.locator("#mockFinish")).toBeHidden();
  await expect(page.locator("#mockRows tr")).toHaveCount(0);
});

test("늦게 온 시험 문제가 그 뒤에 연 일반 문제를 덮지 않는다", async ({ page }) => {
  // ADR-0043. 시험 문제를 여는 것도 문제 화면의 주인(claimView("problem"))을 쓴다. 통을 따로
  // 두면 시험 A 를 눌렀다가 목록에서 P03 을 열었을 때 늦게 온 A 가 P03 을 덮는다 - 사용자는
  // P03 을 푼다고 믿고 시험 A 의 답을 낸다.
  const slow = gate();
  const { mockTest, mockSheet } = require("../fixtures/api");
  await stubApi(page);
  await page.route("**/api/users/1/mock-tests/latest",
      (route) => fulfill(route, mockTest(21, "IN_PROGRESS", ["A", "B"])));
  await page.route("**/api/mock-tests/21/problems/A/open", async (route) => {
    await slow.held;
    await fulfill(route, mockSheet(21, "A"));
  });

  await asUser(page, "1");
  await page.click("#tabMock");
  await page.locator("#mockRows button", { hasText: "문제 A" }).click();
  await page.click("#toProblems");
  await page.locator("#problemList button", { hasText: "P03" }).click();
  await expect(page.locator("#crumbProblem")).toHaveText("P03_CONNECTED_COMPONENT");

  await releaseAndSettle(page, slow, "/api/mock-tests/21/problems/A/open");
  await expect(page.locator("#crumbProblem")).toHaveText("P03_CONNECTED_COMPONENT");
  await expect(page.locator("#hintsBox")).toBeVisible();
});

test("늦게 온 자유 질문의 답이 다른 문제 화면에 붙지 않는다", async ({ page }) => {
  // ADR-0044. 답은 그 문제의 Skill 에 대한 것이다. P02 에서 물은 답이 P03 을 연 뒤에 오면
  // P03 의 질문 칸 아래에 붙어, 사용자는 그것을 P03 에 대한 설명으로 읽는다.
  const slow = gate();
  await stubApi(page);
  await page.route("**/api/users/1", (route) => fulfill(route, {
    userId: 1, nickname: "자유", track: "JOB", dailyMinutes: null, examDate: null,
    learningMode: "FREE" }));
  await page.route("**/api/tutor/questions", async (route) => {
    await slow.held;
    await fulfill(route, { skillCode: "BFS_GRID_TRAVERSAL", answer: "P02 에 대한 늦은 답",
      followUpQuestion: null, promptVersion: "tutor-v1" });
  });

  await asUser(page, "1");
  await page.locator("#problemList button", { hasText: "P02" }).click();
  await expect(page.locator("#tutorBox")).toBeVisible();
  await page.fill("#tutorQuestion", "방문 표시는 언제?");
  await page.click("#tutorAsk");
  await page.click("#toProblems");
  await page.locator("#problemList button", { hasText: "P03" }).click();
  await expect(page.locator("#crumbProblem")).toHaveText("P03_CONNECTED_COMPONENT");

  await releaseAndSettle(page, slow, "/api/tutor/questions");
  await expect(page.locator("#tutorAnswer")).toBeEmpty();
});

/** 검증 에이전트가 재현한 학습 모드 · 시험 끝내기 경합(PR #52). */
function modeUser(mode) {
  return { userId: 1, nickname: "v", track: "JOB", dailyMinutes: null, examDate: null,
    learningMode: mode };
}

test("늦게 온 오늘 탭의 사용자 조회가 방금 바꾼 학습 모드를 덮지 않는다", async ({ page }) => {
  // 오늘 탭과 학습 모드 저장이 다른 통으로 같은 칸을 썼다. 저장한 뒤에 늦게 온 조회가 옛 모드로 되돌렸다.
  const slow = gate();
  let gets = 0;
  await stubApi(page);
  await page.route("**/api/users/1", async (route) => {
    gets += 1;
    if (gets >= 2) {
      await slow.held; // 첫 조회(목표)는 보내고, 오늘 탭이 부른 조회를 붙잡는다
    }
    await fulfill(route, modeUser("NORMAL"));
  });
  await page.route("**/api/users/1/learning-mode", (route) => fulfill(route, modeUser("FREE")));

  await asUser(page, "1");
  await page.click("#tabToday");
  await page.selectOption("#learningMode", "FREE");
  await expect(page.locator("#modeNote")).toContainText("FREE");

  await releaseAndSettle(page, slow, "/api/users/1");
  await expect(page.locator("#learningMode")).toHaveValue("FREE");
});

test("학습 모드를 저장하는 동안 칸을 잠가 화면과 서버가 같은 모드를 가리킨다", async ({ page }) => {
  // 잠그지 않으면 둘을 연달아 골랐을 때 먼저 보낸 PUT 이 나중에 처리되어 화면과 서버가 갈린다.
  const slow = gate();
  let serverMode = "NORMAL";
  let puts = 0;
  await stubApi(page);
  await page.route("**/api/users/1/learning-mode", async (route) => {
    puts += 1;
    const mode = JSON.parse(route.request().postData()).mode;
    if (puts === 1) {
      await slow.held;
    }
    serverMode = mode;
    await fulfill(route, modeUser(mode));
  });

  await asUser(page, "1");
  await page.click("#tabToday");
  await page.selectOption("#learningMode", "STRICT");
  await expect(page.locator("#learningMode")).toBeDisabled();

  await releaseAndSettle(page, slow, "/api/users/1/learning-mode");
  await expect(page.locator("#learningMode")).toBeEnabled();
  await page.selectOption("#learningMode", "FREE");
  await expect(page.locator("#modeNote")).toContainText("FREE");
  await expect(page.locator("#learningMode")).toHaveValue(serverMode);
  expect(serverMode).toBe("FREE");
});

test("학습 모드를 저장하는 동안 오늘 탭을 다시 눌러도 저장 응답이 버려지지 않는다", async ({ page }) => {
  // 읽기(loadLearningMode)가 저장과 같은 통을 써서, 저장 중에 오늘 탭을 다시 누르면 저장의 표를 가져갔다.
  // PUT 응답이 버려져 화면은 NORMAL, 서버는 FREE 였고, 튜터 칸도 숨었다(검증 에이전트가 재현했다).
  const slow = gate();
  let serverMode = "NORMAL";
  await stubApi(page);
  await page.route("**/api/users/1", (route) => fulfill(route, modeUser(serverMode)));
  await page.route("**/api/users/1/learning-mode", async (route) => {
    await slow.held; // PUT 이 서버에서 처리되기 전에 오늘 탭의 조회가 먼저 끝난다
    serverMode = JSON.parse(route.request().postData()).mode;
    await fulfill(route, modeUser(serverMode));
  });

  await asUser(page, "1");
  await page.click("#tabToday");
  await page.selectOption("#learningMode", "FREE");
  await page.click("#tabToday");
  await expect(page.locator("#todayBody")).toBeVisible();

  await releaseAndSettle(page, slow, "/api/users/1/learning-mode");
  await expect(page.locator("#modeNote")).toContainText("FREE");
  await expect(page.locator("#learningMode")).toHaveValue(serverMode);

  await page.click("#toProblems");
  await page.locator("#problemList button", { hasText: "P02" }).click();
  await expect(page.locator("#tutorBox")).toBeVisible();
});

test("늦게 온 목표 조회가 방금 저장한 학습 모드의 튜터 칸을 되돌리지 않는다", async ({ page }) => {
  // 목표를 읽는 refreshUserTrack 도 모드 변수를 따로 적었다 - 칸은 FREE 인데 튜터 칸이 숨었다.
  const slow = gate();
  let serverMode = "NORMAL";
  await stubApi(page);
  await page.route("**/api/users/1", async (route) => {
    const stale = modeUser(serverMode); // 조회는 저장 전에 서버에서 처리됐다
    await slow.held;
    await fulfill(route, stale);
  });
  await page.route("**/api/users/1/learning-mode", (route) => {
    serverMode = JSON.parse(route.request().postData()).mode;
    return fulfill(route, modeUser(serverMode));
  });

  await asUser(page, "1");
  await page.click("#tabToday");
  await page.selectOption("#learningMode", "FREE");
  await expect(page.locator("#modeNote")).toContainText("FREE");

  await releaseAndSettle(page, slow, "/api/users/1");
  await page.click("#toProblems");
  await page.locator("#problemList button", { hasText: "P02" }).click();
  await expect(page.locator("#learningMode")).toHaveValue("FREE");
  await expect(page.locator("#tutorBox")).toBeVisible();
});

test("늦게 끝난 시험 끝내기가 그 사이 옮겨 간 오늘 탭에서 사용자를 끌고 가지 않는다", async ({ page }) => {
  const slow = gate();
  const { mockTest } = require("../fixtures/api");
  await stubApi(page);
  let finished = false;
  await page.route("**/api/users/1/mock-tests/latest", (route) =>
    fulfill(route, mockTest(31, finished ? "FINISHED" : "IN_PROGRESS", ["A", "B"])));
  await page.route("**/api/mock-tests/31/finish", async (route) => {
    await slow.held;
    finished = true;
    await fulfill(route, mockTest(31, "FINISHED", ["A", "B"]));
  });

  await asUser(page, "1");
  await page.click("#tabMock");
  await expect(page.locator("#mockFinish")).toBeVisible();
  await page.click("#mockFinish");
  await page.click("#tabToday");
  await expect(page.locator("#todayBody")).toBeVisible();

  await releaseAndSettle(page, slow, "/api/mock-tests/31/finish");
  await expect(page.locator("#todayBody")).toBeVisible();
  await expect(page.locator("#mockBody")).toBeHidden();
});

test("늦게 끝난 시험 끝내기가 그 사이 연 일반 문제에서 사용자를 끌고 가지 않는다", async ({ page }) => {
  const slow = gate();
  const { mockTest } = require("../fixtures/api");
  await stubApi(page);
  await page.route("**/api/users/1/mock-tests/latest", (route) =>
    fulfill(route, mockTest(41, "IN_PROGRESS", ["A", "B"])));
  await page.route("**/api/mock-tests/41/finish", async (route) => {
    await slow.held;
    await fulfill(route, mockTest(41, "FINISHED", ["A", "B"]));
  });

  await asUser(page, "1");
  await page.click("#tabMock");
  await page.click("#mockFinish");
  await page.click("#toProblems");
  await page.locator("#problemList button", { hasText: "P03" }).click();
  await expect(page.locator("#crumbProblem")).toHaveText("P03_CONNECTED_COMPONENT");

  await releaseAndSettle(page, slow, "/api/mock-tests/41/finish");
  await expect(page.locator("#statementBody")).toBeVisible();
});
