'use strict';

const fs = require('fs');
const path = require('path');

const FIXTURES_DIR = path.join(__dirname, 'fixtures');
const GOLDEN_DIR = path.join(__dirname, 'golden');

/** Replace ${VAR} with process.env.VAR (so no tokens/secrets live in fixture files). */
function subst(value) {
  if (typeof value === 'string') {
    return value.replace(/\$\{([A-Z0-9_]+)\}/g, (_, name) => process.env[name] ?? '');
  }
  if (Array.isArray(value)) return value.map(subst);
  if (value && typeof value === 'object') {
    const out = {};
    for (const [k, v] of Object.entries(value)) out[k] = subst(v);
    return out;
  }
  return value;
}

/** Load every fixture case from fixtures/*.json (each file is an array of cases). */
function loadFixtures() {
  const files = fs.readdirSync(FIXTURES_DIR).filter((f) => f.endsWith('.json'));
  const cases = [];
  for (const file of files) {
    const arr = JSON.parse(fs.readFileSync(path.join(FIXTURES_DIR, file), 'utf8'));
    for (const c of arr) cases.push({ ...c, _file: file });
  }
  return cases;
}

async function send(baseUrl, testCase, pathValue) {
  const url = baseUrl.replace(/\/$/, '') + (pathValue || testCase.path);
  const headers = subst(testCase.headers || {});
  const init = { method: testCase.method || 'GET', headers };
  if (testCase.body !== undefined) {
    init.headers = { 'Content-Type': 'application/json', ...headers };
    init.body = JSON.stringify(subst(testCase.body));
  }
  const res = await fetch(url, init);
  const text = await res.text();
  let json;
  try { json = JSON.parse(text); } catch { json = { __nonJson: text }; }
  return { status: res.status, body: json };
}

function goldenPath(name) {
  return path.join(GOLDEN_DIR, `${name}.json`);
}

function ensureGoldenDir() {
  if (!fs.existsSync(GOLDEN_DIR)) fs.mkdirSync(GOLDEN_DIR, { recursive: true });
}

module.exports = { loadFixtures, send, goldenPath, ensureGoldenDir, subst, GOLDEN_DIR };
