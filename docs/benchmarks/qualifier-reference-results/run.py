"""Reproduce the matched JVM trials using two already-running nREPLs."""
import argparse
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--baseline-port', type=int, default=7789)
parser.add_argument('--candidate-port', type=int, default=7788)
parser.add_argument('--output', type=Path, default=Path('target/qualifier-reference-reproduction'))
args = parser.parse_args()
root = Path(__file__).resolve().parents[3]
output = args.output.resolve()
output.mkdir(parents=True, exist_ok=True)
harness = root / 'modules/eacl-datalevin/test/eacl/bench/qualifier_storage_test.clj'

def clj_string(value):
    return '"' + str(value).replace('\\', '\\\\').replace('"', '\\"') + '"'

for phase in ['trial', 'isolated']:
    for trial in range(1, 4):
        sides = [('baseline', args.baseline_port), ('candidate', args.candidate_port)]
        if trial % 2 == 0:
            sides.reverse()
        for side, port in sides:
            backends = '[:datascript]' if phase == 'isolated' else '[:datascript :datomic :datahike :datalevin]'
            prefix = output / f'{side}-{phase}-{trial}-'
            marker = f'QUALIFIER_COMPLETED_{side}_{phase}_{trial}'
            run = f'''(doseq [backend {backends}]
              (eacl.bench.qualifier-storage-test/run-backend! backend 2000
                (str {clj_string(prefix)} (name backend) ".edn")))'''
            if phase == 'isolated':
                run = f'''(let [measure eacl.bench.qualifier-storage-test/measure]
                  (with-redefs [eacl.bench.qualifier-storage-test/measure
                    (fn [operation batches iterations]
                      (measure operation batches (if (= 100 iterations) 1000 iterations)))]
                    {run}))'''
            expression = f'(do (load-file {clj_string(harness)}) {run} (println {clj_string(marker)}))'
            print(side, phase, trial, flush=True)
            result = subprocess.run(['clj-nrepl-eval', '-p', str(port), expression],
                                    text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=True)
            (output / f'{side}-{phase}-{trial}.log').write_text(result.stdout)
            if marker not in result.stdout:
                raise RuntimeError(f'nREPL did not finish {side} {phase} {trial}; inspect its log')
