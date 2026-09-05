import {readFileSync, readdirSync} from 'node:fs';
import {createHash} from 'node:crypto';

const lock = JSON.parse(readFileSync('formal/qualified/gate.lock.json', 'utf8'));
const fail = message => { throw new Error(message); };
const models = readdirSync('formal/dafny').filter(n => /^Qualified.*\.dfy$/.test(n)).sort();
if (JSON.stringify(models) !== JSON.stringify([...lock.models].sort())) fail('Qualified model inventory changed');
for (const [file, expected] of Object.entries(lock.resourceInputs)) {
  if (createHash('sha256').update(readFileSync(file)).digest('hex') !== expected) fail(`Qualified model input changed: ${file}`);
}
for (const file of models) {
  const source = readFileSync(`formal/dafny/${file}`, 'utf8');
  if (/\b(assume|axiom)\b|\{:\s*(extern|axiom)\b|\{:\s*verify\s+false\b|decreases\s+\*/.test(source)) fail(`Proof escape hatch: ${file}`);
}
if (!Number.isInteger(lock.verifiedObligations) || lock.verifiedObligations < 1 ||
    !Number.isInteger(lock.assertions) || lock.assertions < 1) fail('Empty proof/assertion inventory');
console.log(`Qualified boundary locked: ${models.length} models, ${lock.verifiedObligations} obligations`);
