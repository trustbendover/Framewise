import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { buildUpdate, ownNotes } from '../scripts/build-update-json.mjs';

const notesByFile = {};
for (let i = 0; i < 14; i++) notesByFile[`0.${i}`] = `## Hi\n\n- Note ${i}\n\n### Also in 0.0\n- old`;
notesByFile['0.99'] = '- not released yet';

test('update.json has the APK, checksum and recent notes, newest first', () => {
  const u = buildUpdate({ repo: 'o/r', tag: 'v0.13', versionName: '0.13', versionCode: 20, apkName: 'Framewise-0.13-android.apk', apkSize: 5, sha256: 'a'.repeat(64), notesByFile });
  assert.equal(u.apkUrl, 'https://github.com/o/r/releases/download/v0.13/Framewise-0.13-android.apk');
  assert.equal(u.sha256Url, u.apkUrl + '.sha256');
  assert.equal(u.versionCode, 20);
  assert.equal(u.releases.length, 10);
  assert.equal(u.releases[0].version, '0.13');
  assert.equal(u.releases[0].versionCode, 20);
  assert.equal(u.releases[9].version, '0.4');
  assert.equal(u.notes, '- Note 13');
  assert.ok(!u.releases.some(r => r.version === '0.99'), 'never lists a newer, unreleased version');
});

test('own notes leave out the "Also in" recap', () => {
  assert.equal(ownNotes('- a\n### Also in 0.1\n- b'), '- a');
});

test('the release workflow attaches update.json', async () => {
  const yml = await readFile(new URL('../.github/workflows/release.yml', import.meta.url), 'utf8');
  assert.match(yml, /build-update-json\.mjs "dist\/\$OUT" dist\/update\.json/);
  assert.match(yml, /gh release (upload|create) "\$TAG" dist\/\*/);
});

test('the updater tries update.json before the rate-limited API', async () => {
  const java = await readFile(new URL('../android/app/src/main/java/app/framewise/UpdateManager.java', import.meta.url), 'utf8');
  assert.match(java, /releases\/latest\/download\/update\.json/);
  assert.ok(java.indexOf('get(UPDATE_JSON') < java.indexOf('get(API'));
  assert.doesNotMatch(java, /Camera Wi-Fi has no internet: check/);
});
