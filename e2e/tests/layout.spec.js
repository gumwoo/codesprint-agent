const { test, expect } = require("@playwright/test");
const { stubApi, fulfill, mockTest } = require("../fixtures/api");

/**
 * 좁은 화면. 정본: backend/src/main/resources/static/app.css 의 @media (max-width: 900px).
 *
 * 390px 에서 상단의 "새로 시작" 이 화면 밖으로 밀려 페이지 전체가 옆으로 움직였고, 탭 이름이 한 글자씩
 * 세로로 꺾였다. 눈으로 보기 전에는 아무 검사도 몰랐다 - 그래서 폭과 줄 수를 직접 잰다.
 *
 * 폭 하나로는 부족했다. 상단 접기를 되돌려도 390px 에서는 문서 폭이 넘치지 않고 버튼 글자만 세로로
 * 쌓였다(검증 에이전트). 그래서 320px 도 재고, 상단 요소의 줄 수도 잰다.
 */

async function asUser(page, id = "1") {
  await page.goto("/index.html");
  await page.locator("#problemList button").first().waitFor();
  await page.fill("#userId", id);
  await page.dispatchEvent("#userId", "change");
}

/** 페이지가 옆으로 밀리지 않는다 - 문서 폭이 화면 폭을 넘지 않는다. */
async function expectNoSideScroll(page, where) {
  const [scroll, view] = await page.evaluate(() =>
    [document.documentElement.scrollWidth, window.innerWidth]);
  expect(scroll, `${where}: 문서 폭 ${scroll} > 화면 폭 ${view}`).toBeLessThanOrEqual(view);
}

/**
 * 이 요소들의 글자가 한 줄에 있다. 인라인 요소는 줄 조각 수를, 나머지는 높이를 줄 높이로 나눠 센다.
 */
async function expectOneLine(page, ids) {
  const lines = await page.evaluate((names) => names.map((id) => {
    const el = document.getElementById(id);
    const style = getComputedStyle(el);
    if (style.display === "inline") {
      return [id, el.getClientRects().length];
    }
    const inner = el.clientHeight - parseFloat(style.paddingTop) - parseFloat(style.paddingBottom);
    return [id, Math.round(inner / parseFloat(style.lineHeight))];
  }), ids);
  for (const [id, count] of lines) {
    expect(count, `${id} 가 ${count} 줄로 꺾였다`).toBe(1);
  }
}

const TABS = ["tabProblem", "tabToday", "tabSkills", "tabMock", "tabAnalytics"];
const TOP = ["createUser", "crumbProblem"];

/** 실제 커리큘럼 길이의 코드. stub 의 짧은 코드로는 표가 넘치는지 알 수 없었다. */
async function longSkillCodes(page) {
  const requires = [{ skillCode: "BRUTE_FORCE_ENUMERATION", minimumMastery: 0.6 },
    { skillCode: "SEGMENT_TREE_RANGE_QUERY", minimumMastery: 0.6 }];
  await page.route("**/api/skills", (route) => fulfill(route, { skills: [
    { code: "COMBINATORIAL_ENUMERATION", name: "순열 · 조합 · 부분집합 생성", domain: "BRUTE_FORCE",
      tier: "CORE", requires }] }));
  await page.route("**/api/users/1/skills", (route) => fulfill(route, { userId: 1, skills: [
    { skillCode: "COMBINATORIAL_ENUMERATION", concept: null, recognition: null,
      implementation: null, independent: null, retention: null, speed: null, mastery: null,
      confidence: 0, evidenceCount: 0, status: "LOCKED" }] }));
}

