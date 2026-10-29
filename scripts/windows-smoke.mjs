// Exercise the installed Windows app using an explicitly enabled local WebView2
// debugging port (9224). The production executable does not set that port itself.
import assert from 'node:assert/strict';
import dgram from 'node:dgram';
import {writeFile, mkdir} from 'node:fs/promises';
const ip=process.argv[2] || '192.168.1.2';
const pages=await(await fetch('http://127.0.0.1:9224/json')).json();
const ws=new WebSocket(pages.find(p=>p.title==='getAudioBridge').webSocketDebuggerUrl);
await new Promise((resolve,reject)=>{ws.onopen=resolve;ws.onerror=reject;});
let request=0;
const pending=new Map();
ws.onmessage=({data})=>{const m=JSON.parse(data);if(pending.has(m.id)){pending.get(m.id)(m);pending.delete(m.id);}};
async function invoke(command,args={}) {
  const id=++request;
  const response=new Promise((resolve,reject)=>{
    const timer=setTimeout(()=>{pending.delete(id);reject(new Error('CDP timeout'));},15000);
    pending.set(id,m=>{clearTimeout(timer);resolve(m);});
  });
  ws.send(JSON.stringify({id,method:'Runtime.evaluate',params:{expression:`window.__TAURI_INTERNALS__.invoke(${JSON.stringify(command)},${JSON.stringify(args)})`,awaitPromise:true,returnByValue:true}}));
  const result=await response;
  if(result.error || result.result.exceptionDetails) throw new Error(JSON.stringify(result));
  return result.result.result.value;
}
const pause=ms=>new Promise(r=>setTimeout(r,ms));
const report={date:new Date().toISOString(),checks:[],snapshots:[]};
let socket;
try {
  const before=await invoke('status');
  assert.equal(before.stream.state,'streaming');
  report.snapshots.push({label:'before',...before});
  socket=dgram.createSocket('udp4');
  for(const packet of [Buffer.alloc(2048),Buffer.alloc(5)]) {
    await new Promise((resolve,reject)=>socket.send(packet,45150,ip,e=>e?reject(e):resolve()));
  }
  await pause(1500);
  const after=await invoke('status');
  assert.equal(after.stream.state,'streaming');
  assert(after.stream.invalid>=before.stream.invalid+2,'Malformed datagrams did not reach the server');
  assert(after.stream.packets>before.stream.packets);
  report.checks.push('Oversized and malformed UDP controls do not terminate capture');
  socket.close();socket=undefined;
  await invoke('stop');
  socket=dgram.createSocket('udp4');
  await new Promise((resolve,reject)=>{socket.once('error',reject);socket.bind(45150,ip,resolve);});
  await assert.rejects(()=>invoke('start',{deviceId:'',bindIp:ip,port:45150}));
  report.checks.push('Occupied port produces an error without leaving a transmitter running');
  socket.close();socket=undefined;
  await pause(200);
  await invoke('start',{deviceId:'',bindIp:ip,port:45150});
  await pause(2500);
  const restarted=await invoke('status');assert.equal(restarted.stream.state,'streaming');
  assert(restarted.stream.packets>0);assert.equal(restarted.stream.error,null);
  report.checks.push('Transmission and Android handshake recover after restart');
  report.snapshots.push({label:'restarted',...restarted});
  await mkdir('.logs',{recursive:true});await writeFile('.logs/windows-stable-smoke.json',JSON.stringify(report,null,2));
  console.log(JSON.stringify(report,null,2));
} finally {socket?.close();ws.close();}
