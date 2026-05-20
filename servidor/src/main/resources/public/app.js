// ── Estado ─────────────────────────────────────────────────────────────
let sessionId = localStorage.getItem('sid');
let nomeUser  = localStorage.getItem('nome');
let isAdmin   = localStorage.getItem('isAdmin') === 'true'; // vem do servidor, nunca derivado do nome
let livros    = [];
let filtro    = 'todos';
let pesquisa  = '';
let sse       = null;
let pdfDoc    = null;
let pdfPage   = 1;

// PDF.js worker
if (typeof pdfjsLib !== 'undefined') {
  pdfjsLib.GlobalWorkerOptions.workerSrc =
    'https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174/pdf.worker.min.js';
}

// ── Auth tabs ───────────────────────────────────────────────────────────

function switchTab(t) {
  const si = t === 'si';
  document.getElementById('aform-si').classList.toggle('active',  si);
  document.getElementById('aform-su').classList.toggle('active', !si);
  document.getElementById('atab-si').classList.toggle('active',   si);
  document.getElementById('atab-su').classList.toggle('active',  !si);
}

// ── Autenticação ────────────────────────────────────────────────────────

async function signIn() {
  const email    = v('si-email');
  const password = v('si-pw');
  if (!email || !password) { toast('Preenche todos os campos', 'err'); return; }

  const r = await api('/api/login', 'POST', { email, password });
  if (r.erro) { toast(r.erro, 'err'); return; }
  iniciarSessao(r);
}

async function signUp() {
  const nome     = v('su-nome');
  const email    = v('su-email');
  const password = v('su-pw');
  const confirm  = v('su-pw2');
  if (!nome || !email || !password) { toast('Preenche todos os campos', 'err'); return; }
  if (password.length < 6)          { toast('Password: mínimo 6 caracteres', 'err'); return; }
  if (password !== confirm)          { toast('As passwords não coincidem', 'err'); return; }

  const r = await api('/api/registar', 'POST', { nome, email, password });
  if (r.erro) { toast(r.erro, 'err'); return; }
  iniciarSessao(r);
}

function iniciarSessao(r) {
  sessionId = r.sessionId;
  nomeUser  = r.nome;
  isAdmin   = r.isAdmin === true; // flag vinda do servidor — nunca comparar nome no cliente

  localStorage.setItem('sid',     sessionId);
  localStorage.setItem('nome',    nomeUser);
  localStorage.setItem('isAdmin', String(isAdmin));

  mostrarMain();
}

async function logout() {
  await api('/api/logout', 'POST');
  ['sid','nome','isAdmin'].forEach(k => localStorage.removeItem(k));
  if (sse) { sse.close(); sse = null; }
  sessionId = nomeUser = null; isAdmin = false;
  document.getElementById('main-view').classList.remove('active');
  document.getElementById('auth-view').style.display = 'flex';
  switchTab('si');
}

// ── Inicializar app principal ───────────────────────────────────────────

function mostrarMain() {
  document.getElementById('auth-view').style.display = 'none';
  document.getElementById('main-view').classList.add('active');

  const initials = nomeUser.trim().split(/\s+/).map(w => w[0]).join('').slice(0, 2).toUpperCase();
  document.getElementById('user-av').textContent = initials;
  document.getElementById('user-nm').textContent = nomeUser;

  // Admin panel: visibilidade controlada apenas pela flag do servidor
  document.getElementById('admin-box').style.display = isAdmin ? 'block' : 'none';
  if (isAdmin) recarregarLog();

  carregarLivros();
  ligarSSE();
}

// ── API ─────────────────────────────────────────────────────────────────

async function api(path, method = 'GET', body = null) {
  const h = { 'Content-Type': 'application/json' };
  if (sessionId) h['X-Session-ID'] = sessionId;
  try {
    const res = await fetch(path, {
      method,
      headers: h,
      body: body ? JSON.stringify(body) : null
    });
    return await res.json();
  } catch {
    return { erro: 'Erro de ligação ao servidor' };
  }
}

