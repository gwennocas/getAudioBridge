import { invoke, isTauri } from '@tauri-apps/api/core';
import './style.css';

type Stream = {state:string; error:string|null; packets:number; bytes:number; lost:number; late:number; duplicates:number; invalid:number; rebuffers:number; trims:number; bufferMs:number; targetMs:number; driftPpm:number; jitterMs:number; peer:string|null;peak:number};
type Output = {running:boolean; paused?:boolean; backgroundService?:boolean; wakeLockHeld?:boolean; wifiLockHeld?:boolean; selectedOutput?:string; speakerAvailable?:boolean; headphonesAvailable?:boolean; error:string|null; writes:number; underruns:number; queuedMs:number; route:string; bufferFrames:number; lowLatency:boolean; timestampAvailable:boolean; hardwareQueueMs:number};
type Status = {stream:Stream; output:Output|null};
const icons = {
  wave: '<path d="M3 10v4m4-8v12m5-16v20m5-16v12m4-8v4"/>',
  pc: '<rect x="3" y="3" width="18" height="13" rx="2"/><path d="M8 21h8m-4-5v5"/>',
  phone:'<rect x="6" y="2" width="12" height="20" rx="3"/><path d="M10 18h4"/>',
  play:'<path d="m9 5 11 7-11 7z"/>', stop:'<rect x="6" y="6" width="12" height="12" rx="2"/>',
  arrow:'<path d="M4 12h16m-5-5 5 5-5 5"/>',
  headphones:'<path d="M3 14v-3a9 9 0 0 1 18 0v3"/><rect x="3" y="12" width="4" height="9" rx="2"/><rect x="17" y="12" width="4" height="9" rx="2"/>',
  refresh:'<path d="M20 7v5h-5M4 17v-5h5"/><path d="M6 6a8 8 0 0 1 13 2M5 16a8 8 0 0 0 13 2"/>',
  shield:'<path d="m12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6z"/><path d="m8 12 3 3 5-6"/>',
};
const icon = (name:keyof typeof icons) => `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${icons[name]}</svg>`;
const $ = <T extends HTMLElement=HTMLElement>(id:string) => document.getElementById(id) as T;
const native = isTauri();
let mobile = /Android/i.test(navigator.userAgent);
if (native) mobile = await invoke<string>('platform') === 'android';
let active = false, busy = false, previous: {bytes:number; time:number}|null = null;
let lastBackendError: string | null = null;
let routingBusy = false, latestOutput: Output|null = null;
const saved = (key:string, fallback:string) => localStorage.getItem(`gab-${key}`) || fallback;

