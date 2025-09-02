// T•Solutions Expense App (front-only, lokalno čuvanje)
const LS_KEY = 'ts_expenses_v1';

const $ = id => document.getElementById(id);
const els = {
  month: $('month'), clearAll: $('clearAll'),
  form: $('expenseForm'), date: $('date'), category: $('category'),
  description: $('description'), amount: $('amount'),
  submitBtn: $('submitBtn'), cancelEdit: $('cancelEdit'),
  tbody: $('tbody'), sumTotal: $('sumTotal'), sumCount: $('sumCount'),
  sumAvg: $('sumAvg'), byCategory: $('byCategory'),
};

let expenses = load(); let editingId = null;

function load(){ try { return JSON.parse(localStorage.getItem(LS_KEY)) || []; } catch { return []; } }
function save(){ localStorage.setItem(LS_KEY, JSON.stringify(expenses)); }
function todayISO(){ return new Date().toISOString().slice(0,10); }
function ym(d){ return d.slice(0,7); }
function toRSD(n){ return Number(n).toLocaleString('sr-RS',{style:'currency',currency:'RSD',maximumFractionDigits:2}); }

function setDefaults(){ els.date.value = todayISO(); els.month.value = ym(todayISO()); }
setDefaults(); render();

els.form.addEventListener('submit', (e)=>{
  e.preventDefault();
  const item = {
    id: editingId ?? crypto.randomUUID(),
    date: els.date.value,
    category: els.category.value,
    description: (els.description.value||'').trim(),
    amount: Number(els.amount.value)
  };
  if (!item.date || !item.category || isNaN(item.amount)) return;

  if (editingId){ const i = expenses.findIndex(x=>x.id===editingId); if (i>=0) expenses[i]=item; }
  else { expenses.push(item); }
  save(); resetForm(); render();
});

els.cancelEdit.addEventListener('click', resetForm);
els.clearAll.addEventListener('click', ()=>{
  if (confirm('Obrisati SVE troškove?')){ expenses = []; save(); render(); }
});
els.month.addEventListener('change', render);

function resetForm(){
  editingId=null; els.form.reset(); els.date.value=todayISO();
  els.submitBtn.textContent='Dodaj'; els.cancelEdit.hidden=true;
}

function render(){
  const month = els.month.value || ym(todayISO());
  const list = expenses.filter(x=>ym(x.date)===month).sort((a,b)=>a.date<b.date?1:-1);
  els.tbody.innerHTML='';
  for(const x of list){
    const tr=document.createElement('tr');
    tr.innerHTML = `
      <td>${x.date}</td>
      <td>${x.category}</td>
      <td>${x.description||''}</td>
      <td class="num">${toRSD(x.amount)}</td>
      <td class="actions">
        <button class="action edit">Izmeni</button>
        <button class="action del">Obriši</button>
      </td>`;
    tr.querySelector('.edit').onclick=()=>startEdit(x);
    tr.querySelector('.del').onclick=()=>removeItem(x.id);
    els.tbody.appendChild(tr);
  }
  const total = list.reduce((s,x)=>s+x.amount,0);
  els.sumTotal.textContent=toRSD(total);
  els.sumCount.textContent=list.length;
  els.sumAvg.textContent=list.length?toRSD(total/list.length):toRSD(0);

  const byCat={}; for(const x of list){ byCat[x.category]=(byCat[x.category]||0)+x.amount; }
  els.byCategory.innerHTML='';
  Object.entries(byCat).sort((a,b)=>b[1]-a[1]).forEach(([k,v])=>{
    const chip=document.createElement('div'); chip.className='chip'; chip.textContent=`${k}: ${toRSD(v)}`;
    els.byCategory.appendChild(chip);
  });
}
function startEdit(x){
  editingId=x.id; els.date.value=x.date; els.category.value=x.category;
  els.description.value=x.description||''; els.amount.value=x.amount;
  els.submitBtn.textContent='Sačuvaj izmenu'; els.cancelEdit.hidden=false; window.scrollTo({top:0,behavior:'smooth'});
}
function removeItem(id){
  if(!confirm('Obriši ovaj trošak?'))return;
  expenses=expenses.filter(x=>x.id!==id); save(); render();
}
