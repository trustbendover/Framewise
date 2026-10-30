import test from 'node:test';
import assert from 'node:assert/strict';
import worker, { clientKey } from './worker.js';
const kv = () => { const m = new Map(); return { m, get: async k => m.get(k) ?? null, put: async (k, v, o) => { m.set(k, v); m.set('ttl:' + k, o?.expirationTtl); } }; };
const post = (env, body, ip = '2a09:bac5:4c7:1::5', type = 'application/json') => worker.fetch(new Request('https://x/report', { method: 'POST', headers: { 'content-type': type, 'cf-connecting-ip': ip }, body }), env);
const ok = JSON.stringify({ format: 'framewise-test-report/1', note: 'token: "abc" sk-abcdefghijklmnopqrstu' });

test('stores valid reports with a 90-day TTL, redacted', async () => {
  const env = { FRAMEWISE_REPORTS: kv() };
  assert.equal((await post(env, ok)).status, 201);
  const key = [...env.FRAMEWISE_REPORTS.m.keys()].find(k => /^report:\d{4}-.*Z:[0-9a-f]{8}$/.test(k));
  assert.ok(key);
  assert.equal(env.FRAMEWISE_REPORTS.m.get('ttl:' + key), 90 * 86400);
  assert.doesNotMatch(env.FRAMEWISE_REPORTS.m.get(key), /sk-abcdef/);
});
test('rejects everything else', async () => {
  const env = { FRAMEWISE_REPORTS: kv() };
  assert.equal((await worker.fetch(new Request('https://x/report'), env)).status, 405);
  assert.equal((await worker.fetch(new Request('https://x/reports/list'), env)).status, 404);
  assert.equal((await post(env, '{"format":"x"}')).status, 400);
  assert.equal((await post(env, 'nope')).status, 400);
  assert.equal((await post(env, ok, undefined, 'text/plain')).status, 415);
  assert.equal((await post(env, JSON.stringify({ format: 'framewise-test-report/1', x: 'a'.repeat(70000) }))).status, 413);
});
test('10 per hour per IP (IPv6 by /48), burst limiter honoured', async () => {
  const env = { FRAMEWISE_REPORTS: kv() };
  for (let i = 0; i < 10; i++) assert.equal((await post(env, ok, `2a09:bac5:4c7:${i}::1`)).status, 201);
  assert.equal((await post(env, ok, '2a09:bac5:4c7:99::1')).status, 429);
  assert.equal((await post(env, ok, '203.0.113.9')).status, 201);
  assert.equal((await post({ FRAMEWISE_REPORTS: kv(), BURST: { limit: async () => ({ success: false }) } }, ok)).status, 429);
  assert.equal(clientKey('2A09:BAC5:04C7:26be::3dc:7b'), '2a09:bac5:4c7::/48');
});
