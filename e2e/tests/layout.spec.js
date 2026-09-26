const { test, expect } = require("@playwright/test");
const { stubApi } = require("../fixtures/api");

/**
 * 좁은 화면. 정본: backend/src/main/resources/static/app.css 의 @media (max-width: 900px).
 *
 * 390px 에서 상단의 "새로 시작" 이 화면 밖으로 밀려 페이지 전체가 옆으로 움직였고, 탭 이름이 한 글자씩
 * 세로로 꺾였다. 눈으로 보기 전에는 아무 검사도 몰랐다 - 그래서 폭과 줄 수를 직접 잰다.
 */
test.use({ viewport: { width: 390, height: 844 } });

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

/** 탭 이름이 한 줄에 있다. 줄 수는 높이를 줄 높이로 나눠 센다. */
async function expectTabsOnOneLine(page) {
  const lines = await page.evaluate(() =>
    ["tabProblem", "tabToday", "tabSkills", "tabMock", "tabAnalytics"].map((id) => {
      const el = document.getElementById(id);
      const style = getComputedStyle(el);
      const inner = el.clientHeight - parseFloat(style.paddingTop) - parseFloat(style.paddingBottom);
      return [id, Math.round(inner / parseFloat(style.lineHeight))];
    }));
  for (const [id, count] of lines) {
    expect(count, `${id} 가 ${count} 줄로 꺾였다`).toBe(1);
  }
}

test("좁은 화면에서 어느 탭을 열어도 페이지가 옆으로 밀리지 않고 탭 이름이 꺾이지 않는다", async ({ page }) => {
  await stubApi(page);
  await asUser(page);
  // 사용자가 있으면 목표 칸에 트랙 이름이 들어간다 - 원래 이 긴 이름이 버튼을 밀어냈다.
  await expect(page.locator("#track")).toHaveValue("JOB");
  await expectNoSideScroll(page, "문제 목록");
  await expectTabsOnOneLine(page);

  await page.locator("#problemList button", { hasText: "P02" }).click();
  await expect(page.locator("#statementBody")).toBeVisible();
  await expectNoSideScroll(page, "문제 화면");
  await expectTabsOnOneLine(page);

  for (const [tab, body] of [["#tabToday", "#todayBody"], ["#tabSkills", "#skillsBody"],
    ["#tabAnalytics", "#analyticsBody"]]) {
    await page.click(tab);
    await expect(page.locator(body)).toBeVisible();
    await expectNoSideScroll(page, tab);
  }
});

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