$('app').innerHTML = `
<div class="shell">
 <header><a class="brand" href="#" aria-label="getAudioBridge"><span class="brand-icon">${icon('wave')}</span><span>getAudio<span class="brand-light">Bridge</span></span></a><span class="platform">${icon(mobile?'phone':'pc')}${mobile?'ANDROID · RECEPTOR':'WINDOWS · TRANSMISSOR'}</span></header>
 <main>
  <div class="intro"><div class="eyebrow">SEU ÁUDIO, DO OUTRO LADO.</div><h1>${mobile?'O som do PC.<br><span>No seu fone.</span>':'Do computador.<br><span>Direto para o seu fone.</span>'}</h1><p>${mobile?'Conecte ao computador na mesma rede Wi-Fi e ouça pela saída de áudio do celular.':'Transmita o áudio do Windows pela rede local. Conecte seu fone ao Android e dê play no computador.'}</p></div>
  <section class="route-strip" aria-label="Caminho do áudio"><span>${icon('pc')}<b>Windows</b><small>Captura do sistema</small></span><i>${icon('arrow')}</i><span class="wireless"><div class="signal"><em></em><em></em><em></em></div><b>Wi-Fi local</b><small>PCM · 48 kHz · estéreo</small></span><i>${icon('arrow')}</i><span>${icon('headphones')}<b>Seu fone</b><small>Saída do Android</small></span></section>
  <div class="workspace">
   <section class="panel connection"><div class="panel-heading"><h2>${mobile?'Conectar ao PC':'Configurar transmissão'}</h2><span id="state" class="badge"><i></i>Desconectado</span></div>
    <form id="connection-form">
    ${mobile?'':`<label for="device">Saída de áudio a capturar</label><div class="device-row"><select id="device"><option value="">Saída padrão do Windows</option></select><button id="refresh" type="button" class="icon-button" aria-label="Atualizar saídas de áudio">${icon('refresh')}</button></div><p class="hint">Capture a saída em que o computador está reproduzindo som.</p>`}
    <div class="fields"><div class="ip-field"><label for="ip">${mobile?'IP do computador':'Endereço local para escuta'}</label>${mobile?'<input id="ip" inputmode="decimal" placeholder="192.168.1.100" required autocomplete="off" spellcheck="false"/>':'<select id="ip"><option value="0.0.0.0">Todas as interfaces · 0.0.0.0</option></select>'}</div><div class="port-field"><label for="port">Porta UDP</label><input id="port" type="number" min="1024" max="65535" value="45150" required/></div></div>
    <p id="address-hint" class="hint">${mobile?'Use o endereço exibido no getAudioBridge do Windows.':'No Android, informe um dos IPs deste computador.'}</p>
    ${mobile?`<div class="buffer-control"><div><label for="buffer">Buffer de reprodução</label><output id="buffer-value" for="buffer">60 <small>ms</small></output></div><input id="buffer" type="range" min="20" max="120" step="5" value="60"/><div class="range-labels"><span>Menor atraso</span><span>Maior estabilidade</span></div></div>`:''}
    <button id="toggle" class="primary" type="submit">${icon('play')}<span>${mobile?'Conectar ao computador':'Iniciar transmissão'}</span></button>
    ${mobile?`<fieldset class="output-control"><legend>Saída de áudio</legend><div class="output-buttons" role="group" aria-label="Saída de áudio"><button type="button" data-output="system" aria-pressed="true" disabled>Sistema</button><button type="button" data-output="speaker" aria-pressed="false" disabled>Alto-falante</button><button type="button" data-output="headphones" aria-pressed="false" disabled>Fone / USB</button></div><p class="hint" id="output-hint">Conecte um fone com fio ou USB para habilitar a opção.</p><p class="hint" id="output-route" aria-live="polite"></p></fieldset>`:''}
    <div id="error" role="alert" hidden></div>
    </form>
    <div class="local-note">${icon('shield')}<span>Conexão direta na sua rede. Sem nuvem e sem conta.</span></div>
   </section>
   <section class="panel monitor"><div class="panel-heading"><h2>Monitor da ${mobile?'reprodução':'transmissão'}</h2><span class="live-label" id="live-label">EM ESPERA</span></div>
    <div class="activity" id="activity"><div class="activity-ring">${icon(mobile?'headphones':'wave')}</div><strong id="activity-title">${mobile?'Pronto para ouvir':'Pronto para transmitir'}</strong><p id="activity-description">${mobile?'Conecte para receber o áudio do Windows.':'Inicie e conecte o aplicativo no Android.'}</p></div>
    <div class="metrics"><div><small>${mobile?'Buffer atual':'Tráfego de áudio'}</small><strong id="metric-a">—</strong></div><div><small>${mobile?'Falhas AudioTrack':'Pacotes enviados'}</small><strong id="metric-b">—</strong></div><div><small>${mobile?'Pacotes ausentes':'Descartes no envio'}</small><strong id="metric-c">—</strong></div><div><small>${mobile?'Variação de chegada':'Formato'}</small><strong id="metric-d">${mobile?'—':'PCM 16-bit'}</strong></div></div>
    <div class="buffer-meter" ${mobile?'':'hidden'}><div><span>Ocupação do buffer</span><span id="buffer-meter-label">—</span></div><progress id="buffer-meter" max="160" value="0"></progress></div>
    <details><summary>Detalhes técnicos</summary><dl id="technical"><dt>Transporte</dt><dd>UDP · IPv4</dd><dt>Áudio</dt><dd>48.000 Hz · 2 canais · PCM16</dd><dt>Pacote</dt><dd>240 frames · 5 ms</dd></dl></details>
   </section>
  </div>
  <aside class="tip"><span class="tip-number">i</span><p>${mobile?'<b>Continue ouvindo com a tela bloqueada.</b> O áudio permanece ativo ao trocar de app ou fechar esta janela. Para encerrar, use Desconectar aqui ou nos controles de mídia. O buffer não representa a latência total até o fone.':'<b>Na mesma rede, do início ao fim.</b> Abra o app no Android, informe o IP deste PC e conecte. Se não houver conexão, permita a porta UDP no firewall e verifique o isolamento de clientes do roteador.'}</p></aside>
 </main><footer><span>getAudioBridge <small>v0.2</small></span><span>Áudio local. Conexão direta.</span></footer>
</div>`;

