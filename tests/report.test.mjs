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
