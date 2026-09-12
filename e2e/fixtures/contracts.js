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
  [/^\/api\/submissions\/\d+\/next-problem$/, "next-problem"],
  [/^\/api\/submissions\/\d+$/, "submission-status"],
  [/^\/api\/problems\/[^/]+\/submit$/, "submission-status"],
  [/^\/api\/problems\/[^/]+$/, "problem-view"],
  [/^\/api\/problems$/, "problem-list"],
  [/^\/api\/runs\/\d+$/, "run-result"],
  [/^\/api\/users\/\d+\/diagnostic$/, "diagnostic-step"],
  [/^\/api\/users\/\d+\/reviews$/, "reviews"],
  [/^\/api\/users\/\d+\/skills$/, "skill-map"],
  [/^\/api\/skills$/, "skill-catalog"],
];

/**
 * 계약이 없는 응답. **이유를 적지 않은 항목은 두지 않는다.**
 *
 * 목록이 길어지면 규칙이 아니라 예외가 하네스를 지배한다(ADR-0023 의 같은 규칙).
 */
const UNCONTRACTED = {
  "/api/users": "CreatedUser(userId, nickname) 에 대응하는 계약 파일이 없다. "
      + "계약을 만드는 것은 이 하네스의 결정이 아니라 API 쪽 결정이다",
  "/api/problems/{code}/run": "202 응답(runId)에 대응하는 계약이 없다. "
      + "결과 조회(run-result)만 계약이 있다",
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
async function fulfill(route, body, status = 200) {
  const url = new URL(route.request().url());
  const name = contractFor(url.pathname);

  if (name === undefined) {
    // 규칙은 하나다 - 계약이 있거나, 이유가 있거나.
    const excuse = UNCONTRACTED[url.pathname]
        || UNCONTRACTED[url.pathname.replace(/\/api\/problems\/[^/]+\//, "/api/problems/{code}/")];
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

module.exports = { fulfill, contractFor, UNCONTRACTED };
