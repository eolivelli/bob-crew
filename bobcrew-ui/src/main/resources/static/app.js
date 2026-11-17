'use strict';

// Everything the agent or another user wrote is put on the page with textContent only,
// never as HTML.

const $ = (id) => document.getElementById(id);

const state = {
  user: '',             // who this UI is signed in as
  servers: [],          // list of configured servers [{name, url}]
  roles: [],            // available roles [{id, name, description, image, secrets, repository, branch, capabilities, memory, cpus}]
  serverLimits: new Map(), // server name -> {capabilities, maxMemoryBytes, maxCpus} from its /me
  metricsTimer: null,   // refreshes the metrics panel while it is open
  uploading: false,     // an upload is in progress
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

// Returns the response; throws with the server's message when it is not a success.
async function call(method, path, body) {
  return checked(await fetch('/api' + path, {
    method,
    headers: { 'X-BobCrew': '1', ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  }));
}

async function checked(response) {
  if (!response.ok) {
    let message = response.status + ' ' + response.statusText;
    try {
      const detail = await response.json();
      if (detail && (detail.error || detail.message)) message = detail.error || detail.message;
    } catch (e) { /* not JSON */ }
    throw new Error(message);
  }
  return response;
}

async function api(method, path, body) {
  const response = await call(method, path, body);
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

async function refreshRoles() {
  try {
    state.roles = (await api('GET', '/roles')) || [];
  } catch (e) {
    state.roles = [];
  }
  const dropdown = $('new-role');
  const prev = dropdown.value;
  const none = el('option', '', 'No role');
  none.value = '';
  dropdown.replaceChildren(none, ...state.roles.map((role) => {
    const option = el('option', '', role.name);
    option.value = role.id;
    option.title = [role.description, role.image ? 'image ' + role.image : '',
      role.secrets && role.secrets.length ? 'secrets ' + role.secrets.join(', ') : '',
      role.repository ? 'clones ' + role.repository + (role.branch ? ' (' + role.branch + ')' : '') : '',
      role.capabilities && role.capabilities.length ? 'capabilities ' + role.capabilities.join(', ') : '',
      role.memory ? 'memory ' + role.memory : '', role.cpus ? role.cpus + ' CPUs' : '']
      .filter(Boolean).join(' · ');
    return option;
  }));
  if (state.roles.some((role) => role.id === prev)) dropdown.value = prev;
  dropdown.hidden = !state.roles.length;
  updateOptions(true);
}

const CAPABILITIES = ['docker', 'libvirt'];

// Asks the selected server what sessions may ask for (once per server).
async function refreshServerLimits() {
  const server = $('new-server').value;
  if (!state.serverLimits.has(server)) {
    try {
      const me = await api('GET', server ? `/servers/${encodeURIComponent(server)}/me` : '/me');
      state.serverLimits.set(server, {
        capabilities: (me && me.capabilities) || [],
        maxMemoryBytes: me ? me.maxMemoryBytes : null,
        maxCpus: me ? me.maxCpus : null,
      });
    } catch (e) {
      state.serverLimits.set(server, { capabilities: [], maxMemoryBytes: null, maxCpus: null });
    }
  }
  updateOptions(false);
}

// A capability box shows when the server allows it or the role asks for it; the role's ones are ticked and
// locked. With roleChanged, the memory and CPU fields take the role's values.
function updateOptions(roleChanged) {
  const limits = state.serverLimits.get($('new-server').value) || { capabilities: [] };
  const role = state.roles.find((r) => r.id === $('new-role').value);
  const fromRole = (role && role.capabilities) || [];
  for (const name of CAPABILITIES) {
    const box = $(`cap-${name}`);
    $(`cap-${name}-label`).hidden = !limits.capabilities.includes(name) && !fromRole.includes(name);
    if (fromRole.includes(name)) {
      box.checked = true;
      box.disabled = true;
    } else {
      if (box.disabled) box.checked = false;
      box.disabled = false;
    }
  }
  if (roleChanged) {
    $('new-memory').value = (role && role.memory) || '';
    $('new-cpus').value = (role && role.cpus) || '';
    if (fromRole.length) $('new-options').open = true;
  }
  const caps = [limits.maxMemoryBytes ? `memory up to ${formatBytes(limits.maxMemoryBytes)}` : '',
    limits.maxCpus ? `up to ${limits.maxCpus} CPUs` : ''].filter(Boolean);
  $('new-limits').textContent = caps.length ? `This server allows ${caps.join(' and ')}.` : '';
}

$('new-server').addEventListener('change', () => refreshServerLimits());
$('new-role').addEventListener('change', () => updateOptions(true));

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
        el('span', 'sub', `${session.status.toLowerCase()} · ${session.owner} · ${session.image}`
          + (session.capabilities && session.capabilities.length ? ` · ${session.capabilities.join(', ')}` : '')));
      item.addEventListener('click', () => select(session.id, session.server));
      list.append(item);
    }
  }
  const current = currentSession();
  if (current) showHeader(current);
}

