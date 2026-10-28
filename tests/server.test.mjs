import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { createAppServer, SCENE_SCHEMA } from '../server.mjs';

const PNG = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jhXcAAAAASUVORK5CYII=';
const SCENE = {
  summary: 'A stationary test subject near a window.',
  subjectMotion: 'unknown',
  lighting: 'unknown',
  focusAdvice: 'Place the focus point on the subject.',
  observations: ['The background is brighter than the subject.'],
  limitations: ['A preview cannot establish RAW highlight headroom.'],
};
const payloadFor = (scene = SCENE) => ({
  status: 'completed',
  output: [{ type: 'message', content: [{ type: 'output_text', text: JSON.stringify(scene) }] }],
});
const validBody = () => ({ image: PNG, context: { goal: 'portrait', locks: { aperture: true } }, consent: true });

async function start(t, options = {}) {
  const server = createAppServer(options);
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  t.after(() => new Promise((resolve) => {
    server.close(resolve);
    server.closeAllConnections();
  }));
  const base = `http://127.0.0.1:${server.address().port}`;
  const request = async (route, body, extra = {}) => {
    const response = await fetch(`${base}${route}`, {
      method: body === undefined ? 'GET' : 'POST',
      headers: { ...(body === undefined ? {} : { Origin: base, 'Content-Type': 'application/json' }), ...extra },
      ...(body === undefined ? {} : { body: typeof body === 'string' ? body : JSON.stringify(body) }),
    });
    const text = await response.text();
    let json;
    try { json = JSON.parse(text); } catch { json = undefined; }
    return { status: response.status, headers: response.headers, text, json };
  };
  return { base, request };
}

test('status discloses availability, never the configured API key', async (t) => {
  const { request } = await start(t, { provider: 'openai', apiKey: 'sensitive-test-key' });
  const result = await request('/api/status');
  assert.equal(result.status, 200);
  assert.deepEqual(result.json, { aiConfigured: true, model: 'gpt-4.1-mini', provider: 'openai' });
  assert.doesNotMatch(result.text, /sensitive-test-key/);
  assert.equal(result.headers.get('access-control-allow-origin'), null);
  assert.equal(result.headers.get('cache-control'), 'no-store');
  assert.equal(result.headers.get('x-content-type-options'), 'nosniff');
});

test('API key can be configured and cleared in memory', async (t) => {
  const { request } = await start(t, { provider: 'openai' });
  assert.equal((await request('/api/status')).json.aiConfigured, false);
  const result = await request('/api/config', { apiKey: 'test-secret', model: 'gpt-4.1-mini' });
  assert.equal(result.status, 200);
  assert.equal(result.json.aiConfigured, true);
  assert.doesNotMatch(result.text, /test-secret/);
  assert.equal((await request('/api/config', { apiKey: '' })).json.aiConfigured, false);
  assert.equal((await request('/api/config', { apiKey: 'a key with spaces' })).status, 400);
  assert.equal((await request('/api/config', { apiKey: '', model: 'https://other-site.test' })).status, 400);
});

test('analysis without a key gives a useful setup error and makes no request', async (t) => {
  let called = false;
  const { request } = await start(t, { provider: 'openai', fetchImpl: async () => { called = true; } });
  const result = await request('/api/analyze', validBody());
  assert.equal(result.status, 503);
  assert.match(result.json.error, /API key/);
  assert.equal(called, false);
});

