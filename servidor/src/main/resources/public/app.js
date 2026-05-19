// ── Estado ────────────────────────────────────────────────────────────
let sessionId      = localStorage.getItem('sessionId');
let nomeUtilizador = localStorage.getItem('nomeUtilizador');
let livros         = [];
let filtro         = 'todos';
let pesquisaActual = '';
let sseSource      = null;

// ── Autenticação ──────────────────────────────────────────────────────

async function login() {
    const nome = document.getElementById('login-nome').value.trim();
    if (!nome) { toast('Introduz o teu nome', 'error'); return; }

    const r = await api('/api/login', 'POST', { nome });
    if (r.erro) { toast(r.erro, 'error'); return; }

    sessionId      = r.sessionId;
    nomeUtilizador = r.nome;
    localStorage.setItem('sessionId', sessionId);
    localStorage.setItem('nomeUtilizador', nomeUtilizador);
    mostrarMain();
}

async function logout() {
    await api('/api/logout', 'POST');
    localStorage.removeItem('sessionId');
    localStorage.removeItem('nomeUtilizador');
    if (sseSource) { sseSource.close(); sseSource = null; }
    document.getElementById('main-view').classList.remove('active');
    document.getElementById('login-view').style.display = 'flex';
    document.getElementById('login-nome').value = '';
    sessionId = nomeUtilizador = null;
}

function mostrarMain() {
    document.getElementById('login-view').style.display = 'none';
    document.getElementById('main-view').classList.add('active');
    document.getElementById('user-badge').textContent = '👤 ' + nomeUtilizador;

    if (nomeUtilizador.toLowerCase() === 'admin') {
        document.getElementById('admin-panel').style.display = 'block';
        carregarLog();
    }

    carregarLivros();
    conectarSSE();
}

// ── API helper ────────────────────────────────────────────────────────

async function api(path, method = 'GET', body = null) {
    const opts = { method, headers: { 'Content-Type': 'application/json' } };
    if (sessionId) opts.headers['X-Session-ID'] = sessionId;
    if (body)      opts.body = JSON.stringify(body);
    try {
        const res = await fetch(path, opts);
        return await res.json();
    } catch {
        return { erro: 'Erro de ligação ao servidor' };
    }
}

// ── SSE (eventos em tempo real) ───────────────────────────────────────

function conectarSSE() {
    if (sseSource) sseSource.close();
    sseSource = new EventSource(`/api/eventos?sid=${sessionId}`);

    sseSource.addEventListener('atualizacao', () => {
        carregarLivros();
        toast('Lista de livros actualizada', 'info');
    });

    sseSource.addEventListener('notificacao', e => {
        toast('🔔 ' + e.data, 'success');
    });

    sseSource.onerror = () => { /* reconecta automaticamente */ };
}

// ── Livros ────────────────────────────────────────────────────────────

async function carregarLivros() {
    const dados = await api('/api/livros');
    if (Array.isArray(dados)) { livros = dados; actualizarStats(); renderizar(); }
}

async function pesquisar() {
    pesquisaActual = document.getElementById('search-input').value.trim();
    if (pesquisaActual) {
        const dados = await api('/api/livros/pesquisa?q=' + encodeURIComponent(pesquisaActual));
        if (Array.isArray(dados)) { livros = dados; renderizar(); }
    } else {
        carregarLivros();
    }
}

function actualizarStats() {
    const disp = livros.filter(l => l.estado === 'DISPONIVEL').length;
    document.getElementById('stat-total').textContent = livros.length;
    document.getElementById('stat-disp').textContent  = disp;
    document.getElementById('stat-req').textContent   = livros.length - disp;
}

function setFiltro(f, btn) {
    filtro = f;
    document.querySelectorAll('.filter-tab').forEach(t => t.classList.remove('active'));
    btn.classList.add('active');
    renderizar();
}

function renderizar() {
    let lista = livros;
    if (filtro === 'disponiveis')  lista = lista.filter(l => l.estado === 'DISPONIVEL');
    if (filtro === 'requisitados') lista = lista.filter(l => l.estado !== 'DISPONIVEL');

    const grid = document.getElementById('books-grid');
    if (!lista.length) {
        grid.innerHTML = `<div class="empty-state">
            <div class="icon">📭</div>
            <div>${pesquisaActual ? 'Sem resultados para "' + esc(pesquisaActual) + '"' : 'Sem livros disponíveis'}</div>
        </div>`;
        return;
    }

    grid.innerHTML = lista.map(l => `
        <div class="book-card ${l.estado !== 'DISPONIVEL' ? 'requisitado' : ''}"
             onclick="abrirDetalhes('${l.id}')">
            <div class="book-title">${esc(l.titulo)}</div>
            <div class="book-author">${esc(l.autor)}</div>
            <div class="book-meta">
                <span class="badge ${l.estado === 'DISPONIVEL' ? 'badge-success' : 'badge-warning'}">
                    ${l.estado === 'DISPONIVEL' ? '✓ Disponível' : '⏳ Requisitado'}
                </span>
                <span class="badge badge-info">${esc(l.categoria)}</span>
            </div>
        </div>`).join('');
}

