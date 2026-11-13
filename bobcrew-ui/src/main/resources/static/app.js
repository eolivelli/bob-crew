'use strict';

// Everything the agent or another user wrote is put on the page with textContent only,
// never as HTML.

const $ = (id) => document.getElementById(id);

const state = {
  user: '',             // who this UI is signed in as
  sessions: [],
  current: null,        // id of the selected session
  source: null,         // its EventSource
  events: [],           // every event received for it, for the transcript export
  toolCalls: new Map(), // toolCallId -> card elements
  permissions: new Map(),
  streaming: null,      // {kind, body}: the message that chunks are appended to
  plan: null,           // the plan card of the current turn
};

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

async function refreshSessions() {
  try {
    state.sessions = await api('GET', '/sessions');
  } catch (e) {
    notify(e.message);
    return;
  }
  const list = $('sessions');
  list.replaceChildren();
  for (const session of state.sessions) {
    const item = el('li', session.id === state.current ? 'selected' : '');
    item.append(el('span', 'name', session.title),
      el('span', 'sub', `${session.status.toLowerCase()} · ${session.owner} · ${session.workspace}`));
    item.addEventListener('click', () => select(session.id));
    list.append(item);
  }
  const current = state.sessions.find((s) => s.id === state.current);
  if (current) showHeader(current);
}

