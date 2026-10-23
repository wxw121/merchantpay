import { createHash } from 'node:crypto';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';

const root = fileURLToPath(new URL('../../', import.meta.url));
const require = createRequire(new URL('../../sdk/package.json', import.meta.url));
const { default: openapiTS, astToString } = require('openapi-typescript');
const { values } = parseArgs({ options: {
  check: { type: 'boolean', default: false },
  schema: { type: 'string', default: resolve(root, 'docs/api/openapi.json') },
  'output-dir': { type: 'string', default: resolve(root, 'sdk/src/generated') },
} });
const input = await readFile(values.schema, 'utf8');
const schema = JSON.parse(input);
const header = '// Generated from docs/api/openapi.json. Do not edit; run pnpm --dir sdk generate.\n';
const digest = createHash('sha256').update(JSON.stringify(schema)).digest('hex');
const methods = [];
const identifiers = new Set();
const model = (ref) => {
  if (!ref?.startsWith('#/components/schemas/')) throw new Error('Expected local component schema reference');
  return ref.slice('#/components/schemas/'.length);
};
for (const [path, item] of Object.entries(schema.paths)) {
  for (const [method, operation] of Object.entries(item)) {
    if (method === 'parameters') continue;
    if (!['get', 'post'].includes(method)) throw new Error(`Unsupported operation method: ${method}`);
    const id = operation.operationId;
    if (!/^[A-Za-z_$][\w$]*$/.test(id) || identifiers.has(id)) throw new Error('Invalid or duplicate operationId');
    identifiers.add(id);
    const parameters = [...(item.parameters ?? []), ...(operation.parameters ?? [])];
    if (parameters.some((p) => !['path', 'header'].includes(p.in))) throw new Error('Update generator to support new parameter kinds');
    const pathParams = parameters.filter((p) => p.in === 'path').map((p) => p.name);
    if (pathParams.some((p) => !/^[A-Za-z_$][\w$]*$/.test(p))) throw new Error('Unsupported path parameter name');
    const requestRef = operation.requestBody?.content?.['application/json']?.schema?.$ref;
    const success = Object.entries(operation.responses).find(([status]) => /^2\d\d$/.test(status));
    const responseModel = model(success?.[1]?.content?.['application/json']?.schema?.$ref);
    const requestModel = requestRef && model(requestRef);
    const amountPattern = requestModel && schema.components.schemas[requestModel].properties?.amountMinor?.pattern;
    const descriptor = { operationId: id, method: method.toUpperCase(), path,
      successStatus: Number(success[0]), ...(amountPattern ? { amountPattern } : {}) };
    const args = [...pathParams.map((p) => `${p}: string`), ...(requestModel ? [`body: components["schemas"][${JSON.stringify(requestModel)}]`] : []), 'options?: RequestOptions'];
    methods.push(`  ${id}(${args.join(', ')}): Promise<components["schemas"][${JSON.stringify(responseModel)}]> {\n` +
      `    return this.request(${JSON.stringify(descriptor)}, { ${pathParams.join(', ')} }, ${requestModel ? 'body' : 'undefined'}, options);\n  }`);
  }
}
const artifacts = new Map([
  ['models.ts', header + astToString(await openapiTS(schema))],
  ['client.ts', header + `// Contract SHA-256: ${digest}\nimport type { components } from './models.ts';\nimport { MerchantTransport, type RequestOptions } from '../transport.ts';\n\nexport class MerchantClient extends MerchantTransport {\n${methods.join('\n\n')}\n}\n`],
]);
let drift = false;
if (!values.check) await mkdir(values['output-dir'], { recursive: true });
for (const [name, contents] of artifacts) {
  const output = resolve(values['output-dir'], name);
  if (values.check) {
    const existing = await readFile(output, 'utf8').catch((error) => { if (error.code === 'ENOENT') return null; throw error; });
    if (existing !== contents) { console.error(`Generated client drift: ${name}; run pnpm --dir sdk generate`); drift = true; }
  } else await writeFile(output, contents, 'utf8');
}
if (drift) process.exitCode = 1;
else console.log(values.check ? 'Generated client matches OpenAPI.' : 'Generated TypeScript models and Node client.');