async function apiForm(path, formData) {
  const h = {};
  if (sessionId) h['X-Session-ID'] = sessionId;
  try {
    const res = await fetch(path, { method: 'POST', headers: h, body: formData });
    return await res.json();
  } catch {
    return { erro: 'Erro de ligação ao servidor' };
  }
}

// ── SSE ─────────────────────────────────────────────────────────────────

function ligarSSE() {
  if (sse) sse.close();
  sse = new EventSource(`/api/eventos?sid=${sessionId}`);
  sse.addEventListener('atualizacao', () => {
    carregarLivros();
    toast('Lista de livros actualizada', 'inf');
  });
  sse.addEventListener('notificacao', e => toast('🔔 ' + e.data, 'ok'));
  sse.onerror = () => {};
}

// ── Livros ──────────────────────────────────────────────────────────────

async function carregarLivros() {
  const r = await api('/api/livros');
  if (!Array.isArray(r)) return;
  livros = r;
  actualizarStats();
  renderGrid();
}

async function pesquisar() {
  pesquisa = v('search-input');
  if (pesquisa) {
    const r = await api('/api/livros/pesquisa?q=' + encodeURIComponent(pesquisa));
    if (Array.isArray(r)) { livros = r; renderGrid(); }
  } else {
    carregarLivros();
  }
}

function actualizarStats() {
  const disp = livros.filter(l => l.estado === 'DISPONIVEL').length;
  setText('s-total', livros.length);
  setText('s-disp',  disp);
  setText('s-req',   livros.length - disp);
}

function setFiltro(f, btn) {
  filtro = f;
  document.querySelectorAll('.seg-btn').forEach(b => b.classList.remove('active'));
  btn.classList.add('active');
  renderGrid();
}

function renderGrid() {
  let lista = livros;
  if (filtro === 'disp') lista = lista.filter(l => l.estado === 'DISPONIVEL');
  if (filtro === 'req')  lista = lista.filter(l => l.estado !== 'DISPONIVEL');

  const grid = document.getElementById('grid');

  if (!lista.length) {
    grid.innerHTML = `<div class="empty">
      <div class="empty-icon">📭</div>
      <p>${pesquisa ? `Sem resultados para "${esc(pesquisa)}"` : 'Nenhum livro encontrado'}</p>
    </div>`;
    return;
  }

  grid.innerHTML = lista.map((l, i) => `
    <article class="book ${l.estado === 'DISPONIVEL' ? 'av' : 'req'}"
             style="animation-delay:${i * 35}ms"
             onclick="abrirDetalhes('${l.id}')">
      <div class="book-t">${esc(l.titulo)}</div>
      <div class="book-a">${esc(l.autor)}</div>
      ${l.estudanteActual === nomeUser && l.prazoDevolvacao
        ? `<div class="book-prazo">${badgePrazo(l.prazoDevolvacao)}</div>` : ''}
      <div class="book-f">
        <span class="badge ${l.estado === 'DISPONIVEL' ? 'b-green' : 'b-orange'}">
          ${l.estado === 'DISPONIVEL' ? '✓ disponível' : '⏳ requisitado'}
        </span>
        <div style="display:flex;gap:.35rem;align-items:center">
          ${l.temPdf ? '<span class="badge b-pdf">PDF</span>' : ''}
          <span class="badge b-muted cat-badge"
                onclick="event.stopPropagation();filtrarCategoria('${esc(l.categoria)}')"
                title="Filtrar por esta categoria">${esc(l.categoria)}</span>
        </div>
      </div>
    </article>`).join('');
}

// ── Detalhes ────────────────────────────────────────────────────────────