for (const width of [320, 390]) {
  test.describe(`${width}px`, () => {
    test.use({ viewport: { width, height: 844 } });

    test("어느 탭을 열어도 페이지가 옆으로 밀리지 않고, 탭 이름과 상단이 꺾이지 않는다", async ({ page }) => {
      await stubApi(page);
      await longSkillCodes(page);
      await asUser(page);
      // 사용자가 있으면 목표 칸에 트랙 이름이 들어간다 - 원래 이 긴 이름이 버튼을 밀어냈다.
      await expect(page.locator("#track")).toHaveValue("JOB");
      await expectNoSideScroll(page, "문제 목록");
      await expectOneLine(page, [...TABS, ...TOP]);

      await page.locator("#problemList button", { hasText: "P02" }).click();
      await expect(page.locator("#statementBody")).toBeVisible();
      await expectNoSideScroll(page, "문제 화면");
      await expectOneLine(page, [...TABS, ...TOP]);

      for (const [tab, body] of [["#tabToday", "#todayBody"], ["#tabSkills", "#skillsBody"],
        ["#tabMock", "#mockBody"], ["#tabAnalytics", "#analyticsBody"]]) {
        await page.click(tab);
        await expect(page.locator(body)).toBeVisible();
        await expectNoSideScroll(page, tab);
      }
    });

    test("시험이 없으면 빈 보고서 표를 보이지 않는다", async ({ page }) => {
      // 좁은 화면에서 표에 display 를 준 규칙이 hidden 을 이겨, 머리줄만 있는 보고서가 보였다.
      await stubApi(page);
      await asUser(page);
      await page.click("#tabMock");
      await expect(page.locator("#mockStart")).toBeVisible();
      await expect(page.locator("#mockReport")).toBeHidden();
      await expect(page.locator("#mockTable")).toBeHidden();
    });
  });
}

/** 끝난 시험의 보고서. 결과 칸의 긴 문장이 폭을 가져가, 숫자 칸이 최소폭까지 눌리는 모양이다. */
function finishedReport() {
  const row = (label, code, outcome, opened, run, submit, solved) => ({
    label, problemCode: code, title: `${label} 문제`, primarySkill: "BFS_GRID_TRAVERSAL",
    expectedSolveSeconds: 900, outcome, openedAtSeconds: opened, firstRunAtSeconds: run,
    firstSubmitAtSeconds: submit, solvedAtSeconds: solved, submissions: 3,
    timeSpentSeconds: 3488, overExpected: true, lateGiveUp: outcome === "ATTEMPTED", mistakes: [],
  });
  return {
    mockTestId: 31, startedAt: "2026-09-26T09:00:00Z", closedAt: "2026-09-26T10:00:00Z",
    durationSeconds: 3600, solved: 1, total: 2, openOrder: ["A", "B"], easiestFirst: true,
    problems: [row("A", "P02_GRID_TRAVERSAL", "SOLVED", 12, 3000, 3400, 3500),
      row("B", "P03_CONNECTED_COMPONENT", "ATTEMPTED", 40, 3100, 3450, null)],
  };
}

for (const width of [390, 1440]) {
  test.describe(`${width}px 보고서`, () => {
    test.use({ viewport: { width, height: 900 } });

    test("시험 보고서의 시각은 칸 안에서 끊기지 않는다", async ({ page }) => {
      await stubApi(page);
      await page.route("**/api/users/1/mock-tests/latest",
          (route) => fulfill(route, mockTest(31, "FINISHED", ["A", "B"])));
      await page.route("**/api/mock-tests/31/report*", (route) => fulfill(route, finishedReport()));
      await asUser(page);
      await page.click("#tabMock");
      await expect(page.locator("#mockReportRows tr")).toHaveCount(2);
      const broken = await page.evaluate(() => [...document.querySelectorAll("#mockReport td.num")]
          .map((td) => {
            const range = document.createRange();
            range.selectNodeContents(td);
            return [td.textContent, range.getClientRects().length];
          })
          .filter(([, lines]) => lines > 1));
      expect(broken, "여러 줄로 쪼개진 숫자 칸").toEqual([]);
    });
  });
}

test.describe("390px 첫 방문", () => {
  test.use({ viewport: { width: 390, height: 844 } });

  test("처음 온 사람에게 시작하는 법을 보이고, 사용자가 생기면 감춘다", async ({ page }) => {
    await stubApi(page);
    await page.goto("/index.html");
    await page.locator("#problemList button").first().waitFor();
    await expect(page.locator("#welcome")).toBeVisible();

    await page.fill("#userId", "1");
    await page.dispatchEvent("#userId", "change");
    await expect(page.locator("#welcome")).toBeHidden();

    await page.fill("#userId", "");
    await page.dispatchEvent("#userId", "change");
    await expect(page.locator("#welcome")).toBeVisible();
  });
});
