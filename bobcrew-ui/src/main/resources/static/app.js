'use strict';

// Everything the agent or another user wrote is put on the page with textContent only,
// never as HTML.

const $ = (id) => document.getElementById(id);

const state = {
  user: '',             // who this UI is signed in as
  servers: [],          // list of configured servers [{name, url}]
  sessions: [],
  current: null,        // id of the selected session
  source: null,         // its EventSource
  events: [],           // every event received for it, for the transcript export
  toolCalls: new Map(), // toolCallId -> card elements
  permissions: new Map(),
  streaming: null,      // {kind, body}: the message that chunks are appended to
  plan: null,           // the plan card of the current turn
};

function currentSession() {
  if (!state.current) return null;
  return state.sessions.find((s) => s.id === state.current || (s.server && `${s.server}:${s.id}` === state.current)) || null;
}

function sessionApi(subPath) {
  const session = currentSession();
  if (session && session.server) {
    return `/servers/${encodeURIComponent(session.server)}/sessions/${encodeURIComponent(session.id)}${subPath || ''}`;
  }
  return `/sessions/${encodeURIComponent(state.current)}${subPath || ''}`;
}

async function api(method, path, body) {
  const response = await fetch('/api' + path, {
    method,
    headers: { 'X-BobCrew': '1', ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!response.ok) {
    let message = response.status + ' ' + response.statusText;
    try {
      const detail = await response.json();
      if (detail && (detail.error || detail.message)) message = detail.error || detail.message;
    } catch (e) { /* not JSON */ }
    throw new Error(message);
  }
  return response.status === 204 || response.status === 202 ? null : response.json();
}

function notify(message) {
  const notice = $('notice');
  notice.textContent = message + '  (click to dismiss)';
  notice.hidden = false;
}

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

// ---- session list -------------------------------------------------------

async function refreshServers() {
  try {
    state.servers = await api('GET', '/servers');
  } catch (e) {
    state.servers = [];
  }
  const select = $('new-server');
  const prev = select.value;
  select.replaceChildren(...state.servers.map((s) => {
    const option = el('option', '', s.name);
    option.value = s.name;
    return option;
  }));
  if (prev && state.servers.some((s) => s.name === prev)) {
    select.value = prev;
  } else if (state.servers.length) {
    select.value = state.servers[0].name;
  }
  select.hidden = state.servers.length <= 1;
}

async function refreshSessions() {
  try {
    if (state.servers.length > 1) {
      const lists = await Promise.all(state.servers.map(async (srv) => {
        try {
          const res = await api('GET', `/servers/${encodeURIComponent(srv.name)}/sessions`);
          return (res || []).map((s) => ({ ...s, server: srv.name }));
        } catch (e) {
          return [];
        }
      }));
      state.sessions = lists.flat();
    } else {
      const single = await api('GET', '/sessions');
      const defaultName = state.servers.length === 1 ? state.servers[0].name : '';
      state.sessions = (single || []).map((s) => ({ ...s, server: s.server || defaultName }));
    }
  } catch (e) {
    notify(e.message);
    return;
  }
  const list = $('sessions');
  list.replaceChildren();

  const serverNames = [...new Set(state.sessions.map((s) => s.server || 'default'))];
  const hasMultiple = state.servers.length > 1 || serverNames.length > 1;

  for (const sName of serverNames) {
    if (hasMultiple) {
      list.append(el('li', 'server-group-header', sName));
    }
    const serverSessions = state.sessions.filter((s) => (s.server || 'default') === sName);
    for (const session of serverSessions) {
      const isSelected = session.id === state.current || (session.server && `${session.server}:${session.id}` === state.current);
      const item = el('li', isSelected ? 'selected' : '');
      item.append(el('span', 'name', session.title),
        el('span', 'sub', `${session.status.toLowerCase()} · ${session.owner} · ${session.workspace}`));
      item.addEventListener('click', () => select(session.id, session.server));
      list.append(item);
    }
  }
  const current = currentSession();
  if (current) showHeader(current);
}

async function refreshWorkspaces() {
  const server = $('new-server').value;
  try {
    const path = server ? `/servers/${encodeURIComponent(server)}/workspaces` : '/workspaces';
    const names = await api('GET', path);
    $('workspaces').replaceChildren(...names.map((name) => {
      const option = el('option');
      option.value = name;
      return option;
    }));
  } catch (e) { /* the list is only a convenience */ }
}

function showHeader(session) {
  $('session-header').hidden = false;
  $('session-title').textContent = session.title;
  const serverInfo = session.server ? ` · server ${session.server}` : '';
  $('session-meta').textContent =
    `${session.agent || 'agent'}${serverInfo} · workspace ${session.workspace} · owner ${session.owner}`;
  setStatus(session.status);
  const select = $('session-mode');
  select.replaceChildren(...(session.modes || []).map((mode) => {
    const option = el('option', '', mode.name);
    option.value = mode.id;
    return option;
  }));
  select.hidden = !(session.modes || []).length;
  if (session.currentMode) select.value = session.currentMode;
}

function setStatus(status) {
  const badge = $('session-status');
  badge.textContent = status.toLowerCase();
  badge.className = 'badge ' + status.toLowerCase();
  const open = status === 'IDLE' || status === 'RUNNING';
  $('prompt-form').hidden = !open;
  $('prompt-form').querySelector('button').disabled = status !== 'IDLE';
  $('cancel').disabled = status !== 'RUNNING';
  $('clear').disabled = status !== 'IDLE';
  $('session-mode').disabled = !open;
}

// ---- one session --------------------------------------------------------

function select(id, serverName) {
  if (state.source) state.source.close();
  state.current = id;
  const session = state.sessions.find((s) => s.id === id && (!serverName || s.server === serverName))
      || state.sessions.find((s) => s.id === id);
  const server = serverName || (session ? session.server : null);
  const hashKey = server ? `${server}:${id}` : id;
  history.replaceState(null, '', '#' + hashKey);
  state.events = [];
  state.toolCalls.clear();
  state.permissions.clear();
  state.streaming = null;
  state.plan = null;
  $('transcript').replaceChildren();
  if (session) showHeader(session);
  refreshSessions();

  // On a reconnect the browser sends Last-Event-ID, so the server resumes where we were.
  const eventPath = server ? `/api/servers/${encodeURIComponent(server)}/sessions/${encodeURIComponent(id)}/events?after=0`
      : `/api/sessions/${encodeURIComponent(id)}/events?after=0`;
  const source = new EventSource(eventPath);
  state.source = source;
  source.onmessage = (message) => {
    if (state.source !== source) return;
    const event = JSON.parse(message.data);
    state.events.push(event);
    render(event);
  };
  source.addEventListener('end', () => {
    source.close();
    add(el('div', 'divider', 'session ended'));
  });
}

function add(node) {
  $('transcript').append(node);
  node.scrollIntoView({ block: 'end' });
}

function linked(tag, className, text) {
  const node = el(tag, className);
  node.append(...linkify(text));
  return node;
}

// ---- rendering events into the transcript ------------------------------

function render(event) {
  const data = event.data || {};
  switch (event.type) {
    case 'user_prompt':
      state.streaming = null;
      state.plan = null;
      add(linked('div', 'msg user', data.text || ''));
      break;
    case 'update':
      renderUpdate(data);
      break;
    case 'cleared':
      // everything before it is gone from the server's log too
      state.events = [event];
      state.toolCalls.clear();
      state.permissions.clear();
      state.streaming = null;
      state.plan = null;
      $('transcript').replaceChildren();
      add(el('div', 'divider', 'session cleared' + (event.user ? ' by ' + event.user : '')));
      break;
    case 'permission_request': renderPermission(data); break;
    case 'permission_resolved': resolvePermission(event); break;
    case 'turn_end':
      state.streaming = null;
      if (data.stopReason && data.stopReason !== 'end_turn') add(el('div', 'divider', 'turn ended: ' + data.stopReason));
      break;
    case 'status':
      setStatus(data.status);
      break;
    case 'error':
      state.streaming = null;
      add(linked('div', 'msg error', data.message || 'Error'));
      break;
    default: break;
  }
}

function renderUpdate(data) {
  switch (data.sessionUpdate) {
    case 'agent_thought_chunk':
      renderChunk('thought', (data.content && data.content.text) || '');
      break;
    case 'agent_message_chunk':
      renderChunk('agent', (data.content && data.content.text) || '');
      break;
    case 'tool_call':
      renderToolCall(data);
      break;
    case 'tool_call_update':
      updateToolCall(data);
      break;
    case 'plan':
      renderPlan(data.entries || []);
      break;
    case 'current_mode_update':
      if (data.currentModeId) $('session-mode').value = data.currentModeId;
      break;
    default:
      // unknown kinds: pass through visibly rather than dropping them
      add(el('pre', 'card unknown', JSON.stringify(data, null, 2)));
      break;
  }
}

function renderChunk(kind, text) {
  let streaming = state.streaming;
  if (!streaming || streaming.kind !== kind) {
    const message = el('div', 'msg ' + kind);
    add(message);
    streaming = { kind, body: message, text: '' };
    state.streaming = streaming;
  }
  streaming.text += text;
  streaming.body.replaceChildren(...linkify(streaming.text));
  streaming.body.scrollIntoView({ block: 'end' });
}

function renderToolCall(call) {
  state.streaming = null;
  const card = el('div', 'card tool');
  const head = el('div', 'head');
  head.append(el('span', 'title', call.title || call.kind || 'Tool call'));
  if (call.status) head.append(el('span', 'status status-' + call.status, call.status));
  card.append(head);
  if (call.locations && call.locations.length) {
    card.append(el('pre', 'locations', call.locations.map((l) => l.path || JSON.stringify(l)).join('\n')));
  }
  if (call.rawInput) {
    card.append(el('pre', 'input', typeof call.rawInput === 'string' ? call.rawInput : JSON.stringify(call.rawInput, null, 2)));
  }
  const output = el('pre', 'output');
  output.hidden = true;
  card.append(output);
  add(card);
  state.toolCalls.set(call.toolCallId, { card, head, output });
}

function updateToolCall(call) {
  const card = state.toolCalls.get(call.toolCallId);
  if (!card) return;
  if (call.status) {
    const existing = card.head.querySelector('.status');
    if (existing) existing.remove();
    card.head.append(el('span', 'status status-' + call.status, call.status));
  }
  if (call.output !== undefined && call.output !== null) {
    const output = typeof call.output === 'string' ? call.output : JSON.stringify(call.output, null, 2);
    card.output.replaceChildren(...linkify(output));
    card.output.hidden = false;
  }
}

function renderPlan(entries) {
  state.streaming = null;
  let card = state.plan;
  if (!card) {
    card = el('div', 'card plan');
    card.append(el('div', 'title', 'Plan'), el('ol'));
    add(card);
    state.plan = card;
  }
  const list = card.querySelector('ol');
  list.replaceChildren();
  for (const entry of entries) {
    const item = el('li', entry.status || '', entry.content || '');
    list.append(item);
  }
}

function renderPermission(request) {
  state.streaming = null;
  const card = el('div', 'card permission');
  card.append(el('div', 'title', request.title || 'Permission request'));
  if (request.locations && request.locations.length) {
    card.append(el('pre', 'locations', request.locations.map((l) => l.path || JSON.stringify(l)).join('\n')));
  }
  if (request.rawInput) {
    card.append(el('pre', 'input', typeof request.rawInput === 'string' ? request.rawInput : JSON.stringify(request.rawInput, null, 2)));
  }
  const options = el('div', 'options');
  for (const opt of request.options || []) {
    const option = el('button', opt.kind === 'reject' ? 'danger' : '', opt.name);
    option.type = 'button';
    option.addEventListener('click', async () => {
      try {
        await api('POST', sessionApi(`/permissions/${encodeURIComponent(request.toolCallId)}`), { optionId: opt.optionId });
      } catch (e) {
        notify(e.message);
      }
    });
    options.append(option);
  }
  card.append(options);
  add(card);
  state.permissions.set(request.toolCallId, card);
}

function resolvePermission(event) {
  const data = event.data || {};
  const card = state.permissions.get(data.toolCallId);
  if (!card) return;
  const options = card.querySelector('.options');
  if (options) options.remove();
  const who = event.user ? ' by ' + event.user : '';
  card.append(el('div', 'result', data.optionId ? `Answered (${data.optionId})${who}` : `Cancelled${who}`));
}

// ---- actions ------------------------------------------------------------

$('notice').addEventListener('click', () => { $('notice').hidden = true; });

$('new-session').addEventListener('submit', async (submit) => {
  submit.preventDefault();
  const button = submit.target.querySelector('button');
  button.disabled = true;
  const server = $('new-server').value;
  try {
    const createPath = server ? `/servers/${encodeURIComponent(server)}/sessions` : '/sessions';
    const session = await api('POST', createPath,
      { workspace: $('new-workspace').value.trim(), title: $('new-title').value.trim() });
    if (server) session.server = server;
    $('new-title').value = '';
    state.sessions.unshift(session);
    select(session.id, server);
    refreshWorkspaces();
  } catch (e) {
    notify(e.message);
  } finally {
    button.disabled = false;
  }
});

async function sendPrompt() {
  const text = $('prompt').value;
  if (!text.trim() || !state.current) return;
  try {
    await api('POST', sessionApi('/prompt'), { text });
    $('prompt').value = '';
  } catch (e) {
    notify(e.message);
  }
}

$('prompt-form').addEventListener('submit', (submit) => { submit.preventDefault(); sendPrompt(); });

$('prompt').addEventListener('keydown', (key) => {
  if (key.key === 'Enter' && !key.shiftKey && !key.isComposing) {
    key.preventDefault();
    sendPrompt();
  }
});

$('cancel').addEventListener('click', async () => {
  try { await api('POST', sessionApi('/cancel')); } catch (e) { notify(e.message); }
});

$('session-mode').addEventListener('change', async (change) => {
  try {
    await api('POST', sessionApi('/mode'), { modeId: change.target.value });
  } catch (e) {
    notify(e.message);
    refreshSessions();
  }
});

$('export').addEventListener('click', async () => {
  const session = currentSession();
  if (!session) return;
  if (!state.events.length) {
    notify('Nothing to export yet.');
    return;
  }
  try {
    await exportTranscript(session, state.events, state.user);
  } catch (e) {
    notify('Cannot export the transcript: ' + e.message);
  }
});

$('clear').addEventListener('click', async () => {
  const session = currentSession();
  if (!session) return;
  if (!window.confirm(`Clear this session? Everything in the workspace "${session.workspace}" will be deleted `
      + 'and the conversation will start over. This cannot be undone. The session stays open.')) return;
  try {
    await api('POST', sessionApi('/clear'));
    refreshSessions(); // the agent's modes start over
  } catch (e) {
    notify(e.message);
  }
});

$('close').addEventListener('click', async () => {
  if (!state.current || !window.confirm('Close this session and stop its agent?')) return;
  try {
    await api('DELETE', sessionApi());
    if (state.source) state.source.close();
    state.current = null;
    history.replaceState(null, '', '#');
    $('session-header').hidden = true;
    $('prompt-form').hidden = true;
    $('transcript').replaceChildren(el('p', '', 'Session closed.'));
    refreshSessions();
  } catch (e) {
    notify(e.message);
  }
});

$('new-server').addEventListener('change', () => refreshWorkspaces());

(async function start() {
  await refreshServers();
  try {
    const me = await api('GET', '/me');
    state.user = me.user;
    $('me').textContent = me.user;
  } catch (e) {
    notify(e.message);
  }
  refreshWorkspaces();
  await refreshSessions();
  const linked = location.hash.slice(1);
  if (linked) {
    const [sServer, sId] = linked.includes(':') ? linked.split(':', 2) : [null, linked];
    const match = state.sessions.find((s) => s.id === (sId || linked) && (!sServer || s.server === sServer))
        || state.sessions.find((s) => s.id === linked);
    if (match) select(match.id, match.server);
  }
  setInterval(refreshSessions, 5000);
})();
