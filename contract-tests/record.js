'use strict';

/**
 * Record golden responses from the CURRENT Node service (the source of truth).
 * Run this against the TEST server before migrating an endpoint.
 *
 *   NODE_BASE_URL=https://<test-node-host> USER_JWT=<test-token> node record.js
 *
 * Saves golden/<case name>.json for each fixture case.
 */

const fs = require('fs');
const { loadFixtures, send, goldenPath, ensureGoldenDir } = require('./lib');

async function main() {
  const nodeBase = process.env.NODE_BASE_URL;
  if (!nodeBase) {
    console.error('Set NODE_BASE_URL to the (TEST) Node service base url.');
    process.exit(2);
  }
  ensureGoldenDir();
  const cases = loadFixtures();
  for (const c of cases) {
    try {
      const { status, body } = await send(nodeBase, c, c.nodePath);
      fs.writeFileSync(goldenPath(c.name), JSON.stringify({ status, body }, null, 2));
      console.log(`recorded  ${c.name}  (HTTP ${status})`);
    } catch (e) {
      console.error(`FAILED    ${c.name}: ${e.message}`);
    }
  }
  console.log(`\nGolden files written to contract-tests/golden/`);
}

main();
