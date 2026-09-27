const { test, expect } = require("@playwright/test");
const { gate, stubApi, finished, accepted, fulfill, mockTest, mockSheet } =
  require("../fixtures/api");

/**
 * 시험이 남기고 간 것. 정본: ADR-0043, ADR-0054 의 후속.
 *
 * 둘 다 검증 에이전트가 찾았다. 하나는 사용자를 바꿔도 이전 사용자의 시험 문제가 열려 있던 것,
 * 다른 하나는 시험 직전에 낸 일반 제출의 결과가 시험 문제를 열면 끝난 뒤에도 오지 않던 것이다.
 */

const WITHHELD = "모의 시험 중에는 제출 결과를 보여 주지 않는다 - 시험을 끝내면 열린다";

/** 사용자 id 를 넣고 화면이 자리를 잡을 때까지 기다린다. */
async function asUser(page, id = "1") {
  await page.goto("/index.html");
  await page.locator("#problemList button").first().waitFor();
  await page.fill("#userId", id);
  await page.dispatchEvent("#userId", "change");
}

/**
 * 사용자 1 의 시험 하나와 시험 전에 낸 일반 제출 5 를 깐다. 제출 조회는 서버처럼 시험 중에는 409 와 이유,
 * 끝난 뒤에는 결과다. `lookup` 을 주면 조회를 그 문 뒤에 붙잡는다.
 */
async function examWithEarlierSubmission(page, { lookup } = {}) {
  const exam = { over: false };
  await stubApi(page);
  await page.route("**/api/users/1/mock-tests/latest", (route) =>
    fulfill(route, mockTest(81, exam.over ? "FINISHED" : "IN_PROGRESS", ["A", "B"])));
  await page.route("**/api/mock-tests/81/problems/A/open", (route) => fulfill(route, mockSheet(81, "A")));
  await page.route("**/api/mock-tests/81/finish", async (route) => {
    exam.over = true;
    await fulfill(route, mockTest(81, "FINISHED", ["A", "B"]));
  });
  await page.route("**/api/problems/P02_GRID_TRAVERSAL/submit", (route) =>
    fulfill(route, accepted(5), 202));
  await page.route("**/api/submissions/5", async (route) => {
    if (lookup) {
      await lookup.held;
    }
    if (exam.over) {
      await fulfill(route, finished(5, "RETRY_VARIANT", "BFS_GRID_TRAVERSAL", "구현 연습이 더 필요하다"));
    } else {
      await fulfill(route, { message: WITHHELD }, 409);
    }
  });
  return exam;
}

test("사용자를 바꾸면 이전 사용자의 시험 문제를 놓는다", async ({ page }) => {
  // 검증 에이전트가 재현했다. 사용자 1 의 시험 문제 A 를 열어 둔 채 사용자 2 로 바꾸면 본문 · "시험 · 문제 A" 가
  // 그대로 남았고 제출 · 실행 버튼이 눌렸다. 서버는 남의 시험을 404 로 막지만(MockTestTest.othersTestIsNotFound),
  // 새 사용자에게 이전 사용자의 시험 본문을 보여 주는 것부터가 틀렸다.
  const examCalls = [];
  await stubApi(page);
  await page.route("**/api/users/1/mock-tests/latest", (route) =>
    fulfill(route, mockTest(71, "IN_PROGRESS", ["A", "B"])));
  await page.route("**/api/mock-tests/71/problems/A/open", (route) => fulfill(route, mockSheet(71, "A")));

  await asUser(page, "1");
  await page.click("#tabMock");
  await page.locator("#mockRows button", { hasText: "문제 A" }).click();
  await expect(page.locator("#crumbProblem")).toHaveText("시험 · 문제 A");
  await expect(page.locator("#submitButton")).toBeEnabled();

  page.on("request", (request) => {
    if (request.url().includes("/api/mock-tests/71/")) {
      examCalls.push(request.url());
    }
  });
  await page.fill("#userId", "2");
  await page.dispatchEvent("#userId", "change");

  await expect(page.locator("#crumbProblem")).toHaveText("고르는 중");
  await expect(page.locator("#problemMeta")).toHaveText("");
  await expect(page.locator("#submitButton")).toBeDisabled();
  await expect(page.locator("#runButton")).toBeDisabled();
  await expect(page.locator("#statementBody")).toBeHidden();
  await expect(page.locator("#picker")).toBeVisible();
  // 문제 탭을 눌러도 이전 사용자의 시험 본문으로 돌아가지 않는다
  await page.click("#tabProblem");
  await expect(page.locator("#picker")).toBeVisible();
  expect(examCalls, "이전 사용자의 시험으로 보낸 요청").toEqual([]);
});

test("사용자를 바꿔도 일반 문제는 그대로 열려 있다", async ({ page }) => {
  // 대조군. 시험 문제만 놓는다 - 일반 문제는 누구의 것도 아니다(ownership.spec.js 의 버튼 테스트와 같은 전제).
  await stubApi(page);
  await asUser(page, "1");
  await page.locator("#problemList button", { hasText: "P02" }).click();
  await page.fill("#userId", "2");
  await page.dispatchEvent("#userId", "change");
  await expect(page.locator("#crumbProblem")).toHaveText("P02_GRID_TRAVERSAL");
  await expect(page.locator("#submitButton")).toBeEnabled();
});

