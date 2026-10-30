import test from 'node:test';
import assert from 'node:assert/strict';
import { buildReport, summaryText, redactText, scrub } from '../public/report.mjs';

test('Reports never contain API keys, tokens or password fields', () => {
  const report = buildReport({
    native: { app: { version: '0.5.0' }, errors: [{ area: 'ai', message: 'bad key AIzaSyA1234567890abcdefghijklmnop' }] },
    preferences: { lensName: 'kit', apiKey: 'AIzaSyA1234567890abcdefghijklmnop', password: 'x' },
    history: [{ at: 1, summary: 'token sk-proj-abcdefghijklmnop1234' }],
    jsLog: [{ at: 1, area: 'js', message: 'ghp_abcdefghijklmnopqrstuvwxyz123456' }]
  });
  const text = JSON.stringify(report);
  assert.doesNotMatch(text, /AIza|sk-proj|ghp_|"apiKey"|"password"/);
  assert.equal(report.preferences.lensName, 'kit');
  assert.equal(report.format, 'framewise-test-report/1');
});

test('Summary lists versions, camera and last errors', () => {
  const s = summaryText({ app: { version: '0.5.0' }, device: { manufacturer: 'samsung', model: 'SM-S711B', android: '15' }, camera: { model: 'Canon EOS 2000D', connection: 'usb' }, errors: [{ area: 'usb', message: 'no answer' }], shotHistory: [1, 2] });
  assert.match(s, /0\.5\.0/); assert.match(s, /Android 15/); assert.match(s, /Canon EOS 2000D \(usb\)/); assert.match(s, /\[usb\] no answer/);
});

test('Scrub limits depth and size', () => {
  let deep = {}; let cur = deep; for (let i = 0; i < 20; i++) { cur.a = {}; cur = cur.a; }
  assert.ok(JSON.stringify(scrub(deep)).length < 200);
  assert.equal(scrub('x'.repeat(10000)).length, 4000);
  assert.equal(redactText(null), '');
});

test('Reports are trimmed to fit the 64 KB relay, keeping errors', async () => {
  const { fitReport, RELAY_MAX_BYTES } = await import('../public/report.mjs');
  const big = { format: 'framewise-test-report/1', errors: [{ m: 'boom' }], events: Array.from({ length: 150 }, (_, i) => ({ i, m: 'e'.repeat(200) })), shotHistory: Array.from({ length: 200 }, () => ({ tip: 'x'.repeat(400) })), jsLog: [] };
  const fitted = fitReport(big);
  assert.ok(new TextEncoder().encode(JSON.stringify(fitted)).length <= RELAY_MAX_BYTES);
  assert.deepEqual(fitted.errors, big.errors);
  assert.equal(fitted.trimmed, true);
  const small = { format: 'framewise-test-report/1', errors: [] };
  assert.deepEqual(fitReport(small), small);
});

test('Send test report posts to the relay; the share sheet is "Share another way"', async () => {
  const { readFile } = await import('node:fs/promises');
  const html = await readFile(new URL('../public/index.html', import.meta.url), 'utf8');
  const js = await readFile(new URL('../public/app.js', import.meta.url), 'utf8');
  assert.match(html, /id="reportSendNow" class="primary">Yes, send</);
  assert.match(html, /id="shareReportButton"[^>]*>Share another way/);
  assert.match(html, /goes automatically to the Framewise developer/);
  assert.doesNotMatch(html, /Nothing is sent unless you choose an app/);
  assert.match(js, /window\.Android\.uploadReport\(/);
  assert.match(js, /Sent, thanks!/);
});
