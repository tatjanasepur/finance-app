const API = window.location.origin;
const $ = s => document.querySelector(s);

async function postJSON(url, data){
  const r = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(data)
  });
  let j = {};
  try { j = await r.json(); } catch(e){}
  if (!r.ok) throw new Error(j.error || r.statusText || "Greška");
  return j;
}

/* ---------- LOGIN ---------- */
const loginForm = $("#loginForm");
if (loginForm){
  loginForm.onsubmit = async (e)=>{
    e.preventDefault();
    const fd = new FormData(e.target);

    // Polje u formi se zove "email", ali backend traži username.
    // Uzimamo šta je korisnik uneo (može biti i email ili username),
    // i šaljemo GA kao username ka backendu.
    const identifier = (fd.get("email") || "").trim();
    const password   = (fd.get("password") || "").trim();

    $("#loginMsg").textContent = "Prijavljujem…";
    try{
      await postJSON(`${API}/api/login`, {
        username: identifier,
        password
      });
      location.href = "/"; // uđi u app
    }catch(err){
      $("#loginMsg").textContent = err.message || "Greška";
      console.error(err);
    }
  };
}

/* ---------- REGISTRACIJA ---------- */
const regForm = $("#regForm");
if (regForm){
  regForm.onsubmit = async (e)=>{
    e.preventDefault();
    const fd = new FormData(e.target);

    const payload = {
      username:  (fd.get("username")  || "").trim(),
      full_name: (fd.get("full_name") || "").trim(),
      email:     (fd.get("email")     || "").trim(), // opciono
      password:  (fd.get("password")  || "").trim()
    };

    if (!payload.username || !payload.password){
      $("#regMsg").textContent = "Nedostaju podaci (username/lozinka).";
      return;
    }

    $("#regMsg").textContent = "Kreiram nalog…";
    try{
      await postJSON(`${API}/api/register`, payload);
      $("#regMsg").textContent = "✅ Nalog kreiran. Sad se prijavi gore.";
      // Po želji: auto-login
      // await postJSON(`${API}/api/login`, { username: payload.username, password: payload.password });
      // location.href = "/";
    }catch(err){
      $("#regMsg").textContent = err.message || "Greška";
      console.error(err);
    }
  };
}
