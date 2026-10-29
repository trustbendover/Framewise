import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
import { recommend, formatShutter } from '../public/engine.mjs';
import * as history from '../public/history.mjs';

// Exercise the actual app event paths with a tiny DOM adapter. Camera hardware,
// drawing, Android permission prompts, and real network calls remain device QA.
const source = await readFile(new URL('../public/app.js', import.meta.url), 'utf8');
function section(start, end) {
  const a = source.indexOf(start), b = source.indexOf(end, a + start.length);
  assert.ok(a >= 0 && b > a, `App test entry points must exist: ${start}`);
  return source.slice(a, b);
}
const updateSelection = section('function updateSelection()', '\nfunction scrollSuggestions');
const runAI = section('async function runAI(', '\nfunction selectValue');
const setImage = section('async function setImage(', '\nfunction updateSourceLabels');
const stopCapture = section('function stopCapture()', "\n$('stopButton')");
const confirm = section("$('confirmButton').onclick=", '\nfunction renderLog');
const selectValue = section('function selectValue(', '\nfunction planText');

function camera(overrides = {}) {
  return { lens: { maxAperture: 5.6, minAperture: 22 }, focalLength: 70,
    current: { shutter: 1 / 250, aperture: 5.6, iso: 400 },
    locks: { shutter: false, aperture: false, iso: false }, goal: 'action',
    support: 'handheld', subjectMotion: 'fast', maxISO: 3200,
    meteringEV: 0, exposureBias: 0, ...overrides };
}

function memoryStorage() { const m = new Map(); return { getItem: k => m.has(k) ? m.get(k) : null, setItem: (k, v) => m.set(k, String(v)), removeItem: k => m.delete(k) }; }

function harness(input = camera()) {
  const nodes = new Map();
  const selected = ['shutter', 'aperture', 'iso'].map(key => ({ dataset: { setting: key }, checked: true }));
  const node = id => {
    if (!nodes.has(id)) nodes.set(id, { value: '', textContent: '', disabled: false,
      tagName: 'INPUT', children: [], options: [], selectedOptions: [{ text: 'Kit zoom' }],
      append(child) { this.children.push(child); if (child.id) nodes.set(child.id, child); },
      remove() { nodes.delete(id); },
      querySelector() { return { textContent: '' }; },
      toDataURL() { return 'data:image/jpeg;base64,TEST'; },
      add(option) { this.options.push(option); } });
    return nodes.get(id);
  };
  for (const [key, id] of [['shutter', 'currentShutter'], ['aperture', 'currentAperture'], ['iso', 'currentISO']]) node(id).value = String(input.current[key]);
  const state = { source: 'photo', sourceGeneration: 0, decodeSequence: 0, lastImageSequence: 0,
    timestamp: Date.now(), visible: true, revision: 0, busy: false, locks: input.locks,
    rec: { ...recommend(input), input, scene: null, revision: 0 }, log: [] };
  const env = { state, recommend, formatShutter, $: id => id === 'selectionWarning' ? nodes.get(id) : node(id),
    document: { querySelectorAll: () => selected.filter(x => x.checked),
      createElement: () => ({ id: '', className: '', textContent: '', remove() { nodes.delete(this.id); } }) },
    Option: class { constructor(text, value) { this.text = text; this.value = value; } },
    getInput: () => input, canAnalyze() {}, status() {}, toast() {}, stopLiveAI() {},
    renderLog() {}, updateSourceLabels() {}, drawSource() {}, native: false,
    localStorage: memoryStorage(), ...history, renderHistory() {},
    invalidate() { state.revision++; state.rec = null; },
    scrollSuggestions() { env.scrolls++; }, scrolls: 0,
    renderRecommendation(rec) { state.rec = rec; env.renders++; }, renders: 0,
    requestAnalysis: async () => ({ summary: 'A moving subject.', observations: [], limitations: [] }) };
  vm.createContext(env);
  return { env, state, node, nodes, selected, run(code) { vm.runInContext(code, env); } };
}

test('Deselecting a coupled shutter change warns about resulting exposure and motion', () => {
  const h = harness(); h.run(updateSelection);
  h.env.updateSelection();
  assert.equal(h.nodes.get('selectionWarning'), undefined);
  h.selected.find(x => x.dataset.setting === 'shutter').checked = false;
  h.env.updateSelection();
  assert.match(h.nodes.get('selectionWarning').textContent, /2.00 EV brighter/);
  assert.match(h.nodes.get('selectionWarning').textContent, /locked shutter/);
});

test('Confirmation updates only selected unlocked settings and clears the old meter', () => {
  const p = camera({ locks: { shutter: true, aperture: false, iso: false } });
  const h = harness(p);
  h.selected.find(x => x.dataset.setting === 'aperture').checked = false;
  h.node('meterEV').value = '0';
  const proposedISO = h.state.rec.settings.iso;
  h.run(selectValue + '\n' + confirm);
  h.node('confirmButton').onclick();
  assert.equal(Number(h.node('currentShutter').value), p.current.shutter);
  assert.equal(Number(h.node('currentAperture').value), p.current.aperture);
  assert.equal(Number(h.node('currentISO').value), proposedISO);
  assert.equal(h.node('meterEV').value, '');
  assert.equal(h.state.rec, null);
});

