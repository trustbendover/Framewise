// A creative starting-point advisor. Pixel brightness is deliberately never used
// as an exposure meter: recommendations require the camera's explicit EV reading.
const SHUTTERS = [4000, 3200, 2500, 2000, 1600, 1250, 1000, 800, 640, 500, 400, 320, 250, 200, 160, 125, 100, 80, 60, 50, 40, 30, 25, 20, 15, 13, 10, 8, 6, 5, 4].map(n => 1 / n).concat([0.3, 0.4, 0.5, 0.6, 0.8, 1, 1.3, 1.6, 2, 2.5, 3.2, 4, 5, 6, 8, 10, 13, 15, 20, 25, 30]);
const APERTURES = [1, 1.1, 1.2, 1.4, 1.6, 1.8, 2, 2.2, 2.5, 2.8, 3.2, 3.5, 4, 4.5, 5, 5.6, 6.3, 7.1, 8, 9, 10, 11, 13, 14, 16, 18, 20, 22, 25, 29, 32, 36, 40, 45, 51, 57, 64];
const ISOS = [100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800, 16000, 20000, 25600, 32000];
const GOALS = ['balanced', 'portrait', 'action', 'landscape', 'motion'];
const MOTIONS = ['auto', 'still', 'walking', 'fast', 'birds'];
const EPSILON = 1e-9;
const METER_TOLERANCE = 0.18; // A nearest third-stop setting can differ by ~1/6 EV.
const stops = (a, b) => Math.abs(Math.log2(a / b));
const clamp = (n, low, high) => Math.min(high, Math.max(low, n));
const nearest = (values, target) => values.reduce((a, b) => stops(a, target) <= stops(b, target) ? a : b);

function finite(value, label, min = -Infinity, max = Infinity) {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < min || value > max) {
    throw new TypeError(`${label} must be a finite number${min !== -Infinity || max !== Infinity ? ` between ${min} and ${max}` : ''}.`);
  }
  return value;
}

function member(value, values, label) {
  if (!values.includes(value)) throw new TypeError(`${label} must be one of: ${values.join(', ')}.`);
  return value;
}

export function formatShutter(seconds) {
  finite(seconds, 'Shutter time', Number.MIN_VALUE);
  if (seconds >= 1) return `${Number(seconds.toFixed(2))} s`;
  const reciprocal = 1 / seconds;
  if (reciprocal >= 2) return `1/${Number(reciprocal.toFixed(reciprocal >= 10 ? 0 : 1))} s`;
  return `${Number(seconds.toFixed(2))} s`;
}

function normalize(input, scene) {
  if (!input || typeof input !== 'object' || Array.isArray(input)) throw new TypeError('Camera input must be an object.');
  if (!input.lens || !input.current) throw new TypeError('Provide lens limits and the current camera settings.');
  const lens = {
    maxAperture: finite(input.lens.maxAperture, 'Lens maximum aperture', 0.5, 128),
    minAperture: finite(input.lens.minAperture, 'Lens minimum aperture', 0.5, 128),
  };
  if (lens.maxAperture > lens.minAperture) throw new RangeError('Lens maximum aperture must have an f-number no larger than its minimum aperture.');
  const current = {
    shutter: finite(input.current.shutter, 'Current shutter time', 1 / 4000, 30),
    aperture: finite(input.current.aperture, 'Current aperture', 0.5, 128),
    iso: finite(input.current.iso, 'Current ISO', 100, 32000),
  };
  if (input.locks != null && (typeof input.locks !== 'object' || Array.isArray(input.locks))) throw new TypeError('Locks must be an object of boolean values.');
  const locks = {};
  for (const key of ['shutter', 'aperture', 'iso']) {
    if (input.locks?.[key] !== undefined && typeof input.locks[key] !== 'boolean') throw new TypeError(`The ${key} lock must be true or false.`);
    locks[key] = input.locks?.[key] ?? false;
  }
  if (scene !== null && (typeof scene !== 'object' || Array.isArray(scene))) throw new TypeError('Scene advice must be an object or null.');
  return {
    lens, current, locks,
    focalLength: finite(input.focalLength, 'Focal length', 1, 3000),
    goal: member(input.goal ?? 'balanced', GOALS, 'Goal'),
    support: member(input.support ?? 'handheld', ['handheld', 'tripod'], 'Camera support'),
    subjectMotion: member(input.subjectMotion ?? 'auto', MOTIONS, 'Subject motion'),
    maxISO: finite(input.maxISO ?? 3200, 'Maximum ISO', 100, 32000),
    meteringEV: input.meteringEV == null ? null : finite(input.meteringEV, 'Camera meter EV'),
    exposureBias: finite(input.exposureBias ?? 0, 'Exposure bias'),
  };
}

