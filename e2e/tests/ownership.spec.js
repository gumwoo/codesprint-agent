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
  await page.route("**/api/problems/P02_GRID_TRAVERSAL", async (route) => {
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
  await page.route("**/api/problems/P02_GRID_TRAVERSAL", async (route) => {
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
  await page.route("**/api/problems/P02_GRID_TRAVERSAL", async (route) => {
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
      return fulfill(route, { userId: 11, nickname: "먼저" });
    }
    return fulfill(route, { userId: 12, nickname: "나중" });
  });

  await page.goto("/index.html");
  await page.locator("#problemList button").first().waitFor();
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
