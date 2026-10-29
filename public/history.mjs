// Local shot history: kept only on this phone (WebView storage), never uploaded on its own.
// A short summary is added to the AI request so tips can be personalised.
export const HISTORY_KEY = 'framewise.history';
export const HISTORY_LIMIT = 200;
const num = v => (Number.isFinite(Number(v)) && v !== null && v !== '' ? Number(v) : null);
const text = (v, max = 600) => (typeof v === 'string' ? v.slice(0, max) : '');

export function makeEntry({ at = Date.now(), input = {}, camera = null, scene = {}, source = '' } = {}) {
  const cam = camera || {};
  const cur = input.current || {};
  return {
    at,
    source: text(source, 20),
    settings: {
      iso: num(cam.iso) ?? num(cur.iso),
      shutter: num(cam.shutter) ?? num(cur.shutter),
      aperture: num(cam.aperture) ?? num(cur.aperture),
      exposureCompensation: num(cam.exposureCompensation),
      focalLength: num(cam.focalLength) ?? num(input.focalLength),
      mode: text(cam.mode, 40) || null,
      whiteBalance: text(cam.whiteBalance, 40) || null,
      fromCamera: Boolean(camera)
    },
    goal: text(input.goal, 20),
    summary: text(scene.summary),
    focusAdvice: text(scene.focusAdvice),
    lighting: text(scene.lighting, 20),
    tips: Array.isArray(scene.observations) ? scene.observations.filter(x => typeof x === 'string').slice(0, 6).map(x => x.slice(0, 300)) : [],
    score: Number.isInteger(scene.score) && scene.score >= 1 && scene.score <= 10 ? scene.score : null
  };
}

export function loadHistory(storage) {
  try { const list = JSON.parse(storage.getItem(HISTORY_KEY) || '[]'); return Array.isArray(list) ? list.filter(e => e && Number.isFinite(e.at)) : []; }
  catch { return []; }
}
export function addEntry(storage, entry) {
  const list = [entry, ...loadHistory(storage)].slice(0, HISTORY_LIMIT);
  try { storage.setItem(HISTORY_KEY, JSON.stringify(list)); } catch {}
  return list;
}
export function clearHistory(storage) { try { storage.removeItem(HISTORY_KEY); } catch {} return []; }

function median(values) { const v = values.filter(x => x !== null).sort((a, b) => a - b); if (!v.length) return null; const m = Math.floor(v.length / 2); return v.length % 2 ? v[m] : (v[m - 1] + v[m]) / 2; }
const WORDS = { blur: /blur|soft|shake|motion/i, noise: /noise|grain/i, dark: /dark|underexpos/i, bright: /bright|overexpos|blown|clip/i, focus: /focus|missed/i, horizon: /horizon|tilt|crooked/i, cluttered: /clutter|busy|distract/i };

/** Compact, prompt-sized summary of the most recent shots (default 20). */
export function summarizeHistory(list, limit = 20) {
  const recent = (list || []).slice(0, limit);
  if (!recent.length) return null;
  const s = recent.map(e => e.settings || {});
  const scores = recent.map(e => e.score).filter(Number.isInteger);
  const counts = {};
  for (const e of recent) { const blob = [e.summary, e.focusAdvice, ...(e.tips || [])].join(' '); for (const [k, re] of Object.entries(WORDS)) if (re.test(blob)) counts[k] = (counts[k] || 0) + 1; }
  const recurring = Object.entries(counts).filter(([, n]) => n >= 2).sort((a, b) => b[1] - a[1]).map(([k, n]) => `${k} (${n}/${recent.length})`);
  const goals = {}; for (const e of recent) if (e.goal) goals[e.goal] = (goals[e.goal] || 0) + 1;
  return {
    shots: recent.length,
    typical: { iso: median(s.map(x => num(x.iso))), shutter: median(s.map(x => num(x.shutter))), aperture: median(s.map(x => num(x.aperture))), focalLength: median(s.map(x => num(x.focalLength))) },
    averageScore: scores.length ? Math.round(scores.reduce((a, b) => a + b, 0) / scores.length * 10) / 10 : null,
    recurringIssues: recurring.slice(0, 5),
    commonGoals: Object.entries(goals).sort((a, b) => b[1] - a[1]).slice(0, 3).map(([g]) => g),
    lastTips: recent.slice(0, 3).map(e => e.summary).filter(Boolean).map(x => x.slice(0, 160))
  };
}
