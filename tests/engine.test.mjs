import test from 'node:test';
import assert from 'node:assert/strict';
import { recommend, formatShutter } from '../public/engine.mjs';

function input(overrides = {}) {
  const base = {
    lens: { maxAperture: 5.6, minAperture: 22 }, focalLength: 70,
    current: { shutter: 1 / 250, aperture: 5.6, iso: 400 },
    locks: { shutter: false, aperture: false, iso: false }, goal: 'balanced',
    support: 'handheld', subjectMotion: 'still', maxISO: 3200,
    meteringEV: null, exposureBias: 0,
  };
  return { ...base, ...overrides, current: { ...base.current, ...overrides.current }, locks: { ...base.locks, ...overrides.locks }, lens: { ...base.lens, ...overrides.lens } };
}

test('No meter means unknown ISO, including when scene brightness is supplied', () => {
  const result = recommend(input(), { brightness: 0.9, lighting: 'daylight', subjectMotion: 'still' });
  assert.equal(result.status, 'unmetered');
  assert.equal(result.settings.iso, null);
  assert.equal(result.exposureErrorEV, null);
  assert.match(result.warnings.join(' '), /No camera meter reading/);
});

test('Every locked value is preserved exactly, including nonstandard values', () => {
  const current = { shutter: 0.0037, aperture: 6.1, iso: 777 };
  const result = recommend(input({ current, locks: { shutter: true, aperture: true, iso: true }, meteringEV: 0 }));
  assert.deepEqual(result.settings, current);
  assert.equal(result.status, 'metered');
});

test('A positive meter reading reduces ISO; a negative reading increases it', () => {
  const base = input({ locks: { shutter: true, aperture: true } });
  assert.equal(recommend({ ...base, meteringEV: 1 }).settings.iso, 200);
  assert.equal(recommend({ ...base, meteringEV: -1 }).settings.iso, 800);
});

test('Exposure bias has the correct direction', () => {
  const result = recommend(input({ locks: { shutter: true, aperture: true }, meteringEV: 0, exposureBias: 1 }));
  assert.equal(result.settings.iso, 800);
});

test('The exposure formula includes shutter, aperture, and meter correction', () => {
  const p = input({ current: { shutter: 1 / 250, aperture: 8, iso: 800 }, goal: 'portrait', meteringEV: -0.7, exposureBias: 0.3 });
  const result = recommend(p);
  const s = result.settings;
  const targetISO = p.current.iso * (p.current.shutter / s.shutter) * (s.aperture / p.current.aperture) ** 2 * 2 ** (p.exposureBias - p.meteringEV);
  assert.ok(Math.abs(Math.log2(s.iso / targetISO) - result.exposureErrorEV) < 1e-10);
  assert.ok(Math.abs(result.exposureErrorEV) <= 0.18);
});

test('An ISO ceiling produces a visible underexposure conflict when other settings are locked', () => {
  const result = recommend(input({ locks: { shutter: true, aperture: true }, current: { iso: 3200 }, maxISO: 1600, meteringEV: -2 }));
  assert.equal(result.settings.iso, 1600);
  assert.equal(result.status, 'conflict');
  assert.equal(result.exposureErrorEV, -3);
  assert.match(result.warnings.join(' '), /3.00 EV darker/);
});

test('The engine respects a nonstandard ISO ceiling using supported third-stop values', () => {
  const result = recommend(input({ locks: { shutter: true, aperture: true }, meteringEV: -1, maxISO: 750 }));
  assert.equal(result.settings.iso, 640);
  assert.equal(result.status, 'conflict');
});

test('Portrait aperture uses the entered lens maximum, never an unavailable f/2.8', () => {
  const result = recommend(input({ goal: 'portrait', lens: { maxAperture: 6.3 } }));
  assert.equal(result.settings.aperture, 6.3);
  assert.match(result.warnings.join(' '), /lens limits/);
});

test('Unavailable locked aperture is preserved and clearly marked as a conflict', () => {
  const result = recommend(input({ current: { aperture: 2.8 }, locks: { aperture: true } }));
  assert.equal(result.settings.aperture, 2.8);
  assert.equal(result.status, 'conflict');
  assert.match(result.warnings.join(' '), /outside the entered lens limits/);
});

test('Locked ISO stays known without a meter', () => {
  const result = recommend(input({ locks: { iso: true } }));
  assert.equal(result.settings.iso, 400);
  assert.equal(result.status, 'unmetered');
  assert.equal(result.exposureErrorEV, null);
});

