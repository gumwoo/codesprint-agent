// 화면 파일만 서빙한다. **백엔드를 띄우지 않는다.**
//
// 여기서 재현하려는 결함은 전부 화면 쪽 - 늦게 온 응답이 최신 화면을 덮는 것 -
// 이고, 그것을 결정적으로 재현하려면 응답의 순서를 테스트가 쥐고 있어야 한다.
// 진짜 서버를 쓰면 그 순서가 큐와 DB 사정에 달리게 되고, 실제로 수동 재현에서
// 두 번 실패했다(ADR-0025).
//
// 의존성을 두지 않는다. Node 기본 모듈만 쓴다 - 화면이 빌드 도구 없이 도는
// 것처럼(ADR-0017), 그것을 검증하는 쪽도 가볍게 둔다.
const http = require("node:http");
const fs = require("node:fs");
const path = require("node:path");

const ROOT = path.resolve(__dirname, "..", "backend", "src", "main", "resources", "static");
const PORT = Number(process.env.PORT || 4173);

const TYPES = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
};

http.createServer((request, response) => {
  const url = new URL(request.url, "http://localhost");
  const name = url.pathname === "/" ? "/index.html" : url.pathname;
  const file = path.join(ROOT, path.normalize(name));

  // 정적 서버가 상위 디렉터리를 내보내지 않게 한다. 테스트 도구라도 저장소
  // 전체를 서빙하기 시작하면 그 습관이 남는다.
  if (!file.startsWith(ROOT)) {
    response.writeHead(403).end();
    return;
  }
  fs.readFile(file, (error, body) => {
    if (error) {
      response.writeHead(404).end();
      return;
    }
    response.writeHead(200, { "Content-Type": TYPES[path.extname(file)] || "text/plain" });
    response.end(body);
  });
}).listen(PORT, () => console.log(`static server on ${PORT}`));
