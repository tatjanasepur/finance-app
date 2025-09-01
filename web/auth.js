const API = window.location.origin;
const $ = s => document.querySelector(s);

$("#loginForm").onsubmit = async (e)=>{
  e.preventDefault();
  const fd = new FormData(e.target);
  const payload = { email: fd.get("email"), password: fd.get("password") };
  $("#loginMsg").textContent = "Prijavljujem…";
  try{
    const r = await fetch(`${API}/api/login`, { method:"POST", headers:{'Content-Type':'application/json'}, body: JSON.stringify(payload) });
    const j = await r.json();
    if(!r.ok){ $("#loginMsg").textContent = j.error || "Greška"; return; }
    location.href = "/";
  }catch(err){ $("#loginMsg").textContent = "Greška"; console.error(err); }
};

$("#regForm").onsubmit = async (e)=>{
  e.preventDefault();
  const fd = new FormData(e.target);
  const payload = { email: fd.get("email"), password: fd.get("password"), full_name: fd.get("full_name") };
  $("#regMsg").textContent = "Kreiram nalog…";
  try{
    const r = await fetch(`${API}/api/register`, { method:"POST", headers:{'Content-Type':'application/json'}, body: JSON.stringify(payload) });
    const j = await r.json();
    if(!r.ok){ $("#regMsg").textContent = j.error || "Greška"; return; }
    $("#regMsg").textContent = "Proveri email i klikni verifikacioni link (ako nema SMTP, link je u konzoli servera).";
  }catch(err){ $("#regMsg").textContent = "Greška"; console.error(err); }
};

if (new URLSearchParams(location.search).get("verified") === "1") {
  const n = document.createElement('div');
  n.className = 'ts-smart-card show';
  n.innerHTML = `<div class="ts-smart-emoji">✅</div><div class="ts-smart-text">Email verifikovan — prijavi se.</div>`;
  document.body.appendChild(n);
  setTimeout(()=>{ n.classList.remove('show'); setTimeout(()=>n.remove(),400); }, 3200);
}
