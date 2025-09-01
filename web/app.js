const API = window.location.origin;
const $  = s => document.querySelector(s);
const $$ = s => document.querySelectorAll(s);
let chart;

(async function guard(){
  const r = await fetch(`${API}/api/me`);
  if (!r.ok) { location.href = "/login.html"; return; }
})();

function toastSmart(message, emoji='✨'){
  let box = document.createElement('div');
  box.className = 'ts-smart-card';
  box.innerHTML = `<div class="ts-smart-emoji">${emoji}</div><div class="ts-smart-text">${message}</div>`;
  document.body.appendChild(box);
  setTimeout(()=> box.classList.add('show'), 20);
  setTimeout(()=> { box.classList.remove('show'); setTimeout(()=>box.remove(), 400); }, 3200);
}
function toast(m){ const el = $("#ocrHint"); if(!el) return; el.textContent = m; setTimeout(()=>el.textContent="",3000); }
async function fetchJSON(url, opts={}) {
  const r = await fetch(url,{ headers:{'Content-Type':'application/json'}, credentials:"include", ...opts });
  if(!r.ok){ let msg = ""; try{ msg = await r.text(); }catch{} throw new Error(msg || r.statusText); }
  return r.status === 204 ? null : r.json();
}
$("#logoutBtn")?.addEventListener('click', async ()=>{ await fetchJSON(`${API}/api/logout`, {method:'POST'}); location.href = "/login.html"; });

(function setDefaultDateTime(){
  const dt = document.querySelector('input[name="date"]');
  if (!dt) return;
  const isoLocal = new Date(Date.now() - new Date().getTimezoneOffset()*60000).toISOString().slice(0,16);
  if (!dt.value) dt.value = isoLocal;
})();
function setDefaultDateTimeAgain(){
  const dt = document.querySelector('input[name="date"]');
  if (!dt) return;
  const isoLocal = new Date(Date.now() - new Date().getTimezoneOffset()*60000).toISOString().slice(0,16);
  dt.value = isoLocal;
}

const CATEGORY_KEYWORDS = {
  FOOD:["pekara","burger","pizza","restoran","kafic","kafana","fast","grill","food","bakery","market","super","maxi","idea","univer"],
  DAIRY:["mleko","jogurt","sir","kajmak","kefir","mozzarella","pavlaka","butter","milky"],
  DELIKATES:["salama","prsut","kulen","sunka","delikates","suvomesn"],
  HYGIENE:["dm","lilly","drog","toalet","sapun","samp","detergent","maramice","higij"],
  PHARMACY:["apoteka","lek","pharma","galen","hemofarm","andol","analgin","parac"],
  ENTERTAINMENT:["netflix","spotify","hbo","playstation","game","koncert","bioskop","cinema","movies","youtube"],
  BILLS:["eps","struja","infostan","voda","grejanje","telekom","sbb","mts","a1","telenor","internet","racun"],
  TRANSPORT:["gsp","bus","tramvaj","karta","taksi","uber","bolt","voz","suburban"],
  FUEL:["mol","omv","nis","gazprom","eurodiesel","benzin","gorivo","pumpa"],
  PARKING:["parking","parker","parkin","parkomat"],
  SHOPPING:["zara","hm","nike","adidas","shop","butik","fashion","rider","decathlon","ikea","gigatron","tehnomanija","winwin","tv","oled","qled"],
  PETS:["pet","zoo","vet","granule","macka","pas"],
  EDUCATION:["kurs","udemy","coursera","katedra","prijava","skolarina","ispit"],
  HEALTH:["klinika","bolnica","laboratorija","ultra","stomat","zubar","pregled"],
  OTHER:["kiosk","prodavn","usluga","servis"]
};
function guessCategoryFromText(text) {
  const t = (text||"").toLowerCase();
  for (const [cat, words] of Object.entries(CATEGORY_KEYWORDS)) if (words.some(w => t.includes(w))) return cat;
  return "OTHER";
}

const monthInput = $("#monthPicker");
const now = new Date();
monthInput.value = `${now.getFullYear()}-${String(now.getMonth()+1).padStart(2,'0')}`;
monthInput.oninput = loadAll;

