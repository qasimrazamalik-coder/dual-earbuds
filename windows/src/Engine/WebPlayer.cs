// Dual Earbuds Pro - the browser player page served to phones.
namespace DualEarbuds
{
    public static class WebPlayer
    {
        public const string ForbiddenHtml = @"<!doctype html><html lang='en'><head><meta charset='utf-8'>
<meta name='viewport' content='width=device-width,initial-scale=1'><title>Dual Earbuds</title>
<style>body{margin:0;font-family:system-ui,sans-serif;background:#0B1220;color:#E6EDF7;display:flex;min-height:100vh;align-items:center;justify-content:center;text-align:center;padding:24px}
h1{font-size:22px}p{color:#8FA3BF;line-height:1.5}</style></head><body><div>
<h1>This link is missing its key</h1>
<p>Open the Dual Earbuds app on your laptop and scan the QR code on the Phones page,<br>or copy the full link shown there.</p>
</div></body></html>";

        public const string Html = @"<!doctype html>
<html lang='en'><head><meta charset='utf-8'>
<meta name='viewport' content='width=device-width,initial-scale=1,viewport-fit=cover'>
<meta name='theme-color' content='#0B1220'>
<title>Dual Earbuds</title>
<style>
:root{--bg:#0B1220;--card:#121B2E;--line:#22304A;--text:#E6EDF7;--muted:#8FA3BF;--accent:#14E0C2;--accent-ink:#04201B;--warn:#F5B84B;--bad:#FF6B6B}
*{box-sizing:border-box}
body{margin:0;font-family:system-ui,-apple-system,'Segoe UI',sans-serif;background:var(--bg);color:var(--text);min-height:100vh;display:flex;justify-content:center}
.wrap{width:100%;max-width:440px;padding:22px 18px 40px}
.brand{display:flex;align-items:center;gap:12px;margin-bottom:18px}
.logo{width:42px;height:42px;border-radius:12px;background:linear-gradient(135deg,#14E0C2,#2F7BFF);display:grid;place-items:center}
.logo svg{width:26px;height:26px}
.brand h1{font-size:20px;margin:0}
.brand p{margin:2px 0 0;color:var(--muted);font-size:13px}
.card{background:var(--card);border:1px solid var(--line);border-radius:18px;padding:18px;margin-bottom:14px}
.pc{display:flex;justify-content:space-between;align-items:center;font-size:14px}
.dot{width:10px;height:10px;border-radius:50%;background:var(--muted);display:inline-block;margin-right:8px}
.dot.on{background:var(--accent);box-shadow:0 0 10px var(--accent)}
.dot.warn{background:var(--warn)}
button.big{width:100%;padding:18px;font-size:18px;font-weight:700;border:0;border-radius:16px;background:var(--accent);color:var(--accent-ink);cursor:pointer}
button.big.stop{background:#1E2A42;color:var(--text);border:1px solid var(--line)}
.row{display:grid;grid-template-columns:72px 1fr 60px;gap:10px;align-items:center;margin-top:14px;font-size:14px;color:var(--muted)}
.row b{color:var(--text);text-align:right;font-weight:600}
input[type=range]{width:100%;accent-color:var(--accent)}
.status{margin-top:14px;font-size:14px;color:var(--muted);min-height:20px}
.stats{display:flex;gap:16px;font-size:12px;color:var(--muted);margin-top:8px}
details{font-size:14px;color:var(--muted)}
details summary{cursor:pointer;color:var(--text);font-weight:600}
details li{margin:6px 0;line-height:1.45}
</style></head><body><div class='wrap'>
<div class='brand'>
  <div class='logo'><svg viewBox='0 0 24 24' fill='none' stroke='#04201B' stroke-width='2.2' stroke-linecap='round'><path d='M7 14a3 3 0 1 1 0-6v8'/><path d='M17 10a3 3 0 1 1 0 6V8'/><path d='M10 12h4'/></svg></div>
  <div><h1>Dual Earbuds</h1><p>Laptop sound on this phone's earbuds</p></div>
</div>
<div class='card'>
  <div class='pc'><span><span id='dot' class='dot'></span><span id='pc'>Not connected</span></span><span id='rate'></span></div>
</div>
<button id='go' class='big'>Start listening</button>
<div class='card' style='margin-top:14px'>
  <div class='row' style='margin-top:0'><span>Volume</span><input id='vol' type='range' min='0' max='150' value='100'><b id='volv'>100%</b></div>
  <div class='row'><span>Buffer</span><input id='buf' type='range' min='60' max='600' step='10' value='180'><b id='bufv'>180 ms</b></div>
  <div class='status' id='st'>Connect your second earbuds to this phone, then tap Start.</div>
  <div class='stats'><span id='pk'>0 packets</span><span id='lost'>0 lost</span><span id='lat'></span></div>
</div>
<div class='card'><details><summary>Keep it playing with the screen off</summary><ol>
<li>Tap Start once, then lock the phone. A Dual Earbuds media notification keeps it running.</li>
<li>Chrome: Settings &gt; Apps &gt; Chrome &gt; Battery &gt; <b>Unrestricted</b> (Xiaomi/Redmi: <b>No restrictions</b>).</li>
<li>Xiaomi, Realme, Oppo, Vivo: lock Chrome in the recent-apps screen (pull it down or tap the lock icon).</li>
<li>For guaranteed background play on every phone, use the Dual Earbuds Android app.</li>
</ol></details></div>
<audio id='out' playsinline></audio>
</div>
<script>
'use strict';
const $=id=>document.getElementById(id);
const key=new URLSearchParams(location.search).get('k')||'';
let ctx=null,gain=null,dest=null,ws=null,playhead=0,running=false,packets=0,lost=0,lastSeq=-1,statusTimer=null,uiTimer=null,pcName='',useElement=true;
function load(k,d){try{const v=localStorage.getItem('de_'+k);return v===null?d:Number(v);}catch(e){return d;}}
function save(k,v){try{localStorage.setItem('de_'+k,String(v));}catch(e){}}
$('vol').value=load('vol',100);$('buf').value=load('buf',180);
const target=()=>Number($('buf').value)/1000;
function showVals(){$('volv').textContent=$('vol').value+'%';$('bufv').textContent=$('buf').value+' ms';}
showVals();
$('vol').oninput=()=>{showVals();save('vol',$('vol').value);if(gain)gain.gain.value=Number($('vol').value)/100;sendStatus();};
$('buf').oninput=()=>{showVals();save('buf',$('buf').value);sendStatus();};
function setDot(c){$('dot').className='dot'+(c?' '+c:'');}
function sendStatus(){
  if(!ws||ws.readyState!==1)return;
  ws.send(JSON.stringify({type:'status',device:'Phone browser',earbuds:'',battery:-1,volume:Number($('vol').value),bufferMs:Number($('buf').value),playing:running&&packets>0}));
}
function setupMedia(){
  if(!('mediaSession' in navigator))return;
  try{
    navigator.mediaSession.metadata=new MediaMetadata({title:'Laptop sound',artist:'Dual Earbuds',album:pcName||'Your laptop'});
    navigator.mediaSession.setActionHandler('pause',()=>stop());
    navigator.mediaSession.setActionHandler('stop',()=>stop());
    navigator.mediaSession.setActionHandler('play',()=>{if(!running)start();});
    navigator.mediaSession.playbackState='playing';
  }catch(e){}
}
function onAudio(buf){
  if(!ctx||buf.byteLength<12)return;
  const dv=new DataView(buf);
  const rate=dv.getInt32(0,true),seq=dv.getUint32(4,true);
  const frames=(buf.byteLength-8)/4;
  if(rate<8000||frames<=0||frames!==Math.floor(frames))return;
  if(lastSeq>=0){const gap=(seq-lastSeq-1)>>>0;if(gap>0&&gap<100000)lost+=gap;}
  lastSeq=seq;
  const pcm=new Int16Array(buf,8,frames*2);
  const b=ctx.createBuffer(2,frames,rate);
  const L=b.getChannelData(0),R=b.getChannelData(1);
  for(let i=0;i<frames;i++){L[i]=pcm[2*i]/32768;R[i]=pcm[2*i+1]/32768;}
  const now=ctx.currentTime;
  if(playhead<now+0.02)playhead=now+target();
  else if(playhead>now+target()+0.25)return;
  const s=ctx.createBufferSource();s.buffer=b;s.connect(gain);s.start(playhead);
  playhead+=frames/rate;packets++;
}
function onText(t){
  let m;try{m=JSON.parse(t);}catch(e){return;}
  if(m.type==='hello'){pcName=m.pc||'';$('pc').textContent='Connected to '+pcName;setDot('on');setupMedia();if(!m.rate)$('st').textContent='Connected. Start mirroring on the laptop.';}
  else if(m.type==='ping'){ws.send(JSON.stringify({type:'pong',t:m.t}));}
  else if(m.type==='state'){$('rate').textContent=m.mirroring?(m.rate/1000)+' kHz':'';if(!m.mirroring)$('st').textContent='The laptop stopped mirroring.';}
}
function connect(){
  const name=encodeURIComponent((navigator.userAgentData&&navigator.userAgentData.platform)||'Phone browser');
  ws=new WebSocket((location.protocol==='https:'?'wss://':'ws://')+location.host+'/ws?k='+encodeURIComponent(key)+'&client=web&name='+name);
  ws.binaryType='arraybuffer';
  ws.onopen=()=>{$('st').textContent='Connected. Play something on the laptop.';sendStatus();};
  ws.onmessage=e=>{if(typeof e.data==='string')onText(e.data);else onAudio(e.data);};
  ws.onclose=e=>{
    setDot('warn');
    if(!running)return;
    $('pc').textContent='Reconnecting...';
    $('st').textContent=e.code===1006?'Laptop not reachable. Same Wi-Fi? App still running? On public Wi-Fi, use a phone hotspot instead.':'Disconnected. Reconnecting...';
    setTimeout(()=>{if(running)connect();},1500);
  };
}
async function start(){
  try{
    ctx=ctx||new (window.AudioContext||window.webkitAudioContext)({latencyHint:'interactive'});
    await ctx.resume();
    if(!gain){
      gain=ctx.createGain();
      if(useElement&&ctx.createMediaStreamDestination){
        dest=ctx.createMediaStreamDestination();gain.connect(dest);
        const el=$('out');el.srcObject=dest.stream;
        try{await el.play();}catch(e){useElement=false;gain.disconnect();gain.connect(ctx.destination);}
      }else gain.connect(ctx.destination);
    }else if(useElement){try{await $('out').play();}catch(e){}}
    gain.gain.value=Number($('vol').value)/100;
    running=true;packets=0;lost=0;lastSeq=-1;playhead=0;
    $('go').textContent='Stop';$('go').className='big stop';
    connect();
    statusTimer=setInterval(sendStatus,5000);
    uiTimer=setInterval(()=>{
      $('pk').textContent=packets+' packets';$('lost').textContent=lost+' lost';
      if(ctx)$('lat').textContent='buffer '+Math.max(0,Math.round((playhead-ctx.currentTime)*1000))+' ms';
      if(ws&&ws.readyState===1&&packets>0)$('st').textContent='Playing. You can lock the phone now.';
    },1000);
  }catch(e){$('st').textContent='Could not start audio: '+e.message;}
}
function stop(){
  running=false;
  clearInterval(statusTimer);clearInterval(uiTimer);
  if(ws){try{ws.close(1000);}catch(e){}}
  try{$('out').pause();}catch(e){}
  if('mediaSession' in navigator)try{navigator.mediaSession.playbackState='paused';}catch(e){}
  $('go').textContent='Start listening';$('go').className='big';
  $('st').textContent='Stopped.';setDot('');$('pc').textContent='Not connected';
}
$('go').onclick=()=>{running?stop():start();};
</script></body></html>";
    }
}
