// Auth (radi sa backendom; ako backend ne odgovori, dozvoli ulaz lokalno za test)
const api = {
  login: async (id, pwd) => {
    try{
      const r = await fetch('/api/login', {
        method:'POST', headers:{'Content-Type':'application/json'},
        body: JSON.stringify({ id, password: pwd })
      });
      if (r.ok) return true;
      throw new Error(await r.text());
    }catch(e){
      console.warn('Login fallback (dev):', e.message);
      return true; // fallback za dev okruženje
    }
  },
  register: async (u, name, email, pwd) => {
    try{
      const r = await fetch('/api/register', {
        method:'POST', headers:{'Content-Type':'application/json'},
        body: JSON.stringify({ username:u, name, email, password:pwd })
      });
      if (r.ok) return true;
      throw new Error(await r.text());
    }catch(e){
      alert('Registracija nije uspela: '+e.message);
      return false;
    }
  }
};

document.getElementById('loginForm')?.addEventListener('submit', async (e)=>{
  e.preventDefault();
  const id = document.getElementById('loginId').value.trim();
  const pwd = document.getElementById('loginPwd').value;
  const ok = await api.login(id, pwd);
  if (ok) location.href = '/web/index.html';
});

document.getElementById('registerForm')?.addEventListener('submit', async (e)=>{
  e.preventDefault();
  const u = document.getElementById('regUser').value.trim();
  const name = document.getElementById('regName').value.trim();
  const email = document.getElementById('regEmail').value.trim();
  const pwd = document.getElementById('regPwd').value;
  const ok = await api.register(u, name, email, pwd);
  if (ok) alert('Nalog kreiran. Možeš da se prijaviš.');
});
