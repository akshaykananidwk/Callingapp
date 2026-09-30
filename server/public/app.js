/* CallBridge dashboard — vanilla JS single-page app. */
(() => {
  const $ = (sel, el = document) => el.querySelector(sel);
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const view = $('#view');

  // ---------- helpers ----------
  async function api(path, opts = {}) {
    const res = await fetch(path, {
      method: opts.method || 'GET',
      headers: opts.body ? { 'Content-Type': 'application/json' } : {},
      body: opts.body ? JSON.stringify(opts.body) : undefined,
      credentials: 'same-origin',
    });
    if (res.status === 401 && !path.includes('/login')) { showLogin(); throw new Error('Not signed in'); }
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`);
    return data;
  }
  function toast(msg) {
    const t = $('#toast');
    t.textContent = msg;
    t.classList.remove('hidden');
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => t.classList.add('hidden'), 2500);
  }
  async function copy(text) {
    try { await navigator.clipboard.writeText(text); toast('Copied'); }
    catch { prompt('Copy this:', text); }
  }
  const fmtDate = (d) => d ? new Date(d).toLocaleString(undefined, { day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' }) : '—';
  const fmtAgo = (d) => {
    if (!d) return 'never';
    const s = Math.round((Date.now() - new Date(d)) / 1000);
    if (s < 60) return `${s}s ago`;
    if (s < 3600) return `${Math.round(s / 60)} min ago`;
    if (s < 86400) return `${Math.round(s / 3600)} h ago`;
    return fmtDate(d);
  };
  const fmtDur = (sec) => sec == null ? '—' : `${Math.floor(sec / 60)}:${String(sec % 60).padStart(2, '0')}`;
  const fmtOffset = (ms) => fmtDur(Math.floor((ms || 0) / 1000));
  const dirBadge = (d) => d === 'outgoing' ? '<span class="badge out">Outgoing</span>' : '<span class="badge in">Incoming</span>';
  const statusBadge = (s) => s === 'active' ? '<span class="badge live pulse">● Live</span>' : '<span class="badge">Completed</span>';
  const highlight = (text, q) => {
    const safe = esc(text);
    if (!q) return safe;
    const re = new RegExp(q.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'gi');
    return safe.replace(re, (m) => `<mark>${m}</mark>`);
  };

  // ---------- auth ----------
  function showLogin() {
    $('#app').classList.add('hidden');
    $('#login').classList.remove('hidden');
    disconnectLive();
  }
  function showApp() {
    $('#login').classList.add('hidden');
    $('#app').classList.remove('hidden');
    connectLive();
    route();
  }
  $('#login-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const f = new FormData(e.target);
    $('#login-error').textContent = '';
    try {
      await api('/admin/api/login', { method: 'POST', body: { username: f.get('username'), password: f.get('password') } });
      e.target.reset();
      showApp();
    } catch (err) { $('#login-error').textContent = err.message; }
  });
  $('#logout').addEventListener('click', async () => {
    await api('/admin/api/logout', { method: 'POST', body: {} }).catch(() => {});
    showLogin();
  });

  // ---------- live websocket ----------
  let ws = null;
  let wsRetry = null;
  const listeners = new Set();
  function connectLive() {
    if (ws && ws.readyState <= 1) return;
    const proto = location.protocol === 'https:' ? 'wss' : 'ws';
    ws = new WebSocket(`${proto}://${location.host}/live`);
    ws.onopen = () => { $('#ws-dot').className = 'dot on'; };
    ws.onclose = () => {
      $('#ws-dot').className = 'dot off';
      if (!$('#app').classList.contains('hidden')) wsRetry = setTimeout(connectLive, 3000);
    };
    ws.onmessage = (m) => {
      let ev; try { ev = JSON.parse(m.data); } catch { return; }
      listeners.forEach((fn) => fn(ev));
    };
  }
  function disconnectLive() {
    clearTimeout(wsRetry);
    if (ws) { ws.onclose = null; ws.close(); ws = null; }
  }

  // ---------- router ----------
  let cleanup = null;
  function route() {
    if (cleanup) { cleanup(); cleanup = null; }
    const hash = location.hash.replace(/^#\/?/, '') || 'live';
    const [name, id] = hash.split('/');
    document.querySelectorAll('#nav a').forEach((a) => a.classList.toggle('active', a.dataset.route === name));
    const pages = { live: pageLive, calls: id ? () => pageCall(id) : pageCalls, tokens: pageTokens, settings: pageSettings };
    (pages[name] || pageLive)();
  }
  window.addEventListener('hashchange', route);
  function on(fn) { listeners.add(fn); cleanup = () => listeners.delete(fn); }

  // ---------- Live ----------
  async function pageLive() {
    view.innerHTML = '<p class="muted">Loading…</p>';
    let overview, status;
    try {
      [overview, status] = await Promise.all([api('/admin/api/overview'), api('/api/device/status')]);
    } catch (e) { view.innerHTML = `<p class="error">${esc(e.message)}</p>`; return; }
    const s = overview.stats;
    view.innerHTML = `
      <h1>Live</h1>
      <div class="grid cols-4">
        <div class="card stat"><div class="label">Calls today</div><div class="value">${s.calls_today}</div></div>
        <div class="card stat"><div class="label">Total calls</div><div class="value">${s.total_calls}</div></div>
        <div class="card stat"><div class="label">Talk time</div><div class="value">${s.total_seconds < 60 ? s.total_seconds + ' s' : Math.round(s.total_seconds / 60) + ' min'}</div></div>
        <div class="card stat"><div class="label">Speech-to-text</div>
          <div class="value row" style="font-size:16px"><span class="dot ${overview.stt.ok ? 'on' : 'off'}"></span>${esc(overview.stt.provider)}</div>
          <div class="muted" style="font-size:12px">${esc(overview.stt.detail)}</div></div>
      </div>
      <div class="grid cols-2" style="margin-top:16px">
        <div class="card"><h2>Phones</h2><div id="devices"></div></div>
        <div class="card"><h2>Active calls</h2><div id="active" class="stack"></div></div>
      </div>
      <div class="card" style="margin-top:16px"><h2>Recent calls</h2><div id="recent"></div></div>`;

    const devices = new Map(status.devices.map((d) => [d.token_id, d]));
    const renderDevices = () => {
      const el = $('#devices');
      if (!el) return;
      if (!devices.size) {
        el.innerHTML = `<p class="muted">No phone has connected yet. <a href="#/tokens">Generate a token</a> and enter it in the app.</p>`;
        return;
      }
      el.innerHTML = [...devices.values()].map((d) => {
        const online = d.online || (Date.now() - new Date(d.last_seen_at)) < 150000;
        return `<div class="row" style="padding:8px 0;border-bottom:1px solid var(--border)">
          <span class="dot ${online ? 'on' : 'off'}"></span><strong>${esc(d.name)}</strong>
          <span class="spacer"></span>
          <span class="muted">${d.battery != null ? `🔋 ${d.battery}%` : ''} ${esc(d.network || '')} · ${esc(d.service_status || '')} · ${fmtAgo(d.last_seen_at)}</span>
        </div>`;
      }).join('');
    };
    renderDevices();

    const active = new Map();
    const renderActive = () => {
      const el = $('#active');
      if (!el) return;
      if (!active.size) { el.innerHTML = '<p class="muted">No call in progress.</p>'; return; }
      el.innerHTML = [...active.values()].map(({ call, segs }) => `
        <div class="card live-call">
          <div class="row"><strong>${esc(call.phone_number || 'Unknown number')}</strong>${dirBadge(call.direction)}
            <span class="badge live pulse">● Live</span><span class="spacer"></span>
            <a class="btn small" href="#/calls/${call.id}">Open</a></div>
          <div class="transcript live-transcript" data-call="${call.id}">
            ${segs.length ? segs.map(segHtml).join('') : '<p class="muted">Listening…</p>'}
          </div>
        </div>`).join('');
      el.querySelectorAll('.live-transcript').forEach((t) => { t.scrollTop = t.scrollHeight; });
    };
    await Promise.all(status.active_calls.map(async (c) => {
      const t = await api(`/api/calls/${c.id}/transcript`).catch(() => ({ segments: [] }));
      active.set(c.id, { call: c, segs: t.segments });
    }));
    renderActive();

    const recent = await api('/api/calls?limit=8').catch(() => ({ calls: [] }));
    $('#recent').innerHTML = callsTable(recent.calls);

    on((ev) => {
      if (ev.type === 'device') { devices.set(ev.device.token_id, ev.device); renderDevices(); }
      if (ev.type === 'call_started') { active.set(ev.call.id, { call: ev.call, segs: [] }); renderActive(); }
      if (ev.type === 'transcript') {
        const a = active.get(ev.call_id);
        if (a) { a.segs.push({ text: ev.text, offset_ms: ev.ts }); renderActive(); }
      }
      if (ev.type === 'call_ended') {
        active.delete(ev.call.id); renderActive();
        api('/api/calls?limit=8').then((r) => { const el = $('#recent'); if (el) el.innerHTML = callsTable(r.calls); }).catch(() => {});
      }
    });
  }

  const segHtml = (s, q) => `<div class="seg"><time>${fmtOffset(s.offset_ms)}</time><p>${highlight(s.text, q)}</p></div>`;

  function callsTable(calls) {
    if (!calls.length) return '<div class="empty">No calls yet.</div>';
    return `<div class="table-wrap"><table>
      <thead><tr><th>Number</th><th>Direction</th><th>Started</th><th>Duration</th><th class="hide-sm">Transcript</th><th>Status</th></tr></thead>
      <tbody>${calls.map((c) => `
        <tr class="click" onclick="location.hash='#/calls/${c.id}'">
          <td><strong>${esc(c.phone_number || 'Unknown')}</strong></td>
          <td>${dirBadge(c.direction)}</td>
          <td class="nowrap">${fmtDate(c.started_at)}</td>
          <td>${fmtDur(c.duration_sec)}</td>
          <td class="hide-sm"><div class="snippet">${esc(c.text || '')}</div></td>
          <td>${statusBadge(c.status)}</td>
        </tr>`).join('')}</tbody></table></div>`;
  }

  // ---------- Calls ----------
  const callsState = { page: 1, number: '', from: '', to: '', q: '' };
  async function pageCalls() {
    view.innerHTML = `
      <h1>Calls</h1>
      <div class="card">
        <form id="filters" class="row">
          <label style="flex:2;min-width:200px">Search transcripts<input name="q" placeholder="Words from the conversation" value="${esc(callsState.q)}"></label>
          <label style="flex:1;min-width:140px">Number<input name="number" placeholder="98765…" value="${esc(callsState.number)}"></label>
          <label>From<input name="from" type="date" value="${esc(callsState.from)}"></label>
          <label>To<input name="to" type="date" value="${esc(callsState.to)}"></label>
          <div class="row" style="align-self:flex-end">
            <button class="btn primary" type="submit">Apply</button>
            <button class="btn ghost" type="button" id="clear">Clear</button>
            <button class="btn" type="button" id="csv">Export CSV</button>
          </div>
        </form>
      </div>
      <div class="card" style="margin-top:16px" id="results"><p class="muted">Loading…</p></div>`;
    $('#filters').addEventListener('submit', (e) => {
      e.preventDefault();
      const f = new FormData(e.target);
      Object.assign(callsState, { page: 1, q: f.get('q').trim(), number: f.get('number').trim(), from: f.get('from'), to: f.get('to') });
      loadCalls();
    });
    $('#clear').addEventListener('click', () => {
      Object.assign(callsState, { page: 1, number: '', from: '', to: '', q: '' });
      pageCalls();
    });
    $('#csv').addEventListener('click', exportCsv);
    loadCalls();
  }

  function callsQuery(extra = {}) {
    const p = new URLSearchParams();
    if (callsState.number) p.set('number', callsState.number);
    if (callsState.from) p.set('from_date', new Date(callsState.from + 'T00:00').toISOString());
    if (callsState.to) { const d = new Date(callsState.to + 'T00:00'); d.setDate(d.getDate() + 1); p.set('to_date', d.toISOString()); }
    Object.entries(extra).forEach(([k, v]) => p.set(k, v));
    return p.toString();
  }

  async function loadCalls() {
    const el = $('#results');
    try {
      if (callsState.q) {
        const r = await api(`/api/calls/search?q=${encodeURIComponent(callsState.q)}`);
        if (!r.results.length) { el.innerHTML = '<div class="empty">No matches.</div>'; return; }
        const groups = new Map();
        r.results.forEach((x) => {
          if (!groups.has(x.id)) groups.set(x.id, { call: x, segs: [] });
          if (x.text) groups.get(x.id).segs.push(x);
        });
        el.innerHTML = `<p class="muted">${groups.size} calls match “${esc(callsState.q)}”</p>` + [...groups.values()].map(({ call, segs }) => `
          <div style="padding:12px 0;border-top:1px solid var(--border)">
            <div class="row"><a href="#/calls/${call.id}"><strong>${esc(call.phone_number || 'Unknown')}</strong></a>
              ${dirBadge(call.direction)}<span class="muted">${fmtDate(call.started_at)}</span></div>
            <div class="transcript" style="margin-top:8px">${segs.slice(0, 5).map((s) => segHtml(s, callsState.q)).join('')}</div>
          </div>`).join('');
        return;
      }
      const r = await api(`/api/calls?${callsQuery({ page: callsState.page, limit: 25 })}`);
      const pages = Math.max(1, Math.ceil(r.total / r.limit));
      el.innerHTML = callsTable(r.calls) + `
        <div class="pager"><span class="muted">${r.total} calls · page ${r.page} of ${pages}</span>
          <button class="btn small" id="prev" ${r.page <= 1 ? 'disabled' : ''}>Previous</button>
          <button class="btn small" id="next" ${r.page >= pages ? 'disabled' : ''}>Next</button></div>`;
      $('#prev').onclick = () => { callsState.page--; loadCalls(); };
      $('#next').onclick = () => { callsState.page++; loadCalls(); };
    } catch (e) { el.innerHTML = `<p class="error">${esc(e.message)}</p>`; }
  }

  async function exportCsv() {
    const rows = [['id', 'number', 'direction', 'started_at', 'duration_sec', 'status']];
    for (let page = 1; page < 100; page++) {
      const r = await api(`/api/calls?${callsQuery({ page, limit: 200 })}`);
      r.calls.forEach((c) => rows.push([c.id, c.phone_number || '', c.direction, c.started_at, c.duration_sec ?? '', c.status]));
      if (page * r.limit >= r.total) break;
    }
    const csv = rows.map((r) => r.map((v) => `"${String(v).replace(/"/g, '""')}"`).join(',')).join('\n');
    const a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob([csv], { type: 'text/csv' }));
    a.download = 'callbridge-calls.csv';
    a.click();
  }

  // ---------- Call detail ----------
  async function pageCall(id) {
    view.innerHTML = '<p class="muted">Loading…</p>';
    let data;
    try { data = await api(`/api/calls/${id}/transcript`); }
    catch (e) { view.innerHTML = `<p class="error">${esc(e.message)}</p>`; return; }
    const { call } = data;
    const segs = data.segments;
    const render = () => {
      $('#segs').innerHTML = segs.length ? segs.map((s) => segHtml(s)).join('') : `<p class="muted">${call.status === 'active' ? 'Listening…' : 'No transcript for this call.'}</p>`;
    };
    view.innerHTML = `
      <p><a href="#/calls">← All calls</a></p>
      <div class="card">
        <div class="row">
          <h1 style="margin:0">${esc(call.phone_number || 'Unknown number')}</h1>${dirBadge(call.direction)}${statusBadge(call.status)}
          <span class="spacer"></span>
          <button class="btn small" id="copy">Copy text</button>
          <a class="btn small" href="/api/calls/${call.id}/export.txt">Download .txt</a>
          <button class="btn small danger" id="del">Delete</button>
        </div>
        <p class="muted" style="margin:8px 0 0">${fmtDate(call.started_at)} · Duration ${fmtDur(call.duration_sec)} · Language ${esc(call.language || 'auto')}</p>
      </div>
      <div class="card" style="margin-top:16px"><div id="segs" class="transcript"></div></div>`;
    render();
    $('#copy').onclick = () => copy(segs.map((s) => `[${fmtOffset(s.offset_ms)}] ${s.text}`).join('\n'));
    $('#del').onclick = async () => {
      if (!confirm('Delete this call and its transcript permanently?')) return;
      await api(`/api/calls/${call.id}`, { method: 'DELETE' });
      toast('Deleted');
      location.hash = '#/calls';
    };
    on((ev) => {
      if (ev.type === 'transcript' && ev.call_id === call.id) { segs.push({ text: ev.text, offset_ms: ev.ts }); render(); }
      if (ev.type === 'call_ended' && ev.call.id === call.id) pageCall(id);
    });
  }

  // ---------- Tokens ----------
  async function pageTokens() {
    view.innerHTML = `
      <h1>API tokens</h1>
      <div class="card">
        <h2>Generate a token for a phone</h2>
        <form id="new-token" class="row">
          <label style="flex:1;min-width:220px">Name<input name="name" placeholder="e.g. Samsung S25 Ultra" required maxlength="100"></label>
          <button class="btn primary" style="align-self:flex-end" type="submit">Generate token</button>
        </form>
        <div id="new-token-result"></div>
      </div>
      <div class="card" style="margin-top:16px"><h2>Tokens</h2><div id="token-list"><p class="muted">Loading…</p></div></div>`;
    $('#new-token').addEventListener('submit', async (e) => {
      e.preventDefault();
      const name = new FormData(e.target).get('name');
      try {
        const t = await api('/admin/api/tokens', { method: 'POST', body: { name } });
        e.target.reset();
        $('#new-token-result').innerHTML = `
          <div class="callout" style="margin-top:16px">
            <strong>Copy this token now — it will not be shown again.</strong>
            <div class="token-box"><code id="tok">${esc(t.token)}</code><button class="btn primary" id="copy-tok">Copy</button></div>
            <ol class="steps" style="margin-top:12px">
              <li>Open the CallBridge app → <b>Settings</b> (or the first-launch screen).</li>
              <li>VPS URL: <code class="inline">${esc(location.origin)}</code></li>
              <li>API token: paste the token above → tap <b>Test connection</b>.</li>
            </ol>
          </div>`;
        $('#copy-tok').onclick = () => copy(t.token);
        loadTokens();
      } catch (err) { toast(err.message); }
    });
    loadTokens();
  }

  async function loadTokens() {
    const el = $('#token-list');
    const { tokens } = await api('/admin/api/tokens');
    if (!tokens.length) { el.innerHTML = '<div class="empty">No tokens yet.</div>'; return; }
    el.innerHTML = `<div class="table-wrap"><table>
      <thead><tr><th>Name</th><th>Token</th><th class="hide-sm">Created</th><th>Last used</th><th class="hide-sm">Phone</th><th></th></tr></thead>
      <tbody>${tokens.map((t) => `
        <tr>
          <td><strong>${esc(t.name)}</strong></td>
          <td><code class="inline">${esc(t.token_prefix)}…</code></td>
          <td class="hide-sm">${fmtDate(t.created_at)}</td>
          <td>${fmtAgo(t.last_used_at)}</td>
          <td class="hide-sm">${t.battery != null ? `🔋 ${t.battery}% · ${esc(t.network || '')}` : '—'}</td>
          <td>${t.revoked_at ? '<span class="badge">Revoked</span>' : `<button class="btn small danger" data-revoke="${t.id}">Revoke</button>`}</td>
        </tr>`).join('')}</tbody></table></div>`;
    el.querySelectorAll('[data-revoke]').forEach((b) => b.addEventListener('click', async () => {
      if (!confirm('Revoke this token? The phone using it will stop working until you give it a new token.')) return;
      await api(`/admin/api/tokens/${b.dataset.revoke}`, { method: 'DELETE' });
      toast('Token revoked');
      loadTokens();
    }));
  }

  // ---------- Settings ----------
  async function pageSettings() {
    const me = await api('/admin/api/me');
    const overview = await api('/admin/api/overview');
    view.innerHTML = `
      <h1>Settings</h1>
      <div class="grid cols-2">
        <div class="card">
          <h2>Phone setup</h2>
          <ol class="steps">
            <li><a href="/download/CallBridge.apk">Download the CallBridge APK</a> on the phone and install it.</li>
            <li><a href="#/tokens">Generate an API token</a>.</li>
            <li>In the app enter VPS URL <code class="inline">${esc(location.origin)}</code> and the token.</li>
            <li>Grant permissions, disable battery optimization, switch the service ON.</li>
            <li>Make a test call — the transcript appears on the <a href="#/live">Live</a> page.</li>
          </ol>
        </div>
        <div class="card">
          <h2>Server</h2>
          <p class="row"><span class="dot ${overview.stt.ok ? 'on' : 'off'}"></span>Speech-to-text: <b>${esc(overview.stt.provider)}</b></p>
          <p class="muted">${esc(overview.stt.detail)}</p>
          <p class="muted">API: <code class="inline">${esc(location.origin)}/api</code><br>
             Stream: <code class="inline">${esc(location.origin.replace(/^http/, 'ws'))}/stream</code></p>
        </div>
        <div class="card">
          <h2>Change password</h2>
          <p class="muted">Signed in as <b>${esc(me.username)}</b></p>
          <form id="pw" class="stack">
            <label>Current password<input name="current" type="password" autocomplete="current-password" required></label>
            <label>New password (min 8)<input name="next" type="password" autocomplete="new-password" minlength="8" required></label>
            <button class="btn primary" type="submit">Update password</button>
          </form>
        </div>
      </div>`;
    $('#pw').addEventListener('submit', async (e) => {
      e.preventDefault();
      const f = new FormData(e.target);
      try {
        await api('/admin/api/password', { method: 'POST', body: { current: f.get('current'), next: f.get('next') } });
        e.target.reset();
        toast('Password updated');
      } catch (err) { toast(err.message); }
    });
  }

  // ---------- boot ----------
  fetch('/admin/api/me', { credentials: 'same-origin' })
    .then((r) => (r.ok ? showApp() : showLogin()))
    .catch(showLogin);
})();
