const { defineConfig, devices } = require("@playwright/test");

module.exports = defineConfig({
  testDir: "./tests",
  // **재시도를 두지 않는다.** 이 테스트가 잡으려는 것이 순서에 따라 달라지는
  // 동작인데, 재시도로 통과하면 정확히 그 결함을 숨기게 된다.
  retries: 0,
  // 순서를 테스트가 쥐고 있으므로 서로 간섭하지 않는다.
  fullyParallel: true,
  reporter: process.env.CI ? [["list"], ["junit", { outputFile: "results.xml" }]] : "list",
  use: {
    baseURL: "http://localhost:4173",
    trace: "retain-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: {
    command: "node static-server.js",
    url: "http://localhost:4173/index.html",
    reuseExistingServer: !process.env.CI,
  },
});