test('An AI response from a previous capture source cannot render', async () => {
  const h = harness(); let resolve;
  h.env.requestAnalysis = () => new Promise(done => { resolve = done; });
  h.run(runAI);
  const pending = h.env.runAI();
  h.state.sourceGeneration++;
  resolve({ summary: 'Old source.', observations: [], limitations: [] });
  await pending;
  assert.equal(h.env.renders, 0);
  assert.equal(h.state.busy, false);
  assert.equal(h.node('suggestButton').disabled, false);
});

test('A live video source hidden while AI is running cannot render a live reading', async () => {
  const h = harness(); let resolve;
  h.state.source = 'video';
  h.env.requestAnalysis = () => new Promise(done => { resolve = done; });
  h.run(runAI);
  const pending = h.env.runAI();
  h.state.visible = false;
  resolve({ summary: 'Hidden source.', observations: [], limitations: [] });
  await pending;
  assert.equal(h.env.renders, 0);
});

test('A camera frame still renders when the app view is hidden', async () => {
  const h = harness(); let resolve;
  h.state.source = 'camera';
  h.env.requestAnalysis = () => new Promise(done => { resolve = done; });
  h.run(runAI);
  const pending = h.env.runAI();
  h.state.visible = false;
  resolve({ summary: 'Camera frame.', observations: [], limitations: [] });
  await pending;
  assert.equal(h.env.renders, 1);
});

test('A manual AI result scrolls into view while periodic results do not jump', async () => {
  const h = harness(); h.run(runAI);
  await h.env.runAI();
  assert.equal(h.env.scrolls, 1);
  await h.env.runAI(true);
  assert.equal(h.env.renders, 2);
  assert.equal(h.env.scrolls, 1);
});

test('A delayed image decode from a stopped source cannot overwrite a newer frame', async () => {
  const h = harness(); const images = [];
  h.env.Image = class { constructor() { images.push(this); } };
  h.run(setImage);
  const old = h.env.setImage('old', 'sony', 10);
  h.state.sourceGeneration++;
  const fresh = h.env.setImage('fresh', 'photo', 20);
  images[1].onload(); await fresh;
  images[0].onload(); await old;
  assert.equal(h.state.image.src, 'fresh');
  assert.equal(h.state.source, 'photo');
  assert.equal(h.state.timestamp, 20);
});

test('Out-of-order decode completion cannot replace a newer frame in the same source', async () => {
  const h = harness(); const images = [];
  h.env.Image = class { constructor() { images.push(this); } };
  h.run(setImage);
  const old = h.env.setImage('old', 'sony', 10);
  const fresh = h.env.setImage('fresh', 'sony', 20);
  images[1].onload(); await fresh;
  images[0].onload(); await old;
  assert.equal(h.state.timestamp, 20);
});

test('Stopping capture stops tracks, invalidates the live plan, and labels a still reference', () => {
  const h = harness(); let stopped = 0;
  h.state.source = 'video'; h.state.stream = { getTracks: () => [{ stop() { stopped++; } }] };
  h.state.video = { srcObject: {} };
  h.run(stopCapture); h.env.stopCapture();
  assert.equal(stopped, 1);
  assert.equal(h.state.visible, false);
  assert.equal(h.state.source, 'photo');
  assert.equal(h.state.sourceGeneration, 1);
  assert.equal(h.state.rec, null);
  assert.equal(h.state.video, null);
});

test('Each analysed shot is saved locally and the next request carries a history summary', async () => {
  const h = harness(); const contexts = [];
  h.env.requestAnalysis = async (image, context) => { contexts.push(context); return { summary: 'Slight motion blur on the subject.', observations: ['Hold steadier or use a faster shutter.'], limitations: [], score: 6 }; };
  h.state.source = 'camera'; h.state.cameraSettings = { iso: 800, shutter: 1 / 125, aperture: 4, exposureCompensation: -0.3, mode: 'Manual Exposure' }; h.state.cameraSettingsAt = Date.now();
  h.run(runAI);
  await h.env.runAI();
  assert.equal(contexts[0].history, null);
  const saved = history.loadHistory(h.env.localStorage);
  assert.equal(saved.length, 1);
  assert.equal(saved[0].settings.iso, 800);
  assert.equal(saved[0].settings.fromCamera, true);
  assert.equal(saved[0].score, 6);
  await h.env.runAI(true);
  assert.equal(contexts[1].history.shots, 1);
  assert.equal(contexts[1].history.typical.iso, 800);
});

test('Regression: an automatic "available" status shows the update popup without a manual check', () => {
  const start = source.indexOf('function applyUpdateStatus(update){');
  const end = source.indexOf('\n', start);
  const nodes = new Map();
  const node = id => { if (!nodes.has(id)) nodes.set(id, { id, hidden: true, textContent: '', disabled: false, replaceChildren() {}, append() {} }); return nodes.get(id); };
  const env = { $: node, state: {}, renderNotes() {}, renderSections() {}, parseReleaseNotes: () => ({ bullets: [], extra: [] }), Array };
  vm.createContext(env);
  vm.runInContext(source.slice(start, end), env);
  env.applyUpdateStatus({ state: 'available', message: 'Framewise 0.5.1 is available.', latestVersion: '0.5.1', currentVersion: '0.5.0', notes: '- fix' });
  assert.equal(node('updateBanner').hidden, false);
  assert.match(node('updateBannerText').textContent, /0\.5\.1/);
  assert.equal(node('installUpdateButton').hidden, false);
});
