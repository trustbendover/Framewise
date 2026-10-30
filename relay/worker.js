// Framewise test-report relay. Only POST /report is public; reports are read with the Cloudflare API.
const MAX_BYTES = 64 * 1024;
const LIMIT_PER_HOUR = 10; // KV counter per IP and hour (KV is eventually consistent, so this is approximate)
const TTL = 90 * 24 * 3600;
const SECRETS = [/sk-[A-Za-z0-9_-]{16,}/g, /AIza[0-9A-Za-z_-]{30,}/g, /xai-[A-Za-z0-9_-]{16,}/g, /gh[pousr]_[A-Za-z0-9]{20,}/g,
  /("?(?:api[_-]?key|password|passphrase|token|secret)"?\s*[:=]\s*")[^"]*(")/gi];
const reply = (status, body) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json', 'cache-control': 'no-store' } });

export function redact(text) {
  return SECRETS.reduce((t, re) => t.replace(re, (m, a, b) => (a !== undefined && b !== undefined && typeof a === 'string' ? `${a}[redacted]${b}` : '[redacted]')), text);
}

/** IPv4 as is; IPv6 by its /48 network, because phones rotate their IPv6 privacy addresses. */
export function clientKey(ip) {
  if (!ip.includes(':')) return ip;
  const [head, tail = ''] = ip.split('::');
  const h = head.split(':').filter(Boolean), t = tail.split(':').filter(Boolean);
  const full = [...h, ...Array(Math.max(0, 8 - h.length - t.length)).fill('0'), ...t];
  return full.slice(0, 3).map(x => x.toLowerCase().replace(/^0+(?=.)/, '')).join(':') + '::/48';
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname !== '/report') return reply(404, { error: 'not found' });
    if (request.method !== 'POST') return reply(405, { error: 'POST only' });
    if (!/^application\/json\b/i.test(request.headers.get('content-type') || '')) return reply(415, { error: 'JSON only' });
    const declared = Number(request.headers.get('content-length') || 0);
    if (declared > MAX_BYTES) return reply(413, { error: 'report too large' });

    const ip = clientKey(request.headers.get('cf-connecting-ip') || 'unknown');
    // Burst guard (strict, per Cloudflare location): 3 reports a minute per IP.
    if (env.BURST) { const { success } = await env.BURST.limit({ key: ip }); if (!success) return reply(429, { error: 'too many reports, try again later' }); }
    const hour = new Date().toISOString().slice(0, 13);
    const rlKey = `rl:${ip}:${hour}`;
    const used = Number(await env.FRAMEWISE_REPORTS.get(rlKey)) || 0;
    if (used >= LIMIT_PER_HOUR) return reply(429, { error: 'too many reports, try again later' });

    const buf = await request.arrayBuffer();
    if (buf.byteLength > MAX_BYTES) return reply(413, { error: 'report too large' });
    let report;
    try { report = JSON.parse(new TextDecoder().decode(buf)); } catch { return reply(400, { error: 'invalid JSON' }); }
    if (!report || typeof report !== 'object' || Array.isArray(report) || report.format !== 'framewise-test-report/1')
      return reply(400, { error: 'not a Framewise test report' });

    await env.FRAMEWISE_REPORTS.put(rlKey, String(used + 1), { expirationTtl: 3700 });
    const now = new Date().toISOString();
    const id = crypto.randomUUID().slice(0, 8);
    const key = `report:${now}:${id}`;
    const stored = redact(JSON.stringify({ receivedAt: now, country: request.cf?.country || null, report }));
    await env.FRAMEWISE_REPORTS.put(key, stored, { expirationTtl: TTL });
    return reply(201, { ok: true, id });
  },
};
