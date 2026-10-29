// Local development diagnostics for the app's debug WebView. Never required at runtime.
import { readFile } from 'node:fs/promises';
const [port = '9223', expressionFile] = process.argv.slice(2);
const pages = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
const page = pages.find(p => p.title === 'getAudioBridge');
if (!page) throw new Error('WebView getAudioBridge não encontrada');
const expression = expressionFile ? await readFile(expressionFile,'utf8') : 'document.body.innerText';
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((resolve,reject) => { ws.onopen = resolve; ws.onerror = reject; });
const response = new Promise((resolve,reject)=>{
  const timer = setTimeout(()=>{ws.close();reject(new Error('CDP timeout'));},15000);
  ws.onmessage = ({data})=>{ const msg=JSON.parse(data); if(msg.id===1){clearTimeout(timer);resolve(msg);} };
});
ws.send(JSON.stringify({id:1,method:'Runtime.evaluate',params:{expression,awaitPromise:true,returnByValue:true}}));
const result = await response;
ws.close();
if(result.error || result.result?.exceptionDetails) { console.error(JSON.stringify(result,null,2)); process.exitCode=1; }
else console.log(JSON.stringify(result.result.result.value,null,2));
