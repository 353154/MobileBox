/**
 * MobileBox TMDB 反向代理（Cloudflare Worker）
 * ==========================================
 *
 * 为什么要它：TMDB 条款不允许把 API Key 硬编码进客户端，移动端必须走 server-side；
 * 同时 api.themoviedb.org 在国内基本不可直连，代理是唯一可行的出口。
 *
 * 部署（约 5 分钟）：
 *   1. Cloudflare Dashboard → Workers & Pages → Create → Worker，把本文件全部内容粘贴进去；
 *   2. Settings → Variables and Secrets，添加一个 **Secret**：
 *        TMDB_TOKEN = 你的 v4 Read Access Token（eyJ 开头）
 *        若只有 v3 API Key，改用变量名 TMDB_KEY，脚本会自动挂到 query 上；
 *   3. 部署后把形如 https://xxx.yyy.workers.dev 的地址填进
 *      App 的 data/tmdb/TmdbClient.kt → PROXY_BASE（结尾不要斜杠）；
 *   4. 确认 CLIENT_TOKEN 与 App 里的常量一致（两处都改才会同时生效）。
 *
 * 已内置的三道闸门：
 *   - 路径白名单：只放行 App 实际用到的 5 类读取接口，绝不做通用转发，
 *     否则域名一旦泄露就会被当成免费 TMDB 客户端刷；
 *   - 客户端标记：请求头 X-MB-Client 不匹配直接 403；
 *   - 限流：单 IP 60 次/分 + 全局 600 次/分，超了返回 429，App 侧按熔断处理。
 *
 * 用户自带 Key 时，App 会带 Authorization: Bearer <用户 token>，这里原样透传，
 * 不注入服务端 Key —— 用户消耗的是他自己的额度，我们的 Key 不会被借光。
 */

const UPSTREAM = "https://api.themoviedb.org";

/** 必须与 App 的 TmdbClient.CLIENT_TOKEN 完全一致。 */
const CLIENT_TOKEN = "mobilebox-tmdb-2026";

/**
 * 预告片封面转发：/yt/thumb/{11 位 videoId}
 * ------------------------------------------------------------
 * img.youtube.com 在国内不可达，卡片会只剩深色底 + 播放三角。
 * 这里只转发「固定尺寸的缩略图」这一种小图（每张约 15KB），
 * 并在边缘缓存 30 天 —— 同一张图只会真正回源一次。
 *
 * 注意：Cloudflare 的自助条款不允许用 CDN/Workers 承载「视频」或
 * 「占比过高的图片」，所以这里**只做缩略图**，绝不转发视频流。
 * 体积量级（KB 级、且高度可缓存）属于正常网页资源，不属于该限制场景。
 */
const THUMB_RE = /^\/yt\/thumb\/([A-Za-z0-9_-]{11})$/;

async function serveThumb(key) {
  const cache = caches.default;
  const cacheKey = new Request(`https://thumb.mobilebox.internal/${key}`, { method: "GET" });

  const hit = await cache.match(cacheKey);
  if (hit) return hit;

  let upstream;
  try {
    upstream = await fetch(`https://img.youtube.com/vi/${key}/mqdefault.jpg`, {
      headers: { Accept: "image/*" },
    });
  } catch (error) {
    return new Response("upstream unreachable", { status: 502 });
  }
  if (!upstream.ok) {
    return new Response(`upstream ${upstream.status}`, { status: 502 });
  }

  const headers = new Headers();
  headers.set("Content-Type", upstream.headers.get("Content-Type") || "image/jpeg");
  headers.set("Cache-Control", "public, max-age=2592000, immutable");
  headers.set("X-Thumb-Source", "youtube");

  const out = new Response(upstream.body, { status: 200, headers });
  // 缓存一份副本：第一次回源，之后同样的 key 不再打上游。
  await cache.put(cacheKey, out.clone());
  return out;
}

/** 只放行读接口。新增调用之前先在这里加白名单，别图省事放开通配。 */
const ALLOWED_PATHS = [
  /^\/3\/configuration$/,
  /^\/3\/search\/(movie|tv|multi)$/,
  /^\/3\/(movie|tv)\/\d+$/,
  /^\/3\/(movie|tv)\/\d+\/(credits|images|videos|similar|recommendations)$/,
  /^\/3\/genre\/(movie|tv)\/list$/,
];

