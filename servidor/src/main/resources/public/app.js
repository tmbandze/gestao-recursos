// ── Estado ────────────────────────────────────────────────────────────
let sessionId = localStorage.getItem('sid');
let nomeUser  = localStorage.getItem('nome');
let livros    = [];
let filtro    = 'todos';
let pesquisa  = '';
let sse       = null;

// ── Tabs ──────────────────────────────────────────────────────────────

function switchTab(t) {
    const isSi = t === 'si';
    document.getElementById('form-si').classList.toggle('active',  isSi);
    document.getElementById('form-su').classList.toggle('active', !isSi);
    document.getElementById('tab-si').classList.toggle('active',   isSi);
    document.getElementById('tab-su').classList.toggle('active',  !isSi);
    document.getElementById('tab-pill').classList.toggle('right',  !isSi);
}

// ── Autenticação ──────────────────────────────────────────────────────

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
    if (!nome || !email || !password)  { toast('Preenche todos os campos', 'err'); return; }
    if (password.length < 6)           { toast('Password: mínimo 6 caracteres', 'err'); return; }
    if (password !== confirm)          { toast('As passwords não coincidem', 'err'); return; }
    const r = await api('/api/registar', 'POST', { nome, email, password });
    if (r.erro) { toast(r.erro, 'err'); return; }
    iniciarSessao(r);
}

function iniciarSessao(r) {
    sessionId = r.sessionId;
    nomeUser  = r.nome;
    localStorage.setItem('sid',  sessionId);
    localStorage.setItem('nome', nomeUser);
    mostrarMain();
}

async function logout() {
    await api('/api/logout', 'POST');
    localStorage.removeItem('sid');
    localStorage.removeItem('nome');
    if (sse) { sse.close(); sse = null; }
    sessionId = nomeUser = null;
    document.getElementById('main-view').classList.remove('active');
    document.getElementById('auth-view').style.display = 'flex';
    switchTab('si');
}

// ── Mostrar app principal ─────────────────────────────────────────────

function mostrarMain() {
    document.getElementById('auth-view').style.display = 'none';
    document.getElementById('main-view').classList.add('active');

    const initials = nomeUser.split(' ').map(w => w[0]).join('').slice(0, 2).toUpperCase();
    document.getElementById('user-av').textContent = initials;
    document.getElementById('user-nm').textContent = nomeUser;

    if (nomeUser.toLowerCase() === 'admin') {
        document.getElementById('admin-box').style.display = 'block';
        recarregarLog();
    }

    carregarLivros();
    ligarSSE();
}

// ── API ───────────────────────────────────────────────────────────────

async function api(path, method = 'GET', body = null) {
    const h = { 'Content-Type': 'application/json' };
    if (sessionId) h['X-Session-ID'] = sessionId;
    try {
        const res = await fetch(path, { method, headers: h, body: body ? JSON.stringify(body) : null });
        return await res.json();
    } catch { return { erro: 'Erro de ligação ao servidor' }; }
}

// ── SSE ───────────────────────────────────────────────────────────────

function ligarSSE() {
    if (sse) sse.close();
    sse = new EventSource(`/api/eventos?sid=${sessionId}`);
    sse.addEventListener('atualizacao', () => { carregarLivros(); toast('Lista actualizada', 'inf'); });
    sse.addEventListener('notificacao', e  => toast('🔔 ' + e.data, 'ok'));
    sse.onerror = () => {};
}

// ── Livros ────────────────────────────────────────────────────────────

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
    set('s-total', livros.length);
    set('s-disp',  disp);
    set('s-req',   livros.length - disp);
}

function setFiltro(f, btn) {
    filtro = f;
    document.querySelectorAll('.ft').forEach(b => b.classList.remove('active'));
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

    grid.innerHTML = lista.map(l => `
        <article class="book ${l.estado === 'DISPONIVEL' ? 'av' : 'req'}"
                 onclick="abrirDetalhes('${l.id}')">
            <div class="book-t">${esc(l.titulo)}</div>
            <div class="book-a">${esc(l.autor)}</div>
            <div class="book-f">
                <span class="badge ${l.estado === 'DISPONIVEL' ? 'b-green' : 'b-gold'}">
                    ${l.estado === 'DISPONIVEL' ? '✓ disponível' : '⏳ requisitado'}
                </span>
                <span class="badge b-muted">${esc(l.categoria)}</span>
            </div>
        </article>`).join('');
}

// ── Detalhes ──────────────────────────────────────────────────────────

