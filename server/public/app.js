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

  // ---------- live websocket + global phone state ----------
  let ws = null;
  let wsRetry = null;
  const listeners = new Set();
  const S = {
    phones: new Map(), // token_id -> device
    active: new Map(), // call id -> { call, segs }
    listenCall: null,
    talking: false,
    level: 0,
    logs: [],
  };
  const pendingCmds = new Map();
  let reqSeq = 0;

  function connectLive() {
    if (ws && ws.readyState <= 1) return;
    const proto = location.protocol === 'https:' ? 'wss' : 'ws';
    ws = new WebSocket(`${proto}://${location.host}/live`);
    ws.binaryType = 'arraybuffer';
    ws.onopen = async () => {
      $('#ws-dot').className = 'dot on';
      // Re-subscribe to audio after a reconnect.
      if (S.listenCall) wsSend({ type: 'listen', call_id: S.listenCall });
      if (S.talking && S.listenCall) wsSend({ type: 'talk', call_id: S.listenCall, on: true });
      try {
        const st = await api('/api/device/status');
        S.active.clear();
        for (const c of st.active_calls) {
          const t = await api(`/api/calls/${c.id}/transcript`).catch(() => ({ segments: [] }));
          S.active.set(c.id, { call: c, segs: t.segments });
        }
        emit({ type: 'refresh' });
      } catch {}
    };
    ws.onclose = () => {
      $('#ws-dot').className = 'dot off';
      if (!$('#app').classList.contains('hidden')) wsRetry = setTimeout(connectLive, 3000);
    };
    ws.onmessage = (m) => {
      if (m.data instanceof ArrayBuffer) { audio.play(m.data); return; }
      let ev; try { ev = JSON.parse(m.data); } catch { return; }
      handleGlobal(ev);
      emit(ev);
    };
  }
  function disconnectLive() {
    clearTimeout(wsRetry);
    if (ws) { ws.onclose = null; ws.close(); ws = null; }
    audio.stopMic();
  }
  const emit = (ev) => listeners.forEach((fn) => fn(ev));
  const wsSend = (msg) => { if (ws && ws.readyState === 1) ws.send(typeof msg === 'string' || msg instanceof ArrayBuffer ? msg : JSON.stringify(msg)); };

  /** Send a phone command and wait for the result. */
  function command(type, extra = {}) {
    return new Promise((resolve) => {
      const req = ++reqSeq;
      pendingCmds.set(req, resolve);
      setTimeout(() => { if (pendingCmds.delete(req)) resolve({ ok: false, error: 'No response from server' }); }, 20000);
      wsSend({ type, req, ...extra });
    });
  }

  function handleGlobal(ev) {
    switch (ev.type) {
      case 'cmd_result': {
        const r = pendingCmds.get(ev.req);
        if (r) { pendingCmds.delete(ev.req); r(ev); }
        break;
      }
      case 'phones': {
        S.phones = new Map(ev.devices.map((d) => [d.token_id, d]));
        const ringing = ev.devices.find((d) => d.state?.state === 'ringing');
        if (ringing) showIncoming(ringing.state.number, ringing);
        break;
      }
      case 'phone':
        if (ev.device.online) S.phones.set(ev.device.token_id, ev.device);
        else S.phones.delete(ev.device.token_id);
        if (ev.device.state?.state !== 'ringing') hideIncoming();
        break;
      case 'incoming':
        showIncoming(ev.number, ev.device);
        break;
      case 'call_started':
        S.active.set(ev.call.id, { call: ev.call, segs: [] });
        hideIncoming();
        if (audio.unlocked) setListen(ev.call.id);
        break;
      case 'transcript': {
        const a = S.active.get(ev.call_id);
        if (a) a.segs.push({ text: ev.text, offset_ms: ev.ts });
        break;
      }
      case 'call_ended':
        S.active.delete(ev.call.id);
        if (S.listenCall === ev.call.id) { setListen(null); setTalk(false); }
        break;
      case 'phone_log':
        S.logs.unshift({ at: new Date(), name: ev.name, message: ev.message });
        S.logs.length = Math.min(S.logs.length, 20);
        break;
    }
  }

  function setListen(callId) {
    S.listenCall = callId;
    wsSend({ type: 'listen', call_id: callId });
  }
  async function setTalk(on) {
    if (on) {
      if (!S.listenCall) return toast('No active call');
      try { await audio.startMic((buf) => wsSend(buf)); }
      catch (e) { return toast('Microphone blocked: ' + e.message); }
      S.talking = true;
      wsSend({ type: 'talk', call_id: S.listenCall, on: true });
    } else {
      S.talking = false;
      audio.stopMic();
      wsSend({ type: 'talk', call_id: null, on: false });
    }
    emit({ type: 'refresh' });
  }

  // ---------- audio engine (browser speaker + microphone) ----------
  const audio = {
    ctx: null, gain: null, nextTime: 0, unlocked: false,
    mic: null, micNode: null, micLoudAt: 0,
    async unlock() {
      if (!this.ctx) {
        this.ctx = new (window.AudioContext || window.webkitAudioContext)();
        this.gain = this.ctx.createGain();
        this.gain.connect(this.ctx.destination);
      }
      if (this.ctx.state === 'suspended') await this.ctx.resume();
      this.unlocked = true;
    },
    play(buf) {
      if (!this.ctx || !S.listenCall) return;
      const i16 = new Int16Array(buf);
      const f = new Float32Array(i16.length);
      let sum = 0;
      for (let i = 0; i < i16.length; i++) { f[i] = i16[i] / 32768; sum += f[i] * f[i]; }
      S.level = Math.min(1, Math.sqrt(sum / (f.length || 1)) * 4);
      const ab = this.ctx.createBuffer(1, f.length, 16000);
      ab.copyToChannel(f, 0);
      const src = this.ctx.createBufferSource();
      src.buffer = ab;
      src.connect(this.gain);
      const now = this.ctx.currentTime;
      if (this.nextTime < now + 0.03 || this.nextTime > now + 0.8) this.nextTime = now + 0.12; // jitter buffer
      src.start(this.nextTime);
      this.nextTime += ab.duration;
      // Half-duplex echo gate: while you speak, your own voice returning from the phone is quieted.
      const speaking = S.talking && performance.now() - this.micLoudAt < 500;
      this.gain.gain.setTargetAtTime(speaking ? 0.12 : 1, now, 0.05);
    },
    async startMic(send) {
      await this.unlock();
      if (this.mic) return;
      if (!navigator.mediaDevices?.getUserMedia) throw new Error('needs HTTPS');
      this.mic = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true } });
      await this.ctx.audioWorklet.addModule('/mic-worklet.js');
      const srcNode = this.ctx.createMediaStreamSource(this.mic);
      this.micNode = new AudioWorkletNode(this.ctx, 'mic-processor');
      this.micNode.port.onmessage = (e) => {
        const i16 = new Int16Array(e.data);
        let sum = 0;
        for (let i = 0; i < i16.length; i++) sum += (i16[i] / 32768) ** 2;
        if (Math.sqrt(sum / i16.length) > 0.02) this.micLoudAt = performance.now();
        send(e.data);
      };
      const mute = this.ctx.createGain();
      mute.gain.value = 0;
      srcNode.connect(this.micNode).connect(mute).connect(this.ctx.destination);
    },
    stopMic() {
      if (this.mic) this.mic.getTracks().forEach((t) => t.stop());
      if (this.micNode) this.micNode.disconnect();
      this.mic = null;
      this.micNode = null;
    },
    ring: null,
    startRing() {
      if (!this.ctx || this.ring) return;
      const beep = () => {
        const t = this.ctx.currentTime;
        [0, 0.35].forEach((d) => {
          const o = this.ctx.createOscillator();
          const g = this.ctx.createGain();
          o.frequency.value = 880;
          g.gain.setValueAtTime(0.0001, t + d);
          g.gain.exponentialRampToValueAtTime(0.25, t + d + 0.02);
          g.gain.exponentialRampToValueAtTime(0.0001, t + d + 0.3);
          o.connect(g).connect(this.ctx.destination);
          o.start(t + d); o.stop(t + d + 0.32);
        });
      };
      beep();
      this.ring = setInterval(beep, 2000);
    },
    stopRing() { clearInterval(this.ring); this.ring = null; },
  };

  // ---------- incoming call popup (any page) ----------
  let titleBlink = null;
  function showIncoming(number, device) {
    const el = $('#incoming');
    $('#incoming-number').textContent = number || 'Unknown number';
    $('#incoming-device').textContent = device?.name || '';
    el.classList.remove('hidden');
    audio.startRing();
    const base = 'CallBridge';
    clearInterval(titleBlink);
    titleBlink = setInterval(() => { document.title = document.title === base ? `📞 ${number || 'Incoming call'}` : base; }, 900);
    if ('Notification' in window && Notification.permission === 'granted' && document.hidden) {
      const n = new Notification('Incoming call', { body: number || 'Unknown number', icon: '/favicon.svg', requireInteraction: true });
      n.onclick = () => { window.focus(); n.close(); };
    }
    $('#incoming-answer').onclick = async () => {
      await audio.unlock();
      hideIncoming();
      location.hash = '#/live';
      const r = await command('answer', { token_id: device?.token_id });
      if (!r.ok) toast(r.error || 'Could not answer');
    };
    $('#incoming-reject').onclick = async () => {
      hideIncoming();
      const r = await command('hangup', { token_id: device?.token_id });
      if (!r.ok) toast(r.error || 'Could not reject');
    };
  }
  function hideIncoming() {
    $('#incoming').classList.add('hidden');
    audio.stopRing();
    clearInterval(titleBlink);
    document.title = 'CallBridge';
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
  function on(fn) { listeners.add(fn); const prev = cleanup; cleanup = () => { listeners.delete(fn); prev && prev(); }; }

  // ---------- Phone (live) ----------
  const stateLabel = (d) => {
    const st = d?.state?.state;
    if (st === 'ringing') return '<span class="badge live pulse">Ringing</span>';
    if (st === 'offhook') return '<span class="badge live">On call</span>';
    return '<span class="badge ok">Ready</span>';
  };

  async function pageLive() {
    view.innerHTML = `
      <div class="grid phone-grid">
        <div class="card">
          <div class="row"><h2 style="margin:0">Phone</h2><span class="spacer"></span>
            <button class="btn small" id="enable-audio">🔔 Enable sound & alerts</button></div>
          <div id="phones" style="margin:12px 0"></div>
          <form id="dial-form" class="stack" autocomplete="off">
            <input id="dial-number" class="dial-input" inputmode="tel" placeholder="Enter number" aria-label="Number to call">
            <div class="keypad">${['1','2','3','4','5','6','7','8','9','*','0','+'].map((k) => `<button type="button" class="key" data-key="${k}">${k}</button>`).join('')}</div>
            <div class="row">
              <button type="button" class="btn ghost" id="dial-back">⌫</button>
              <button type="submit" class="btn call-btn" id="dial-call">📞 Call</button>
            </div>
          </form>
        </div>
        <div class="card" id="call-panel"></div>
      </div>
      <div class="grid cols-4" style="margin-top:16px" id="stats"></div>
      <div class="card" style="margin-top:16px"><h2>Recent calls</h2><div id="recent"><p class="muted">Loading…</p></div></div>`;

    const numberEl = $('#dial-number');
    view.querySelectorAll('.key').forEach((k) => k.addEventListener('click', () => { numberEl.value += k.dataset.key; numberEl.focus(); }));
    $('#dial-back').onclick = () => { numberEl.value = numberEl.value.slice(0, -1); };
    $('#dial-form').addEventListener('submit', async (e) => {
      e.preventDefault();
      const number = numberEl.value.trim();
      if (!number) return;
      await audio.unlock();
      $('#dial-call').disabled = true;
      const r = await command('dial', { number, token_id: selectedPhone() });
      $('#dial-call').disabled = false;
      toast(r.ok ? `Calling ${number}…` : (r.error || 'Call failed'));
    });
    $('#enable-audio').onclick = async () => {
      await audio.unlock();
      if ('Notification' in window && Notification.permission === 'default') await Notification.requestPermission();
      if (!S.listenCall && S.active.size) setListen([...S.active.keys()][0]);
      toast('Sound and alerts enabled');
      render();
    };

    let selected = null;
    const selectedPhone = () => selected || [...S.phones.keys()][0] || null;

    function renderPhones() {
      const el = $('#phones');
      if (!el) return;
      if (!S.phones.size) {
        el.innerHTML = `<p class="muted"><span class="dot off"></span> No phone connected. Open the CallBridge app (v1.2+) with the service ON. <a href="#/tokens">Tokens</a></p>`;
        return;
      }
      const cur = selectedPhone();
      el.innerHTML = [...S.phones.values()].map((d) => `
        <label class="phone-row">
          <input type="radio" name="phone" value="${d.token_id}" ${d.token_id === cur ? 'checked' : ''} ${S.phones.size === 1 ? 'hidden' : ''}>
          <span class="dot on"></span><strong>${esc(d.name)}</strong> ${stateLabel(d)}
          <span class="spacer"></span>
          <span class="muted small">${d.info?.battery != null ? `🔋 ${d.info.battery}%` : ''} ${esc(d.info?.network || '')} ${d.info?.app_version ? 'v' + esc(d.info.app_version) : ''}</span>
        </label>
        ${d.info?.permissions && d.info.permissions.length ? `<p class="error small">Missing on phone: ${d.info.permissions.map(esc).join(', ')}</p>` : ''}`).join('');
      el.querySelectorAll('input[name=phone]').forEach((r) => r.onchange = () => { selected = r.value; render(); });
    }

    function renderCall() {
      const el = $('#call-panel');
      if (!el) return;
      const dev = S.phones.get(selectedPhone());
      const st = dev?.state?.state || 'idle';
      const act = [...S.active.values()].sort((a, b) => new Date(b.call.started_at) - new Date(a.call.started_at))[0];
      if (st === 'idle' && !act) {
        el.innerHTML = `<h2>Current call</h2><div class="empty">No call in progress.<br><span class="small">Incoming calls pop up here automatically.</span></div>
          ${S.logs.length ? `<h2 style="margin-top:12px">Phone log</h2><div class="small muted">${S.logs.slice(0, 6).map((l) => `<div>${l.at.toLocaleTimeString()} · ${esc(l.message)}</div>`).join('')}</div>` : ''}`;
        return;
      }
      const number = act?.call.phone_number || dev?.state?.number || 'Unknown number';
      const direction = act?.call.direction || dev?.state?.direction;
      const started = act ? new Date(act.call.started_at) : null;
      const listening = act && S.listenCall === act.call.id;
      el.innerHTML = `
        <div class="row"><h2 style="margin:0">${esc(number)}</h2>${direction ? dirBadge(direction) : ''}${stateLabel(dev)}
          <span class="spacer"></span><span class="timer" id="call-timer">${started ? fmtDur(Math.floor((Date.now() - started) / 1000)) : ''}</span></div>
        <div class="call-actions">
          ${st === 'ringing' ? '<button class="btn call-btn" id="c-answer">📞 Answer</button>' : ''}
          <button class="btn hang-btn" id="c-hangup">${st === 'ringing' ? 'Reject' : 'Hang up'}</button>
          ${act ? `<button class="btn ${listening ? 'primary' : ''}" id="c-listen">${listening ? '🔊 Listening' : '🔈 Listen'}</button>
                   <button class="btn ${S.talking ? 'primary' : ''}" id="c-talk">${S.talking ? '🎙 Mic ON' : '🎙 Talk'}</button>` : ''}
          <button class="btn" id="c-speaker">Speaker</button>
        </div>
        ${act ? `<div class="meter"><div id="level" style="width:0%"></div></div>` : ''}
        <div class="transcript live-transcript" id="live-segs">
          ${act ? (act.segs.length ? act.segs.map((x) => segHtml(x)).join('') : '<p class="muted">Listening for speech…</p>') : ''}
        </div>`;
      const t = $('#live-segs'); if (t) t.scrollTop = t.scrollHeight;
      const tok = selectedPhone();
      const run = async (type, extra = {}) => { const r = await command(type, { token_id: tok, ...extra }); if (!r.ok) toast(r.error || `${type} failed`); };
      $('#c-answer') && ($('#c-answer').onclick = async () => { await audio.unlock(); run('answer'); });
      $('#c-hangup').onclick = () => run('hangup');
      let speakerOn = true;
      $('#c-speaker').onclick = () => { run('speaker', { on: speakerOn }); speakerOn = !speakerOn; };
      if ($('#c-listen')) $('#c-listen').onclick = async () => { await audio.unlock(); setListen(listening ? null : act.call.id); if (listening) setTalk(false); renderCall(); };
      if ($('#c-talk')) $('#c-talk').onclick = async () => { if (!S.listenCall) setListen(act.call.id); await setTalk(!S.talking); };
    }

    async function renderStats() {
      try {
        const o = await api('/admin/api/overview');
        const s = o.stats;
        const el = $('#stats'); if (!el) return;
        el.innerHTML = `
          <div class="card stat"><div class="label">Calls today</div><div class="value">${s.calls_today}</div></div>
          <div class="card stat"><div class="label">Total calls</div><div class="value">${s.total_calls}</div></div>
          <div class="card stat"><div class="label">Talk time</div><div class="value">${s.total_seconds < 60 ? s.total_seconds + ' s' : Math.round(s.total_seconds / 60) + ' min'}</div></div>
          <div class="card stat"><div class="label">Speech-to-text</div>
            <div class="value row" style="font-size:16px"><span class="dot ${o.stt.ok ? 'on' : 'off'}"></span>${esc(o.stt.provider)}</div></div>`;
      } catch {}
      const recent = await api('/api/calls?limit=8').catch(() => ({ calls: [] }));
      const r = $('#recent'); if (r) r.innerHTML = callsTable(recent.calls);
    }

    function render() { renderPhones(); renderCall(); }
    render();
    renderStats();
    const tick = setInterval(() => {
      const act = [...S.active.values()][0];
      const tEl = $('#call-timer');
      if (tEl && act) tEl.textContent = fmtDur(Math.floor((Date.now() - new Date(act.call.started_at)) / 1000));
      const lv = $('#level'); if (lv) { lv.style.width = `${Math.round(S.level * 100)}%`; S.level *= 0.85; }
    }, 200);
    on((ev) => {
      if (['phone', 'phones', 'call_started', 'transcript', 'refresh', 'phone_log', 'incoming'].includes(ev.type)) render();
      if (ev.type === 'call_ended') { render(); renderStats(); }
    });
    const prevCleanup = cleanup;
    cleanup = () => { clearInterval(tick); prevCleanup && prevCleanup(); };
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
        ${call.recording_path ? `<div class="row" style="margin-top:12px"><audio controls preload="metadata" src="/api/calls/${call.id}/recording" style="flex:1;min-width:240px"></audio>
          <a class="btn small" href="/api/calls/${call.id}/recording?download=1">Download recording</a></div>` : ''}
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
