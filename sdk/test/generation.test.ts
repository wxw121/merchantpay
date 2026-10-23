import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { test } from 'node:test';

const generator = fileURLToPath(new URL('../../scripts/api/generate-client.mjs', import.meta.url));
const contract = fileURLToPath(new URL('../../docs/api/openapi.json', import.meta.url));

test('generation is deterministic and --check rejects edited artifacts and contract drift', async () => {
  const directory = await mkdtemp(resolve(tmpdir(), 'merchantpay-sdk-'));
  try {
    const run = (...args: string[]) => spawnSync(process.execPath, [generator, '--output-dir', directory, ...args], { encoding: 'utf8' });
    assert.equal(run().status, 0);
    const first = await readFile(resolve(directory, 'client.ts'), 'utf8');
    assert.equal(run().status, 0);
    assert.equal(await readFile(resolve(directory, 'client.ts'), 'utf8'), first);
    assert.equal(run('--check').status, 0);
    await writeFile(resolve(directory, 'client.ts'), first + '\n// edit\n');
    const edited = run('--check');
    assert.equal(edited.status, 1);
    assert.match(edited.stderr, /drift: client.ts/);
    assert.equal(await readFile(resolve(directory, 'client.ts'), 'utf8'), first + '\n// edit\n', '--check must not repair files');
    assert.equal(run().status, 0);
    const schema = JSON.parse(await readFile(contract, 'utf8'));
    schema.components.schemas.CreatePayment.properties.customerReference = { type: 'string' };
    const changed = resolve(directory, 'changed-openapi.json');
    await writeFile(changed, JSON.stringify(schema));
    const drift = run('--check', '--schema', changed);
    assert.equal(drift.status, 1);
    assert.match(drift.stderr, /drift: models.ts/);
    assert.match(drift.stderr, /drift: client.ts/);
  } finally { await rm(directory, { recursive: true, force: true }); }
});
