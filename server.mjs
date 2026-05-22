import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { extname, join, normalize } from "node:path";

const port = Number(process.env.PORT || 5177);
const root = process.cwd();

const mimeTypes = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml; charset=utf-8",
};

function json(res, statusCode, body) {
  res.writeHead(statusCode, {
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store",
  });
  res.end(JSON.stringify(body));
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let body = "";
    req.on("data", (chunk) => {
      body += chunk;
      if (body.length > 1024 * 64) {
        req.destroy();
        reject(new Error("Request body is too large."));
      }
    });
    req.on("end", () => resolve(body));
    req.on("error", reject);
  });
}

function isLikelyXUrl(value) {
  try {
    const url = new URL(value);
    return ["x.com", "twitter.com", "www.x.com", "www.twitter.com", "mobile.twitter.com"].includes(url.hostname);
  } catch {
    return false;
  }
}

function extractMediaSummary(html) {
  const links = [...html.matchAll(/href="([^"]+)"/gi)]
    .map((match) => match[1].replaceAll("&amp;", "&"))
    .filter((href) => /^https?:\/\//i.test(href));

  const mediaLinks = [...new Set(
    links.filter((href) =>
      /(?:dl\.snapcdn\.app|video\.twimg\.com|pbs\.twimg\.com|twcdn\.net)/i.test(href),
    ),
  )];

  return {
    mediaLinkCount: mediaLinks.length,
    mediaLinks: mediaLinks.slice(0, 20),
    hasVideo: /download\s*mp4|video\.twimg\.com|\.mp4/i.test(html),
    hasImage: /pbs\.twimg\.com|download\s*image|\.(?:jpg|jpeg|png|webp)/i.test(html),
  };
}

async function resolveViaSaveTwitter(tweetUrl) {
  const form = new URLSearchParams({
    q: tweetUrl,
    lang: "en",
    cftoken: "",
  });

  const startedAt = Date.now();
  const response = await fetch("https://savetwitter.net/api/ajaxSearch", {
    method: "POST",
    headers: {
      "content-type": "application/x-www-form-urlencoded; charset=UTF-8",
      "user-agent":
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125 Safari/537.36",
      "origin": "https://savetwitter.net",
      "referer": "https://savetwitter.net/en4",
      "x-requested-with": "XMLHttpRequest",
    },
    body: form,
  });

  const text = await response.text();
  let payload;
  try {
    payload = JSON.parse(text);
  } catch {
    throw new Error(`Unexpected response: ${text.slice(0, 160)}`);
  }

  return {
    upstreamStatus: response.status,
    elapsedMs: Date.now() - startedAt,
    status: payload.status,
    statusCode: payload.statusCode,
    message: payload.msg || "",
    html: payload.data || "",
    summary: payload.data ? extractMediaSummary(payload.data) : null,
  };
}

async function handleResolve(req, res) {
  try {
    const body = await readBody(req);
    const input = JSON.parse(body || "{}");
    const tweetUrl = String(input.url || "").trim();

    if (!isLikelyXUrl(tweetUrl)) {
      json(res, 400, {
        ok: false,
        message: "请粘贴 x.com 或 twitter.com 的公开帖子分享链接。",
      });
      return;
    }

    const result = await resolveViaSaveTwitter(tweetUrl);
    const ok = result.status === "ok" && Boolean(result.html);

    json(res, 200, {
      ok,
      source: "savetwitter",
      inputUrl: tweetUrl,
      ...result,
      message: ok ? "" : result.message || "没有解析到可下载媒体。",
    });
  } catch (error) {
    json(res, 500, {
      ok: false,
      message: error instanceof Error ? error.message : "解析失败。",
    });
  }
}

async function serveStatic(req, res) {
  const url = new URL(req.url || "/", `http://localhost:${port}`);
  const pathname = url.pathname === "/" ? "/index.html" : url.pathname;
  const safePath = normalize(pathname).replace(/^(\.\.[/\\])+/, "");
  const filePath = join(root, safePath);

  try {
    const data = await readFile(filePath);
    res.writeHead(200, {
      "content-type": mimeTypes[extname(filePath)] || "application/octet-stream",
      "cache-control": "no-store",
    });
    res.end(data);
  } catch {
    res.writeHead(404, { "content-type": "text/plain; charset=utf-8" });
    res.end("Not found");
  }
}

createServer(async (req, res) => {
  if (req.method === "POST" && req.url === "/api/resolve") {
    await handleResolve(req, res);
    return;
  }

  if (req.method === "GET" || req.method === "HEAD") {
    await serveStatic(req, res);
    return;
  }

  res.writeHead(405, { "content-type": "text/plain; charset=utf-8" });
  res.end("Method not allowed");
}).listen(port, () => {
  console.log(`Prototype running at http://localhost:${port}`);
});
