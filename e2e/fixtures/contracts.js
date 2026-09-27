// stub 이 실제 계약을 지키는지 본다.
//
// **이 층이 스스로 만든 위험이다.** 백엔드를 띄우지 않는 대신 응답을 손으로 쓰는데,
// 그 모양이 서버와 갈리면 테스트는 통과하든 실패하든 **아무것도 재지 못한다.**
// 실제로 겪었다 - GET /api/submissions/{id} 의 `result` 래퍼를 빠뜨려 화면이 영원히
// "채점 중" 에 머물렀고, 그때 테스트는 그저 실패했다. 무엇이 틀렸는지는 말해 주지
// 않았다.
//
// 그래서 **응답을 내보내는 자리에서** 검증한다. 따로 "검증용 샘플" 을 두면 그것과
// 실제로 쓰는 응답이 갈린다 - 이 저장소가 계속 만나온 패턴이다.
const fs = require("node:fs");
const path = require("node:path");
const Ajv = require("ajv/dist/2020");

const CONTRACTS = path.resolve(__dirname, "..", "..", "contracts");

const ajv = new Ajv({ strict: false, allErrors: true });
for (const file of fs.readdirSync(CONTRACTS).filter((f) => f.endsWith(".json"))) {
  ajv.addSchema(JSON.parse(fs.readFileSync(path.join(CONTRACTS, file), "utf8")));
}

/**
 * 경로 -> 계약. **순서가 중요하다** - 먼저 맞는 것을 쓰므로 긴 경로를 앞에 둔다.
 *
 * 여기 없는 경로는 `UNCONTRACTED` 에 있어야 한다. 둘 다 아니면 실패한다 -
 * 조용히 넘기면 계약 없이 만들어진 stub 이 생기는 줄도 모른다.
 *
 * **값으로 `null` 을 두지 않는다.** "계약이 있다" 와 "이유가 있다" 사이에 제3의
 * 상태를 만들면 그 경로는 두 검사를 모두 비켜 간다 - 실제로 `/run` 이 그랬고,
 * 그 상태에서는 `UNCONTRACTED` 의 이유를 통째로 지워도 전부 통과했다.
 */
const BY_PATH = [
  [/^\/api\/tutor\/questions$/, "tutor-answer"],
  [/^\/api\/mock-tests\/\d+\/problems\/[^/]+\/open$/, "mock-test-problem"],
  [/^\/api\/mock-tests\/\d+\/submissions\/\d+$/, "mock-test-verdict"],
  [/^\/api\/mock-tests\/\d+\/runs\/\d+$/, "mock-test-run"],
  [/^\/api\/mock-tests\/\d+\/report$/, "mock-test-report"],
  [/^\/api\/mock-tests\/\d+\/finish$/, "mock-test"],
  [/^\/api\/mock-tests\/\d+$/, "mock-test"],
  [/^\/api\/users\/\d+\/mock-tests\/latest$/, "mock-test"],
  [/^\/api\/users\/\d+\/mock-tests$/, "mock-test"],
  [/^\/api\/users\/\d+\/learning-mode$/, "user"],
  [/^\/api\/submissions\/\d+\/next-problem$/, "next-problem"],
  [/^\/api\/submissions\/\d+$/, "submission-status"],
  [/^\/api\/problems\/[^/]+\/submit$/, "submission-status"],
  [/^\/api\/problems\/[^/]+\/hints\/\d+$/, "hint-view"],
  [/^\/api\/problems\/[^/]+$/, "problem-view"],
  [/^\/api\/problems$/, "problem-list"],
  [/^\/api\/runs\/\d+$/, "run-result"],
  [/^\/api\/users\/\d+\/diagnostic$/, "diagnostic-step"],
  [/^\/api\/users\/\d+\/reviews$/, "reviews"],
  [/^\/api\/users\/\d+\/skills$/, "skill-map"],
  [/^\/api\/users\/\d+\/track$/, "user"],
  [/^\/api\/users\/\d+\/settings$/, "user"],
  [/^\/api\/users\/\d+\/today$/, "today"],
  [/^\/api\/users\/\d+\/mistakes$/, "mistake-summary"],
  [/^\/api\/users\/\d+\/analytics$/, "analytics"],
  [/^\/api\/problems\/[^/]+\/explanations$/, "explain-back"],
  [/^\/api\/users\/\d+$/, "user"],
  [/^\/api\/users$/, "user"],
  [/^\/api\/tracks$/, "track-list"],
  [/^\/api\/skills$/, "skill-catalog"],
];

