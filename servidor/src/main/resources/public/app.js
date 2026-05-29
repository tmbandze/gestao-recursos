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
let _avaliando = {};   // livroId → número de estrelas seleccionadas

// ── Estado do Chat ──────────────────────────────────────────────────────
let chatAberto     = false;
let chatSala       = 'global';  // 'global' | 'priv'
let chatPrivAlvo   = null;      // nome do destinatário privado
let chatNaoLidas   = { global: 0, priv: 0 };

// PDF.js worker
if (typeof pdfjsLib !== 'undefined') {
  pdfjsLib.GlobalWorkerOptions.workerSrc =
    'https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174/pdf.worker.min.js';
}

// ── Auth tabs ───────────────────────────────────────────────────────────

function switchTab(t) {
  ['si','su','rec'].forEach(id => {
    const form = document.getElementById('aform-' + id);
    const tab  = document.getElementById('atab-'  + id);
    if (form) form.classList.toggle('active', id === t);
    if (tab)  tab.classList.toggle('active',  id === t);
  });
  // Mostrar/esconder tab de recuperação conforme necessário
  const tabRec = document.getElementById('atab-rec');
  if (tabRec) tabRec.style.display = (t === 'rec') ? 'block' : 'none';
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
  document.getElementById('chat-panel').style.display = 'none';
  chatAberto = false;
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
  // Histórico de actividade: apenas para admins
  document.getElementById('btn-historico').style.display = isAdmin ? 'inline-flex' : 'none';
  if (isAdmin) {
    recarregarLog(); carregarUtilizadores(); carregarMultas();
    carregarPendentes(); carregarSuspeitos(); carregarRecuperacoes();
  } else {
    verificarMulta();
  }

  carregarLivros();
  ligarSSE();
  iniciarChat();
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
    // Sessão expirou (servidor reiniciado ou timeout) → volta ao login
    if (res.status === 401) {
      ['sid','nome','isAdmin'].forEach(k => localStorage.removeItem(k));
      sessionId = nomeUser = null; isAdmin = false;
      if (sse) { sse.close(); sse = null; }
      document.getElementById('main-view').classList.remove('active');
      document.getElementById('auth-view').style.display = 'flex';
      switchTab('si');
      toast('Sessão expirada — faz login novamente', 'err');
      return { erro: 'Sessão expirada' };
    }
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
  sse.addEventListener('utilizadores_update', () => {
    if (isAdmin) { carregarUtilizadores(); carregarSuspeitos(); }
  });
  sse.addEventListener('recuperacao_update', () => {
    if (isAdmin) carregarRecuperacoes();
  });
  sse.addEventListener('pendente_update', e => {
    if (isAdmin) {
      carregarPendentes();
      if (e.data && e.data.startsWith('novo_pendente:')) {
        const info = e.data.replace('novo_pendente:', '');
        toast('⏳ Novo livro para aprovar: ' + info, 'inf');
      }
    }
  });
  sse.addEventListener('multa_update', e => {
    if (!isAdmin && e.data && e.data.startsWith('perdoada:')) {
      const alvo = e.data.split(':')[1];
      if (alvo === nomeUser) {
        esconderBannerMulta();
        toast('✅ A tua multa foi perdoada pelo administrador!', 'ok');
      }
    } else if (!isAdmin && e.data && e.data.startsWith('nova_multa:')) {
      verificarMulta();
    } else if (isAdmin) {
      carregarMultas();
    }
  });
  sse.addEventListener('notificacao', e => toast('🔔 ' + e.data, 'ok'));
  sse.addEventListener('chat_mensagem', e => {
    try {
      const msg = JSON.parse(e.data);
      chatReceberMensagem(msg, false);
    } catch {}
  });
  sse.addEventListener('chat_priv', e => {
    try {
      const msg = JSON.parse(e.data);
      chatReceberMensagem(msg, true);
    } catch {}
  });
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

  grid.innerHTML = lista.map((l, i) => {
    const meuPrazo = l.prazosEstudantes?.[nomeUser] || null;
    const euTenho  = Array.isArray(l.estudantesActuais) && l.estudantesActuais.includes(nomeUser);
    const disp = l.estado === 'DISPONIVEL';
    const multi = (l.totalExemplares || 1) > 1;
    const dispBadge = multi
      ? `<span class="badge ${disp ? 'b-green' : 'b-orange'}">
           ${l.copiasDisponiveis > 0 ? `✓ ${l.copiasDisponiveis}/${l.totalExemplares} disponíveis` : `⏳ 0/${l.totalExemplares} disponíveis`}
         </span>`
      : `<span class="badge ${disp ? 'b-green' : 'b-orange'}">
           ${disp ? '✓ disponível' : '⏳ requisitado'}
         </span>`;
    // Badge de avaliação
    const numAv  = (l.avaliacoes || []).length;
    const avgAv  = numAv
      ? l.avaliacoes.reduce((s, a) => s + a.estrelas, 0) / numAv
      : 0;
    const starBadge = numAv
      ? `<span class="badge b-star" title="${avgAv.toFixed(1)} estrelas · ${numAv} avaliação(ões)">
           <span class="star-gold">★</span> ${avgAv.toFixed(1)}
         </span>`
      : '';
    return `
    <article class="book ${disp ? 'av' : 'req'}"
             style="animation-delay:${i * 35}ms"
             onclick="abrirDetalhes('${l.id}')">
      <div class="book-cover">
        <img src="/api/livros/${l.id}/capa"
             loading="lazy"
             alt="Capa de ${esc(l.titulo)}"
             onerror="this.remove()">
      </div>
      <div class="book-t">${esc(l.titulo)}</div>
      <div class="book-a">${esc(l.autor)}</div>
      ${euTenho && meuPrazo ? `<div class="book-prazo">${badgePrazo(meuPrazo)}</div>` : ''}
      <div class="book-f">
        ${dispBadge}
        <div style="display:flex;gap:.35rem;align-items:center">
          ${starBadge}
          ${l.temPdf ? '<span class="badge b-pdf">PDF</span>' : ''}
          <span class="badge b-muted cat-badge"
                onclick="event.stopPropagation();filtrarCategoria('${esc(l.categoria)}')"
                title="Filtrar por esta categoria">${esc(l.categoria)}</span>
        </div>
      </div>
    </article>`;
  }).join('');
}

