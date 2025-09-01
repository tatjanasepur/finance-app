const API = window.location.origin;
const $ = s => document.querySelector(s);
(async function(){ const r = await fetch(`${API}/api/me`); if (!r.ok) { location.href = "/login.html"; return; } })();
$("#logoutBtn")?.addEventListener('click', async ()=>{ await fetch(`${API}/api/logout`,{method:'POST'}); location.href="/login.html"; });

async function load(){
  const r = await fetch(`${API}/api/subscriptions`); const subs = await r.json();
  const box = $("#subs"); box.innerHTML = subs.length? "" : `<div class="empty">Nema pretplata još 🙂</div>`;
  for (const s of subs){
    const card = document.createElement('div'); card.className = 'card glass';
    const icon = serviceIcon(s.service);
    card.innerHTML = `
      <div class="row" style="justify-content:space-between;align-items:center;">
        <div class="row" style="gap:10px;align-items:center;">
          <div style="font-size:22px">${icon}</div>
          <div><b>${s.service}</b><div style="color:var(--muted);font-size:12px">Sledeća naplata: ${new Date(s.next_due).toLocaleDateString()}</div></div>
        </div>
        <div><b>${(+s.amount).toFixed(2)} €</b></div>
      </div>`;
    box.appendChild(card);
  }
}
function serviceIcon(name){
  name = name.toLowerCase();
  if (name.includes('netflix')) return '🎬';
  if (name.includes('spotify')) return '🎧';
  if (name.includes('hbo')) return '🎞️';
  if (name.includes('youtube')) return '▶️';
  if (name.includes('playstation')) return '🎮';
  if (name.includes('disney')) return '🧞';
  if (name.includes('icloud') || name.includes('google')) return '☁️';
  return '💳';
}
$("#subForm").onsubmit = async (e)=>{
  e.preventDefault();
  const fd = new FormData(e.target);
  const payload = {
    service: fd.get("service"),
    amount: String(fd.get("amount")),
    next_due: new Date(fd.get("next_due")).toISOString(),
    period_days: parseInt(fd.get("period_days")||"30",10)
  };
  const r = await fetch(`${API}/api/subscriptions`, { method:"POST", headers:{'Content-Type':'application/json'}, body: JSON.stringify(payload) });
  if (!r.ok){ alert("Greška"); return; }
  await load();
};
load();
