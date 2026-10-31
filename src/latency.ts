// Independent diagnostic samples: never a measurement of PC-to-headphone latency.
export function partialLatencyMs(bufferMs:number, reportedMs:number|undefined, available:boolean, receiving:boolean): number|null {
  if (!receiving || !available || reportedMs === undefined || !Number.isFinite(reportedMs) || reportedMs < 0 || !Number.isFinite(bufferMs) || bufferMs < 0) return null;
  return bufferMs + reportedMs;
}
