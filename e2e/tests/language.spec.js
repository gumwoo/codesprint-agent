const { test, expect } = require("@playwright/test");
const { stubApi, accepted, fulfill } = require("../fixtures/api");

/**
 * 제출 언어. 정본: ADR-0045.
 *
 * 언어는 채점 이미지를 고른다 - 화면이 고른 언어와 서버에 실린 언어가 다르면, 사용자는 Java 로
 * 썼는데 Python 이미지가 채점해 COMPILE_ERROR 를 본다. 그래서 요청 본문을 직접 본다.
 */

async function asUser(page, id = "1") {
  await page.goto("/index.html");
  await page.locator("#problemList button").first().waitFor();
  await page.fill("#userId", id);
  await page.dispatchEvent("#userId", "change");
}

test("고른 언어로 제출하고 실행한다 - 파일 이름도 그 언어의 것이다", async ({ page }) => {
  const sent = [];
  await stubApi(page);
  await page.route("**/api/problems/*/submit", (route) => {
    sent.push(["submit", JSON.parse(route.request().postData()).language]);
    return fulfill(route, accepted(1), 202);
  });
  await page.route("**/api/problems/*/run", (route) => {
    sent.push(["run", JSON.parse(route.request().postData()).language]);
    return fulfill(route, { runId: 1 }, 202);
  });

  await asUser(page);
  await page.locator("#problemList button", { hasText: "P02" }).click();
  await expect(page.locator("#sourceFile")).toHaveText("solution.py");

  await page.selectOption("#language", "JAVA");
  await expect(page.locator("#sourceFile")).toHaveText("Main.java");
  await page.click("#submitButton");
  await page.click("#runButton");

  await page.selectOption("#language", "CPP");
  await expect(page.locator("#sourceFile")).toHaveText("solution.cpp");
  await page.click("#submitButton");

  await expect.poll(() => sent.length).toBe(3);
  expect(sent).toEqual([["submit", "JAVA"], ["run", "JAVA"], ["submit", "CPP"]]);
});