async function abrirDetalhes(id) {
  const d = await api('/api/livros/' + id);
  if (d.erro) { toast(d.erro, 'err'); return; }

  document.getElementById('det-titulo').textContent = d.titulo;

  const filaHtml = d.filaEspera?.length
    ? `<div class="queue-list">${d.filaEspera.map((n, i) =>
        `<div class="queue-item">${i + 1}. ${esc(n)}</div>`).join('')}</div>`
    : '<span style="color:var(--ink-d)">—</span>';

  document.getElementById('det-body').innerHTML = `
    <div class="drow">
      <span class="dlabel">Autor</span>
      <span class="dval">${esc(d.autor)}</span>
    </div>
    <div class="drow">
      <span class="dlabel">Categoria</span>
      <span class="dval">${esc(d.categoria)}</span>
    </div>
    <div class="drow">
      <span class="dlabel">Estado</span>
      <span class="dval">
        <span class="badge ${d.estado === 'DISPONIVEL' ? 'b-green' : 'b-orange'}">
          ${d.estado === 'DISPONIVEL' ? '✓ disponível' : '⏳ requisitado'}
        </span>
      </span>
    </div>
    ${d.estado !== 'DISPONIVEL' ? `
    <div class="drow">
      <span class="dlabel">Com</span>
      <span class="dval">${esc(d.estudanteActual || '—')}</span>
    </div>` : ''}
    ${d.prazoDevolvacao && d.estudanteActual === nomeUser ? `
    <div class="drow">
      <span class="dlabel">Prazo</span>
      <span class="dval">${badgePrazo(d.prazoDevolvacao)}</span>
    </div>` : ''}
    <div class="drow">
      <span class="dlabel">Fila</span>
      <span class="dval">${filaHtml}</span>
    </div>`;

  const acts = document.getElementById('det-acts');
  acts.innerHTML = '';

  if (d.estado === 'DISPONIVEL') {
    acts.appendChild(mkBtn('Requisitar', 'btn-success', () => acaoLivro(id, 'requisitar')));
  } else if (d.estudanteActual === nomeUser) {
    acts.appendChild(mkBtn('↩ Devolver', 'btn-danger', () => acaoLivro(id, 'devolver')));
  } else {
    acts.appendChild(mkBtn('⏳ Entrar na fila de espera', 'btn-ghost', () => acaoLivro(id, 'requisitar')));
  }

  if (d.temPdf) {
    const canRead = isAdmin || d.estudanteActual === nomeUser;
    if (canRead) {
      acts.appendChild(mkBtn('📖 Ler PDF', 'btn-primary', () => {
        closeOv('ov-det');
        abrirPdf(id, d.titulo);
      }));
    } else {
      const locked = document.createElement('span');
      locked.className = 'pdf-locked';
      locked.textContent = '🔒 PDF disponível — requisita para ler';
      acts.appendChild(locked);
    }
  }

  if (isAdmin) {
    const sep = document.createElement('div');
    sep.style.cssText = 'width:100%;height:1.5px;background:var(--border);margin:.25rem 0';
    acts.appendChild(sep);
    acts.appendChild(mkBtn('🗑 Apagar livro', 'btn-danger', () => apagarLivro(id, d.titulo)));
  }

  openOv('ov-det');
}

async function apagarLivro(id, titulo) {
  if (!confirm(`Apagar "${titulo}"?\nEsta acção não pode ser revertida.`)) return;
  closeOv('ov-det');
  const r = await api(`/api/livros/${id}`, 'DELETE');
  r.erro ? toast(r.erro, 'err') : toast('Livro apagado com sucesso', 'ok');
  carregarLivros();
}

function filtrarCategoria(cat) {
  document.getElementById('search-input').value = cat;
  pesquisar();
}

async function acaoLivro(id, acao) {
  closeOv('ov-det');
  const r = await api(`/api/livros/${id}/${acao}`, 'POST');
  r.erro ? toast(r.erro, 'err') : toast(r.mensagem, 'ok');
  carregarLivros();
}

// ── Adicionar livro ─────────────────────────────────────────────────────

function abrirAdd() { openOv('ov-add'); document.getElementById('add-t').focus(); }

