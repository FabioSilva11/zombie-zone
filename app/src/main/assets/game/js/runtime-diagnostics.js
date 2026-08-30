const DEFAULT_CAPACITY = 500;
const DEFAULT_SAMPLE_INTERVAL_MS = 500;

function finite(value, fallback = null) {
  return Number.isFinite(value) ? value : fallback;
}

function rounded(value, digits = 4) {
  return Number.isFinite(value) ? Number(value.toFixed(digits)) : null;
}

function errorDetails(error) {
  if (error instanceof Error) {
    return { name: error.name, message: error.message, stack: error.stack ?? '' };
  }
  if (error && typeof error === 'object') {
    return { name: error.name ?? 'Error', message: error.message ?? String(error), stack: error.stack ?? '' };
  }
  return { name: 'Error', message: String(error ?? 'Erro desconhecido'), stack: '' };
}

export function angularDistance(a, b) {
  if (!Number.isFinite(a) || !Number.isFinite(b)) return Number.POSITIVE_INFINITY;
  return Math.abs(Math.atan2(Math.sin(a - b), Math.cos(a - b)));
}

export function installRuntimeDiagnostics({
  windowRef = typeof window === 'undefined' ? null : window,
  consoleRef = typeof console === 'undefined' ? null : console,
  performanceRef = typeof performance === 'undefined' ? null : performance,
  capacity = DEFAULT_CAPACITY,
  sampleIntervalMs = DEFAULT_SAMPLE_INTERVAL_MS,
} = {}) {
  const entries = [];
  const anomalyTimes = new Map();
  const startedAt = performanceRef?.now?.() ?? 0;
  let sequence = 0;
  let lastSampleAt = Number.NEGATIVE_INFINITY;
  let disposed = false;

  const emit = (level, type, data = {}) => {
    if (disposed) return null;
    const elapsedMs = (performanceRef?.now?.() ?? startedAt) - startedAt;
    const entry = {
      sequence: sequence += 1,
      elapsedMs: rounded(elapsedMs, 1),
      timestamp: new Date().toISOString(),
      level,
      type,
      ...data,
    };
    entries.push(entry);
    if (entries.length > Math.max(1, capacity)) entries.splice(0, entries.length - capacity);
    const method = level === 'error' ? 'error' : level === 'warn' ? 'warn' : 'log';
    consoleRef?.[method]?.(`[ZZ_DIAG] ${type}`, entry);
    return entry;
  };

  const emitAnomaly = (type, data, cooldownMs = 1000) => {
    const now = performanceRef?.now?.() ?? Date.now();
    if (now - (anomalyTimes.get(type) ?? Number.NEGATIVE_INFINITY) < cooldownMs) return;
    anomalyTimes.set(type, now);
    emit('error', type, data);
  };

  const capture = (type, error, context = {}) => emit('error', type, {
    ...context,
    error: errorDetails(error),
  });

  const onWindowError = (event) => capture('window-error', event.error ?? event.message, {
    filename: event.filename ?? '',
    line: finite(event.lineno),
    column: finite(event.colno),
  });
  const onUnhandledRejection = (event) => capture('unhandled-rejection', event.reason);
  windowRef?.addEventListener?.('error', onWindowError);
  windowRef?.addEventListener?.('unhandledrejection', onUnhandledRejection);

  const api = {
    record(level, type, data = {}) { return emit(level, type, data); },
    capture,
    sampleFrame({
      nowSeconds,
      delta,
      stick,
      moved,
      facingMovement = moved,
      position,
      playerYaw,
      cameraYaw,
      facingSource = 'camera',
      lookChanged = false,
      cameraCollision = null,
      physicsState = null,
    }) {
      const nowMs = Number.isFinite(nowSeconds) ? nowSeconds * 1000 : (performanceRef?.now?.() ?? Date.now());
      const numericState = [delta, position?.x, position?.y, position?.z, playerYaw, cameraYaw];
      if (numericState.some((value) => !Number.isFinite(value))) {
        emitAnomaly('non-finite-frame-state', {
          delta: finite(delta),
          position: { x: finite(position?.x), y: finite(position?.y), z: finite(position?.z) },
          playerYaw: finite(playerYaw),
          cameraYaw: finite(cameraYaw),
        });
        return;
      }

      const movedMagnitude = Math.hypot(moved?.x ?? 0, moved?.z ?? 0);
      const facingMovementMagnitude = Math.hypot(facingMovement?.x ?? 0, facingMovement?.z ?? 0);
      const expectedYaw = facingSource === 'movement' && facingMovementMagnitude > 1e-8
        ? Math.atan2(facingMovement.x, -facingMovement.z)
        : facingSource === 'camera' ? cameraYaw : playerYaw;
      const facingError = angularDistance(playerYaw, expectedYaw);
      if (facingSource !== 'locked' && facingError > 0.02) {
        emitAnomaly('facing-desync', {
          facingSource,
          playerYaw: rounded(playerYaw),
          cameraYaw: rounded(cameraYaw),
          expectedYaw: rounded(expectedYaw),
          angularError: rounded(facingError),
        });
      }

      const stickMagnitude = Math.hypot(stick?.x ?? 0, stick?.y ?? 0);
      if (!lookChanged && stickMagnitude <= 0.04 && nowMs - lastSampleAt < sampleIntervalMs * 4) return;
      if (nowMs - lastSampleAt < sampleIntervalMs) return;
      lastSampleAt = nowMs;
      emit('info', 'movement-sample', {
        delta: rounded(delta),
        stick: { x: rounded(stick?.x ?? 0), y: rounded(stick?.y ?? 0), magnitude: rounded(stickMagnitude) },
        moved: { x: rounded(moved?.x ?? 0), y: rounded(moved?.y ?? 0), z: rounded(moved?.z ?? 0), magnitude: rounded(movedMagnitude) },
        facingMovement: { x: rounded(facingMovement?.x ?? 0), z: rounded(facingMovement?.z ?? 0), magnitude: rounded(facingMovementMagnitude) },
        position: { x: rounded(position.x), y: rounded(position.y), z: rounded(position.z) },
        playerYaw: rounded(playerYaw),
        cameraYaw: rounded(cameraYaw),
        facingSource,
        angularError: rounded(facingError),
        blocked: stickMagnitude > 0.2 && movedMagnitude < 1e-6,
        cameraCollision: cameraCollision ? {
          collided: Boolean(cameraCollision.collided),
          distance: rounded(cameraCollision.distance),
          directDistance: rounded(cameraCollision.directDistance),
        } : null,
        physicsState,
      });
    },
    attachCanvas(canvas) {
      const onLost = (event) => {
        event.preventDefault?.();
        emit('error', 'webgl-context-lost');
      };
      const onRestored = () => emit('info', 'webgl-context-restored');
      canvas?.addEventListener?.('webglcontextlost', onLost);
      canvas?.addEventListener?.('webglcontextrestored', onRestored);
      return () => {
        canvas?.removeEventListener?.('webglcontextlost', onLost);
        canvas?.removeEventListener?.('webglcontextrestored', onRestored);
      };
    },
    snapshot() { return entries.map((entry) => structuredClone(entry)); },
    errors() { return entries.filter((entry) => entry.level === 'error').map((entry) => structuredClone(entry)); },
    clear() { entries.length = 0; anomalyTimes.clear(); lastSampleAt = Number.NEGATIVE_INFINITY; },
    dispose() {
      windowRef?.removeEventListener?.('error', onWindowError);
      windowRef?.removeEventListener?.('unhandledrejection', onUnhandledRejection);
      disposed = true;
    },
  };

  if (windowRef) windowRef.__ZOMBIE_ZONE_DIAGNOSTICS__ = api;
  emit('info', 'diagnostics-ready', { capacity, sampleIntervalMs });
  return api;
}
