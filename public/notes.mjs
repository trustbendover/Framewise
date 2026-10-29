// Combined patch notes: everything between the version a user last saw and the current one.
const parts = v => (String(v ?? '').replace(/^v/i, '').match(/\d+/g) || []).map(Number).concat([0, 0, 0]).slice(0, 3);
export function compareVersions(a, b) { const x = parts(a), y = parts(b); for (let i = 0; i < 3; i++) if (x[i] !== y[i]) return x[i] - y[i]; return 0; }

/** Releases newer than `seen` up to the card's version, newest first. Unknown `seen` → current only. */
export function notesSince(card, seen) {
  if (!card || typeof card.version !== 'string') return [];
  const all = Array.isArray(card.releases) && card.releases.length ? card.releases : [{ version: card.version, notes: card.notes || [] }];
  const list = all.filter(r => r && typeof r.version === 'string' && Array.isArray(r.notes)
    && compareVersions(r.version, card.version) <= 0
    && (seen ? compareVersions(r.version, seen) > 0 : compareVersions(r.version, card.version) === 0));
  return list.sort((a, b) => compareVersions(b.version, a.version)).map(r => ({ version: r.version, notes: r.notes.map(String) }));
}

export function titleFor(card, sections, seen) {
  const short = v => String(v).replace(/\.0$/, '');
  if (sections.length > 1 && seen) return `What's new since Framewise ${short(seen)}`;
  return card.title || `What's new in Framewise ${short(card.version)}`;
}
