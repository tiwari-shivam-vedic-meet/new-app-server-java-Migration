'use strict';

/**
 * Remove volatile / environment-specific fields before comparing Node vs Java,
 * so diffs only flag REAL behavioural differences.
 *
 * - GLOBAL_VOLATILE keys are stripped wherever they appear (any depth).
 * - per-fixture `ignore` dot-paths (supporting [] for "every array element" and
 *   * for "any key") are stripped at their specific locations.
 */

const GLOBAL_VOLATILE = new Set([
  '__v', 'iat', 'exp', '$clusterTime', 'operationTime',
]);

function stripGlobal(value) {
  if (Array.isArray(value)) {
    return value.map(stripGlobal);
  }
  if (value && typeof value === 'object') {
    const out = {};
    for (const [k, v] of Object.entries(value)) {
      if (GLOBAL_VOLATILE.has(k)) continue;
      out[k] = stripGlobal(v);
    }
    return out;
  }
  return value;
}

/** Delete a dot-path like "result.list[].createdAt" or "result.*.token". */
function deletePath(obj, parts) {
  if (obj == null || parts.length === 0) return;
  const [head, ...rest] = parts;

  if (head === '[]') {
    if (Array.isArray(obj)) obj.forEach((el) => deletePath(el, rest));
    return;
  }
  if (head === '*') {
    if (obj && typeof obj === 'object') {
      Object.values(obj).forEach((v) => deletePath(v, rest));
    }
    return;
  }
  if (rest.length === 0) {
    if (obj && typeof obj === 'object') delete obj[head];
    return;
  }
  deletePath(obj?.[head], rest);
}

function normalize(response, ignorePaths = []) {
  let out = stripGlobal(response);
  for (const path of ignorePaths) {
    deletePath(out, path.split('.').flatMap((p) => {
      // split "list[]" into ["list","[]"]
      const m = p.match(/^([^\[]+)(\[\])?$/);
      return m && m[2] ? [m[1], '[]'] : [p];
    }));
  }
  return out;
}

module.exports = { normalize, GLOBAL_VOLATILE };
