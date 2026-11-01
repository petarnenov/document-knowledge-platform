# Observation schema reproduction

These are future fixture evidence shapes, not accepted runtime observations, business API contracts, authority tokens or a readiness result. `OBSERVED` states a case observation; continuousBorrowClaim is always NOT_CLAIMED. UNKNOWN settlement cannot be OBSERVED. POOL_PENDING_WAIT with UNAVAILABLE pool evidence must be INCOMPLETE; an OBSERVED pool case therefore requires SAMPLED_ONLY evidence. This field constraint does not certify measurement truth. The specification/evidence reviewer must check source/identity/timing/authority semantics; JSON schema alone cannot establish truth.

Use the repository's existing locked Ajv8.20.0 installation. From the repository root, set DKP_AJV_MODULE to the absolute installed `ajv@8.20.0/node_modules/ajv/dist/2020.js` path (the author used the existing integration worktree installation without copying or modifying dependencies), then execute:

```sh
node <<'JS'
const fs = require('node:fs');
const Ajv = require(process.env.DKP_AJV_MODULE);
const base = 'openspec/changes/dkp-005-deadline-fixture/contracts/';
const validate = new Ajv.default({strict:true,allErrors:true}).compile(
  JSON.parse(fs.readFileSync(base+'observation-receipt.schema.json','utf8')));
const fixtures = JSON.parse(fs.readFileSync(base+'receipt-fixtures.json','utf8'));
for (const fixture of fixtures) {
  if (validate(fixture.value) !== fixture.valid) throw new Error('Fixture expectation failed');
}
console.log(JSON.stringify({valid:fixtures.filter(x=>x.valid).length,
  invalid:fixtures.filter(x=>!x.valid).length,result:'PASS'}));
JS
```

Pin/runtime evidence remains in research-inputs.json and research.md; fetched primary sources are public official release-tag inputs. No MBean, Attach/JMX, OIDC, provider or SQL operation occurs in this reproduction. No dependency/contract number is changed.
