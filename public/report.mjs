// Test reports for beta testers. Built on the phone, shared only through Android's share sheet.
const SECRET_PATTERNS = [
  /AIza[0-9A-Za-z_\-]{20,}/g,
  /sk-[A-Za-z0-9_\-]{16,}/g,
  /(gh[pousr]_|github_pat_)[A-Za-z0-9_]{20,}/g
];
const SECRET_KEYS = /^(api[_-]?key|apikey|key|password|passphrase|token|secret|authorization|camerapassword)$/i;

export function redactText(text) {
  let out = String(text ?? '');
  for (const p of SECRET_PATTERNS) out = out.replace(p, '[removed]');
  return out;
}

/** Deep copy without secret-looking fields, with secret-looking strings removed. */
export function scrub(value, depth = 0) {
  if (depth > 8) return null;
  if (typeof value === 'string') return redactText(value).slice(0, 4000);
  if (Array.isArray(value)) return value.slice(0, 300).map(v => scrub(v, depth + 1));
  if (value && typeof value === 'object') {
    const out = {};
    for (const [k, v] of Object.entries(value)) { if (SECRET_KEYS.test(k)) continue; out[k] = scrub(v, depth + 1); }
    return out;
  }
  return value ?? null;
}

export function buildReport({ native = {}, history = [], preferences = {}, jsLog = [], ui = {}, now = Date.now() } = {}) {
  return scrub({ format: 'framewise-test-report/1', createdAt: new Date(now).toISOString(), ...native, ui, preferences, jsLog: jsLog.slice(-100), shotHistory: history.slice(0, 200) });
}

/** Short plain-text summary for the message body (the full JSON is attached). */
export function summaryText(report) {
  const r = report || {};
  const errors = Array.isArray(r.errors) ? r.errors : [];
  const lines = [
    `Framewise ${r.app?.version ?? '?'} test report`,
    `Phone: ${r.device?.manufacturer ?? ''} ${r.device?.model ?? ''}, Android ${r.device?.android ?? '?'}`.trim(),
    `Camera: ${r.camera?.model || 'not connected'} (${r.camera?.connection || 'none'})`,
    `Crashed last time: ${r.crashedLastTime ? 'yes' : 'no'} · errors: ${errors.length} · shots in history: ${(r.shotHistory || []).length}`
  ];
  for (const e of errors.slice(-5)) lines.push(`- [${e.area}] ${String(e.message).slice(0, 200)}`);
  lines.push('The full report is attached as a JSON file.');
  return redactText(lines.join('\n'));
}