async function adicionarLivro() {
  const titulo    = v('add-t');
  const autor     = v('add-a');
  const categoria = document.getElementById('add-c').value || 'Geral';
  const pdfInput  = document.getElementById('add-pdf');
  if (!titulo || !autor) { toast('Título e autor são obrigatórios', 'err'); return; }

  const pdfFile = pdfInput?.files?.[0];
  if (pdfFile && pdfFile.size > 50_000_000) {
    toast('O ficheiro PDF não pode exceder 50 MB', 'err'); return;
  }

  const fd = new FormData();
  fd.append('titulo',    titulo);
  fd.append('autor',     autor);
  fd.append('categoria', categoria);
  if (pdfFile) fd.append('pdf', pdfFile);

  const r = await apiForm('/api/livros', fd);
  closeOv('ov-add');
  document.getElementById('add-t').value = '';
  document.getElementById('add-a').value = '';
  document.getElementById('add-c').value = 'Geral';
  if (pdfInput) pdfInput.value = '';
  r.erro ? toast(r.erro, 'err') : toast('Livro adicionado' + (pdfFile ? ' com PDF' : '') + ' com sucesso!', 'ok');
  carregarLivros();
}

// ── Histórico ───────────────────────────────────────────────────────────

async function abrirHistorico() {
  openOv('ov-hist');
  const r = await api('/api/historico');
  document.getElementById('hist-log').textContent = r.log || '(sem registos)';
}

async function recarregarLog() {
  const btn = document.getElementById('btn-reload-log');
  if (btn) btn.textContent = '…';
  const r  = await api('/api/historico');
  const el = document.getElementById('admin-log');
  el.textContent = r.log || '(sem registos)';
  el.scrollTop   = el.scrollHeight; // mostra as entradas mais recentes (fundo)
  if (btn) { btn.textContent = '✓ Actualizado'; setTimeout(() => { btn.textContent = '↺ Actualizar'; }, 1500); }
}

// ── Modais ──────────────────────────────────────────────────────────────

const openOv  = id => document.getElementById(id).classList.add('open');
const closeOv = id => document.getElementById(id).classList.remove('open');

document.querySelectorAll('.ov').forEach(o =>
  o.addEventListener('click', e => { if (e.target === o) o.classList.remove('open'); }));

// ── Toasts ──────────────────────────────────────────────────────────────

function toast(msg, tipo = 'inf') {
  const el = document.createElement('div');
  el.className = `toast ${tipo}`;
  el.textContent = msg;
  document.getElementById('toasts').appendChild(el);
  setTimeout(() => el.remove(), 4000);
}

// ── Histórico Pessoal ────────────────────────────────────────────────────

async function abrirMeusLivros() {
  openOv('ov-meus');
  document.getElementById('meus-content').innerHTML =
    '<div class="empty"><div class="spinner"></div><p>A carregar…</p></div>';

  const r = await api('/api/historico/pessoal');
  if (r.erro) {
    document.getElementById('meus-content').innerHTML =
      `<p class="hist-empty">${esc(r.erro)}</p>`;
    return;
  }

  let html = '<div class="hist-sec-title">Actualmente requisitados</div>';
  if (!r.activos.length) {
    html += '<div class="hist-empty">Nenhum livro requisitado de momento.</div>';
  } else {
    html += r.activos.map(e => `
      <div class="hist-row">
        <div class="hist-titulo">${esc(e.tituloLivro)}</div>
        <div class="hist-meta">
          <span class="badge b-muted">Desde ${formatDate(e.dataInicio)}</span>
          ${badgePrazo(e.prazo)}
        </div>
      </div>`).join('');
  }

  html += '<div class="mdivider"></div><div class="hist-sec-title">Histórico de devoluções</div>';
  if (!r.devolvidos.length) {
    html += '<div class="hist-empty">Ainda não devolveste nenhum livro.</div>';
  } else {
    html += r.devolvidos.map(e => `
      <div class="hist-row">
        <div class="hist-titulo">${esc(e.tituloLivro)}</div>
        <div class="hist-meta">
          <span class="badge b-muted">${formatDate(e.dataInicio)} → ${formatDate(e.dataFim)}</span>
          <span class="badge b-green">✓ devolvido</span>
        </div>
      </div>`).join('');
  }

  document.getElementById('meus-content').innerHTML = html;
}

// ── Utils ────────────────────────────────────────────────────────────────