// ── Detalhes ──────────────────────────────────────────────────────────

async function abrirDetalhes(id) {
    const d = await api('/api/livros/' + id);
    if (d.erro) { toast(d.erro, 'error'); return; }

    document.getElementById('detalhe-titulo').textContent = d.titulo;

    const filaHtml = d.filaEspera && d.filaEspera.length
        ? `<div class="fila-list">${d.filaEspera.map((n, i) =>
            `<div class="fila-item">${i + 1}. ${esc(n)}</div>`).join('')}</div>`
        : '<span style="color:var(--text-muted)">—</span>';

    document.getElementById('detalhe-conteudo').innerHTML = `
        <div class="detail-row"><span class="detail-label">Autor</span>
            <span class="detail-value">${esc(d.autor)}</span></div>
        <div class="detail-row"><span class="detail-label">Categoria</span>
            <span class="detail-value">${esc(d.categoria)}</span></div>
        <div class="detail-row"><span class="detail-label">Estado</span>
            <span class="detail-value">
                <span class="badge ${d.estado === 'DISPONIVEL' ? 'badge-success' : 'badge-warning'}">
                    ${d.estado === 'DISPONIVEL' ? '✓ Disponível' : '⏳ Requisitado'}
                </span>
            </span></div>
        ${d.estado !== 'DISPONIVEL' ? `
        <div class="detail-row"><span class="detail-label">Com</span>
            <span class="detail-value">${esc(d.estudanteActual || '—')}</span></div>` : ''}
        <div class="detail-row"><span class="detail-label">Fila espera</span>
            <span class="detail-value">${filaHtml}</span></div>`;

    const acoes = document.getElementById('detalhe-acoes');
    acoes.innerHTML = '';

    if (d.estado === 'DISPONIVEL') {
        acoes.appendChild(btn('📖 Requisitar', 'btn-success', () => requisitar(id)));
    } else if (d.estudanteActual === nomeUtilizador) {
        acoes.appendChild(btn('↩ Devolver', 'btn-danger', () => devolver(id)));
    } else {
        acoes.appendChild(btn('⏳ Entrar na fila', 'btn-ghost', () => requisitar(id)));
    }

    abrirModal('modal-detalhes');
}

// ── Acções ────────────────────────────────────────────────────────────

async function requisitar(id) {
    fecharModal('modal-detalhes');
    const r = await api('/api/livros/' + id + '/requisitar', 'POST');
    r.erro ? toast(r.erro, 'error') : toast(r.mensagem, 'success');
    carregarLivros();
}

async function devolver(id) {
    fecharModal('modal-detalhes');
    const r = await api('/api/livros/' + id + '/devolver', 'POST');
    r.erro ? toast(r.erro, 'error') : toast(r.mensagem, 'success');
    carregarLivros();
}

async function adicionarLivro() {
    const titulo    = document.getElementById('add-titulo').value.trim();
    const autor     = document.getElementById('add-autor').value.trim();
    const categoria = document.getElementById('add-categoria').value.trim() || 'Geral';
    if (!titulo || !autor) { toast('Título e autor são obrigatórios', 'error'); return; }

    const r = await api('/api/livros', 'POST', { titulo, autor, categoria });
    fecharModal('modal-adicionar');
    ['add-titulo','add-autor','add-categoria'].forEach(id => document.getElementById(id).value = '');
    r.erro ? toast(r.erro, 'error') : toast('Livro adicionado!', 'success');
    carregarLivros();
}

async function abrirHistorico() {
    abrirModal('modal-historico');
    const r = await api('/api/historico');
    document.getElementById('historico-box').textContent = r.log || '(sem registos)';
}

async function carregarLog() {
    const r = await api('/api/historico');
    document.getElementById('log-box').textContent = r.log || '(sem registos)';
}

// ── Modais ────────────────────────────────────────────────────────────

const abrirModal  = id => document.getElementById(id).classList.add('open');
const fecharModal = id => document.getElementById(id).classList.remove('open');

function abrirModalAdicionar() {
    abrirModal('modal-adicionar');
    document.getElementById('add-titulo').focus();
}

document.querySelectorAll('.modal-overlay').forEach(o =>
    o.addEventListener('click', e => { if (e.target === o) o.classList.remove('open'); }));

// ── Toasts ────────────────────────────────────────────────────────────

function toast(msg, tipo = 'info') {
    const el = Object.assign(document.createElement('div'), { className: `toast ${tipo}`, textContent: msg });
    document.getElementById('toast-container').appendChild(el);
    setTimeout(() => el.remove(), 4000);
}

// ── Utilitários ───────────────────────────────────────────────────────

function esc(s) {
    if (!s) return '';
    return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}

function btn(texto, classe, onclick) {
    const b = document.createElement('button');
    b.className = 'btn ' + classe;
    b.textContent = texto;
    b.onclick = onclick;
    return b;
}

// ── Inicialização ─────────────────────────────────────────────────────

document.getElementById('login-nome').addEventListener('keydown', e => {
    if (e.key === 'Enter') login();
});

if (sessionId && nomeUtilizador) mostrarMain();