function showHeader(session) {
  $('session-header').hidden = false;
  $('session-title').textContent = session.title;
  const serverInfo = session.server ? ` · server ${session.server}` : '';
  $('session-meta').textContent =
    `${session.agent || 'agent'}${serverInfo} · image ${session.image}`
    + (session.capabilities && session.capabilities.length ? ` · capabilities ${session.capabilities.join(', ')}` : '')
    + (session.resources ? ` · ${resourcesText(session.resources)}` : '')
    + (session.secrets && session.secrets.length ? ` · secrets ${session.secrets.join(', ')}` : '')
    + (session.repository ? ` · repository ${session.repository.url}`
      + (session.repository.branch ? ` (${session.repository.branch})` : '') : '')
    + `${session.containerId ? ' · container ' + session.containerId : ''} · owner ${session.owner}`
    + (session.sharedWith && session.sharedWith.length ? ` · shared with ${session.sharedWith.join(', ')}` : '');
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
  $('metrics-toggle').disabled = status === 'CLOSED';
  $('upload').disabled = !open || state.uploading;
  $('session-mode').disabled = !open;
}

// ---- one session --------------------------------------------------------

function select(id, serverName) {
  hideRoles();
  hideMetrics();
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
    case 'file_uploaded':
    case 'repository_cloned':
      add(el('div', 'divider', workspaceEventText(event)));
      break;
    case 'shared':
      add(el('div', 'divider', sharedEventText(event)));
      refreshSessions();
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
    case 'available_commands_update':
      // the agent lists its slash commands at session start; the page has no use for them
      break;
    case 'session_info_update':
      // the agent's own title and timestamp for the session (the title is a cut-off reply); not shown
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
        await api('POST', sessionApi('/permissions'), { toolCallId: request.toolCallId, optionId: opt.optionId });
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
    const roleId = $('new-role').value;
    // fetched first, so that a vanished role stops us before a session is created
    const role = roleId ? await api('GET', `/roles/${encodeURIComponent(roleId)}`) : null;
    // the server clones the role's repository before it answers, which can take a while
    button.textContent = role && role.repository ? 'Cloning the repository…' : 'Starting…';
    const createPath = server ? `/servers/${encodeURIComponent(server)}/sessions` : '/sessions';
    // the UI app turns the role into the image and secrets; their values never come here
    const capabilities = CAPABILITIES.filter((name) => $(`cap-${name}`).checked && !$(`cap-${name}-label`).hidden);
    const memory = $('new-memory').value.replace(/\s+/g, '').toUpperCase();
    const cpus = $('new-cpus').value ? Number($('new-cpus').value) : null;
    const resources = { ...(memory ? { memory } : {}), ...(cpus ? { cpus } : {}) };
    const response = await call('POST', createPath, {
      title: $('new-title').value.trim() || (role ? role.name : ''),
      ...(role ? { role: role.id } : {}),
      ...(capabilities.length ? { capabilities } : {}),
      ...(Object.keys(resources).length ? { resources } : {}),
    });
    const session = await response.json();
    if (server) session.server = server;
    const missing = response.headers.get('X-BobCrew-Missing-Secrets');
    if (missing) {
      notify(`These secrets of the role are not in ~/.bobcrew/secrets.json, so the session has none: ${missing}`);
    }
    $('new-title').value = '';
    state.sessions.unshift(session);
    if (role) {
      // the role's prompt is the session's first prompt; a failure leaves the session usable
      try {
        await api('POST', `${createPath}/${encodeURIComponent(session.id)}/prompt`, { text: role.prompt });
      } catch (e) {
        notify(`The session was created, but the role prompt was not sent: ${e.message}`);
      }
    }
    select(session.id, server);
  } catch (e) {
    notify(e.message);
  } finally {
    button.disabled = false;
    button.textContent = 'New session';
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

$('clear').addEventListener('click', () => {
  if (!currentSession()) return;
  const dialog = $('clear-dialog');
  dialog.returnValue = 'cancel';
  dialog.showModal();
});

$('clear-dialog').addEventListener('close', async () => {
  const choice = $('clear-dialog').returnValue;
  if (choice !== 'keep' && choice !== 'wipe') return;
  try {
    await api('POST', sessionApi('/clear'), { wipeWorkspace: choice === 'wipe' });
    refreshSessions(); // the agent's modes start over
  } catch (e) {
    notify(e.message);
  }
});

// ---- file uploads ---------------------------------------------------------

// Uploads one file at a time; each goes to <folder>/<file name> in the workspace, replacing a file there.
async function uploadFiles(files, folder) {
  if (!currentSession() || !files.length || state.uploading) return;
  const base = (folder || '').trim().replace(/\/+$/, '');
  const sessionPath = sessionApi('/files');
  state.uploading = true;
  $('upload').disabled = true;
  const done = [];
  try {
    for (const file of files) {
      $('upload').textContent = `Uploading ${done.length + 1}/${files.length}…`;
      const path = base ? `${base}/${file.name}` : file.name;
      try {
        await checked(await fetch(`/api${sessionPath}?path=${encodeURIComponent(path)}`, {
          method: 'PUT',
          headers: { 'X-BobCrew': '1', 'Content-Type': 'application/octet-stream' },
          body: file,
        }));
        done.push(file.name);
      } catch (e) {
        notify(`${file.name} was not uploaded: ${e.message}`
          + (done.length ? ` (uploaded before it: ${done.join(', ')})` : ''));
        return;
      }
    }
  } finally {
    state.uploading = false;
    $('upload').textContent = 'Upload files';
    const session = currentSession();
    if (session) setStatus(session.status);
  }
}

$('upload').addEventListener('click', () => {
  if (!currentSession()) return;
  $('upload-files').value = '';
  const dialog = $('upload-dialog');
  dialog.returnValue = 'cancel';
  dialog.showModal();
});

$('upload-dialog').addEventListener('close', () => {
  if ($('upload-dialog').returnValue !== 'upload') return;
  uploadFiles([...$('upload-files').files], $('upload-folder').value);
});

// files dropped on the conversation go to the top of the workspace
$('transcript').addEventListener('dragover', (drag) => {
  if (!currentSession() || $('upload').disabled || !drag.dataTransfer.types.includes('Files')) return;
  drag.preventDefault();
  $('transcript').classList.add('dropping');
});
$('transcript').addEventListener('dragleave', () => $('transcript').classList.remove('dropping'));
$('transcript').addEventListener('drop', (drop) => {
  $('transcript').classList.remove('dropping');
  if (!currentSession() || $('upload').disabled) return;
  drop.preventDefault();
  uploadFiles([...drop.dataTransfer.files], '');
});

// ---- container metrics ----------------------------------------------------

function formatBytes(bytes) {
  if (bytes === null || bytes === undefined) return 'n/a';
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB'];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${value.toFixed(unit === 0 ? 0 : 1)} ${units[unit]}`;
}

// "8.0 GiB, 2 CPUs", or "no limits", from a session's resources.
function resourcesText(resources) {
  const parts = [resources.memoryBytes ? formatBytes(resources.memoryBytes) : '',
    resources.cpus ? `${resources.cpus} CPUs` : ''].filter(Boolean);
  return parts.length ? parts.join(', ') : 'no limits';
}

function formatDuration(seconds) {
  if (seconds === null || seconds === undefined) return 'n/a';
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;
  return h ? `${h}h ${m}m` : m ? `${m}m ${s}s` : `${s}s`;
}

function showMetrics(metrics) {
  const memory = metrics.memoryUsageBytes !== null && metrics.memoryUsageBytes !== undefined
    ? `${formatBytes(metrics.memoryUsageBytes)} of ${formatBytes(metrics.memoryLimitBytes)}`
      + (metrics.memoryLimitBytes ? ` (${(100 * metrics.memoryUsageBytes / metrics.memoryLimitBytes).toFixed(1)}%)` : '')
    : 'n/a';
  const rows = [
    ['State', metrics.state || 'n/a'],
    ['Uptime', formatDuration(metrics.uptimeSeconds)],
    ['CPU', metrics.cpuPercent === null || metrics.cpuPercent === undefined ? 'n/a' : `${metrics.cpuPercent}%`],
    ['Memory', memory],
    ['Processes', metrics.pids === null || metrics.pids === undefined ? 'n/a' : String(metrics.pids)],
    ['Network in / out', `${formatBytes(metrics.networkRxBytes)} / ${formatBytes(metrics.networkTxBytes)}`],
    ['Disk read / write', `${formatBytes(metrics.blockReadBytes)} / ${formatBytes(metrics.blockWriteBytes)}`],
    ['Container', `${metrics.containerId || ''} · ${metrics.image || ''}`],
  ];
  $('metrics-list').replaceChildren(...rows.flatMap(([name, value]) => [el('dt', '', name), el('dd', '', value)]));
  $('metrics-updated').textContent = 'updated ' + new Date().toLocaleTimeString();
}

async function loadMetrics() {
  const sessionPath = sessionApi('/metrics');
  try {
    const metrics = await api('GET', sessionPath);
    // the user may have moved on while Docker was sampling
    if (!$('metrics').hidden && sessionApi('/metrics') === sessionPath) showMetrics(metrics);
  } catch (e) {
    $('metrics-updated').textContent = 'unavailable: ' + e.message;
  }
}

// Docker samples for a second or two, so every 5 s is plenty; nothing is fetched while the panel is closed.
function scheduleMetrics() {
  clearTimeout(state.metricsTimer);
  state.metricsTimer = setTimeout(async () => {
    if ($('metrics').hidden) return;
    await loadMetrics();
    if (!$('metrics').hidden) scheduleMetrics();
  }, 5000);
}

function hideMetrics() {
  clearTimeout(state.metricsTimer);
  state.metricsTimer = null;
  $('metrics').hidden = true;
  $('metrics-toggle').classList.remove('active');
}

$('metrics-toggle').addEventListener('click', async () => {
  if (!$('metrics').hidden) {
    hideMetrics();
    return;
  }
  $('metrics').hidden = false;
  $('metrics-toggle').classList.add('active');
  $('metrics-list').replaceChildren();
  $('metrics-updated').textContent = 'sampling…';
  await loadMetrics();
  scheduleMetrics();
});

// Only the owner (or an admin) may change this; the server says so otherwise.
$('share').addEventListener('click', async () => {
  const session = currentSession();
  if (!session) return;
  const answer = window.prompt('Share this session with (user names, comma separated; empty for nobody). '
    + 'They can prompt the agent, answer its permission requests and see everything it has, '
    + 'including the secrets the session was given.', (session.sharedWith || []).join(', '));
  if (answer === null) return;
  const users = answer.split(',').map((name) => name.trim()).filter((name) => name);
  try {
    await api('PUT', sessionApi('/sharing'), { users });
    refreshSessions();
  } catch (e) {
    notify(e.message);
  }
});

$('close').addEventListener('click', async () => {
  if (!state.current || !window.confirm('Close this session and stop its agent?')) return;
  try {
    await api('DELETE', sessionApi());
    if (state.source) state.source.close();
    hideMetrics();
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

// manifests can be added while the page is open; look again when the user starts a new session
$('new-title').addEventListener('focus', () => refreshRoles());

// ---- roles view (read-only) ----------------------------------------------

function hideRoles() {
  document.querySelector('main').classList.remove('showing-roles');
  $('roles-view').hidden = true;
}

async function showRoles() {
  hideMetrics();
  document.querySelector('main').classList.add('showing-roles');
  $('roles-view').hidden = false;
  $('role-name').textContent = '';
  $('role-description').textContent = '';
  $('role-manifest').textContent = '';
  await refreshRoles();
  const list = $('roles-list');
  if (!state.roles.length) {
    list.replaceChildren(el('li', '', 'No roles found.'));
    return;
  }
  list.replaceChildren(...state.roles.map((role) => {
    const item = el('li');
    item.append(el('span', 'name', role.name), el('span', 'sub', role.description || role.id));
    item.addEventListener('click', () => showRole(role.id, item));
    return item;
  }));
  showRole(state.roles[0].id, list.firstElementChild);
}

async function showRole(id, item) {
  for (const other of $('roles-list').children) other.classList.remove('selected');
  item.classList.add('selected');
  try {
    const role = await api('GET', `/roles/${encodeURIComponent(id)}`);
    $('role-name').textContent = `${role.name} (${role.id})`;
    $('role-description').textContent = [role.description,
      `Image: ${role.image || "the server's default"}`,
      `Secrets passed from this computer's secrets.json: ${role.secrets && role.secrets.length ? role.secrets.join(', ') : 'none'}`,
      ...(role.repository ? [`Repository cloned into the workspace: ${role.repository}`
        + (role.branch ? ` (branch ${role.branch})` : ' (default branch)')] : []),
      ...(role.capabilities && role.capabilities.length
        ? [`Capabilities (privileged container): ${role.capabilities.join(', ')}`] : []),
      ...(role.memory || role.cpus ? [`Resources: ${role.memory || 'default'} memory, ${role.cpus || 'default'} CPUs`] : [])]
      .filter(Boolean).join(' · ');
    $('role-manifest').textContent = role.manifest;
  } catch (e) {
    notify(e.message);
  }
}

$('show-roles').addEventListener('click', () => showRoles());
$('hide-roles').addEventListener('click', () => hideRoles());

(async function start() {
  await refreshServers();
  try {
    const me = await api('GET', '/me');
    state.user = me.user;
    $('me').textContent = me.user;
  } catch (e) {
    notify(e.message);
  }
  refreshRoles();
  refreshServerLimits();
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
