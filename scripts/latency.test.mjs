import {test} from 'node:test';
import assert from 'node:assert/strict';
import {partialLatencyMs} from '../src/latency.ts';
test('uses actual buffer occupancy and adds the reported presentation queue once',()=>{
  assert.equal(partialLatencyMs(52,15,true,true),67);
  assert.equal(partialLatencyMs(60,0,true,true),60);
});
test('does not show a total for an unavailable report, inactive stream or invalid data',()=>{
  for(const args of [[60,undefined,false,true],[60,0,false,true],[60,15,true,false],[60,NaN,true,true],[-1,15,true,true]])
    assert.equal(partialLatencyMs(...args),null);
});
