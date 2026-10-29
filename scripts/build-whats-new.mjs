// Regenerates public/whats-new.json from release-notes/*.md so the in-app card can show
// every version a user skipped. Run: node scripts/build-whats-new.mjs
import { readFile, readdir, writeFile } from 'node:fs/promises';
const root = new URL('../', import.meta.url);
const gradle = await readFile(new URL('android/app/build.gradle', root), 'utf8');
const version = gradle.match(/versionName '([^']+)'/)[1];
const parts = v => (String(v).replace(/^v/i, '').match(/\d+/g) || []).map(Number).concat([0, 0, 0]).slice(0, 3);
const cmp = (a, b) => { const x = parts(a), y = parts(b); for (let i = 0; i < 3; i++) if (x[i] !== y[i]) return x[i] - y[i]; return 0; };
const releases = [];
for (const file of await readdir(new URL('release-notes/', root))) {
  const m = file.match(/^v(.+)\.md$/); if (!m) continue;
  const text = await readFile(new URL(`release-notes/${file}`, root), 'utf8');
  releases.push({ version: m[1], notes: text.split(/^### Also in/m)[0].split('\n').filter(l => l.startsWith('- ')).map(l => l.slice(2).trim()) });
}
releases.sort((a, b) => cmp(b.version, a.version));
const current = releases.find(r => cmp(r.version, version) === 0);
if (!current) throw new Error(`release-notes for ${version} missing`);
const card = { version, title: `What's new in Framewise ${version.replace(/\.0$/, '')}`, notes: current.notes, releases };
await writeFile(new URL('public/whats-new.json', root), JSON.stringify(card, null, 2) + '\n');
console.log(`whats-new.json: ${releases.length} releases, current ${version}`);
