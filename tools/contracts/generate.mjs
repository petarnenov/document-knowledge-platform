import { mkdtemp, mkdir, rm, cp, readFile, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { resolve, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { root, run, filesUnder } from './lib.mjs';
import { ensureGenerator } from './generator.mjs';
import { installNullModel } from './null-model.mjs';

export async function generate(destination) {
  const generatorJar = await ensureGenerator();
  const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', 'java') : 'java';
  const scratch = await mkdtemp(join(tmpdir(), 'dkp-sdk-generation-'));
  try {
    for (const [language, generator, properties] of [
      ['java', 'java', 'library=native,hideGenerationTimestamp=true,useJakartaEe=true,openApiNullable=false,apiPackage=org.dkp.client.api,modelPackage=org.dkp.client.model,invokerPackage=org.dkp.client'],
      ['typescript', 'typescript-fetch', 'hideGenerationTimestamp=true,supportsES6=true,importFileExtension=.js'],
    ]) {
      const output = join(scratch, language);
      run(java, ['-jar', generatorJar,
        'generate', '-i', resolve(root, 'contracts/openapi/v1/platform.yaml'),
        '-g', generator, '-o', output, '--additional-properties', properties,
        '--global-property', 'apiTests=false,modelTests=false,apiDocs=false,modelDocs=false',
      ]);
      const target = join(destination, language, 'src');
      await rm(target, { recursive: true, force: true });
      await mkdir(target, { recursive: true });
      if (language === 'java') {
        await cp(join(output, 'src/main'), join(target, 'main'), { recursive: true });
      } else {
        for (const file of ['runtime.ts', 'index.ts', 'apis', 'models']) {
          await cp(join(output, file), join(target, file), { recursive: true });
        }
        await installNullModel(target);
      }
      // Normalize template trailing whitespace consistently without hand-editing output.
      for (const file of await filesUnder(target)) {
        const content = await readFile(file, 'utf8');
        await writeFile(file, content.replace(/[ \t]+$/gm, '').trimEnd() + '\n');
      }
      if (language === 'typescript') {
        // Consumer applications retain stricter flags than the generator. Publish
        // checked declarations at the SDK boundary rather than importing its internals.
        const declarations = join(destination, language, 'types');
        await rm(declarations, { recursive: true, force: true });
        const configuration = join(scratch, 'declarations.json');
        await writeFile(configuration, JSON.stringify({
          extends: resolve(root, 'generated-clients/typescript/tsconfig.json'),
          compilerOptions: {
            noEmit: false, declaration: true, emitDeclarationOnly: true,
            rootDir: target, outDir: declarations,
          },
          include: [join(target, '**/*.ts')],
        }));
        run(resolve(root, 'node_modules/.bin/tsc'), ['--project', configuration]);
      }
    }
  } finally {
    await rm(scratch, { recursive: true, force: true });
  }
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  await generate(resolve(root, 'generated-clients'));
  console.log('Generated Java and TypeScript platform clients.');
}