test("시험 때문에 결과를 받지 못하면 서버의 이유를 그대로 보이고 서버 장애라고 하지 않는다", async ({ page }) => {
  // ADR-0054 의 남는 위험. 409 이유 뒤에 "서버가 돌아오면 여기에 나타난다" 가 붙어 서버 장애처럼 읽혔고,
  // 연결 실패와 같이 세 번 실패한 뒤에야 보였다.
  await examWithEarlierSubmission(page);
  await asUser(page, "1");
  await page.locator("#problemList button", { hasText: "P02" }).click();
  await page.click("#submitButton");

  await expect(page.locator("#submitNote")).toHaveText(
      `${WITHHELD} (409). 제출은 접수됐다 - 시험이 끝나면 여기에 나오고, `
      + "이 문제를 떠났으면 모의 시험 탭에서 본다.");
  await expect(page.locator("#submitNote")).not.toContainText("서버가 돌아오면");
});

test("시험 전에 낸 제출은 시험 문제를 연 뒤에도 끝나면 찾아갈 수 있다", async ({ page }) => {
  // ADR-0054 의 남는 위험. 시험 문제를 열면 폴러가 끊겨(openMockProblem → cancelActivePolling) 끝난 뒤에도
  // 그 결과가 화면에 오지 않았다 - 제출 이력 화면도 없다. 조회를 붙잡아 두어 폴러가 409 를 한 번도 보기 전에
  // 시험 문제를 연다 - 여는 쪽이 적어 두지 않으면 아무도 적지 않는다.
  const lookup = gate();
  await examWithEarlierSubmission(page, { lookup });
  await asUser(page, "1");
  await page.locator("#problemList button", { hasText: "P02" }).click();
  await page.click("#submitButton");
  await expect(page.locator("#state")).toHaveText("채점 중");

  await page.click("#tabMock");
  await page.locator("#mockRows button", { hasText: "문제 A" }).click();
  await expect(page.locator("#crumbProblem")).toHaveText("시험 · 문제 A");
  // 시험 중에는 권하지 않는다 - 눌러도 409 다
  await page.click("#tabMock");
  await expect(page.locator("#mockFinish")).toBeVisible();
  await expect(page.locator("#mockWithheld")).toBeHidden();

  await page.click("#mockFinish");
  await expect(page.locator("#mockStart")).toBeVisible();
  await expect(page.locator("#mockWithheld")).toBeVisible();
  await expect(page.locator("#mockWithheld")).toContainText("P02_GRID_TRAVERSAL");
  lookup.release();

  await page.click("#mockWithheldView");
  await expect(page.locator("#crumbProblem")).toHaveText("P02_GRID_TRAVERSAL");
  await expect(page.locator("#state")).toHaveText("WRONG_ANSWER");
  await expect(page.locator("#nextAction")).toContainText("구현 연습이 더 필요하다");
  await expect(page.locator("#mockWithheld")).toBeHidden();
});

test("409 를 본 뒤 다른 문제로 옮겨도 시험이 끝나면 찾아갈 수 있다", async ({ page }) => {
  // 같은 결함의 다른 길이다. 폴러가 409 를 본 뒤에 일반 문제를 열면(openProblem → cancelActivePolling)
  // 그 제출은 다시 볼 곳이 없었다. 409 를 본 쪽이 적어 둔다.
  await examWithEarlierSubmission(page);
  await asUser(page, "1");
  await page.locator("#problemList button", { hasText: "P02" }).click();
  await page.click("#submitButton");
  await expect(page.locator("#submitNote")).toContainText(WITHHELD);

  await page.click("#toProblems");
  await page.locator("#problemList button", { hasText: "P03" }).click();
  await expect(page.locator("#crumbProblem")).toHaveText("P03_CONNECTED_COMPONENT");

  await page.click("#tabMock");
  await page.click("#mockFinish");
  await expect(page.locator("#mockWithheld")).toContainText("P02_GRID_TRAVERSAL");
  await page.click("#mockWithheldView");
  await expect(page.locator("#crumbProblem")).toHaveText("P02_GRID_TRAVERSAL");
  await expect(page.locator("#nextAction")).toContainText("구현 연습이 더 필요하다");
});

test("가려졌던 결과는 그 사용자에게만 권한다", async ({ page }) => {
  // 적어 둔 것은 사용자별이다. 다른 사용자의 모의 시험 탭에 이전 사용자의 제출을 권하면 남의 기록을 여는 길이 된다.
  await examWithEarlierSubmission(page);
  await page.route("**/api/users/2/mock-tests/latest", (route) =>
    fulfill(route, mockTest(82, "FINISHED", ["A", "B"])));
  await asUser(page, "1");
  await page.locator("#problemList button", { hasText: "P02" }).click();
  await page.click("#submitButton");
  await expect(page.locator("#submitNote")).toContainText(WITHHELD);

  await page.fill("#userId", "2");
  await page.dispatchEvent("#userId", "change");
  await page.click("#tabMock");
  await expect(page.locator("#mockStart")).toBeVisible();
  await expect(page.locator("#mockWithheld")).toBeHidden();

  // 대조: 사용자 1 로 돌아와 시험을 끝내면 권한다 - 위의 "보이지 않는다" 가 아무것도 권하지 않아서가 아니다
  await page.fill("#userId", "1");
  await page.dispatchEvent("#userId", "change");
  await expect(page.locator("#mockFinish")).toBeVisible();
  await page.click("#mockFinish");
  await expect(page.locator("#mockWithheld")).toContainText("P02_GRID_TRAVERSAL");
});