test('requires explicit frame consent and valid bounded image/context', async (t) => {
  const { request } = await start(t, { provider: 'openai', apiKey: 'key', fetchImpl: () => assert.fail('must not call provider') });
  assert.equal((await request('/api/analyze', { ...validBody(), consent: false })).status, 400);
  assert.equal((await request('/api/analyze', { ...validBody(), image: 'https://example.com/image.jpg' })).status, 400);
  assert.equal((await request('/api/analyze', { ...validBody(), image: 'data:image/png;base64,aGVsbG8=' })).status, 400);
  assert.equal((await request('/api/analyze', { ...validBody(), context: [] })).status, 400);
  assert.equal((await request('/api/analyze', { ...validBody(), context: { huge: 'x'.repeat(40_000) } })).status, 413);
  const tooLarge = Buffer.alloc(2 * 1024 * 1024 + 1);
  tooLarge.set([137, 80, 78, 71, 13, 10, 26, 10]);
  assert.equal((await request('/api/analyze', { ...validBody(), image: `data:image/png;base64,${tooLarge.toString('base64')}` })).status, 413);
});

test('calls only the fixed Responses endpoint with structured output and storage disabled', async (t) => {
  let captured;
  const { request } = await start(t, { provider: 'openai',
    apiKey: 'private-key',
    fetchImpl: async (url, init) => {
      captured = { url, init, body: JSON.parse(init.body) };
      return { ok: true, json: async () => payloadFor() };
    },
  });
  const result = await request('/api/analyze', validBody());
  assert.equal(result.status, 200);
  assert.deepEqual(result.json, { scene: SCENE });
  assert.equal(captured.url, 'https://api.openai.com/v1/responses');
  assert.equal(captured.init.headers.Authorization, 'Bearer private-key');
  assert.equal(captured.body.store, false);
  assert.equal(captured.body.model, 'gpt-4.1-mini');
  assert.equal(captured.body.text.format.strict, true);
  assert.deepEqual(captured.body.text.format.schema, SCENE_SCHEMA);
  assert.equal(captured.body.input[0].content[1].image_url, PNG);
  assert.equal(captured.body.tools, undefined);
  assert.match(captured.body.instructions, /single frame cannot establish subject speed/);
  assert.match(captured.body.instructions, /Do not provide numeric shutter/);
  assert.equal(captured.init.signal instanceof AbortSignal, true);
  assert.doesNotMatch(result.text, /private-key/);
});

test('rejects malformed, refused, or incomplete AI responses', async (t) => {
  const cases = [
    [{ status: 'incomplete', output: [] }, 502],
    [{ status: 'completed', output: [{ content: [{ type: 'refusal', refusal: 'Cannot assess.' }] }] }, 422],
    [{ status: 'completed', output: [{ content: [{ type: 'output_text', text: '{broken' }] }] }, 502],
    [payloadFor({ ...SCENE, subjectMotion: 'supersonic' }), 502],
    [payloadFor({ ...SCENE, extra: 'unexpected' }), 502],
    [payloadFor({ ...SCENE, observations: [42] }), 502],
    [payloadFor({ summary: 'Only one field' }), 502],
  ];
  for (const [payload, expected] of cases) {
    await t.test(`returns ${expected} for ${JSON.stringify(payload).slice(0, 75)}`, async (t) => {
      const { request } = await start(t, { provider: 'openai', apiKey: 'key', fetchImpl: async () => ({ ok: true, json: async () => payload }) });
      const result = await request('/api/analyze', validBody());
      assert.equal(result.status, expected);
      assert.equal(typeof result.json.error, 'string');
    });
  }
});

test('provider errors are readable and do not expose upstream content', async (t) => {
  const { request } = await start(t, { provider: 'openai', apiKey: 'key', fetchImpl: async () => ({ ok: false, status: 401, json: async () => ({ error: 'private-provider-content' }) }) });
  const result = await request('/api/analyze', validBody());
  assert.equal(result.status, 502);
  assert.match(result.json.error, /API key/);
  assert.doesNotMatch(result.text, /private-provider-content/);
});

