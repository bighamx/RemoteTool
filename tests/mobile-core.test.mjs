import {test} from 'node:test';
import assert from 'node:assert/strict';
import {parseEvent,readEvents,isFinal,isActive,coordinates,windowsKey} from '../WebApplication/wwwroot/mobile/mobile-core.mjs';

test('SSE handles comments, multiline JSON and event ids',()=>{
    assert.equal(parseEvent(': keepalive'),null);
    assert.deepEqual(parseEvent('id: 12\ndata: {"event":\ndata: "message.delta","delta":"中"}'),{id:'12',event:'message',data:{event:'message.delta',delta:'中'}});
});
test('SSE preserves split UTF-8 and CRLF across arbitrary network chunks',async()=>{
    const bytes=new TextEncoder().encode('id: 7\r\ndata: {"delta":"你好"}\r\n\r\n: keepalive\r\n\r\ndata: {"done":true}\n\n');
    const stream=new ReadableStream({start(controller){for(const byte of bytes)controller.enqueue(new Uint8Array([byte]));controller.close();}});
    const events=[];for await(const event of readEvents(stream))events.push(event);
    assert.equal(events.length,2);assert.equal(events[0].data.delta,'你好');assert.equal(events[0].id,'7');assert.equal(events[1].data.done,true);
});
test('unknown acceptance is neither active nor a final success',()=>{
    assert.equal(isFinal('acceptance_unknown'),false);assert.equal(isActive('acceptance_unknown'),false);
    assert.equal(isActive('submitting'),true);assert.equal(isFinal('interrupted'),true);
});
test('coordinates clamp at image bounds and physical keys map on Windows',()=>{
    assert.deepEqual(coordinates({clientX:-5,clientY:400},{left:0,top:0,width:100,height:200}),{x:0,y:1});
    assert.equal(windowsKey('KeyA'),65);assert.equal(windowsKey('MetaLeft'),91);assert.equal(windowsKey('Unknown'),undefined);
});