const PER_IP_PER_MIN = 60;
const GLOBAL_PER_MIN = 600;

/** isolate 内计数。够挡脚本乱刷；需要跨实例精确限流请换 KV 或 Durable Object。 */
const ipBuckets = new Map();
let globalWindow = { count: 0, resetAt: 0 };

function allow(ip) {
  const now = Date.now();

  if (now > globalWindow.resetAt) globalWindow = { count: 0, resetAt: now + 60_000 };
  if (++globalWindow.count > GLOBAL_PER_MIN) return false;

  const bucket = ipBuckets.get(ip);
  if (!bucket || now > bucket.resetAt) {
    ipBuckets.set(ip, { count: 1, resetAt: now + 60_000 });
  } else if (++bucket.count > PER_IP_PER_MIN) {
    return false;
  }

  // 防止 Map 无限增长：超过 5000 个 IP 时清一轮过期项。
  if (ipBuckets.size > 5000) {
    for (const [key, value] of ipBuckets) {
      if (now > value.resetAt) ipBuckets.delete(key);
    }
  }
  return true;
}

function json(status, payload) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" },
  });
}

export default {
  async fetch(request, env) {
    if (request.method !== "GET" && request.method !== "HEAD") {
      return json(405, { error: "method not allowed" });
    }
    const inbound = new URL(request.url);

    // 缩略图路由要在 JSON 闸门之前处理：图片由 Coil 直接发起，带不了自定义头，
    // 所以改用 query token 鉴权。密钥本身不是秘密（编译进 APK），只用来挡随机扫描。
    const thumb = THUMB_RE.exec(inbound.pathname);
    if (thumb) {
      if (inbound.searchParams.get("t") !== CLIENT_TOKEN) {
        return new Response("forbidden", { status: 403 });
      }
      const thumbIp = request.headers.get("CF-Connecting-IP") || "unknown";
      if (!allow(thumbIp)) {
        return new Response("too many requests", { status: 429 });
      }
      return serveThumb(thumb[1]);
    }

    if (request.headers.get("X-MB-Client") !== CLIENT_TOKEN) {
      return json(403, { error: "forbidden" });
    }

    if (!ALLOWED_PATHS.some((re) => re.test(inbound.pathname))) {
      return json(404, { error: "path not allowed" });
    }

    const ip = request.headers.get("CF-Connecting-IP") || "unknown";
    if (!allow(ip)) {
      return json(429, { error: "too many requests" });
    }

    const upstream = new URL(UPSTREAM + inbound.pathname);
    inbound.searchParams.forEach((value, key) => upstream.searchParams.set(key, value));

    const headers = new Headers({ Accept: "application/json" });
    const userAuth = request.headers.get("Authorization");

    if (userAuth && /^Bearer\s+\S+/i.test(userAuth)) {
      // 用户自带 v4 Token：透传，不消耗服务端额度。
      headers.set("Authorization", userAuth);
    } else if (env.TMDB_TOKEN) {
      headers.set("Authorization", `Bearer ${env.TMDB_TOKEN}`);
    } else if (env.TMDB_KEY) {
      // v3 Key 走 query；App 若已带 api_key（用户用 v3 key + 直连路径），这里不覆盖。
      if (!upstream.searchParams.has("api_key")) {
        upstream.searchParams.set("api_key", env.TMDB_KEY);
      }
    } else {
      return json(500, { error: "server key not configured" });
    }

    const started = Date.now();
    let response;
    try {
      response = await fetch(upstream.toString(), { headers, method: request.method });
    } catch (error) {
      return json(502, { error: "upstream unreachable", detail: String(error).slice(0, 120) });
    }

    // 透传上游 body 与状态码：App 需要 401/429/5xx 的原始语义来做熔断与 Key 失效判定。
    const body = await response.text();
    const out = new Response(body, {
      status: response.status,
      headers: {
        "Content-Type": response.headers.get("Content-Type") || "application/json; charset=utf-8",
        // 不让中间层缓存：用户 key 与服务端 key 的响应不能混。
        "Cache-Control": "no-store",
        "X-Proxy-Upstream-Ms": String(Date.now() - started),
      },
    });
    return out;
  },
};
