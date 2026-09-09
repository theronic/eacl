import {readFileSync, readdirSync} from 'node:fs';

const gate = JSON.parse(readFileSync('formal/qualified/gate.json', 'utf8'));
const fail = message => { throw new Error(message); };
const models = readdirSync('formal/dafny').filter(n => /^Qualified.*\.dfy$/.test(n)).sort();
if (JSON.stringify(models) !== JSON.stringify([...gate.models].sort())) fail('Qualified model inventory changed');
for (const file of models) {
  const source = readFileSync(`formal/dafny/${file}`, 'utf8');
  if (/\b(assume|axiom)\b|\{:\s*(extern|axiom)\b|\{:\s*verify\s+false\b|decreases\s+\*/.test(source)) fail(`Proof escape hatch: ${file}`);
}
for (const field of ['verificationTimeLimitSeconds', 'proofResourceLimit']) {
  if (!Number.isInteger(gate[field]) || gate[field] < 1) fail(`Invalid proof budget: ${field}`);
}
if (models.length === 0) fail('Empty proof inventory');
console.log(`Qualified boundary checked: ${models.length} models`);