test('does not allow overlapping cloud analyses', async (t) => {
  let release;
  let started;
  const startedPromise = new Promise((resolve) => { started = resolve; });
  const held = new Promise((resolve) => { release = resolve; });
  const { request } = await start(t, { provider: 'openai',
    apiKey: 'key', fetchImpl: async () => {
      started();
      await held;
      return { ok: true, json: async () => payloadFor() };
    },
  });
  const first = request('/api/analyze', validBody());
  await startedPromise;
  const second = await request('/api/analyze', validBody());
  assert.equal(second.status, 429);
  release();
  assert.equal((await first).status, 200);
});

test('requires local same-origin JSON mutations', async (t) => {
  const { request } = await start(t, { provider: 'openai' });
  assert.equal((await request('/api/config', { apiKey: 'key' }, { Origin: 'https://evil.example' })).status, 403);
  assert.equal((await request('/api/config', { apiKey: 'key' }, { Origin: 'null' })).status, 403);
  assert.equal((await request('/api/config', { apiKey: 'key' }, { Origin: '' })).status, 403);
  assert.equal((await request('/api/config', { apiKey: 'key' }, { 'Sec-Fetch-Site': 'cross-site' })).status, 403);
  assert.equal((await request('/api/config', { apiKey: 'key' }, { 'Content-Type': 'text/plain' })).status, 415);
  assert.equal((await request('/api/config', '{bad json')).status, 400);
  assert.equal((await request('/api/config', [])).status, 400);
});

test('rejects hostile Host headers and never serves source or arbitrary paths', async (t) => {
  const { base, request } = await start(t, { provider: 'openai' });
  const hostile = await new Promise((resolve, reject) => {
    const req = http.get(`${base}/api/status`, { headers: { Host: 'evil.example' } }, (res) => {
      res.resume();
      res.on('end', () => resolve(res.statusCode));
    });
    req.on('error', reject);
  });
  assert.equal(hostile, 403);
  for (const route of ['/server.mjs', '/package.json', '/.env', '/tests/server.test.mjs', '/..%2fserver.mjs', '/%2e%2e/server.mjs']) {
    const result = await request(route);
    assert.equal(result.status, 404, route);
    assert.doesNotMatch(result.text, /INSTRUCTIONS|createAppServer/);
  }
});

const geminiPayload = (scene = SCENE) => ({ candidates: [{ finishReason: 'STOP', content: { role: 'model', parts: [{ text: JSON.stringify(scene) }] } }] });

test('Gemini and its free-tier-capable model are the default without a configured key', async (t) => {
  const { request } = await start(t);
  const status = await request('/api/status');
  assert.deepEqual(status.json, { aiConfigured: false, model: 'gemini-3.1-flash-lite', provider: 'gemini' });
  assert.equal((await request('/api/analyze', validBody())).status, 503);
});

test('Gemini sends only to its fixed endpoint with the key in a header and an inline image', async (t) => {
  let captured;
  const { request } = await start(t, { apiKey: 'private-gemini-test-key', fetchImpl: async (url, init) => {
    captured = { url, init, body: JSON.parse(init.body) };
    return { ok: true, json: async () => geminiPayload() };
  } });
  const result = await request('/api/analyze', validBody());
  assert.equal(result.status, 200);
  assert.deepEqual(result.json, { scene: SCENE });
  assert.equal(captured.url, 'https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent');
  assert.equal(captured.init.headers['x-goog-api-key'], 'private-gemini-test-key');
  assert.equal(captured.init.headers.Authorization, undefined);
  assert.doesNotMatch(captured.url + captured.init.body + result.text, /private-gemini-test-key/);
  assert.equal(captured.body.contents[0].role, 'user');
  assert.deepEqual(captured.body.contents[0].parts[1].inlineData, { mimeType: 'image/png', data: PNG.split(',')[1] });
  assert.match(captured.body.contents[0].parts[0].text, /Photographer context/);
  assert.match(captured.body.systemInstruction.parts[0].text, /Sony a6100 still-photo/);
  assert.match(captured.body.systemInstruction.parts[0].text, /single frame cannot establish subject speed/);
  assert.match(captured.body.systemInstruction.parts[0].text, /Do not provide numeric shutter/);
  assert.deepEqual(captured.body.generationConfig, { responseMimeType: 'application/json', responseJsonSchema: SCENE_SCHEMA, maxOutputTokens: 1200 });
  assert.equal(captured.body.tools, undefined);
  assert.equal(captured.init.signal instanceof AbortSignal, true);
});

