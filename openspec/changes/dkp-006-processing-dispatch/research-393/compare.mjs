import {readFileSync, writeFileSync} from 'node:fs';
import {pathToFileURL} from 'node:url';
import {assess, inputRows} from '../research-379/conformance.mjs';

export function evaluate(vectors, output, reference) {
  const rows = assess(vectors, output, reference);
  const actual = new Map(output.trim().split('\n').map(line => line.split('\t')));
  return rows.map(row => ({...row,
    actual: actual.get(row.id) === 'REJECT' ? 'REJECT' : Buffer.from(actual.get(row.id), 'base64').toString('utf8')}));
}

export function summarize(rows) {
  return {total:rows.length, passed:rows.filter(row=>row.java==='PASS').length,
    failed:rows.filter(row=>row.java==='FAIL').map(row=>row.id),
    javascriptValuePositives:rows.filter(row=>row.reference==='PASS').length,
    javascriptRawNegatives:'NOT_PROVEN_BY_VALUE_REFERENCE'};
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [action, directory] = process.argv.slice(2);
  const shared = JSON.parse(readFileSync(new URL('../research-379/conformance-vectors.json', import.meta.url))).vectors;
  const additional = JSON.parse(readFileSync(new URL('./additional-vectors.json', import.meta.url))).vectors;
  const vectors = [...shared, ...additional];
  if(action === 'prepare') writeFileSync(directory+'/input.tsv',inputRows(vectors));
  else if(action === 'assess') {
    const {default:reference} = await import(pathToFileURL(directory+'/reference.mjs'));
    const modes={};
    for(const mode of ['TITANIUM_STRICT','ERDTMAN_STRICT','GRAALJS_STRICT']) {
      const rows=evaluate(vectors,readFileSync(directory+'/'+mode+'.tsv','utf8'),reference);
      modes[mode]={shared:summarize(rows.slice(0,shared.length)),additional:summarize(rows.slice(shared.length)),rows};
    }
    writeFileSync(directory+'/observed-results.json',JSON.stringify({status:'RESEARCH_ONLY_NO_RUNTIME_SELECTION',modes},null,2)+'\n');
  } else throw new Error('Exact research action required');
}