async function loadExpenses(){
  const month = $("#monthPicker").value || "";
  const list = await fetchJSON(`${API}/api/expenses?month=${encodeURIComponent(month)}`);
  const q = ($("#search").value||"").toLowerCase();
  const filtered = list.filter(x => (x.name + x.category).toLowerCase().includes(q));
  const tbody = $("#table tbody"); tbody.innerHTML="";
  $("#empty").style.display = filtered.length ? "none" : "block";
  for(const e of filtered){
    const tr = document.createElement("tr");
    tr.innerHTML = `
      <td>${e.id}</td>
      <td>${e.name}</td>
      <td>${e.category}</td>
      <td>${(+e.amount).toFixed(2)}</td>
      <td>${new Date(e.date).toLocaleString()}</td>
      <td><button class="del" data-id="${e.id}">Obriši</button></td>`;
    tbody.appendChild(tr);
  }
  $$(".del").forEach(b => b.onclick = async ()=>{ await fetchJSON(`${API}/api/expenses/${b.dataset.id}`,{method:"DELETE"}); await loadAll(); });
}
async function loadStats(){
  const month = $("#monthPicker").value || "";
  const data = await fetchJSON(`${API}/api/stats?month=${encodeURIComponent(month)}`);
  const labels = Object.keys(data), values = Object.values(data);
  if(chart) chart.destroy();
  chart = new Chart($("#pie"), { type:"pie", data:{ labels, datasets:[{ data: values }] },
    options:{ plugins:{ legend:{ position:'bottom', labels:{ color:getComputedStyle(document.body).getPropertyValue('--fg') } } } } });
}
async function loadAll(){ await loadExpenses(); await loadStats(); }
$("#search").oninput = loadExpenses;
$("#themeToggle").onclick = ()=> document.body.classList.toggle("light");

$("#expenseForm").onsubmit = async (e)=>{
  e.preventDefault();
  $("#saveBtn").disabled = true;
  try{
    const fd = new FormData(e.target);
    const payload = {
      name: (fd.get("name")||"").trim(),
      category: fd.get("category"),
      amount: String(fd.get("amount")),
      date: fd.get("date") ? new Date(fd.get("date")).toISOString() : new Date().toISOString()
    };
    if(!payload.name || !payload.category || !payload.amount) { toast("Popuni sva polja."); return; }
    await fetchJSON(`${API}/api/expenses`, { method:"POST", body: JSON.stringify(payload) });
    e.target.reset(); setDefaultDateTimeAgain(); toast("Sačuvano ✔"); await loadAll();
    if (payload.category==='ENTERTAINMENT' && /netflix|spotify|hbo|youtube/i.test(payload.name)) toastSmart('Pretplata evidentirana 🎬','🎬');
    else if (payload.category==='FUEL') toastSmart('Full tank, full power ⛽','⛽');
    else toastSmart('Sačuvano u budžet ✔️','✅');
  }catch(err){ console.error(err); toast("Greška pri čuvanju."); }
  finally{ $("#saveBtn").disabled = false; }
};