test('Gemini parses final text without exposing thought parts', async (t) => {
  const payload = geminiPayload();
  payload.candidates[0].content.parts.unshift({ thought: true, text: 'Private internal material that is not JSON.' });
  const { request } = await start(t, { apiKey: 'test', fetchImpl: async () => ({ ok: true, json: async () => payload }) });
  const result = await request('/api/analyze', validBody());
  assert.equal(result.status, 200);
  assert.deepEqual(result.json.scene, SCENE);
  assert.doesNotMatch(result.text, /Private internal material/);
});

test('Gemini rejects blocked, partial, malformed, ambiguous, and cross-provider responses', async (t) => {
  const cases = [
    [null, 502],
    [{ promptFeedback: { blockReason: 'SAFETY' } }, 422],
    [{ candidates: [{ finishReason: 'SAFETY' }] }, 422],
    [{ candidates: [{ finishReason: 'MAX_TOKENS', content: { parts: [{ text: JSON.stringify(SCENE) }] } }] }, 502],
    [{ candidates: [] }, 502],
    [{ candidates: [...geminiPayload().candidates, ...geminiPayload().candidates] }, 502],
    [{ candidates: [{ finishReason: 'STOP', content: { parts: [{ thought: true, text: JSON.stringify(SCENE) }] } }] }, 502],
    [{ candidates: [{ finishReason: 'STOP', content: { parts: [{ functionCall: { name: 'unexpected' } }, { text: JSON.stringify(SCENE) }] } }] }, 502],
    [{ candidates: [{ finishReason: 'STOP', content: { parts: [{ text: '{broken' }] } }] }, 502],
    [geminiPayload({ ...SCENE, exposureISO: 100 }), 502],
    [geminiPayload({ ...SCENE, observations: [42] }), 502],
    [payloadFor(), 502],
  ];
  for (const [index, [payload, expected]] of cases.entries()) {
    await t.test(`Gemini response case ${index + 1}`, async (t) => {
      const { request } = await start(t, { apiKey: 'test', fetchImpl: async () => ({ ok: true, json: async () => payload }) });
      const result = await request('/api/analyze', validBody());
      assert.equal(result.status, expected);
      assert.equal(typeof result.json.error, 'string');
    });
  }
});

test('OpenAI mode cannot interpret a Gemini response as its own result', async (t) => {
  const { request } = await start(t, { provider: 'openai', apiKey: 'test', fetchImpl: async () => ({ ok: true, json: async () => geminiPayload() }) });
  assert.equal((await request('/api/analyze', validBody())).status, 502);
});

test('Provider changes choose the proper default model and never silently reuse the previous key', async (t) => {
  const { request } = await start(t, { apiKey: 'first-test-key' });
  const unchanged = await request('/api/config', { provider: 'openai', apiKey: 'first-test-key' });
  assert.equal(unchanged.status, 400);
  assert.equal((await request('/api/status')).json.provider, 'gemini');
  const switched = await request('/api/config', { provider: 'openai', apiKey: 'different-test-key' });
  assert.equal(switched.status, 200);
  assert.deepEqual(switched.json, { provider: 'openai', model: 'gpt-4.1-mini', aiConfigured: true });
  const cleared = await request('/api/config', { provider: 'gemini', apiKey: '' });
  assert.deepEqual(cleared.json, { provider: 'gemini', model: 'gemini-3.1-flash-lite', aiConfigured: false });
});

