import {readFileSync, readdirSync} from 'node:fs';

const gate = JSON.parse(readFileSync('formal/caveats/gate.json', 'utf8'));
const fail = message => { throw new Error(message); };
const discovered = readdirSync('formal/dafny').filter(n => /^(Caveat.*|QualifierLifecycle)\.dfy$/.test(n)).sort();
if (JSON.stringify(discovered) !== JSON.stringify([...gate.models].sort())) fail('Caveat proof manifest changed');
for (const name of discovered) {
  const source = readFileSync(`formal/dafny/${name}`, 'utf8');
  if (/\b(assume|axiom)\b|\{:\s*(extern|axiom)\b|\{:\s*verify\s+false\b|decreases\s+\*/.test(source)) {
    fail(`Unreviewed proof escape hatch in ${name}`);
  }
}
for (const field of ['verificationTimeLimitSeconds', 'proofResourceLimit']) {
  if (!Number.isInteger(gate[field]) || gate[field] < 1) fail(`Invalid proof budget: ${field}`);
}
if (discovered.length === 0) fail('Empty proof inventory');
console.log(`Caveat boundary checked: ${discovered.length} models`);