const v       = id => document.getElementById(id)?.value?.trim() ?? '';
const setText = (id, val) => { const el = document.getElementById(id); if (el) el.textContent = val; };

function esc(s) {
  if (!s) return '';
  return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
}

function formatDate(iso) {
  if (!iso) return '—';
  const d = new Date(iso + 'T00:00:00');
  return d.toLocaleDateString('pt-PT', { day: 'numeric', month: 'short', year: 'numeric' });
}

function diasRestantes(prazo) {
  if (!prazo) return null;
  const hoje = new Date(); hoje.setHours(0, 0, 0, 0);
  return Math.round((new Date(prazo + 'T00:00:00') - hoje) / 86_400_000);
}

function badgePrazo(prazo) {
  const d = diasRestantes(prazo);
  if (d === null) return '';
  if (d < 0)   return `<span class="badge b-red">Atrasado ${Math.abs(d)} dia(s)</span>`;
  if (d === 0) return `<span class="badge b-red">Devolve hoje!</span>`;
  if (d <= 3)  return `<span class="badge b-orange">${d} dia(s) para devolver</span>`;
  return `<span class="badge b-green">Prazo: ${formatDate(prazo)}</span>`;
}

function mkBtn(text, cls, fn) {
  const b = document.createElement('button');
  b.className = `btn ${cls}`; b.textContent = text; b.onclick = fn;
  return b;
}

// ── Enter keys ───────────────────────────────────────────────────────────

document.getElementById('si-pw').addEventListener('keydown',  e => { if (e.key === 'Enter') signIn(); });
document.getElementById('su-pw2').addEventListener('keydown', e => { if (e.key === 'Enter') signUp(); });

// ── PDF Viewer ───────────────────────────────────────────────────────────

async function abrirPdf(id, titulo) {
  document.getElementById('pdf-titulo').textContent = titulo;
  document.getElementById('pdf-canvas').style.display = 'none';
  document.getElementById('pdf-loading').style.display = 'flex';
  document.getElementById('pdf-info').textContent = '—';
  pdfDoc = null; pdfPage = 1;
  openOv('ov-pdf');

  try {
    const res = await fetch(`/api/livros/${id}/ler`, { headers: { 'X-Session-ID': sessionId } });
    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      closeOv('ov-pdf');
      toast(err.erro || 'Não foi possível carregar o PDF', 'err');
      return;
    }
    const data = await res.arrayBuffer();
    pdfDoc = await pdfjsLib.getDocument({ data }).promise;
    await renderPdfPage(1);
    document.getElementById('pdf-loading').style.display = 'none';
    document.getElementById('pdf-canvas').style.display = 'block';
  } catch {
    closeOv('ov-pdf');
    toast('Erro ao carregar o PDF', 'err');
  }
}

async function renderPdfPage(num) {
  if (!pdfDoc) return;
  const page   = await pdfDoc.getPage(num);
  const canvas = document.getElementById('pdf-canvas');
  const vp     = page.getViewport({ scale: 1.5 });
  canvas.width  = vp.width;
  canvas.height = vp.height;
  await page.render({ canvasContext: canvas.getContext('2d'), viewport: vp }).promise;
  document.getElementById('pdf-info').textContent = `Página ${num} de ${pdfDoc.numPages}`;
}

async function pdfPrevPage() {
  if (!pdfDoc || pdfPage <= 1) return;
  await renderPdfPage(--pdfPage);
}

async function pdfNextPage() {
  if (!pdfDoc || pdfPage >= pdfDoc.numPages) return;
  await renderPdfPage(++pdfPage);
}

document.addEventListener('keydown', e => {
  if (!document.getElementById('ov-pdf').classList.contains('open')) return;
  if (e.key === 'Escape')                              closeOv('ov-pdf');
  if (e.key === 'ArrowRight' || e.key === 'ArrowDown') pdfNextPage();
  if (e.key === 'ArrowLeft'  || e.key === 'ArrowUp')   pdfPrevPage();
});

// ── Restaurar sessão (isAdmin lido do localStorage, não derivado do nome) ──

if (sessionId && nomeUser) mostrarMain();
