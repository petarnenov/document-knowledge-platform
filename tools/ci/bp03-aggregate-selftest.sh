#!/usr/bin/env bash
set -euo pipefail
cd /workspace
python -m coverage run --branch --include='*/bp03-aggregate-linux.py' \
  -m unittest discover -s tools/ci -p test_bp03_aggregate_linux.py
python -m coverage json -o /tmp/dkp222-controller-coverage.json
python - <<'PY'
import coverage, json, os, platform
if platform.python_version() != '3.14.10' or coverage.__version__ != '7.17.0' or os.environ.get('COVERAGE_CORE') != 'ctrace':
    raise ValueError('Pinned controller verification environment required')
with open('/tmp/dkp222-controller-coverage.json') as source:
    result = json.load(source)['totals']
if (result['num_statements'] <= 0 or result['num_branches'] <= 0
        or result['covered_lines'] / result['num_statements'] < .75
        or result['covered_branches'] / result['num_branches'] < .75):
    raise ValueError('Independent75% line and branch floors required')
print(json.dumps(dict(profile='bp03-aggregate-controller', result='PASS', coverage=result)))
PY
