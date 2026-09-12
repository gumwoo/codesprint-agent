// 화면이 부르는 API 를 고정하고, **그 응답이 도착하는 순서를 테스트가 쥔다.**
//
// 지연 시간(setTimeout)만으로는 부족하다. 수동 재현에서 두 번 실패했는데, 둘 다
// 늦게 보낸 응답이 먼저 도착해 경합 자체가 성립하지 않았기 때문이다. 그런 테스트는
// **조용히 통과하는 날이 생긴다** - 이 부류에서 가장 나쁜 결과다.
//
// 그래서 "몇 초 늦춘다" 가 아니라 "내가 놓을 때까지 잡고 있는다" 로 만든다.

/** 잡았다 놓는 문. `held` 를 기다리다가 `release()` 에서 열린다. */
function gate() {
  let open;
  const held = new Promise((resolve) => {
    open = resolve;
  });
  return { held, release: () => open() };
}

/** 화면이 부르는 것들의 기본 응답. 테스트가 필요한 것만 덮어쓴다. */
const DEFAULTS = {
  problems: {
    problems: [
      { code: "P02_GRID_TRAVERSAL", title: "도달할 수 있는 칸", kind: "NORMAL",
        primarySkill: "BFS_GRID_TRAVERSAL" },
      { code: "P03_CONNECTED_COMPONENT", title: "영역의 개수", kind: "NORMAL",
        primarySkill: "BFS_GRID_TRAVERSAL" },
    ],
  },
  diagnostic: {
    userId: 1, done: true, targetSkill: null, problem: null,
    assessed: 8, total: 8, reason: "8개를 직접 확인했다",
  },
  reviews: { userId: 1, now: "2026-09-12T00:00:00Z", reviews: [] },
};

function problem(code, title) {
  return {
    code, title, kind: "NORMAL", source: "DEV_FIXTURE",
    statement: `${code} 본문`, timeLimitMs: 2000, memoryLimitMb: 256,
    expectedSolveSeconds: 600,
    skills: [{ skillCode: "BFS_GRID_TRAVERSAL", role: "PRIMARY", weight: 1.0 }],
    samples: [{ input: "1\n", expectedOutput: "1\n" }],
  };
}

/**
 * 끝난 제출 하나.
 *
 * **모양을 서버와 맞춘다.** GET /api/submissions/{id} 는 판정을 `result` 안에 넣어
 * 돌려준다(SubmissionStatusResponse) - 처음에 그 래퍼를 빠뜨렸더니 화면이 영원히
 * "채점 중" 에 머물렀다. stub 으로 만드는 테스트의 값은 여기 달려 있다:
 * **모양이 어긋나면 통과하든 실패하든 아무것도 재지 못한다.**
 *
 * 이 모양이 서버와 갈리는 것은 WebClientTest 가 막지 못한다 - 그쪽은 경로와
 * method 만 대조한다. 그래서 여기 필드를 고칠 때는 contracts/submit-response
 * 와 SubmissionController 를 함께 본다.
 */
function finished(submissionId, action, targetSkill, reason) {
  return {
    submissionId,
    state: "DONE",
    result: {
      submissionId,
      judge: { status: "WRONG_ANSWER", passed: 0, total: 6, executionMs: 90,
        memoryKb: 20480, failedCaseId: 3, stderr: null },
      review: null,
      skillUpdates: [],
      nextAction: { type: action, targetSkill, reason },
      promptVersion: null,
    },
  };
}

/** REVIEW_CONCEPT 가 가리키는 자료. */
function conceptFor(skillCode) {
  return {
    skillCode, title: `${skillCode} 자료`, summary: "요약",
    keyPoints: ["핵심"], example: "예시 코드", selfCheck: "확인 질문",
  };
}

function json(body) {
  return { status: 200, contentType: "application/json", body: JSON.stringify(body) };
}

/**
 * 기본 라우팅을 깐다. 테스트는 이 위에 `page.route` 를 더 얹어 순서를 만든다.
 *
 * <p>편집기(CDN)는 막는다. 화면은 없어도 textarea 로 도는데(ADR-0017), 네트워크
 * 사정에 따라 테스트가 느려지거나 갈리는 것을 막는다.
 */
async function stubApi(page, overrides = {}) {
  await page.route("**://cdnjs.cloudflare.com/**", (route) => route.abort());
  await page.route("**://fonts.googleapis.com/**", (route) => route.abort());

  await page.route("**/api/**", async (route) => {
    const url = new URL(route.request().url());
    const p = url.pathname;

    if (p === "/api/problems") {
      return route.fulfill(json(overrides.problems || DEFAULTS.problems));
    }
    if (/^\/api\/problems\/[^/]+$/.test(p)) {
      const code = p.split("/").pop();
      return route.fulfill(json(problem(code, `${code} 제목`)));
    }
    if (p.endsWith("/diagnostic")) {
      return route.fulfill(json(overrides.diagnostic || DEFAULTS.diagnostic));
    }
    if (p.endsWith("/reviews")) {
      return route.fulfill(json(overrides.reviews || DEFAULTS.reviews));
    }
    if (p.endsWith("/skills") || p === "/api/skills") {
      return route.fulfill(json({ userId: 1, skills: [] }));
    }
    // 테스트가 쓰지 않는 것은 명시적으로 막는다. 조용히 통과시키면 화면이
    // 부르는 줄도 모르는 엔드포인트가 생긴다.
    return route.fulfill({ status: 404, body: "stub 없음: " + p });
  });
}

module.exports = { gate, stubApi, problem, json, finished, conceptFor, DEFAULTS };
