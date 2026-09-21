'use strict';

/**
 * Replay each fixture against the Java /v2 service and diff against the golden Node
 * response. Exits non-zero if ANY case differs — wire this into CI as the gate that
 * an endpoint may only go live on /v2 with a 100% clean diff.
 *
 *   JAVA_BASE_URL=http://localhost:8081 USER_JWT=<test-token> node run.js
 */

const fs = require('fs');
const { loadFixtures, send, goldenPath } = require('./lib');
const { normalize } = require('./normalize');
const { deepDiff } = require('./diff');

async function main() {
  const javaBase = process.env.JAVA_BASE_URL || 'http://localhost:8081';
  const cases = loadFixtures();
  let failures = 0;

  for (const c of cases) {
    const gp = goldenPath(c.name);
    if (!fs.existsSync(gp)) {
      console.error(`SKIP  ${c.name}: no golden file (run record.js first)`);
      failures++;
      continue;
    }
    const golden = JSON.parse(fs.readFileSync(gp, 'utf8'));
    let actual;
    try {
      actual = await send(javaBase, c, c.javaPath);
    } catch (e) {
      console.error(`FAIL  ${c.name}: request error ${e.message}`);
      failures++;
      continue;
    }

    const ignore = c.ignore || [];
    const statusDiff = golden.status !== actual.status
      ? [`http status ${golden.status} (node) != ${actual.status} (java)`] : [];
    const bodyDiff = deepDiff(normalize(golden.body, ignore), normalize(actual.body, ignore));
    const diffs = [...statusDiff, ...bodyDiff];

    if (diffs.length === 0) {
      console.log(`PASS  ${c.name}`);
    } else {
      failures++;
      console.log(`FAIL  ${c.name}`);
      diffs.slice(0, 40).forEach((d) => console.log(`        - ${d}`));
      if (diffs.length > 40) console.log(`        ...and ${diffs.length - 40} more`);
    }
  }

  console.log(`\n${cases.length - failures}/${cases.length} passed.`);
  process.exit(failures === 0 ? 0 : 1);
}

main();