// OCR/QR (datum ≠ iznos)
const CURRENCY_WORDS = /(rsd|дин|din|eur|€|euro|evro)/i;
const TOTAL_WORDS    = /(ukupno|za\s*u?platu|total|iznos|sum|plaćanje|cena)/i;
function clean(txt='') { return txt.replace(/[^\p{L}\p{N}\s\.,:\/\-€]/gu, ' ').replace(/\s{2,}/g, ' ').trim(); }
function extractDate(text='') {
  const t = clean(text);
  let m = t.match(/\b(\d{1,2})[.\-/](\d{1,2})[.\-/](\d{4})\s+(\d{1,2}):(\d{2})\b/);
  if (m) return new Date(+m[3], +m[2]-1, +m[1], +m[4], +m[5]);
  m = t.match(/\b(\d{1,2})[.\-/](\d{1,2})[.\-/](\d{4})\b/); if (m) return new Date(+m[3], +m[2]-1, +m[1]);
  m = t.match(/\b(\d{4})-(\d{2})-(\d{2})[T\s](\d{2}):(\d{2})\b/); if (m) return new Date(+m[1], +m[2]-1, +m[3], +m[4], +m[5]);
  m = t.match(/\b(\d{1,2})\.(\d{1,2})\.\b/); if (m) { const now = new Date(); return new Date(now.getFullYear(), +m[2]-1, +m[1]); }
  return null;
}
function extractAmount(text='') {
  const t = clean(text).toLowerCase();
  let m = t.match(new RegExp(`${TOTAL_WORDS.source}[^\\d]*(\\d{1,3}(?:[\\.\\s]\\d{3})*[\\,\\.]\\d{2}|\\d+[\\,\\.]\\d{2})`));
  if (m) return Number(m[1].replace(/\./g,'').replace(',', '.'));
  m = t.match(new RegExp(`(\\d{1,3}(?:[\\.\\s]\\d{3})*[\\,\\.]\\d{2}|\\d+[\\,\\.]\\d{2})\\s*${CURRENCY_WORDS.source}`));
  if (m) return Number(m[1].replace(/\./g,'').replace(',', '.'));
  const candidates = [...t.matchAll(/\b(\d{1,4}[\,\.]\d{2})\b/g)].map(r => ({ raw: r[1], idx: r.index ?? 0 }));
  const filtered = candidates.filter(c => {
    const around = t.slice(Math.max(0, c.idx-3), c.idx+5);
    if (/(\b|[^0-9])\d{1,2}\.\d{1,2}(\b|[^0-9])/.test(around)) return false;
    return true;
  });
  if (filtered.length) return filtered.map(c => Number(c.raw.replace(',', '.'))).sort((a,b)=>b-a)[0];
  return null;
}
function fillFromText(text){
  const date = extractDate(text) || new Date();
  const amt = extractAmount(text);
  const first = (text.split('\n').map(s=>s.trim()).filter(Boolean)[0]||"").slice(0,40);
  $('input[name="name"]').value = first || $('input[name="name"]').value || "Račun";
  $('input[name="amount"]').value = amt ? amt.toFixed(2) : $('input[name="amount"]').value || "";
  const isoLocal = new Date(date.getTime() - date.getTimezoneOffset()*60000).toISOString().slice(0,16);
  $('input[name="date"]').value = isoLocal;
  $('#category').value = guessCategoryFromText(text);
}
function fillFromQR(data){
  try {
    const u = new URL(data);
    const host = u.hostname.replace(/^www\./,'');
    $('input[name="name"]').value = host.toUpperCase();
    const qvals = [...u.searchParams.values()].join(' ');
    if(qvals){
      const m = qvals.replace(',', '.').match(/\d+\.\d{2}/g);
      if(m && m.length) $('input[name="amount"]').value = parseFloat(m[m.length-1]).toFixed(2);
      const maybeDate = extractDate(qvals); if (maybeDate) {
        const isoLocal = new Date(maybeDate.getTime() - maybeDate.getTimezoneOffset()*60000).toISOString().slice(0,16);
        $('input[name="date"]').value = isoLocal;
      }
    }
    $('#category').value = guessCategoryFromText(host);
    return;
  }catch(_) {}
  fillFromText(data);
}
$("#receiptInput").onchange = async (ev)=>{
  const file = ev.target.files[0]; if(!file) return;
  toast("Čitam račun…");
  try{
    const { data:{ text } } = await Tesseract.recognize(file, 'eng');
    fillFromText(text);
    toast("Popunjeno iz računa ✔ (proveri)");
    const txt = (text||"").toLowerCase();
    if (/coffee|kafa|espresso|latte|cappuccino/.test(txt)) toastSmart('Great! You made time for coffee today ☕','☕');
    else if (/tv|televizor|oled|qled/.test(txt)) toastSmart('Expensive! Treat yo’ self 💸','💸');
  }catch(e){ console.error(e); toast("Nisam uspeo da pročitam."); }
  ev.target.value = "";
};
let qrStream, qrTimer;
const qrModal = $("#qrModal"), qrVideo = $("#qrVideo"), qrCanvas = $("#qrCanvas");
$("#qrBtn").onclick = async ()=>{
  try{
    qrStream = await navigator.mediaDevices.getUserMedia({ video:{ facingMode: { ideal: "environment" } }, audio:false });
    qrVideo.srcObject = qrStream; qrModal.style.display = "flex";
    const ctx = qrCanvas.getContext("2d");
    qrTimer = setInterval(()=>{
      if(!qrVideo.videoWidth) return;
      qrCanvas.width = qrVideo.videoWidth; qrCanvas.height = qrVideo.videoHeight;
      ctx.drawImage(qrVideo,0,0,qrCanvas.width,qrCanvas.height);
      const imageData = ctx.getImageData(0,0,qrCanvas.width,qrCanvas.height);
      const code = jsQR(imageData.data, imageData.width, imageData.height);
      if(code && code.data){ try{ fillFromQR(code.data); toast("QR prepoznat ✔"); }catch(e){ console.error(e); } closeQR(); }
    }, 300);
  }catch(err){ console.error(err); toast("Kamera nije dostupna (dozvole?)"); }
};
$("#themeToggle").onclick = ()=> document.body.classList.toggle("light");
$("#closeQR").onclick = closeQR;
function closeQR(){ qrModal.style.display = "none"; if(qrTimer){ clearInterval(qrTimer); qrTimer=null;} if(qrStream){ qrStream.getTracks().forEach(t=>t.stop()); qrStream=null; } }

loadAll();
