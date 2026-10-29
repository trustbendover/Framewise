import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { notesSince, titleFor, compareVersions } from '../public/notes.mjs';

const card = JSON.parse(await readFile(new URL('../public/whats-new.json', import.meta.url), 'utf8'));

test('Friend on 0.4.1 sees notes of every version he skipped, newest first', () => {
  const fake = { version: '0.5.0', notes: ['five'], releases: [{ version: '0.5', notes: ['five'] }, { version: '0.4.2', notes: ['four-two'] }, { version: '0.4.1', notes: ['old'] }, { version: '0.6', notes: ['future'] }] };
  const s = notesSince(fake, '0.4.1');
  assert.deepEqual(s.map(x => x.version), ['0.5', '0.4.2']);
  assert.equal(titleFor(fake, s, '0.4.1'), "What's new since Framewise 0.4.1");
});

test('Bundled card covers every release and the 0.4.1 → 0.5 jump', () => {
  const s = notesSince(card, '0.4.1');
  assert.ok(s.length >= 1);
  assert.equal(compareVersions(s[0].version, card.version), 0);
  assert.ok(s.every(x => compareVersions(x.version, '0.4.1') > 0));
  assert.deepEqual(s[0].notes, card.notes);
});

test('Unknown last-seen version shows only the current notes; same version shows nothing', () => {
  assert.equal(notesSince(card, null).length, 1);
  assert.equal(notesSince(card, card.version).length, 0);
  assert.equal(notesSince({ version: '1.0', notes: ['a'] }, '0.9')[0].notes[0], 'a');
});

test('Version comparison handles v-prefix and short tags', () => {
  assert.equal(compareVersions('v0.5', '0.5.0'), 0);
  assert.ok(compareVersions('0.4.10', '0.4.9') > 0);
  assert.ok(compareVersions('0.5', '0.4.1') > 0);
});
