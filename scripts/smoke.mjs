// Hardware integration smoke test. Requires Windows debug WebView on 9224 and
// adb forward tcp:9223 localabstract:webview_devtools_remote_<Android app pid>.
// The audio itself continues to use the real LAN UDP socket, not the debug bridge.
import assert from 'node:assert/strict';
import { writeFile, mkdir } from 'node:fs/promises';
const pause = ms => new Promise(r=>setTimeout(r,ms));
async function open(port) {
  const pages = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
  const page = pages.find(p=>p.title==='getAudioBridge');
  assert(page,'Abra o getAudioBridge');
  const ws = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise((resolve,reject)=>{ws.onopen=resolve;ws.onerror=reject;});
  let id=0; const pending = new Map();
  ws.onmessage=({data})=>{const m=JSON.parse(data);if(m.id && pending.has(m.id)){pending.get(m.id)(m);pending.delete(m.id);}};
  const evaluate = async expression => {
    const request=++id;
    const response=new Promise((resolve,reject)=>{
      const timeout=setTimeout(()=>{pending.delete(request);reject(new Error('CDP timeout'));},15000);
      pending.set(request,m=>{clearTimeout(timeout);resolve(m);});
    });
    ws.send(JSON.stringify({id:request,method:'Runtime.evaluate',params:{expression,awaitPromise:true,returnByValue:true}}));
    const r=await response;
    if(r.error || r.result.exceptionDetails) throw new Error(JSON.stringify(r));
    return r.result.result.value;
  };
  return {evaluate,close:()=>ws.close(),invoke:(command,args={})=>evaluate(`window.__TAURI_INTERNALS__.invoke(${JSON.stringify(command)},${JSON.stringify(args)})`)};
}
const pc = await open(9224), android = await open(9223);
const ip=process.argv[2] || '192.168.1.2';
const report = {date:new Date().toISOString(),ip,checks:[],snapshots:[]};
try {
  let status=await android.invoke('status');
  if (!status.output.running) await android.invoke('connect',{ip,port:45150,bufferMs:60});
  await pause(2500);
  status=await android.invoke('status');
  assert.equal(status.stream.state,'receiving'); assert(status.output.running);
  report.snapshots.push({label:'start',...status});
  const packets=status.stream.packets;
  await pause(5000);
  status=await android.invoke('status');
  assert(status.stream.packets>packets+500); assert(status.stream.bufferMs<160);
  report.checks.push('Continuous native Wi-Fi UDP reception');
  for(const target of [20,120,60]) {
    await android.evaluate(`document.getElementById('buffer').value=${target};document.getElementById('buffer').dispatchEvent(new Event('input'));`);
    await pause(2000);
    status=await android.invoke('status'); assert.equal(status.stream.targetMs,target);
    assert(status.stream.bufferMs<target+45);
    report.snapshots.push({label:`buffer-${target}`,...status});
  }
  report.checks.push('20/120/60 ms buffer controls and bounds');
  const beforeRestart=status.stream.packets;
  await pc.invoke('stop'); await pause(300);
  await pc.invoke('start',{deviceId:'',bindIp:ip,port:45150});
  await pause(2500);
  status=await android.invoke('status');
  assert.equal(status.stream.state,'receiving');assert(status.stream.packets>beforeRestart);
  report.checks.push('Transmitter stop/restart without reconnecting Android');
  await android.invoke('disconnect');
  status=await android.invoke('status');
  assert.equal(status.output.running,false);assert.equal(status.stream.state,'disconnected');
  report.checks.push('Disconnect releases playback and receiver');
  await android.invoke('connect',{ip,port:45150,bufferMs:60});
  await pause(2500);
  status=await android.invoke('status');assert.equal(status.stream.state,'receiving');assert(status.output.running);
  report.checks.push('Reconnect restores playback');
  report.snapshots.push({label:'final',...status});
  console.log(JSON.stringify(report,null,2));
  await mkdir('.logs',{recursive:true});await writeFile('.logs/hardware-smoke.json',JSON.stringify(report,null,2));
} finally { pc.close();android.close(); }