// ── Detalhes ────────────────────────────────────────────────────────────

async function abrirDetalhes(id) {
  const d = await api('/api/livros/' + id);
  if (d.erro) { toast(d.erro, 'err'); return; }

  document.getElementById('det-titulo').textContent = d.titulo;

  // Capa no modal de detalhes
  let detCapaEl = document.getElementById('det-capa-img');
  if (!detCapaEl) {
    detCapaEl = document.createElement('div');
    detCapaEl.id = 'det-capa-img';
    detCapaEl.style.cssText =
      'width:100%;aspect-ratio:3/4;max-height:260px;overflow:hidden;border-radius:var(--rs);' +
      'background:linear-gradient(160deg,var(--surface),var(--bg));' +
      'display:flex;align-items:center;justify-content:center;' +
      'font-size:2rem;color:var(--border-d);margin-bottom:1rem;position:relative;';
    detCapaEl.innerHTML = '📚';
    document.getElementById('det-body').before(detCapaEl);
  }
  detCapaEl.innerHTML = '📚';  // placeholder enquanto carrega
  const capaImg = document.createElement('img');
  capaImg.src = `/api/livros/${d.id}/capa`;
  capaImg.style.cssText = 'position:absolute;inset:0;width:100%;height:100%;object-fit:cover;object-position:top';
  capaImg.onerror = () => capaImg.remove();
  detCapaEl.appendChild(capaImg);

  // Dados de exemplares
  const euTenho  = Array.isArray(d.estudantesActuais) && d.estudantesActuais.includes(nomeUser);
  const meuPrazo = d.prazosEstudantes?.[nomeUser] || null;
  const multi    = (d.totalExemplares || 1) > 1;
  const dispCnt  = d.copiasDisponiveis ?? (d.estado === 'DISPONIVEL' ? 1 : 0);

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
    ${multi ? `
    <div class="drow">
      <span class="dlabel">Exemplares</span>
      <span class="dval">
        <span class="badge ${dispCnt > 0 ? 'b-green' : 'b-orange'}">
          ${dispCnt}/${d.totalExemplares} disponíveis
        </span>
      </span>
    </div>` : `
    <div class="drow">
      <span class="dlabel">Estado</span>
      <span class="dval">
        <span class="badge ${d.estado === 'DISPONIVEL' ? 'b-green' : 'b-orange'}">
          ${d.estado === 'DISPONIVEL' ? '✓ disponível' : '⏳ requisitado'}
        </span>
      </span>
    </div>`}
    ${d.estudantesActuais?.length ? `
    <div class="drow">
      <span class="dlabel">Com</span>
      <span class="dval">${isAdmin
        ? d.estudantesActuais.map(n => esc(n)).join(', ')
        : euTenho ? '<span class="badge b-green">✓ tu próprio</span>' : `${d.estudantesActuais.length} utilizador(es)`}</span>
    </div>` : ''}
    ${euTenho && meuPrazo ? `
    <div class="drow">
      <span class="dlabel">Prazo</span>
      <span class="dval">${badgePrazo(meuPrazo)}</span>
    </div>` : ''}
    <div class="drow">
      <span class="dlabel">Fila</span>
      <span class="dval">${filaHtml}</span>
    </div>`;

  // ── Secção de avaliações ─────────────────────────────────────────────
  document.getElementById('det-body').innerHTML +=
    renderAvaliacoes(d.avaliacoes || [], id, d.mediaEstrelas || 0, d.numAvaliacoes || 0);

  // Pré-seleccionar estrelas se já avaliou
  const minhaAvExist = (d.avaliacoes || []).find(a => a.utilizador === nomeUser);
  if (minhaAvExist) {
    _avaliando[id] = minhaAvExist.estrelas;
    actualizarEstrelasUI(id, minhaAvExist.estrelas);
  }

  const acts = document.getElementById('det-acts');
  acts.innerHTML = '';

  if (dispCnt > 0 && !euTenho) {
    acts.appendChild(mkBtn('Requisitar', 'btn-success', () => acaoLivro(id, 'requisitar')));
  } else if (euTenho) {
    acts.appendChild(mkBtn('↩ Devolver', 'btn-danger', () => acaoLivro(id, 'devolver')));
  } else {
    acts.appendChild(mkBtn('⏳ Entrar na fila de espera', 'btn-ghost', () => acaoLivro(id, 'requisitar')));
  }

  if (d.temPdf) {
    const canRead = isAdmin || euTenho;
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
  if (r.erro) {
    toast(r.erro, 'err');
  } else if (r.multaAplicada) {
    toast('⚠️ ' + r.mensagem, 'err');  // vermelho p/ destacar multa
    verificarMulta();                   // actualizar banner
  } else {
    toast(r.mensagem, 'ok');
  }
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
  if (r.erro) {
    toast(r.erro, 'err');
  } else if (r.pendente) {
    toast('📋 ' + (r.mensagem || 'Livro submetido para revisão do administrador.'), 'inf');
    if (isAdmin) carregarPendentes();
  } else {
    toast('✅ ' + (r.mensagem || 'Livro adicionado com sucesso!'), 'ok');
  }
  carregarLivros();
}

// ── Histórico ───────────────────────────────────────────────────────────

async function abrirHistorico() {
  openOv('ov-hist');
  const r = await api('/api/historico');
  document.getElementById('hist-log').textContent = r.log || '(sem registos)';
}

async function carregarUtilizadores() {
  const btn = document.getElementById('btn-reload-users');
  if (btn) btn.textContent = '…';
  const r = await api('/api/admin/utilizadores');
  const box = document.getElementById('admin-users');
  if (!box || !Array.isArray(r.utilizadores)) return;
  box.innerHTML = r.utilizadores.map(u => `
    <div class="user-pill">
      <div class="user-dot ${u.conectado ? 'on' : 'off'}"></div>
      ${esc(u.nome)}
    </div>`).join('');
  if (btn) btn.textContent = '↺';
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

  // Verificar multas em paralelo
  const [r, rm] = await Promise.all([
    api('/api/historico/pessoal'),
    isAdmin ? Promise.resolve(null) : api('/api/multas')
  ]);
  if (r.erro) {
    document.getElementById('meus-content').innerHTML =
      `<p class="hist-empty">${esc(r.erro)}</p>`;
    return;
  }

  let html = '';

  // Secção de multa pendente
  if (rm && rm.multaTotal > 0) {
    html += `<div style="background:#fff3cd;border:1.5px solid #f0c040;border-radius:var(--rs);
                         padding:.75rem 1rem;margin-bottom:1rem">
      <div style="font-weight:700;color:#7a4f00;margin-bottom:.35rem">💸 Multa pendente: €${rm.multaTotal.toFixed(2)}</div>
      <div style="font-size:.82rem;color:#7a4f00">
        Tens ${(rm.multas||[]).length} multa(s) por atraso de devolução.
        Não podes requisitar novos livros até regularizares.
      </div>
      <button class="btn btn-ghost btn-sm" style="margin-top:.5rem"
              onclick="abrirDetalhesMulta(${JSON.stringify(rm.multas||[])})">
        Ver detalhes
      </button>
    </div>`;
  }

  html += '<div class="hist-sec-title">Actualmente requisitados</div>';
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

// ── Recuperação de password ──────────────────────────────────────────────

async function solicitarRecuperacao() {
  const email = v('rec-email');
  if (!email) { toast('Introduz o teu email', 'err'); return; }

  const btn = document.getElementById('rec-btn1');
  btn.disabled = true; btn.textContent = 'A solicitar…';

  const r = await api('/api/recuperar-password', 'POST', { email });

  btn.disabled = false; btn.textContent = 'Pedir código de recuperação';

  if (r.erro) { toast(r.erro, 'err'); return; }

  toast(r.mensagem, 'ok');

  // Mostrar passo 2
  document.getElementById('rec-step2').style.display = 'block';
  document.getElementById('rec-btn1').style.display  = 'none';
}

async function confirmarReset() {
  const token   = (v('rec-token') || '').toUpperCase();
  const novaPwd = v('rec-nova-pw');
  if (!token || !novaPwd) { toast('Preenche todos os campos', 'err'); return; }
  if (novaPwd.length < 6) { toast('A nova password deve ter pelo menos 6 caracteres', 'err'); return; }

  const r = await api('/api/reset-password', 'POST', { token, novaPassword: novaPwd });
  if (r.erro) { toast(r.erro, 'err'); return; }

  toast(r.mensagem, 'ok');

  // Limpar e voltar ao login
  document.getElementById('rec-email').value   = '';
  document.getElementById('rec-token').value   = '';
  document.getElementById('rec-nova-pw').value = '';
  document.getElementById('rec-step2').style.display = 'none';
  document.getElementById('rec-btn1').style.display  = 'block';
  switchTab('si');
}

// ── Admin: livros pendentes de aprovação ────────────────────────────────

async function carregarPendentes() {
  const box = document.getElementById('admin-pendentes');
  if (!box) return;
  box.innerHTML = '<span style="font-size:.82rem;color:var(--ink-d)">A carregar…</span>';
  const r = await api('/api/admin/pendentes');
  if (r.erro || !Array.isArray(r.pendentes)) {
    box.innerHTML = '<span style="font-size:.82rem;color:var(--red)">Erro ao carregar.</span>';
    return;
  }
  if (!r.pendentes.length) {
    box.innerHTML = '<span style="font-size:.82rem;color:var(--green)">✓ Nenhum livro aguarda aprovação.</span>';
    return;
  }
  box.innerHTML = r.pendentes.map(l => {
    const suspeito = l.flagAdmin;
    const bgColor  = suspeito ? 'var(--red-bg)' : 'var(--navy-bg)';
    const brColor  = suspeito ? '#e9b0b0'        : 'var(--navy-br)';
    const relatorio = l.relatorioScan || 'N/A';
    const dupHtml = l.duplicadoId ? `
      <div style="font-size:.76rem;background:#fff8e6;border:1.5px solid #f0d890;
                  border-radius:var(--rs);padding:.3rem .6rem;margin-bottom:.5rem;">
        ⚠ <strong>Possível duplicado</strong> — já existe "${esc(l.duplicadoTitulo)}"
        com ${l.duplicadoExemplares} exemplar(es).
        <button class="btn btn-outline-orange btn-sm" style="margin-left:.5rem"
                onclick="adicionarExemplar('${l.id}','${l.duplicadoId}','${esc(l.titulo)}','${l.duplicadoExemplares}')">
          ➕ Aprovar como novo exemplar
        </button>
      </div>` : '';
    return `
    <div style="background:${bgColor};border:1.5px solid ${brColor};border-radius:var(--rs);
                padding:.7rem 1rem;margin-bottom:.5rem;">
      <div style="font-family:var(--fd);font-size:.9rem;font-weight:600;margin-bottom:.15rem">
        ${esc(l.titulo)}
        ${suspeito ? '<span style="color:var(--red);font-size:.75rem;font-weight:600;margin-left:.4rem">🚨 SUSPEITO</span>' : ''}
      </div>
      <div style="font-size:.78rem;color:var(--ink-m);margin-bottom:.25rem">
        ${esc(l.autor)} &nbsp;·&nbsp; ${esc(l.categoria)}
        &nbsp;·&nbsp; Upload por: <strong>${esc(l.uploadPor || '—')}</strong>
      </div>
      ${dupHtml}
      <div style="font-size:.76rem;margin-bottom:.5rem;padding:.3rem .6rem;
                  background:var(--paper);border-radius:var(--rs);border:1px solid var(--border)">
        🔍 ${esc(relatorio)}
      </div>
      <div style="display:flex;gap:.4rem;flex-wrap:wrap">
        <button class="btn btn-success btn-sm"
                onclick="aprovarPendente('${l.id}','${esc(l.titulo)}')">
          ✅ Aprovar (novo livro)
        </button>
        <button class="btn btn-danger btn-sm"
                onclick="rejeitarPendente('${l.id}','${esc(l.titulo)}')">
          ❌ Rejeitar
        </button>
      </div>
    </div>`;
  }).join('');
}

async function aprovarPendente(id, titulo) {
  if (!confirm(`Aprovar e publicar o livro "${titulo}"?`)) return;
  const r = await api(`/api/admin/livros/${id}/aprovar`, 'POST');
  r.erro ? toast(r.erro, 'err') : toast(r.mensagem, 'ok');
  carregarPendentes();
  carregarLivros();
}

async function rejeitarPendente(id, titulo) {
  const motivo = prompt(`Rejeitar "${titulo}"\nMotivo (opcional):`);
  if (motivo === null) return; // cancelado
  const r = await api(`/api/admin/livros/${id}/rejeitar`, 'POST', { motivo });
  r.erro ? toast(r.erro, 'err') : toast(r.mensagem, 'ok');
  carregarPendentes();
  carregarLivros();
}

async function adicionarExemplar(idPendente, idExistente, titulo, exemplares) {
  if (!confirm(`Aprovar "${titulo}" como novo exemplar?\n\nO livro já existe com ${exemplares} exemplar(es). Será adicionada mais 1 cópia física (total: ${+exemplares + 1}).`)) return;
  const r = await api(`/api/admin/livros/${idPendente}/aprovar-como-exemplar/${idExistente}`, 'POST');
  r.erro ? toast(r.erro, 'err') : toast(r.mensagem, 'ok');
  carregarPendentes();
  carregarLivros();
}

// ── Admin: conteúdo suspeito ─────────────────────────────────────────────

let _modAcao = null, _modAlvo = null;

async function carregarSuspeitos() {
  const box = document.getElementById('admin-suspeitos');
  if (!box) return;
  box.innerHTML = '<span style="font-size:.82rem;color:var(--ink-d)">A carregar…</span>';
  const r = await api('/api/admin/suspeitos');
  if (r.erro || !Array.isArray(r.livros)) {
    box.innerHTML = '<span style="font-size:.82rem;color:var(--ink-d)">Erro ao carregar.</span>';
    return;
  }
  if (!r.livros.length) {
    box.innerHTML = '<span style="font-size:.82rem;color:var(--green)">✓ Nenhum conteúdo suspeito.</span>';
    return;
  }
  box.innerHTML = r.livros.map(l => `
    <div style="background:var(--red-bg);border:1.5px solid #e9b0b0;border-radius:var(--rs);
                padding:.7rem 1rem;margin-bottom:.5rem;">
      <div style="font-family:var(--fd);font-size:.9rem;font-weight:600;margin-bottom:.25rem">
        ${esc(l.titulo)}
      </div>
      <div style="font-size:.78rem;color:var(--ink-m);margin-bottom:.5rem">
        Upload por: <strong>${esc(l.uploadPor || '—')}</strong>
        &nbsp;·&nbsp; Motivo: <span style="color:var(--red)">${esc(l.motivoSuspeicao || '—')}</span>
      </div>
      <div style="display:flex;gap:.4rem;flex-wrap:wrap">
        <button class="btn btn-danger btn-sm" onclick="apagarLivroSuspeito('${l.id}','${esc(l.titulo)}')">
          🗑 Apagar Livro
        </button>
        ${l.uploadPor ? `
        <button class="btn btn-outline-orange btn-sm"
                onclick="abrirModerar('avisar','${esc(l.uploadPor)}','${esc(l.motivoSuspeicao||'')}')">
          ⚠ Avisar ${esc(l.uploadPor)}
        </button>
        <button class="btn btn-danger btn-sm"
                onclick="abrirModerar('bloquear','${esc(l.uploadPor)}','')">
          🚫 Bloquear ${esc(l.uploadPor)}
        </button>` : ''}
      </div>
    </div>`).join('');
}

async function apagarLivroSuspeito(id, titulo) {
  if (!confirm(`Apagar permanentemente:\n"${titulo}"?\nEsta acção não pode ser revertida.`)) return;
  const r = await api(`/api/livros/${id}`, 'DELETE');
  r.erro ? toast(r.erro, 'err') : toast('Livro apagado.', 'ok');
  carregarSuspeitos();
  carregarLivros();
}

function abrirModerar(acao, nomePara, motivo) {
  _modAcao = acao; _modAlvo = nomePara;
  document.getElementById('mod-titulo').textContent =
    acao === 'avisar' ? `Avisar "${nomePara}"` : `Bloquear "${nomePara}"`;
  document.getElementById('mod-body').innerHTML = acao === 'avisar'
    ? `<div class="field"><label>Motivo do aviso</label>
       <input type="text" id="mod-motivo" value="${esc(motivo)}" placeholder="Descreve o motivo"></div>`
    : `<p>Bloquear <strong>${esc(nomePara)}</strong>? O utilizador não poderá fazer login.</p>`;
  openOv('ov-moderar');
}

async function confirmarModeracao() {
  if (!_modAcao || !_modAlvo) return;
  let r;
  if (_modAcao === 'avisar') {
    const motivo = v('mod-motivo') || 'Conteúdo impróprio detectado';
    r = await api(`/api/admin/avisar/${encodeURIComponent(_modAlvo)}`, 'POST', { motivo });
  } else {
    r = await api(`/api/admin/bloquear/${encodeURIComponent(_modAlvo)}`, 'POST');
  }
  closeOv('ov-moderar');
  r.erro ? toast(r.erro, 'err') : toast('Acção executada com sucesso.', 'ok');
  carregarUtilizadores();
}

// ── Admin: recuperações de password pendentes ────────────────────────────

async function carregarRecuperacoes() {
  const box = document.getElementById('admin-recuperacoes');
  if (!box) return;

  const r = await api('/api/admin/recuperacoes');
  if (r.erro) { box.innerHTML = `<span style="font-size:.82rem;color:var(--red)">${esc(r.erro)}</span>`; return; }

  if (!r.recuperacoes.length) {
    box.innerHTML = '<span style="font-size:.82rem;color:var(--green)">✓ Nenhum pedido pendente.</span>';
    return;
  }

  const agora = new Date();
  box.innerHTML = r.recuperacoes.map(rec => {
    const exp   = new Date(rec.expira);
    const mins  = Math.max(0, Math.round((exp - agora) / 60000));
    const urgente = mins < 30;
    return `
    <div style="background:var(--navy-bg);border:1.5px solid var(--navy-br);border-radius:var(--rs);
                padding:.7rem 1rem;margin-bottom:.5rem;display:flex;align-items:center;gap:.75rem;flex-wrap:wrap">
      <div style="flex:1;min-width:160px">
        <div style="font-family:var(--fd);font-size:.88rem;font-weight:600">${esc(rec.nome)}</div>
        <div style="font-size:.75rem;color:var(--ink-m)">${esc(rec.email)}</div>
        <div style="font-size:.72rem;color:${urgente ? 'var(--orange)' : 'var(--ink-d)'}">
          Expira em ${mins} min
        </div>
      </div>
      <div style="display:flex;align-items:center;gap:.5rem">
        <span style="font-family:var(--fm);font-size:1rem;font-weight:700;
                     letter-spacing:.18em;color:var(--navy);background:var(--paper);
                     border:1.5px solid var(--navy-br);border-radius:var(--rs);
                     padding:.25rem .7rem" id="tok-${esc(rec.token)}">${esc(rec.token)}</span>
        <button class="btn btn-ghost btn-sm"
                onclick="copiarToken('${esc(rec.token)}')"
                title="Copiar token">📋 Copiar</button>
      </div>
    </div>`;
  }).join('');
}

function copiarToken(token) {
  navigator.clipboard.writeText(token).then(() => {
    toast('Token copiado: ' + token, 'ok');
  }).catch(() => {
    // fallback para browsers sem clipboard API
    const el = document.getElementById('tok-' + token);
    if (el) {
      const sel = window.getSelection();
      const range = document.createRange();
      range.selectNodeContents(el);
      sel.removeAllRanges();
      sel.addRange(range);
    }
    toast('Selecciona o token manualmente: ' + token, 'inf');
  });
}

// ── Multas por atraso ────────────────────────────────────────────────────

async function verificarMulta() {
  if (isAdmin) return;
  const r = await api('/api/multas');
  if (r.multaTotal > 0) {
    mostrarBannerMulta(r.multaTotal, r.multas || []);
  } else {
    esconderBannerMulta();
  }
}

function mostrarBannerMulta(total, multas) {
  let banner = document.getElementById('multa-banner');
  if (!banner) {
    banner = document.createElement('div');
    banner.id = 'multa-banner';
    banner.className = 'multa-banner';
    // Inserir antes do main
    const main = document.querySelector('#main-view main');
    if (main) main.before(banner);
  }
  banner.innerHTML = `
    <span class="multa-banner-icon">💸</span>
    <span>
      <strong>Tens uma multa pendente de ${total.toFixed(2)}€</strong>
      por atraso de devolução.
      Não podes requisitar novos livros até regularizares.
      Contacta o administrador.
    </span>
    <button class="btn btn-ghost btn-sm" onclick="abrirDetalhesMulta(${JSON.stringify(multas)})">
      Ver detalhes
    </button>`;
}

function esconderBannerMulta() {
  const banner = document.getElementById('multa-banner');
  if (banner) banner.remove();
}

function abrirDetalhesMulta(multas) {
  const total = multas.reduce((s, m) => s + m.valor, 0);
  let html = `<p style="margin-bottom:.75rem;font-size:.875rem">
    Total em dívida: <strong style="color:var(--red)">€${total.toFixed(2)}</strong>
  </p><div style="display:flex;flex-direction:column;gap:.5rem">`;
  for (const m of multas) {
    html += `<div style="background:var(--red-bg);border:1.5px solid #e9b0b0;
                         border-radius:var(--rs);padding:.6rem .9rem;">
      <div style="font-weight:600;font-size:.875rem">${esc(m.tituloLivro)}</div>
      <div style="font-size:.78rem;color:var(--ink-m);margin-top:.15rem">
        ${m.diasAtraso} dia(s) de atraso &nbsp;·&nbsp;
        <span style="color:var(--red)">€${(+m.valor).toFixed(2)}</span>
        &nbsp;·&nbsp; ${formatDate(m.data)}
      </div>
    </div>`;
  }
  html += `</div><p style="font-size:.78rem;color:var(--ink-d);margin-top:.75rem">
    Contacta o administrador para regularizar. Taxa: €0.50/dia de atraso.
  </p>`;

  // Usar modal de detalhes temporariamente
  document.getElementById('det-titulo').textContent = 'As tuas multas';
  document.getElementById('det-body').innerHTML = html;
  document.getElementById('det-acts').innerHTML = '';
  openOv('ov-det');
}

// ── Admin: multas ────────────────────────────────────────────────────────

async function carregarMultas() {
  const box = document.getElementById('admin-multas');
  if (!box) return;
  box.innerHTML = '<span style="font-size:.82rem;color:var(--ink-d)">A carregar…</span>';
  const r = await api('/api/admin/multas');
  if (!Array.isArray(r.multas) || !r.multas.length) {
    box.innerHTML = '<span style="font-size:.82rem;color:var(--green)">✓ Sem multas pendentes.</span>';
    return;
  }
  box.innerHTML = r.multas.map(u => `
    <div style="background:#fff3cd;border:1.5px solid #f0c040;border-radius:var(--rs);
                padding:.65rem 1rem;margin-bottom:.4rem;
                display:flex;align-items:center;gap:.75rem;flex-wrap:wrap">
      <div style="flex:1;min-width:140px">
        <div style="font-weight:600;font-size:.875rem">${esc(u.nome)}</div>
        <div style="font-size:.76rem;color:#7a4f00">
          Multa: <strong>€${(+u.multaTotal).toFixed(2)}</strong>
          &nbsp;·&nbsp; ${(u.multas||[]).length} ocorrência(s)
        </div>
      </div>
      <button class="btn btn-success btn-sm"
              onclick="perdoarMulta('${esc(u.nome)}')">
        ✓ Perdoar multa
      </button>
    </div>`).join('');
}

async function perdoarMulta(nomeUtilizador) {
  if (!confirm(`Perdoar toda a multa de "${nomeUtilizador}"?`)) return;
  const r = await api(`/api/admin/multas/${encodeURIComponent(nomeUtilizador)}/perdoar`, 'POST');
  r.erro ? toast(r.erro, 'err') : toast(r.mensagem, 'ok');
  carregarMultas();
}

// ── Avaliações ───────────────────────────────────────────────────────────

/**
 * Gera o HTML da secção de avaliações do modal de detalhes.
 */
function renderAvaliacoes(avaliacoes, livroId, media, total) {
  const minhaAv = avaliacoes.find(a => a.utilizador === nomeUser);

  // Cabeçalho
  let html = `<div class="mdivider"></div>
  <div class="aval-section">
    <div class="aval-header">
      <span class="aval-sec-title">Avaliações</span>
      ${total > 0
        ? `<span class="aval-media-info">
             <span class="star-gold">${renderStarsStatic(media)}</span>
             <strong>${(+media).toFixed(1)}</strong>
             <span style="color:var(--ink-d)">· ${total} avaliação(ões)</span>
           </span>`
        : ''}
    </div>`;

  // Formulário para avaliar
  html += `<div class="aval-form">
    <div class="aval-form-label">${minhaAv ? 'A tua avaliação' : 'Avaliar este livro'}</div>
    <div class="star-row" id="star-row-${livroId}">`;
  for (let i = 1; i <= 5; i++) {
    html += `<button class="star-btn" data-star="${i}"
               onclick="selecionarEstrela(${i},'${livroId}')"
               onmouseover="hoverEstrela(${i},'${livroId}')"
               onmouseout="unhoverEstrela('${livroId}')">★</button>`;
  }
  html += `</div>
    <textarea id="aval-txt-${livroId}" class="aval-textarea"
              placeholder="Comentário (opcional)…" rows="2">${esc(minhaAv?.comentario || '')}</textarea>
    <div style="display:flex;gap:.5rem;flex-wrap:wrap">
      <button class="btn btn-success btn-sm" onclick="submeterAvaliacao('${livroId}')">
        ${minhaAv ? '✏ Actualizar' : '★ Avaliar'}
      </button>
      ${minhaAv
        ? `<button class="btn btn-ghost btn-sm"
                   onclick="removerAvaliacao('${livroId}','${esc(nomeUser)}')">
             Remover
           </button>`
        : ''}
    </div>
  </div>`;

  // Lista de avaliações existentes
  if (avaliacoes.length) {
    html += `<div class="aval-list">`;
    for (const a of avaliacoes) {
      const isMine = a.utilizador === nomeUser;
      html += `<div class="aval-item${isMine ? ' mine' : ''}">
        <div class="aval-item-head">
          <span class="aval-user">${esc(a.utilizador)}</span>
          <span class="aval-stars">${'★'.repeat(a.estrelas)}${'☆'.repeat(5 - a.estrelas)}</span>
          <span class="aval-data">${formatDate(a.data)}</span>
          ${isAdmin && !isMine
            ? `<button class="btn btn-danger btn-sm"
                       style="padding:.18rem .45rem;font-size:.7rem;margin-left:auto"
                       onclick="removerAvaliacao('${livroId}','${esc(a.utilizador)}')">✕</button>`
            : ''}
        </div>
        ${a.comentario ? `<div class="aval-coment">${esc(a.comentario)}</div>` : ''}
      </div>`;
    }
    html += `</div>`;
  } else {
    html += `<p class="hist-empty">Nenhuma avaliação ainda — sê o primeiro!</p>`;
  }

  html += `</div>`;
  return html;
}

/** Renderiza estrelas estáticas (média visual). */
function renderStarsStatic(media) {
  let s = '';
  for (let i = 1; i <= 5; i++) s += i <= Math.round(media) ? '★' : '☆';
  return s;
}

/** Actualiza visualmente as estrelas do formulário. */
function actualizarEstrelasUI(livroId, n) {
  const row = document.getElementById('star-row-' + livroId);
  if (!row) return;
  row.querySelectorAll('.star-btn').forEach((btn, i) => {
    btn.classList.toggle('active',  i < n);
    btn.classList.remove('hovered');
  });
}

function selecionarEstrela(n, livroId) {
  _avaliando[livroId] = n;
  actualizarEstrelasUI(livroId, n);
}

function hoverEstrela(n, livroId) {
  const row = document.getElementById('star-row-' + livroId);
  if (!row) return;
  row.querySelectorAll('.star-btn').forEach((btn, i) => {
    btn.classList.toggle('hovered', i < n);
  });
}

function unhoverEstrela(livroId) {
  const sel = _avaliando[livroId] || 0;
  actualizarEstrelasUI(livroId, sel);
}

async function submeterAvaliacao(livroId) {
  const estrelas = _avaliando[livroId];
  if (!estrelas) { toast('Selecciona pelo menos 1 estrela ★', 'err'); return; }
  const comentario = document.getElementById('aval-txt-' + livroId)?.value?.trim() || '';
  const r = await api(`/api/livros/${livroId}/avaliar`, 'POST', { estrelas, comentario });
  if (r.erro) { toast(r.erro, 'err'); return; }
  toast(r.mensagem || 'Avaliação registada!', 'ok');
  delete _avaliando[livroId];
  abrirDetalhes(livroId);   // recarrega modal com avaliações actualizadas
  carregarLivros();          // actualiza badge de estrelas no grid
}

async function removerAvaliacao(livroId, utilizador) {
  const msg = utilizador === nomeUser
    ? 'Remover a tua avaliação?'
    : `Remover avaliação de "${utilizador}"?`;
  if (!confirm(msg)) return;
  const r = await api(
    `/api/livros/${livroId}/avaliacoes/${encodeURIComponent(utilizador)}`,
    'DELETE'
  );
  if (r.erro) { toast(r.erro, 'err'); return; }
  toast(r.mensagem || 'Avaliação removida.', 'ok');
  delete _avaliando[livroId];
  abrirDetalhes(livroId);
  carregarLivros();
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

// ── Exportar CSV ────────────────────────────────────────────────────────

function exportarCSV(tipo) {
  const url = `/api/admin/relatorio/${tipo}.csv?sid=${encodeURIComponent(sessionId)}`;
  const a = document.createElement('a');
  a.href = url;
  a.download = `${tipo}-${new Date().toISOString().slice(0,10)}.csv`;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  toast(`A descarregar ${tipo}.csv…`, 'ok');
}

// ── Chat em Tempo Real ──────────────────────────────────────────────────

function iniciarChat() {
  const panel = document.getElementById('chat-panel');
  panel.style.display = 'flex';
  // Admin: preparar tab privado com selector
  if (isAdmin) chatCarregarInterlocutores();
  else {
    // Utilizador normal: privado é sempre com admin
    chatPrivAlvo = 'admin';
  }
}

function toggleChat() {
  chatAberto = !chatAberto;
  document.getElementById('chat-box').classList.toggle('oculto', !chatAberto);
  if (chatAberto) {
    chatNaoLidas[chatSala] = 0;
    chatAtualizarBadge();
    chatCarregarMensagens();
    setTimeout(() => document.getElementById('chat-input').focus(), 100);
  }
}

function switchChatTab(sala, btn) {
  chatSala = sala;
  document.querySelectorAll('.chat-tab').forEach(t => t.classList.remove('active'));
  btn.classList.add('active');
  // Mostrar selector de utilizador só para admin no tab privado
  const paraRow = document.getElementById('chat-para-row');
  if (isAdmin && sala === 'priv') {
    paraRow.style.display = 'flex';
    chatPrivAlvo = document.getElementById('chat-para-select').value || null;
  } else {
    paraRow.style.display = 'none';
    if (sala === 'priv') chatPrivAlvo = 'admin';
  }
  // limpar nao-lidas deste tab
  chatNaoLidas[sala] = 0;
  chatAtualizarBadge();
  chatCarregarMensagens();
}

async function chatCarregarInterlocutores() {
  if (!isAdmin) return;
  const lista = await api('/api/chat/interlocutores');
  const sel = document.getElementById('chat-para-select');
  if (!Array.isArray(lista)) return;
  // manter opção vazia + adicionar utilizadores
  const existentes = Array.from(sel.options).map(o => o.value).filter(Boolean);
  lista.forEach(nome => {
    if (!existentes.includes(nome)) {
      const opt = document.createElement('option');
      opt.value = opt.textContent = nome;
      sel.appendChild(opt);
    }
  });
}

function chatPrivMudou() {
  chatPrivAlvo = document.getElementById('chat-para-select').value || null;
  chatCarregarMensagens();
}

async function chatCarregarMensagens() {
  const msgs = document.getElementById('chat-msgs');
  msgs.innerHTML = '<div class="chat-empty"><div class="chat-empty-icon">💬</div><span>A carregar…</span></div>';

  let url = '/api/chat/mensagens';
  if (chatSala === 'priv' && chatPrivAlvo) {
    url += '?para=' + encodeURIComponent(chatPrivAlvo);
  } else if (chatSala === 'priv' && isAdmin && !chatPrivAlvo) {
    // nenhum utilizador seleccionado
    msgs.innerHTML = '<div class="chat-empty"><div class="chat-empty-icon">🔒</div><span>Selecciona um utilizador para ver a conversa</span></div>';
    return;
  }

  const r = await api(url);
  if (!Array.isArray(r) || r.length === 0) {
    msgs.innerHTML = '<div class="chat-empty"><div class="chat-empty-icon">💬</div><span>Sem mensagens ainda.<br>Sê o primeiro a escrever!</span></div>';
    return;
  }
  chatRenderMensagens(r);
}

function chatRenderMensagens(lista) {
  const msgs = document.getElementById('chat-msgs');
  msgs.innerHTML = '';
  let ultimaData = null;

  lista.forEach(msg => {
    const dt = new Date(msg.data);
    const dataStr = dt.toLocaleDateString('pt-PT', { day:'numeric', month:'long' });
    if (dataStr !== ultimaData) {
      ultimaData = dataStr;
      const sep = document.createElement('div');
      sep.className = 'chat-date-sep';
      sep.textContent = dataStr;
      msgs.appendChild(sep);
    }
    msgs.appendChild(chatCriarBolha(msg));
  });
  msgs.scrollTop = msgs.scrollHeight;
}

function chatCriarBolha(msg) {
  const mine = msg.de === nomeUser;
  const wrap = document.createElement('div');
  wrap.className = 'chat-msg ' + (mine ? 'mine' : 'theirs');
  wrap.dataset.id = msg.id;

  const dt = new Date(msg.data);
  const hora = dt.toLocaleTimeString('pt-PT', { hour: '2-digit', minute: '2-digit' });

  if (!mine) {
    const meta = document.createElement('div');
    meta.className = 'chat-msg-meta';
    const nameSpan = document.createElement('span');
    nameSpan.className = 'chat-msg-name' + (msg.de.toLowerCase() === 'admin' ? ' is-admin' : '');
    nameSpan.textContent = msg.de;
    meta.appendChild(nameSpan);
    const timeSpan = document.createElement('span');
    timeSpan.textContent = hora;
    meta.appendChild(timeSpan);
    wrap.appendChild(meta);
  }

  const bubble = document.createElement('div');
  bubble.className = 'chat-bubble';
  bubble.textContent = msg.texto;
  wrap.appendChild(bubble);

  if (mine) {
    const meta = document.createElement('div');
    meta.className = 'chat-msg-meta';
    meta.textContent = hora;
    wrap.appendChild(meta);
  }
  return wrap;
}

function chatReceberMensagem(msg, privada) {
  const sala = privada ? 'priv' : 'global';
  const tabActiva = chatAberto && chatSala === sala;

  // Verificar se a mensagem é relevante para o tab privado actual
  if (privada) {
    const relevante = (msg.de === nomeUser && msg.para === chatPrivAlvo)
                   || (msg.para === nomeUser && msg.de === chatPrivAlvo)
                   || (isAdmin && (msg.de === chatPrivAlvo || msg.para === chatPrivAlvo));
    if (tabActiva && relevante) {
      const msgs = document.getElementById('chat-msgs');
      // Remover placeholder vazio
      const empty = msgs.querySelector('.chat-empty');
      if (empty) empty.remove();
      msgs.appendChild(chatCriarBolha(msg));
      msgs.scrollTop = msgs.scrollHeight;
    } else {
      chatNaoLidas.priv = (chatNaoLidas.priv || 0) + 1;
      // Atualizar lista de interlocutores se admin
      if (isAdmin) chatCarregarInterlocutores();
    }
  } else {
    if (tabActiva) {
      const msgs = document.getElementById('chat-msgs');
      const empty = msgs.querySelector('.chat-empty');
      if (empty) empty.remove();
      msgs.appendChild(chatCriarBolha(msg));
      msgs.scrollTop = msgs.scrollHeight;
    } else {
      chatNaoLidas.global = (chatNaoLidas.global || 0) + 1;
    }
  }

  chatAtualizarBadge();
  chatAtualizarDots();

  // Notificação toast se o chat estiver fechado ou noutra sala
  if (!tabActiva) {
    const quem = msg.de === nomeUser ? 'Tu' : msg.de;
    const preview = msg.texto.length > 50 ? msg.texto.slice(0, 50) + '…' : msg.texto;
    if (!chatAberto) toast(`💬 ${quem}: ${preview}`, 'inf');
  }
}

function chatAtualizarBadge() {
  const total = (chatNaoLidas.global || 0) + (chatNaoLidas.priv || 0);
  const badge = document.getElementById('chat-badge');
  badge.style.display = total > 0 ? 'block' : 'none';
  badge.textContent = total > 99 ? '99+' : total;
}

function chatAtualizarDots() {
  const dotG = document.getElementById('chat-dot-global');
  const dotP = document.getElementById('chat-dot-priv');
  if (dotG) dotG.style.display = chatNaoLidas.global > 0 ? 'block' : 'none';
  if (dotP) dotP.style.display = chatNaoLidas.priv  > 0 ? 'block' : 'none';
}

async function enviarChat() {
  const input = document.getElementById('chat-input');
  const texto = input.value.trim();
  if (!texto) return;

  if (chatSala === 'priv') {
    if (!chatPrivAlvo) { toast('Selecciona um destinatário', 'err'); return; }
    if (chatPrivAlvo.toLowerCase() === nomeUser.toLowerCase()) {
      toast('Não podes enviar mensagem a ti próprio', 'err'); return;
    }
  }

  const para = chatSala === 'priv' ? chatPrivAlvo : '';
  input.value = '';
  chatAutoResize(input);

  const r = await api('/api/chat/enviar', 'POST', { para, texto });
  if (r.erro) toast(r.erro, 'err');
}

function chatKeyDown(e) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    enviarChat();
  }
}

function chatAutoResize(el) {
  el.style.height = 'auto';
  el.style.height = Math.min(el.scrollHeight, 100) + 'px';
}

// ── Restaurar sessão (isAdmin lido do localStorage, não derivado do nome) ──

if (sessionId && nomeUser) mostrarMain();
