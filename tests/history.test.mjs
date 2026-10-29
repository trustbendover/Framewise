import test from 'node:test';
import assert from 'node:assert/strict';
import { makeEntry, loadHistory, addEntry, clearHistory, summarizeHistory, HISTORY_LIMIT, HISTORY_KEY } from '../public/history.mjs';

function storage() { const m = new Map(); return { getItem: k => m.has(k) ? m.get(k) : null, setItem: (k, v) => m.set(k, String(v)), removeItem: k => m.delete(k) }; }
const input = { goal: 'action', focalLength: 70, current: { shutter: 1 / 250, aperture: 5.6, iso: 400 } };

test('Camera settings win over typed values, typed values fill gaps', () => {
  const e = makeEntry({ at: 1, input, camera: { iso: 1600, exposureCompensation: 0.7, mode: 'Aperture Priority' }, scene: { summary: 'ok', observations: ['a'], score: 8 } });
  assert.equal(e.settings.iso, 1600);
  assert.equal(e.settings.aperture, 5.6);
  assert.equal(e.settings.exposureCompensation, 0.7);
  assert.equal(e.settings.mode, 'Aperture Priority');
  assert.equal(e.score, 8);
  assert.equal(makeEntry({ input, scene: { score: 42 } }).score, null);
});

test('History is capped, newest first, and can be cleared', () => {
  const s = storage();
  for (let i = 0; i < HISTORY_LIMIT + 5; i++) addEntry(s, makeEntry({ at: i, input, scene: { summary: String(i) } }));
  const list = loadHistory(s);
  assert.equal(list.length, HISTORY_LIMIT);
  assert.equal(list[0].summary, String(HISTORY_LIMIT + 4));
  clearHistory(s);
  assert.deepEqual(loadHistory(s), []);
});

test('Corrupt storage is ignored', () => {
  const s = storage(); s.setItem(HISTORY_KEY, '{not json');
  assert.deepEqual(loadHistory(s), []);
});

test('Summary covers the last 20 shots with typical settings and recurring issues', () => {
  const list = [];
  for (let i = 0; i < 25; i++) list.push(makeEntry({ at: i, input: { ...input, current: { ...input.current, iso: i < 20 ? 800 : 6400 } }, scene: { summary: i % 2 ? 'Image looks slightly soft from motion blur.' : 'Nice light.', observations: [], score: 7 } }));
  const sum = summarizeHistory(list);
  assert.equal(sum.shots, 20);
  assert.equal(sum.typical.iso, 800);
  assert.equal(sum.averageScore, 7);
  assert.match(sum.recurringIssues[0], /^blur \(10\/20\)$/);
  assert.equal(summarizeHistory([]), null);
  assert.ok(JSON.stringify(sum).length < 1500, 'summary stays compact for the prompt');
});