const ipInput = $<HTMLInputElement|HTMLSelectElement>('ip');
const portInput = $<HTMLInputElement>('port');
portInput.value = saved('port','45150');
if (mobile) { ipInput.value = saved('ip',''); $<HTMLInputElement>('buffer').value = saved('buffer','60'); updateBuffer(); }
function showError(e:unknown) { const el = $('error'); el.hidden = !e; el.textContent = e ? String(e) : ''; }
function updateBuffer() { const value = $<HTMLInputElement>('buffer').value; $('buffer-value').innerHTML = `${value} <small>ms</small>`; }
function controls() {
  const button = $<HTMLButtonElement>('toggle');
  button.disabled = busy || !native;
  button.classList.toggle('is-active', active);
  button.innerHTML = `${icon(active?'stop':'play')}<span>${busy?'Aguarde…':active?(mobile?'Desconectar':'Parar transmissão'):(mobile?'Conectar ao computador':'Iniciar transmissão')}</span>`;
  ipInput.disabled = active || busy; portInput.disabled = active || busy;
  if (!mobile) { $<HTMLSelectElement>('device').disabled = active || busy; $<HTMLButtonElement>('refresh').disabled = active || busy; }
}
async function loadDevices() {
  try {
    const current = $<HTMLSelectElement>('device').value;
    const devices = await invoke<{id:string;name:string}[]>('devices');
    const select = $<HTMLSelectElement>('device');
    select.replaceChildren(new Option('Saída padrão do Windows',''));
    devices.forEach(d => select.add(new Option(d.name,d.id)));
    select.value = current;
    const addresses = [...new Set(await invoke<string[]>('addresses'))];
    const ip = ipInput as HTMLSelectElement; const selected = ip.value;
    ip.replaceChildren(new Option('Todas as interfaces · 0.0.0.0','0.0.0.0'));
    addresses.forEach(a => ip.add(new Option(a,a))); ip.value = selected || '0.0.0.0';
    $('address-hint').textContent = addresses.length ? `IP para informar no Android: ${addresses.join(' ou ')}` : 'Nenhum IPv4 local encontrado. Verifique sua conexão Wi-Fi.';
  } catch(e) { showError(e); }
}
if (!mobile) { $('refresh').onclick = loadDevices; if (native) await loadDevices(); }
if (mobile) $<HTMLInputElement>('buffer').oninput = async () => {
  updateBuffer(); const bufferMs = Number($<HTMLInputElement>('buffer').value);
  localStorage.setItem('gab-buffer',String(bufferMs));
  if (native) try { await invoke('set_buffer',{bufferMs}); } catch(e) { showError(e); }
};
function outputControls(o: Output|null) {
  if (!mobile) return;
  for (const button of Array.from(document.querySelectorAll<HTMLButtonElement>('[data-output]'))) {
    const mode = button.dataset.output!;
    button.setAttribute('aria-pressed',String(mode === (o?.selectedOutput || 'system')));
    button.disabled = !native || routingBusy || !o || (mode === 'speaker' && !o.speakerAvailable) || (mode === 'headphones' && !o.headphonesAvailable);
  }
  $('output-hint').textContent = o?.selectedOutput === 'headphones' && !o.headphonesAvailable ? 'Fone desconectado. O Android está usando a saída do sistema.' : o?.headphonesAvailable ? 'Você pode trocar a saída durante a reprodução.' : 'Conecte um fone com fio ou USB para habilitar a opção.';
  $('output-route').textContent = o?.running ? `Saída em uso: ${o.route || 'Verificando…'}` : 'A saída escolhida será usada ao conectar.';
}
if (mobile) for (const button of Array.from(document.querySelectorAll<HTMLButtonElement>('[data-output]'))) button.onclick = async () => {
  if (routingBusy || !native) return;
  routingBusy = true; outputControls(latestOutput); showError(null);
  try { await invoke('set_output',{output:button.dataset.output}); await poll(); }
  catch (e) { showError(e); }
  finally { routingBusy = false; outputControls(latestOutput); }
};
$('connection-form').onsubmit = async e => {
  e.preventDefault(); if (busy || !native) return; busy = true; controls(); showError(null);
  try {
    const port = Number(portInput.value);
    if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('Use uma porta entre 1024 e 65535.');
    localStorage.setItem('gab-port',String(port));
    if (active) { await invoke(mobile?'disconnect':'stop'); active = false; }
    else if (mobile) {
      const ip = ipInput.value.trim();
      if (!/^\d{1,3}(\.\d{1,3}){3}$/.test(ip) || ip.split('.').some(p=> Number(p)>255)) throw new Error('Informe um IPv4 válido do computador.');
      localStorage.setItem('gab-ip',ip);
      await invoke('connect',{ip,port,bufferMs:Number($<HTMLInputElement>('buffer').value)}); active = true;
    } else { await invoke('start',{deviceId:$<HTMLSelectElement>('device').value,bindIp:ipInput.value,port}); active = true; }
    previous = null; await poll();
  } catch(e) { showError(e); }
  finally { busy = false; controls(); }
};
const labels: Record<string,string> = {stopped:'Parado',starting:'Iniciando',listening:'Aguardando celular',streaming:'Transmitindo',connecting:'Conectando',receiving:'Recebendo áudio',waiting:'Aguardando PC',disconnected:'Desconectado',error:'Falha'};
const textMetric = (id:string,value:string) => { $(id).textContent = value; };
async function poll() {
  if (!native) return;
  try {
    const {stream:s,output:o} = await invoke<Status>('status');
    latestOutput = o; outputControls(o);
    const paused = mobile && !!o?.paused;
    const playing = mobile ? !!o?.running && !paused && s.state === 'receiving' : s.state === 'streaming';
    if (!busy) { active = mobile ? !!o?.running : ['starting','listening','streaming'].includes(s.state); controls(); }
    $('state').classList.toggle('connected',playing);
    $('state').lastChild!.textContent = paused ? 'Áudio pausado' : labels[s.state] || s.state;
    $('activity').classList.toggle('playing',playing);
    $('live-label').textContent = playing?'AO VIVO':active?'AGUARDANDO':'EM ESPERA';
    $('activity-title').textContent = playing?(mobile?'Áudio em reprodução':'Seu áudio está a caminho'):active?(mobile?'Procurando o computador…':'Aguardando conexão…'):(mobile?'Pronto para ouvir':'Pronto para transmitir');
    $('activity-description').textContent = playing?(mobile?(o?.route || 'Saída do sistema'):`Android conectado · ${s.peer}`):active?(mobile?'Verifique se a transmissão está ativa no PC.':'Conecte o Android usando o IP deste computador.'):(mobile?'Conecte para receber o áudio do Windows.':'Inicie e conecte o aplicativo no Android.');
    if (paused) { $('activity-title').textContent = 'Áudio pausado'; $('activity-description').textContent = 'O sistema priorizou outro áudio. Retome pelos controles de mídia ou desconecte.'; }
    const now = performance.now(); const rate = previous && s.bytes >= previous.bytes ? (s.bytes - previous.bytes)*8/(now-previous.time)/1000 : 0;
    previous = {bytes:s.bytes,time:now};
    textMetric('metric-a',mobile?`${s.bufferMs.toFixed(1)} ms`:`${rate.toFixed(2)} Mbps`);
    textMetric('metric-b',mobile?String(o?.underruns ?? 0):s.packets.toLocaleString('pt-BR'));
    textMetric('metric-c',s.lost.toLocaleString('pt-BR'));
    textMetric('metric-d',mobile?`${s.jitterMs.toFixed(1)} ms`:'PCM 16-bit');
    if (mobile) {
      $<HTMLProgressElement>('buffer-meter').value = s.bufferMs;
      $('buffer-meter-label').textContent = `${s.bufferMs.toFixed(0)} / ${s.targetMs || $<HTMLInputElement>('buffer').value} ms alvo`;
      const rows: [string,string][] = [['Pacotes recebidos',String(s.packets)],['Atrasados / duplicados',`${s.late} / ${s.duplicates}`],['Rebufferizações / cortes',`${s.rebuffers} / ${s.trims}`],['Correção de relógio',`${s.driftPpm.toFixed(0)} ppm`],['Fila AudioTrack',`${(o?.queuedMs || 0).toFixed(1)} ms`],['Buffer nativo',`${o?.bufferFrames || 0} frames`],['Modo de baixa latência',o?.lowLatency?'Ativo':'Não concedido'],['Timestamp de áudio',o?.timestampAvailable?`${o.hardwareQueueMs.toFixed(1)} ms em fila`:'Indisponível'],['Pacotes inválidos',String(s.invalid)]];
      rows.unshift(['Pico PCM',s.peak > 0 ? `${(20*Math.log10(s.peak)).toFixed(1)} dBFS` : 'Silêncio']);
      rows.push(['Serviço em segundo plano',o?.backgroundService?'Ativo':'Parado']);
      $('technical').replaceChildren(...rows.flatMap(([k,v])=>{ const dt=document.createElement('dt'),dd=document.createElement('dd');dt.textContent=k;dd.textContent=v;return [dt,dd]; }));
    }
    const backendError = s.error || o?.error || null;
    if (backendError) { showError(backendError); lastBackendError = backendError; }
    else if (lastBackendError) { if ($('error').textContent === lastBackendError) showError(null); lastBackendError = null; }
  } catch(e) { showError(e); }
}
if (!native) showError('Prévia da interface. Abra o aplicativo Tauri para capturar ou reproduzir áudio.');
controls(); await poll();
// A new poll starts only after the previous one finishes, preventing IPC buildup.
async function refreshLoop() { await poll(); setTimeout(refreshLoop,600); }
setTimeout(refreshLoop,600);