test('At 200 mm handheld, a still subject starts at least as fast as the APS-C guideline', () => {
  const result = recommend(input({ focalLength: 200 }));
  assert.ok(result.settings.shutter <= 1 / 300);
  assert.match(result.reasons.shutter, /APS-C reciprocal/);
});

test('At 200 mm, intentional motion blur keeps its intent and flags handheld conflict', () => {
  const result = recommend(input({ goal: 'motion', focalLength: 200 }));
  assert.equal(result.settings.shutter, 1 / 15);
  assert.equal(result.status, 'conflict');
  assert.match(result.warnings.join(' '), /tripod/);
});

test('A tripod resolves camera shake for intentional motion blur', () => {
  const result = recommend(input({ goal: 'motion', focalLength: 200, support: 'tripod' }));
  assert.equal(result.status, 'unmetered');
  assert.equal(result.settings.shutter, 1 / 15);
});

test('Explicit motion choice overrides AI scene inference', () => {
  const p = input({ subjectMotion: 'still', support: 'tripod' });
  assert.equal(recommend(p, { subjectMotion: 'birds' }).settings.shutter, 1 / 125);
  assert.ok(recommend({ ...p, subjectMotion: 'auto' }, { subjectMotion: 'birds' }).settings.shutter <= 1 / 2000);
});

test('Action does not slow the shutter to conceal an unattainable exposure', () => {
  const result = recommend(input({ goal: 'action', subjectMotion: 'fast', meteringEV: -8, maxISO: 800 }));
  assert.equal(result.status, 'conflict');
  assert.ok(result.settings.shutter <= 1 / 1000);
  assert.ok(result.settings.iso <= 800);
  assert.ok(result.exposureErrorEV < 0);
});

test('All locks plus an incompatible meter target yields an exact residual', () => {
  const p = input({ locks: { shutter: true, aperture: true, iso: true }, meteringEV: 2 });
  const result = recommend(p);
  assert.deepEqual(result.settings, p.current);
  assert.equal(result.status, 'conflict');
  assert.equal(result.exposureErrorEV, 2);
});

test('Locked ISO can be met by an unlocked shutter within sharpness constraints', () => {
  const result = recommend(input({ support: 'tripod', subjectMotion: 'still', locks: { iso: true, aperture: true }, meteringEV: -2 }));
  assert.equal(result.settings.iso, 400);
  assert.equal(result.status, 'metered');
  assert.ok(Math.abs(result.exposureErrorEV) <= 0.18);
});

test('A locked ISO above the ceiling is preserved and reported', () => {
  const result = recommend(input({ locks: { iso: true }, current: { iso: 800 }, maxISO: 400 }));
  assert.equal(result.settings.iso, 800);
  assert.equal(result.status, 'conflict');
  assert.match(result.warnings.join(' '), /locked ISO exceeds/);
});

test('The Sony shutter bounds and lens bounds hold across creative goals', () => {
  for (const goal of ['balanced', 'portrait', 'action', 'landscape', 'motion']) {
    for (const meteringEV of [-10, 0, 10]) {
      const result = recommend(input({ goal, meteringEV, support: 'tripod' }));
      assert.ok(result.settings.shutter >= 1 / 4000 && result.settings.shutter <= 30);
      assert.ok(result.settings.aperture >= 5.6 && result.settings.aperture <= 22);
      assert.ok(result.settings.iso >= 100 && result.settings.iso <= 3200);
    }
  }
});

test('Invalid camera input produces understandable errors', () => {
  assert.throws(() => recommend(null), /input must be an object/);
  assert.throws(() => recommend(input({ focalLength: NaN })), /Focal length/);
  assert.throws(() => recommend(input({ current: { shutter: 1 / 8000 } })), /Current shutter time/);
  assert.throws(() => recommend(input({ lens: { maxAperture: 22, minAperture: 5.6 } })), /f-number/);
  assert.throws(() => recommend(input({ locks: { shutter: 'yes' } })), /shutter lock/);
  assert.throws(() => recommend(input({ meteringEV: '0' })), /Camera meter EV/);
  assert.throws(() => recommend(input({ goal: 'sports' })), /Goal/);
  assert.throws(() => recommend(input({ maxISO: 50 })), /Maximum ISO/);
});

test('Shutter formatting is readable and rejects invalid time values', () => {
  assert.equal(formatShutter(1 / 250), '1/250 s');
  assert.equal(formatShutter(2.5), '2.5 s');
  assert.throws(() => formatShutter(0), /Shutter time/);
});
