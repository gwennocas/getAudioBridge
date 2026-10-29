// Summarize the latest connection from adb logcat -v threadtime -s getAudioBridge.
// Logs must be from one test day; optional PID excludes an earlier app instance.
import {readFile,writeFile} from 'node:fs/promises';
const [input,output,pidFilter]=process.argv.slice(2);
if(!input || !output) throw new Error('Usage: node scripts/summarize-native-log.mjs input.log output.json [pid]');
const lines=(await readFile(input,'utf8')).split(/\r?\n/);
let samples=[];
for(const line of lines) {
  const match=line.match(/^(\d{2}-\d{2}) (\d{2}:\d{2}:\d{2}\.\d+)\s+(\d+)\s+\d+\s+D getAudioBridge:\s+writes=(\d+) queuedMs=([\d.]+) underruns=(\d+) bufferFrames=(\d+) route=(.*?) metrics=(\{.*\})$/);
  if(!match || (pidFilter && match[3]!==pidFilter)) continue;
  const sample={time:`${new Date().getFullYear()}-${match[1]}T${match[2]}-03:00`,pid:Number(match[3]),writes:Number(match[4]),queuedMs:Number(match[5]),underruns:Number(match[6]),bufferFrames:Number(match[7]),route:match[8],stream:JSON.parse(match[9])};
  if(samples.length && (sample.pid!==samples.at(-1).pid || sample.writes<=samples.at(-1).writes)) samples=[];
  samples.push(sample);
}
if(samples.length<2) throw new Error('At least two samples from the latest connection are required');
const first=samples[0],last=samples.at(-1);
const range=values=>({min:Math.min(...values),max:Math.max(...values)});
const report={
  profile:'release',start:first.time,end:last.time,durationSeconds:(Date.parse(last.time)-Date.parse(first.time))/1000,sampleCount:samples.length,
  targetsMs:[...new Set(samples.map(s=>s.stream.targetMs))],routes:[...new Set(samples.map(s=>s.route))],
  packetsDuringInterval:last.stream.packets-first.stream.packets,
  missingDuringInterval:last.stream.lost-first.stream.lost,
  rebuffersDuringInterval:last.stream.rebuffers-first.stream.rebuffers,
  trimsDuringInterval:last.stream.trims-first.stream.trims,
  underrunsDuringInterval:last.underruns-first.underruns,
  nativeQueueMs:range(samples.map(s=>s.queuedMs)),nativeBufferFrames:range(samples.map(s=>s.bufferFrames)),
  jitterBufferMs:range(samples.map(s=>s.stream.bufferMs)),driftPpm:range(samples.map(s=>s.stream.driftPpm)),
  errors:samples.filter(s=>s.stream.error).map(s=>s.stream.error),
  first,last,
  note:'Native queue and jitter buffer are separate indicators, not physical headphone latency. This is a limited hardware observation, not a guarantee for every network or device.'
};
await writeFile(output,JSON.stringify(report,null,2));console.log(JSON.stringify(report,null,2));
