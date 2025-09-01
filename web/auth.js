const API = window.location.origin;
const $ = s => document.querySelector(s);

async function postJSON(url, data){
  const r = await fetch(url, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(data) });
  const j = await r.json().catch(()=> ({}));
  if(!r.ok) throw new Error(j.error || r.statusText);
  return j;
}

/* ---------- LOGIN ---------- */
const loginForm = $("#loginForm");
if (loginForm){
  loginForm.onsubmit = async (e)=>{
    e.preventDefault();
    const fd = new FormData(e.target);
    const payload = { email: fd.get("email"), password: fd.get("password") };
    $("#loginMsg").textContent = "Prijavljujem…";
    try{
      await postJSON(`${API}/api/login`, payload);
      location.href = "/";
    }catch(err){
      $("#loginMsg").textContent = err.message || "Greška";
      console.error(err);
    }
  };
}

/* ---------- REGISTER ---------- */
const regForm = $("#regForm");
if (regForm){
  regForm.onsubmit = async (e)=>{
    e.preventDefault();
    const fd = new FormData(e.target);
    const payload = {
      email: fd.get("email"),
      name: fd.get("full_name") || fd.get("name") || "",
      password: fd.get("password")
    };
    $("#regMsg").textContent = "Kreiram nalog…";
    try{
      await postJSON(`${API}/api/register`, payload);
      $("#regMsg").textContent = "Proveri email i klikni verifikacioni link. (Ako SMTP nije podešen, link je u server konzoli.)";
    }catch(err){
      $("#regMsg").textContent = err.message || "Greška";
      console.error(err);
    }
  };
}

/* ---------- VERIFY BY URL ?verify=TOKEN ---------- */
(async function handleVerifyParam(){
  const token = new URLSearchParams(location.search).get("verify");
  if(!token) return;
  try{
    const r = await fetch(`${API}/api/verify?token=${encodeURIComponent(token)}`);
    if(!r.ok) throw new Error(await r.text());
    showToast("✅ Email verifikovan — prijavi se.");
    // skini param iz URL-a
    const u = new URL(location.href); u.searchParams.delete("verify"); history.replaceState({}, "", u);
  }catch(e){
    showToast("❌ Verifikacioni link je nevažeći ili iskorišćen.");
    console.error(e);
  }
})();

/* ---------- Helpers ---------- */
function showToast(text){
  const n = document.createElement('div');
  n.className = 'ts-smart-card show';
  n.innerHTML = `<div class="ts-smart-emoji">✨</div><div class="ts-smart-text">${text}</div>`;
  document.body.appendChild(n);
  setTimeout(()=>{ n.classList.remove('show'); setTimeout(()=>n.remove(),400); }, 3200);
}

/* ---------- LOGOUT BTN (opcija u headeru) ---------- */
const logoutBtn = $("#logoutBtn");
if (logoutBtn){
  logoutBtn.onclick = async ()=>{
    try{ await postJSON(`${API}/api/logout`, {}); location.href = "/login.html"; }
    catch(e){ console.error(e); }
  };
}
