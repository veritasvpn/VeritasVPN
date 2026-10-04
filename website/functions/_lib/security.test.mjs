import { readFile } from 'node:fs/promises';
import { webcrypto } from 'node:crypto';
import assert from 'node:assert/strict';
import test from 'node:test';
globalThis.crypto ||= webcrypto;
const source = await readFile(new URL('./security.js', import.meta.url), 'utf8');
const {rateLimit, verifyTurnstile, boundedJSON} = await import('data:text/javascript;base64,'+Buffer.from(source).toString('base64'));
test('limiter fails closed and authenticates each attempt', async () => {
 const req=new Request('https://veritasvpn.cloud/',{headers:{'CF-Connecting-IP':'192.0.2.1'}});
 const env={TOOLS_RATE_LIMIT_SECRET:'a'.repeat(64)};
 assert.equal((await rateLimit(req,{}, {bucket:'check-ip'})).status,503);
 const original=globalThis.fetch;
 try {
  let count=0;
  globalThis.fetch=async (url,init)=>{
   assert.equal(url,'https://api.veritasvpn.cloud/api/v1/auth/tool-limit');
   assert.match(init.headers['X-Tool-Signature'],/^[a-f0-9]{64}$/);
   assert.equal(JSON.parse(init.body).ip,'192.0.2.1');
   return new Response(null,{status:++count<=10?204:429});
  };
  const results=await Promise.all(Array.from({length:50},()=>rateLimit(req,env,{bucket:'check-ip'})));
  assert.equal(results.filter(r=>r===null).length,10);
  globalThis.fetch=async()=>{throw new Error('timeout')};
  assert.equal((await rateLimit(req,env,{bucket:'check-ip'})).status,503);
  globalThis.fetch=async()=>new Response(null,{status:401});
  assert.equal((await rateLimit(req,env,{bucket:'check-ip'})).status,503);
 } finally {globalThis.fetch=original}
});
test('missing challenge secret never succeeds',async()=>assert.equal((await verifyTurnstile({},'arbitrary','192.0.2.1')).ok,false));
test('bounded JSON rejects oversized bodies',async()=>{
 await assert.rejects(boundedJSON(new Response('x'.repeat(4097))));
 assert.deepEqual(await boundedJSON(new Response('{"ok":true}')),{ok:true});
});
