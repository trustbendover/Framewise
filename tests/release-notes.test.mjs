import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, readdir } from 'node:fs/promises';
import vm from 'node:vm';

const root = new URL('../', import.meta.url);
const gradle = await readFile(new URL('android/app/build.gradle', root), 'utf8');
const versionName = gradle.match(/versionName '([^']+)'/)[1];
const tag = 'v' + versionName.replace(/\.0$/, '');
const app = await readFile(new URL('public/app.js', root), 'utf8');
function section(start, end) {
  const a = app.indexOf(start), b = app.indexOf(end, a + start.length);
  assert.ok(a >= 0 && b > a, `missing ${start}`);
  return app.slice(a, b);
}

test('Every app version has friendly release notes', async () => {
  const notes = await readFile(new URL(`release-notes/${tag}.md`, root), 'utf8');
  const bullets = notes.split('\n').filter(line => line.startsWith('- '));
  assert.ok(bullets.length >= 2, 'at least two bullet points');
  for (const bullet of bullets) assert.ok(bullet.length <= 160, `keep it short: ${bullet}`);
  assert.doesNotMatch(notes, /versionCode/i, 'the workflow adds the versionCode line itself');
});

test('The in-app "What\'s new" card matches the release notes of this version', async () => {
  const card = JSON.parse(await readFile(new URL('public/whats-new.json', root), 'utf8'));
  assert.equal(card.version, versionName);
  const notes = await readFile(new URL(`release-notes/${tag}.md`, root), 'utf8');
  const bullets = notes.split('\n').filter(line => line.startsWith('- ')).map(line => line.slice(2).trim());
  assert.deepEqual(card.notes, bullets);
});

test('Every release-notes file is listed in the changelog', async () => {
  const changelog = await readFile(new URL('CHANGELOG.md', root), 'utf8');
  for (const file of await readdir(new URL('release-notes/', root))) assert.ok(changelog.includes(`release-notes/${file}`), file);
});

test('Release notes shown in the app hide the machine-readable versionCode line', () => {
  const env = {};
  vm.createContext(env);
  vm.runInContext(section('function parseReleaseNotes(', '\nfunction renderNotes'), env);
  const parsed = env.parseReleaseNotes('## What\'s new\n\n- Live view connects straight to your camera.\n- **Faster** updates.\n\nUninstall 0.2 once.\n\n---\nInstall the APK.\n\nversionCode: 4');
  assert.deepEqual([...parsed.bullets], ['Live view connects straight to your camera.', 'Faster updates.']);
  assert.deepEqual([...parsed.extra], ['Uninstall 0.2 once.']);
});

test('Camera settings from getEvent fill in the manual fields once and invalidate old advice', () => {
  const fields = { currentShutter: { value: String(1 / 250) }, currentAperture: { value: '5.6' }, currentISO: { value: '400' }, meterEV: { value: '1' }, cameraAutofill: { checked: true } };
  let invalidations = 0;
  const env = { $: id => fields[id], invalidate() { invalidations++; }, selectValue(id, value) { fields[id].value = String(value); }, Number, Math };
  vm.createContext(env);
  vm.runInContext(section('function applyCameraSettings(', '\nfunction cameraButtons'), env);
  assert.equal(env.applyCameraSettings({ shutter: 1 / 1000, aperture: 8, iso: 800 }), true);
  assert.equal(Number(fields.currentShutter.value), 1 / 1000);
  assert.equal(fields.currentAperture.value, '8');
  assert.equal(fields.currentISO.value, '800');
  assert.equal(fields.meterEV.value, '');
  assert.equal(env.applyCameraSettings({ shutter: 1 / 1000, aperture: 8, iso: 800 }), false, 'unchanged values do not reset advice');
  assert.equal(env.applyCameraSettings({ isoText: 'AUTO' }), false, 'auto ISO leaves the field alone');
  fields.cameraAutofill.checked = false;
  assert.equal(env.applyCameraSettings({ iso: 1600 }), false);
  assert.equal(invalidations, 1);
});