test('Configuration rejects provider confusion, cross-provider keys, and Gemini URL injection atomically', async (t) => {
  const { request } = await start(t);
  const bodies = [
    { apiKey: 'test', provider: 'other' },
    { apiKey: 'test', provider: null },
    { apiKey: 'sk-openai-test', provider: 'gemini' },
    { apiKey: 'AIzaGeminiTest', provider: 'openai' },
    { apiKey: 'test', provider: 'gemini', model: '../models/other' },
    { apiKey: 'test', provider: 'gemini', model: 'gemini:otherMethod' },
    { apiKey: 'test', provider: 'gemini', model: 'gemini?key=other' },
    { apiKey: 'test', provider: 'gemini', model: 'https://example.com' },
    { provider: 'gemini' },
  ];
  for (const body of bodies) assert.equal((await request('/api/config', body)).status, 400);
  assert.deepEqual((await request('/api/status')).json, { provider: 'gemini', model: 'gemini-3.1-flash-lite', aiConfigured: false });
  assert.throws(() => createAppServer({ provider: 'other' }), /Choose Gemini or OpenAI/);
});

test('Gemini quota errors mention free-tier limits without a paid fallback or upstream secrets', async (t) => {
  let calls = 0;
  const { request } = await start(t, { apiKey: 'test', fetchImpl: async () => {
    calls++;
    return { ok: false, status: 429, json: async () => ({ error: 'sensitive-upstream-details' }) };
  } });
  const result = await request('/api/analyze', validBody());
  assert.equal(result.status, 429);
  assert.match(result.json.error, /free-tier limits/);
  assert.match(result.json.error, /No paid fallback/);
  assert.doesNotMatch(result.text, /sensitive-upstream-details/);
  assert.equal(calls, 1);
});

test('Clearing the key aborts in-flight analysis, rejects its stale result, and prevents later transmission', async (t) => {
  let release, started, signal, calls = 0;
  const startedPromise = new Promise(resolve => { started = resolve; });
  const held = new Promise(resolve => { release = resolve; });
  const { request } = await start(t, { apiKey: 'test', fetchImpl: async (_url, init) => {
    calls++; signal = init.signal; started();
    await held; // Deliberately ignore abort to test the generation guard too.
    return { ok: true, json: async () => geminiPayload() };
  } });
  const analysis = request('/api/analyze', validBody());
  await startedPromise;
  const cleared = await request('/api/config', { apiKey: '' });
  assert.equal(cleared.json.aiConfigured, false);
  assert.equal(signal.aborted, true);
  release();
  const stale = await analysis;
  assert.equal(stale.status, 409);
  assert.match(stale.json.error, /setup changed/);
  assert.equal((await request('/api/analyze', validBody())).status, 503);
  assert.equal(calls, 1);
});

test('Changing providers aborts the old request and accepts a new provider request safely', async (t) => {
  let release, started, firstSignal, calls = 0;
  const startedPromise = new Promise(resolve => { started = resolve; });
  const held = new Promise(resolve => { release = resolve; });
  const targets = [];
  const { request } = await start(t, { apiKey: 'gemini-test', fetchImpl: async (url, init) => {
    targets.push(url); calls++;
    if (calls === 1) { firstSignal = init.signal; started(); await held; return { ok: true, json: async () => geminiPayload() }; }
    assert.equal(init.headers.Authorization, 'Bearer openai-test');
    return { ok: true, json: async () => payloadFor() };
  } });
  const old = request('/api/analyze', validBody());
  await startedPromise;
  assert.equal((await request('/api/config', { apiKey: 'openai-test', provider: 'openai' })).status, 200);
  assert.equal(firstSignal.aborted, true);
  assert.equal((await request('/api/analyze', validBody())).status, 200);
  release();
  assert.equal((await old).status, 409);
  assert.equal(targets.length, 2);
  assert.match(targets[0], /^https:\/\/generativelanguage\.googleapis\.com\//);
  assert.equal(targets[1], 'https://api.openai.com/v1/responses');
});
