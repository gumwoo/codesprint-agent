const { defineConfig, devices } = require("@playwright/test");

// 포트를 바꿀 수 있게 둔다. 개발 PC 의 4173 에 다른 정적 서버가 떠 있었고,
// reuseExistingServer 때문에 테스트가 **그 서버의 페이지**를 열었다 - 문제 목록이 없어
// 전부 시간 초과로 끝났다. 남의 서버를 우리 화면으로 착각하는 것보다 실패가 낫다.
const port = Number(process.env.E2E_PORT || 4173);

module.exports = defineConfig({
  testDir: "./tests",
  // **재시도를 두지 않는다.** 이 테스트가 잡으려는 것이 순서에 따라 달라지는
  // 동작인데, 재시도로 통과하면 정확히 그 결함을 숨기게 된다.
  retries: 0,
  // 순서를 테스트가 쥐고 있으므로 서로 간섭하지 않는다.
  fullyParallel: true,
  reporter: process.env.CI ? [["list"], ["junit", { outputFile: "results.xml" }]] : "list",
  use: {
    baseURL: `http://localhost:${port}`,
    trace: "retain-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: {
    command: "node static-server.js",
    url: `http://localhost:${port}/index.html`,
    env: { PORT: String(port) },
    reuseExistingServer: !process.env.CI,
  },
});