async function abrirDetalhes(id) {
    const d = await api('/api/livros/' + id);
    if (d.erro) { toast(d.erro, 'err'); return; }

    document.getElementById('det-titulo').textContent = d.titulo;

    const filaHtml = d.filaEspera?.length
        ? `<div class="queue-list">${d.filaEspera.map((n, i) =>
            `<div class="queue-item">${i + 1}. ${esc(n)}</div>`).join('')}</div>`
        : '<span style="color:var(--tx-d)">—</span>';

    document.getElementById('det-body').innerHTML = `
        <div class="drow"><span class="dlabel">Autor</span>
            <span class="dval">${esc(d.autor)}</span></div>
        <div class="drow"><span class="dlabel">Categoria</span>
            <span class="dval">${esc(d.categoria)}</span></div>
        <div class="drow"><span class="dlabel">Estado</span>
            <span class="dval">
                <span class="badge ${d.estado === 'DISPONIVEL' ? 'b-green' : 'b-gold'}">
                    ${d.estado === 'DISPONIVEL' ? '✓ disponível' : '⏳ requisitado'}
                </span>
            </span></div>
        ${d.estado !== 'DISPONIVEL' ? `
        <div class="drow"><span class="dlabel">Com</span>
            <span class="dval">${esc(d.estudanteActual || '—')}</span></div>` : ''}
        <div class="drow"><span class="dlabel">Fila</span>
            <span class="dval">${filaHtml}</span></div>`;

    const acts = document.getElementById('det-acts');
    acts.innerHTML = '';

    if (d.estado === 'DISPONIVEL') {
        acts.appendChild(mkBtn('📖 Requisitar', 'btn-green', () => acaoLivro(id, 'requisitar')));
    } else if (d.estudanteActual === nomeUser) {
        acts.appendChild(mkBtn('↩ Devolver', 'btn-red', () => acaoLivro(id, 'devolver')));
    } else {
        acts.appendChild(mkBtn('⏳ Entrar na fila', 'btn-ghost', () => acaoLivro(id, 'requisitar')));
    }

    openOv('ov-det');
}

async function acaoLivro(id, acao) {
    closeOv('ov-det');
    const r = await api(`/api/livros/${id}/${acao}`, 'POST');
    r.erro ? toast(r.erro, 'err') : toast(r.mensagem, 'ok');
    carregarLivros();
}

// ── Adicionar livro ───────────────────────────────────────────────────

function abrirAdd() { openOv('ov-add'); document.getElementById('add-t').focus(); }

async function adicionarLivro() {
    const titulo    = v('add-t');
    const autor     = v('add-a');
    const categoria = v('add-c') || 'Geral';
    if (!titulo || !autor) { toast('Título e autor obrigatórios', 'err'); return; }
    const r = await api('/api/livros', 'POST', { titulo, autor, categoria });
    closeOv('ov-add');
    ['add-t','add-a','add-c'].forEach(id => set(id, ''));
    r.erro ? toast(r.erro, 'err') : toast('Livro adicionado!', 'ok');
    carregarLivros();
}

// ── Histórico ─────────────────────────────────────────────────────────

async function abrirHistorico() {
    openOv('ov-hist');
    const r = await api('/api/historico');
    document.getElementById('hist-log').textContent = r.log || '(sem registos)';
}

async function recarregarLog() {
    const r = await api('/api/historico');
    document.getElementById('admin-log').textContent = r.log || '(sem registos)';
}

// ── Modais ────────────────────────────────────────────────────────────

const openOv  = id => document.getElementById(id).classList.add('open');
const closeOv = id => document.getElementById(id).classList.remove('open');

document.querySelectorAll('.ov').forEach(o =>
    o.addEventListener('click', e => { if (e.target === o) o.classList.remove('open'); }));

// ── Toasts ────────────────────────────────────────────────────────────

function toast(msg, tipo = 'inf') {
    const el = document.createElement('div');
    el.className = `toast ${tipo}`;
    el.textContent = msg;
    document.getElementById('toasts').appendChild(el);
    setTimeout(() => el.remove(), 4000);
}

// ── Utils ─────────────────────────────────────────────────────────────

const v   = id => document.getElementById(id)?.value?.trim() ?? '';
const set = (id, val) => { const el = document.getElementById(id); if (el) el.value !== undefined ? el.value = val : el.textContent = val; };

function esc(s) {
    if (!s) return '';
    return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
}

function mkBtn(text, cls, fn) {
    const b = document.createElement('button');
    b.className = `btn ${cls}`; b.textContent = text; b.onclick = fn;
    return b;
}

// ── Enter keys ────────────────────────────────────────────────────────

document.getElementById('si-pw').addEventListener('keydown',  e => { if (e.key === 'Enter') signIn(); });
document.getElementById('su-pw2').addEventListener('keydown', e => { if (e.key === 'Enter') signUp(); });

// ── Restaurar sessão ──────────────────────────────────────────────────

if (sessionId && nomeUser) mostrarMain();