export function recommend(input, scene = null) {
  const p = normalize(input, scene);
  const { lens, current, locks, goal, support } = p;
  const warnings = [];
  let conflict = false;
  const inferredMotion = ['still', 'walking', 'fast', 'birds'].includes(scene?.subjectMotion) ? scene.subjectMotion : 'unknown';
  const motion = p.subjectMotion === 'auto' ? inferredMotion : p.subjectMotion;
  const motionLimit = ({ still: 30, walking: 1 / 250, fast: 1 / 1000, birds: 1 / 2000, unknown: 1 / 125 })[motion];
  const handheldLimit = 1 / (1.5 * p.focalLength);
  const sharpLimit = Math.max(1 / 4000, Math.min(support === 'handheld' ? handheldLimit : 30, motionLimit, goal === 'action' ? 1 / 1000 : 30));
  const shutterTarget = goal === 'motion' ? 1 / 15 : Math.min(1 / 125, sharpLimit);
  const allApertures = [...new Set([lens.maxAperture, ...APERTURES.filter(a => a >= lens.maxAperture && a <= lens.minAperture), lens.minAperture])].sort((a, b) => a - b);
  const desiredAperture = goal === 'portrait' || goal === 'action' ? lens.maxAperture : goal === 'landscape' || goal === 'motion' ? 8 : 5.6;
  const apertureTarget = nearest(allApertures, clamp(desiredAperture, lens.maxAperture, lens.minAperture));
  let availableApertures = allApertures;
  if (goal === 'portrait') availableApertures = [apertureTarget];
  if (goal === 'landscape') availableApertures = allApertures.filter(a => a >= apertureTarget && a <= Math.max(11, apertureTarget));
  const availableShutters = SHUTTERS.filter(s => goal === 'motion' ? s >= 1 / 30 - EPSILON && s <= 1 / 8 + EPSILON : s <= sharpLimit + EPSILON);
  const shutterOptions = locks.shutter ? [current.shutter] : availableShutters;
  const apertureOptions = locks.aperture ? [current.aperture] : availableApertures;
  const isoOptions = locks.iso ? [current.iso] : ISOS.filter(iso => iso <= p.maxISO);
  let shutter = locks.shutter ? current.shutter : nearest(shutterOptions, shutterTarget);
  let aperture = locks.aperture ? current.aperture : apertureTarget;
  let iso = locks.iso ? current.iso : null;
  let exposureErrorEV = null;

  if (locks.aperture && (current.aperture < lens.maxAperture || current.aperture > lens.minAperture)) {
    conflict = true;
    warnings.push('The locked aperture is outside the entered lens limits. It has been preserved; check the lens information.');
  }
  if (locks.iso && current.iso > p.maxISO) {
    conflict = true;
    warnings.push('The locked ISO exceeds your ISO ceiling. It has been preserved; unlock it or raise the ceiling.');
  }
  if (goal === 'motion' && support === 'handheld') {
    conflict = true;
    warnings.push(`Intentional motion blur uses a slow shutter. At ${p.focalLength} mm, the handheld starting point is about ${formatShutter(nearest(SHUTTERS.filter(s => s <= Math.max(1 / 4000, handheldLimit) + EPSILON), handheldLimit))}; use a tripod or accept camera movement.`);
  }
  if (locks.shutter && goal !== 'motion' && current.shutter > sharpLimit + EPSILON) {
    conflict = true;
    warnings.push('The locked shutter is slower than the motion or handheld starting point. Unlock it, add support, or accept blur.');
  }
  if (locks.shutter && goal === 'motion' && current.shutter < 1 / 30 - EPSILON) {
    conflict = true;
    warnings.push('The locked shutter is faster than this intentional-blur starting point. Unlock it or accept less motion blur.');
  }
  if (goal === 'action' && p.subjectMotion === 'auto' && inferredMotion === 'unknown') warnings.push('Subject speed is unknown. The action shutter is a starting point; inspect a test frame for motion blur.');
  if (goal === 'portrait' && lens.maxAperture >= 5.6) warnings.push('This lens limits how wide you can open the aperture. Use subject-to-background distance and focal length to shape background blur.');
  if (goal === 'landscape' && lens.minAperture < 8) warnings.push('The lens cannot reach the f/8 starting point. Check depth of field in a test frame.');
  if (motion === 'unknown') warnings.push('Subject motion has not been confirmed. Choose a motion level for a more specific starting point.');

  if (p.meteringEV !== null) {
    let best = null;
    for (const candidateShutter of shutterOptions) {
      for (const candidateAperture of apertureOptions) {
        // Positive meter EV means the CURRENT settings are overexposed.
        const desiredLogISO = Math.log2(current.iso) + Math.log2(current.shutter / candidateShutter) + 2 * Math.log2(candidateAperture / current.aperture) + p.exposureBias - p.meteringEV;
        const candidateISO = isoOptions.reduce((a, b) => Math.abs(Math.log2(a) - desiredLogISO) <= Math.abs(Math.log2(b) - desiredLogISO) ? a : b);
        const error = Math.log2(candidateISO) - desiredLogISO;
        const withinMeter = Math.abs(error) <= METER_TOLERANCE;
        const score = 4 * stops(candidateAperture, apertureTarget) + 2 * stops(candidateShutter, shutterTarget) + 0.03 * Math.log2(candidateISO / 100);
        const candidate = { shutter: candidateShutter, aperture: candidateAperture, iso: candidateISO, error, withinMeter, score };
        if (!best || (withinMeter && !best.withinMeter) || (withinMeter === best.withinMeter && (withinMeter ? score < best.score - EPSILON : Math.abs(error) < Math.abs(best.error) - EPSILON || (Math.abs(Math.abs(error) - Math.abs(best.error)) <= EPSILON && score < best.score)))) best = candidate;
      }
    }
    ({ shutter, aperture, iso } = best);
    exposureErrorEV = best.error;
    if (!best.withinMeter) {
      conflict = true;
      const direction = exposureErrorEV > 0 ? 'brighter' : 'darker';
      warnings.push(`Available settings leave the image approximately ${Math.abs(exposureErrorEV).toFixed(2)} EV ${direction} than the requested meter target. Review locks, ISO ceiling, creative intent, or lighting.`);
    }
    if (Math.abs(Math.log2(shutter / shutterTarget)) > 0.4 || Math.abs(Math.log2(aperture / apertureTarget)) > 0.25) warnings.push('The camera meter reading required a change from the initial creative starting point. Review the shutter and aperture below.');
  } else {
    warnings.push('No camera meter reading: ISO and exposure cannot be determined from a preview. Read the camera meter at the entered current settings, then enter its EV value.');
  }

  const isMoving = ['walking', 'fast', 'birds'].includes(motion) || goal === 'action';
  const focus = typeof scene?.focusAdvice === 'string' && scene.focusAdvice.trim() ? scene.focusAdvice.trim().slice(0, 500) : isMoving ? 'Start with AF-C and a focus area that stays on the subject; check eye or subject sharpness.' : 'Start with AF-S for a stationary subject, or AF-C if the subject may move; confirm the intended focus point.';
  const lighting = scene?.lighting;
  const whiteBalance = lighting === 'daylight' ? 'Daylight WB is a starting point; use a neutral reference for consistent color.' : lighting === 'shade' ? 'Shade WB is a starting point; verify skin tones or use a neutral reference.' : lighting === 'tungsten' ? 'Tungsten WB is a starting point; use a neutral reference under the actual lights.' : lighting === 'mixed' ? 'Mixed lighting may not have one neutral WB. Prioritize the light on your subject and use a neutral reference.' : 'Start with Auto WB; use a grey card or a fixed WB for consistency.';
  const status = conflict ? 'conflict' : p.meteringEV === null ? 'unmetered' : 'metered';
  return {
    settings: { shutter, aperture, iso }, status, exposureErrorEV, warnings,
    reasons: {
      shutter: locks.shutter ? 'Your shutter value is locked and has been preserved exactly.' : goal === 'motion' ? 'A slow shutter is a starting point for intentional subject blur; the result depends on movement and panning.' : `Starting point based on ${motion === 'unknown' ? 'unconfirmed subject movement' : `${motion} subject movement`}${support === 'handheld' ? ` and the APS-C reciprocal guideline at ${p.focalLength} mm` : ' with camera support'}. Check a test frame.`,
      aperture: locks.aperture ? 'Your aperture value is locked and has been preserved exactly.' : goal === 'portrait' ? 'Use the widest available aperture as a starting point for subject separation. Distance and focal length also affect blur.' : goal === 'landscape' ? 'Start near f/8 for depth of field, within lens limits. Confirm near and far detail at your chosen focus distance.' : goal === 'action' ? 'Start wide to admit light while keeping a fast shutter; stop down if additional depth of field is needed.' : goal === 'motion' ? 'Start near f/8 for depth of field while exploring motion blur.' : 'A moderate aperture is a starting point; adjust it for your intended depth of field.',
      iso: locks.iso ? 'Your ISO value is locked and has been preserved exactly.' : p.meteringEV === null ? 'Unknown until you provide the camera meter EV reading at the entered current settings.' : 'Calculated from the camera meter EV, current settings, exposure bias, and proposed shutter/aperture; rounded to an available third-stop ISO within your ceiling.',
    },
    focus, whiteBalance,
    title: status === 'conflict' ? 'Settings need a tradeoff' : status === 'unmetered' ? 'Starting point — camera meter needed' : 'Metered starting point — verify a test frame',
  };
}