async function refreshWorkspaces() {
  try {
    const names = await api('GET', '/workspaces');
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
  $('session-meta').textContent =
    `${session.agent || 'agent'} · workspace ${session.workspace} · owner ${session.owner}`;
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

function select(id) {
  if (state.source) state.source.close();
  state.current = id;
  // keeps the selection across a reload, and makes the session linkable
  history.replaceState(null, '', '#' + id);
  state.events = [];
  state.toolCalls.clear();
  state.permissions.clear();
  state.streaming = null;
  state.plan = null;
  $('transcript').replaceChildren();
  const session = state.sessions.find((s) => s.id === id);
  if (session) showHeader(session);
  refreshSessions();

  // On a reconnect the browser sends Last-Event-ID, so the server resumes where we were.
  const source = new EventSource(`/api/sessions/${encodeURIComponent(id)}/events?after=0`);
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
  const transcript = $('transcript');
  const atBottom = transcript.scrollHeight - transcript.scrollTop - transcript.clientHeight < 80;
  transcript.append(node);
  if (atBottom) transcript.scrollTop = transcript.scrollHeight;
  return node;
}

// An element whose text has its https links made clickable.
function linked(tag, className, text) {
  const node = el(tag, className);
  node.append(...linkify(text));
  return node;
}

// A link can arrive split across chunks, so the message is rebuilt from its whole text; doing it
// at most every 30 ms keeps a long reply, or a replay of many chunks, cheap.
function appendChunk(kind, text) {
  if (!state.streaming || state.streaming.kind !== kind || !state.streaming.body.isConnected) {
    const message = add(el('div', 'msg ' + kind));
    state.streaming = { kind, body: message, text: '', queued: false };
  }
  const streaming = state.streaming;
  streaming.text += text;
  if (streaming.queued) return;
  streaming.queued = true;
  setTimeout(() => {
    streaming.queued = false;
    const transcript = $('transcript');
    const atBottom = transcript.scrollHeight - transcript.scrollTop - transcript.clientHeight < 80;
    streaming.body.replaceChildren(...linkify(streaming.text));
    if (atBottom) transcript.scrollTop = transcript.scrollHeight;
  }, 30);
}

function toolCard(id) {
  let card = state.toolCalls.get(id);
  if (!card) {
    const root = add(el('div', 'card tool'));
    const head = el('div', 'head');
    const status = el('span', 'status');
    const title = el('span', 'title');
    head.append(status, title);
    const output = el('pre');
    output.hidden = true;
    root.append(head, output);
    card = { root, status, title, output };
    state.toolCalls.set(id, card);
    state.streaming = null;
  }
  return card;
}

function updateTool(update) {
  const card = toolCard(update.toolCallId);
  if (update.title) card.title.textContent = update.title;
  if (update.status) {
    card.status.textContent = update.status.replace('_', ' ');
    card.status.className = 'status status-' + update.status;
  }
  const output = toolOutput(update);
  if (output !== null) {
    card.output.replaceChildren(...linkify(output));
    card.output.hidden = false;
  }
}

function renderPlan(update) {
  if (!state.plan || !state.plan.isConnected) {
    state.plan = add(el('div', 'card plan'));
    state.streaming = null;
  }
  const list = el('ol');
  for (const entry of update.entries || []) {
    list.append(linked('li', entry.status || '', entry.content));
  }
  state.plan.replaceChildren(el('div', 'title', 'Plan'), list);
}

function renderPermission(data) {
  state.streaming = null;
  const root = add(el('div', 'card permission'));
  root.append(el('div', 'title', 'Permission needed: ' + ((data.toolCall && data.toolCall.title) || data.toolCallId)));
  const options = el('div', 'options');
  for (const option of data.options || []) {
    const button = el('button', '', option.name);
    button.type = 'button';
    button.addEventListener('click', async () => {
      try {
        await api('POST', `/sessions/${state.current}/permissions/${encodeURIComponent(data.toolCallId)}`,
          { optionId: option.optionId });
      } catch (e) {
        notify(e.message);
      }
    });
    options.append(button);
  }
  const result = el('div', 'result');
  root.append(options, result);
  state.permissions.set(data.toolCallId, { options, result, names: data.options || [] });
}

function resolvePermission(event) {
  const card = state.permissions.get(event.data.toolCallId);
  if (!card) return;
  for (const button of card.options.querySelectorAll('button')) button.disabled = true;
  const chosen = card.names.find((option) => option.optionId === event.data.optionId);
  if (event.data.timedOut) {
    card.result.textContent = 'Nobody answered in time: ' + (chosen ? chosen.name : 'cancelled');
  } else if (event.data.outcome === 'selected') {
    card.result.textContent = `${event.user || 'someone'} chose: ${chosen ? chosen.name : event.data.optionId}`;
  } else {
    card.result.textContent = 'Cancelled';
  }
  state.permissions.delete(event.data.toolCallId);
}

function render(event) {
  const data = event.data || {};
  switch (event.type) {
    case 'user_prompt': {
      state.streaming = null;
      state.plan = null;
      const message = add(el('div', 'msg user'));
      message.append(el('span', 'who', event.user || ''), ...linkify(data.text || ''));
      break;
    }
    case 'update':
      switch (data.sessionUpdate) {
        case 'agent_message_chunk': appendChunk('agent', textOf(data.content)); break;
        case 'agent_thought_chunk': appendChunk('thought', textOf(data.content)); break;
        case 'tool_call':
        case 'tool_call_update': updateTool(data); break;
        case 'plan': renderPlan(data); break;
        case 'current_mode_update': $('session-mode').value = data.currentModeId; break;
        default: break; // updates this UI does not show
      }
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

// ---- actions ------------------------------------------------------------

$('notice').addEventListener('click', () => { $('notice').hidden = true; });

$('new-session').addEventListener('submit', async (submit) => {
  submit.preventDefault();
  const button = submit.target.querySelector('button');
  button.disabled = true;
  try {
    const session = await api('POST', '/sessions',
      { workspace: $('new-workspace').value.trim(), title: $('new-title').value.trim() });
    $('new-title').value = '';
    state.sessions.unshift(session);
    select(session.id);
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
    await api('POST', `/sessions/${state.current}/prompt`, { text });
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
  try { await api('POST', `/sessions/${state.current}/cancel`); } catch (e) { notify(e.message); }
});

$('session-mode').addEventListener('change', async (change) => {
  try {
    await api('POST', `/sessions/${state.current}/mode`, { modeId: change.target.value });
  } catch (e) {
    notify(e.message);
    refreshSessions();
  }
});

$('export').addEventListener('click', async () => {
  const session = state.sessions.find((s) => s.id === state.current);
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
  const session = state.sessions.find((s) => s.id === state.current);
  if (!session) return;
  if (!window.confirm(`Clear this session? Everything in the workspace "${session.workspace}" will be deleted `
      + 'and the conversation will start over. This cannot be undone. The session stays open.')) return;
  try {
    await api('POST', `/sessions/${state.current}/clear`);
    refreshSessions(); // the agent's modes start over
  } catch (e) {
    notify(e.message);
  }
});

$('close').addEventListener('click', async () => {
  if (!state.current || !window.confirm('Close this session and stop its agent?')) return;
  try {
    await api('DELETE', `/sessions/${state.current}`);
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

(async function start() {
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
  if (linked && state.sessions.some((session) => session.id === linked)) select(linked);
  setInterval(refreshSessions, 5000);
})();
