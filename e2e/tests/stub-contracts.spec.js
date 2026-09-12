const { test, expect } = require("@playwright/test");
const fs = require("node:fs");
const path = require("node:path");
const { contractFor, UNCONTRACTED } = require("../fixtures/contracts");

/**
 * 하네스 자신을 보는 검사. 정본: ADR-0025.
 *
 * 계약 검증은 **stub 이 그 문을 지날 때만** 일한다. `route.fulfill` 을 직접 부르면
 * 조용히 우회되고, 그때 남는 것은 "검사가 있다" 는 믿음뿐이다 - 이 저장소가
 * 커리큘럼 검사에 메타테스트를 요구하는 것과 같은 이유다.
 */

const SPECS = path.resolve(__dirname);

test("테스트가 계약 검증을 우회하지 않는다", () => {
  for (const file of fs.readdirSync(SPECS).filter((f) => f.endsWith(".spec.js"))) {
    const lines = fs.readFileSync(path.join(SPECS, file), "utf8").split("\n");
    lines.forEach((line, i) => {
      expect(line, `${file}:${i + 1} - 응답 본문은 fixtures/contracts.js 의 fulfill 로 낸다`)
          .not.toMatch(/route\.fulfill\s*\(/);
    });
  }
});

/**
 * 화면이 부르는 경로가 전부 계약에 이어져 있는가.
 *
 * `WebClientTest` 는 이 경로들이 **실재하는 매핑인지**를 본다. 여기서 보는 것은
 * 다른 축이다 - 그 경로의 **모양을 아는 계약이 있는지**. 새 엔드포인트를 부르기
 * 시작하면 stub 이 아무 모양이나 답해도 되는 상태가 되는데, 그것을 여기서 막는다.
 */
test("화면이 부르는 모든 API 경로에 계약이 이어져 있다", () => {
  const app = fs.readFileSync(
      path.resolve(__dirname, "..", "..", "backend", "src", "main", "resources",
          "static", "app.js"), "utf8");

  const called = new Set();
  for (const m of app.matchAll(/["'`](\/api\/[^"'`?\s]*)/g)) {
    called.add(m[1]);
  }
  expect(called.size).toBeGreaterThan(0);

  for (const raw of called) {
    // 화면은 경로를 템플릿으로 만든다(`/api/users/${id}/skills`). 검증기가 보는
    // 것은 실제 URL 이므로, 여기서도 자리표시자를 대표값으로 바꿔 대조한다.
    const concrete = raw.replace(/\$\{[^}]*\}/g, "1").replace(/\/$/, "");
    const known = contractFor(concrete) !== undefined
        || UNCONTRACTED[concrete] !== undefined
        || Object.keys(UNCONTRACTED).some((k) =>
            new RegExp("^" + k.replace(/\{[^}]*\}/g, "[^/]+") + "$").test(concrete));
    expect(known, `${raw} -> ${concrete} 의 계약을 모른다`).toBe(true);
  }
});
