// Writes the update.json release asset read by the in-app updater (0.5.3+). It is downloaded through
// https://github.com/<repo>/releases/latest/download/update.json, which is not limited like the GitHub API.
// Usage: node scripts/build-update-json.mjs <apk path> <out file>   (env: REPO, TAG, VERSION_NAME, VERSION_CODE)
import { readFile, readdir, writeFile, stat } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { basename } from 'node:path';

export const MAX_RELEASES = 10;
const parts = v => (String(v).replace(/^v/i, '').match(/\d+/g) || []).map(Number).concat([0, 0, 0]).slice(0, 3);
export const cmp = (a, b) => { const x = parts(a), y = parts(b); for (let i = 0; i < 3; i++) if (x[i] !== y[i]) return x[i] - y[i]; return 0; };
/** Only this version's own bullets: the "### Also in" recap of older versions is left out. */
export const ownNotes = text => text.split(/^### Also in/m)[0].split('\n').filter(l => l.startsWith('- ')).join('\n');

export function buildUpdate({ repo, tag, versionName, versionCode, apkName, apkSize, sha256, notesByFile }) {
  const base = `https://github.com/${repo}/releases/download/${tag}/`;
  const releases = Object.entries(notesByFile)
    .map(([version, text]) => ({ version, notes: ownNotes(text) }))
    .filter(r => cmp(r.version, versionName) <= 0)
    .sort((a, b) => cmp(b.version, a.version))
    .slice(0, MAX_RELEASES);
  const current = releases.find(r => cmp(r.version, versionName) === 0);
  if (!current) throw new Error(`release-notes for ${versionName} missing`);
  releases[releases.indexOf(current)] = { ...current, versionCode };
  return {
    versionCode, versionName, tag,
    pageUrl: `https://github.com/${repo}/releases/tag/${tag}`,
    apkName, apkUrl: base + apkName, apkSize, sha256, sha256Url: base + apkName + '.sha256',
    notes: current.notes, releases,
  };
}

if (process.argv[1] && import.meta.url.endsWith(basename(process.argv[1]))) {
  const [apk, out] = process.argv.slice(2);
  const { REPO, TAG, VERSION_NAME, VERSION_CODE } = process.env;
  if (!apk || !out || !REPO || !TAG || !VERSION_NAME || !VERSION_CODE) throw new Error('usage: REPO TAG VERSION_NAME VERSION_CODE env + <apk> <out>');
  const bytes = await readFile(apk);
  const notesByFile = {};
  const dir = new URL('../release-notes/', import.meta.url);
  for (const file of await readdir(dir)) {
    const m = file.match(/^v(.+)\.md$/); if (m) notesByFile[m[1]] = await readFile(new URL(file, dir), 'utf8');
  }
  const update = buildUpdate({ repo: REPO, tag: TAG, versionName: VERSION_NAME, versionCode: Number(VERSION_CODE),
    apkName: basename(apk), apkSize: (await stat(apk)).size, sha256: createHash('sha256').update(bytes).digest('hex'), notesByFile });
  await writeFile(out, JSON.stringify(update, null, 2) + '\n');
  console.log(`update.json: ${update.versionName} (${update.versionCode}), ${update.releases.length} releases`);
}
