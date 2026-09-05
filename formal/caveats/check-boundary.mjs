import {readFileSync, readdirSync} from 'node:fs';
import {createHash} from 'node:crypto';

const lock = JSON.parse(readFileSync('formal/caveats/gate.lock.json', 'utf8'));
const fail = message => { throw new Error(message); };
const discovered = readdirSync('formal/dafny').filter(n => /^(Caveat.*|QualifierLifecycle)\.dfy$/.test(n)).sort();
if (JSON.stringify(discovered) !== JSON.stringify([...lock.models].sort())) fail('Caveat proof manifest changed');
for (const [file, expected] of Object.entries(lock.resourceInputs)) {
  const actual = createHash('sha256').update(readFileSync(file)).digest('hex');
  if (actual !== expected) fail(`Caveat resource/profile boundary changed: ${file}`);
}
for (const name of discovered) {
  const source = readFileSync(`formal/dafny/${name}`, 'utf8');
  if (/\b(assume|axiom)\b|\{:\s*(extern|axiom)\b|\{:\s*verify\s+false\b|decreases\s+\*/.test(source)) {
    fail(`Unreviewed proof escape hatch in ${name}`);
  }
}
if (!Number.isInteger(lock.verifiedObligations) || lock.verifiedObligations < 1) fail('Empty proof count');
if (!Number.isInteger(lock.assertions) || lock.assertions < 1) fail('Empty finite assertion count');
console.log(`Caveat profile boundary locked: ${discovered.length} models, ${lock.verifiedObligations} obligations`);