/**
 * 계약이 없는 응답. **이유를 적지 않은 항목은 두지 않는다.**
 *
 * 목록이 길어지면 규칙이 아니라 예외가 하네스를 지배한다(ADR-0023 의 같은 규칙).
 */
const UNCONTRACTED = {
  "/api/problems/{code}/run": "202 응답(runId)에 대응하는 계약이 없다. "
      + "결과 조회(run-result)만 계약이 있다",
  "/api/mock-tests/{id}/problems/{label}/run": "202 응답(runId)에 대응하는 계약이 없다. "
      + "결과 조회(mock-test-run)만 계약이 있다",
  "/api/mock-tests/{id}/problems/{label}/submit": "202 응답(submissionId)에 대응하는 계약이 없다. "
      + "판정 조회(mock-test-verdict)만 계약이 있다",
};

function contractFor(pathname) {
  for (const [pattern, name] of BY_PATH) {
    if (pattern.test(pathname)) {
      return name;
    }
  }
  return undefined;
}

/**
 * 계약에 대고 검증한 뒤 응답한다. **stub 은 전부 이 문을 지난다.**
 *
 * @param status 202 처럼 200 이 아닌 응답도 같은 계약을 지킨다 - 접수 응답과 조회
 *     응답이 같은 모양인 것은 서버 쪽 결정이고(submission-status), 여기서 바꾸지 않는다.
 */
/**
 * 거절 본문이 {message} 가 아닌 경로. **이유를 적지 않은 항목은 두지 않는다.** 여기 있는 경로의 4xx 를
 * api-error 로 보면, 서버 그대로의 stub 이 거절되고 {message} 로 고친 stub 은 서버와 갈린다.
 */
const REJECTION_UNCONTRACTED = [
  [/^\/api\/problems\/[^/]+\/hints\/\d+$/,
    "HintController 의 404 · 409 · 400 은 {error} 로 준다 - 화면이 body.error 를 읽는다"],
  [/^\/api\/problems\/[^/]+\/run$/, "RunController 의 404 · 400 본문은 평문 문자열이다"],
];

function rejectionExcuse(pathname) {
  const found = REJECTION_UNCONTRACTED.find(([pattern]) => pattern.test(pathname));
  return found ? found[1] : undefined;
}

async function fulfill(route, body, status = 200) {
  const url = new URL(route.request().url());
  // 거절(4xx)은 경로가 아니라 **거절의 계약**을 지난다 - 경로의 계약(예: submission-status)에 대고 보면
  // 409 본문은 항상 어긋난다. 이유를 싣는 핸들러 대부분(제출 · 모의 시험 · Explain Back)은 {message} 이고,
  // 아닌 경로는 REJECTION_UNCONTRACTED 에 이유와 함께 있다.
  if (status >= 400 && rejectionExcuse(url.pathname)) {
    return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
  }
  const name = status >= 400 ? "api-error" : contractFor(url.pathname);

  if (name === undefined) {
    // 규칙은 하나다 - 계약이 있거나, 이유가 있거나.
    const excuse = UNCONTRACTED[url.pathname]
        || UNCONTRACTED[url.pathname.replace(/\/api\/problems\/[^/]+\//, "/api/problems/{code}/")]
        || UNCONTRACTED[url.pathname.replace(/\/api\/mock-tests\/\d+\/problems\/[^/]+\//,
            "/api/mock-tests/{id}/problems/{label}/")];
    if (!excuse) {
      throw new Error(
          `계약을 모르는 경로다: ${url.pathname}\n`
          + "  contracts/ 에 계약이 있으면 BY_PATH 에 잇고,\n"
          + "  없으면 UNCONTRACTED 에 **이유와 함께** 적는다.");
    }
  } else {
    const validate = ajv.getSchema(`https://codesprint.dev/contracts/${name}.schema.json`);
    if (!validate(body)) {
      throw new Error(
          `stub 이 ${name}.schema.json 을 어긴다: ${url.pathname}\n`
          + validate.errors.map((e) => `  ${e.instancePath || "/"} ${e.message}`).join("\n")
          + `\n  실제 응답: ${JSON.stringify(body).slice(0, 300)}`);
    }
  }

  return route.fulfill({
    status,
    contentType: "application/json",
    body: JSON.stringify(body),
  });
}

module.exports = { fulfill, contractFor, UNCONTRACTED, REJECTION_UNCONTRACTED };
