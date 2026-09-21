'use strict';

/** Deep compare two normalized JSON values; return a list of field-level mismatches. */
function deepDiff(a, b, path = '') {
  const diffs = [];

  if (typeA(a) !== typeA(b)) {
    diffs.push(`${path || '<root>'}: type ${typeA(a)} (node) != ${typeA(b)} (java)`);
    return diffs;
  }

  if (Array.isArray(a)) {
    if (a.length !== b.length) {
      diffs.push(`${path}: array length ${a.length} (node) != ${b.length} (java)`);
    }
    const n = Math.min(a.length, b.length);
    for (let i = 0; i < n; i++) {
      diffs.push(...deepDiff(a[i], b[i], `${path}[${i}]`));
    }
    return diffs;
  }

  if (a && typeof a === 'object') {
    const keys = new Set([...Object.keys(a), ...Object.keys(b)]);
    for (const k of keys) {
      if (!(k in a)) { diffs.push(`${path}.${k}: missing in node, present in java`); continue; }
      if (!(k in b)) { diffs.push(`${path}.${k}: present in node, missing in java`); continue; }
      diffs.push(...deepDiff(a[k], b[k], path ? `${path}.${k}` : k));
    }
    return diffs;
  }

  if (a !== b) {
    diffs.push(`${path}: ${JSON.stringify(a)} (node) != ${JSON.stringify(b)} (java)`);
  }
  return diffs;
}

function typeA(v) {
  if (Array.isArray(v)) return 'array';
  if (v === null) return 'null';
  return typeof v;
}

module.exports = { deepDiff };
