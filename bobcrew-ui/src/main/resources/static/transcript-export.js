'use strict';

// Helpers for turning ACP content into text, and the "export transcript" feature: a session's
// events become one standalone HTML page, gzipped, which the browser downloads.
//
// The page is built as a string, so every piece of text from the agent or from other users goes
// through esc() on its way in. The exported file also carries a Content-Security-Policy that
// forbids scripts and any network access, so it stays inert even if something slipped through.

function textOf(content) {
  if (!content) return '';
  if (content.type === 'text') return content.text || '';
  if (content.type === 'resource_link') return `[${content.name || content.uri || 'resource'}]`;
  return `[${content.type || 'content'}]`;
}

// The text of a tool call's output, or null when the update carries none.
function toolOutput(update) {
  if (!update.content || !update.content.length) return null;
  return update.content.map((item) => {
    if (item.type === 'diff') return `--- ${item.path}\n${item.newText || ''}`;
    if (item.type === 'terminal') return `[terminal ${item.terminalId}]`;
    return textOf(item.content);
  }).join('\n');
}

function esc(value) {
  return String(value === undefined || value === null ? '' : value)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

// Class names come from agent data (a tool status, a plan entry status): keep letters, digits,
// underscore and dash only.
function cssToken(value) {
  return String(value === undefined || value === null ? '' : value).replace(/[^A-Za-z0-9_-]/g, '');
}

function formatTime(time) {
  const date = new Date(time);
  return Number.isNaN(date.getTime()) ? '' : date.toISOString().slice(0, 16).replace('T', ' ') + ' UTC';
}

const TRANSCRIPT_CSS = `
:root { --bg:#f6f7f9; --panel:#fff; --border:#d9dde3; --text:#1c2430; --muted:#667085; --user:#e5efff;
  --thought:#f1f1f4; --ok:#1a7f4b; --warn:#b25e09; --bad:#c0352b;
  --mono: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; }
@media (prefers-color-scheme: dark) {
  :root { --bg:#14171c; --panel:#1c2027; --border:#2f3540; --text:#e6e9ee; --muted:#98a2b3; --user:#1f2f4d;
    --thought:#23272f; --ok:#4cc38a; --warn:#f0a44b; --bad:#ff7b72; }
}
* { box-sizing: border-box; }
body { margin: 0; background: var(--bg); color: var(--text); font: 15px/1.5 system-ui, -apple-system, "Segoe UI", sans-serif; }
main { max-width: 900px; margin: 0 auto; padding: 24px 16px 48px; display: flex; flex-direction: column; gap: 10px; }
header { border-bottom: 1px solid var(--border); padding-bottom: 12px; margin-bottom: 8px; }
h1 { font-size: 20px; margin: 0 0 4px; }
.meta { color: var(--muted); font-size: 13px; margin: 0; overflow-wrap: anywhere; }
.msg { max-width: 92%; padding: 9px 13px; border-radius: 10px; white-space: pre-wrap; overflow-wrap: anywhere; }
.msg.user { align-self: flex-end; background: var(--user); }
.msg.agent { align-self: flex-start; background: var(--panel); border: 1px solid var(--border); }
.msg.thought { align-self: flex-start; background: var(--thought); color: var(--muted); font-size: 13px; font-style: italic; }
.msg.error { align-self: flex-start; border: 1px solid var(--bad); color: var(--bad); }
.msg .who { display: block; font-size: 11px; color: var(--muted); font-style: normal; }
.card { align-self: flex-start; max-width: 92%; border: 1px solid var(--border); border-radius: 8px; background: var(--panel); padding: 8px 12px; font-size: 14px; }
.card .head { display: flex; gap: 8px; align-items: center; }
.card .title { font-weight: 600; overflow-wrap: anywhere; }
.card pre { margin: 6px 0 0; padding: 8px; background: var(--bg); border-radius: 6px; font: 12px/1.45 var(--mono); white-space: pre-wrap; overflow-wrap: anywhere; }
.card.permission { border-color: var(--warn); }
.card.permission .result, .card.permission .options { margin-top: 6px; color: var(--muted); font-size: 13px; }
.card.plan ol { margin: 6px 0 0; padding-left: 20px; }
.card.plan li.completed { color: var(--muted); text-decoration: line-through; }
.card.plan li.in_progress { font-weight: 600; }
.status-pending, .status-in_progress { color: var(--warn); }
.status-completed { color: var(--ok); }
.status-failed { color: var(--bad); }
.divider { align-self: center; color: var(--muted); font-size: 12px; }
`;

// Replays the session's events into display items, the way the live page builds its cards: chunks
// of one kind join into one message, a tool call or plan is one card that later updates change.
function transcriptItems(events) {
  const items = [];
  const toolCalls = new Map();
  const permissions = new Map();
  let streaming = null; // the message that the next chunk of the same kind is appended to
  let plan = null;      // the plan card of the current turn

  for (const event of events) {
    const data = event.data || {};
    switch (event.type) {
      case 'cleared':
        // the conversation started over: what came before is not part of the transcript
        items.length = 0;
        toolCalls.clear();
        permissions.clear();
        streaming = null;
        plan = null;
        items.push({ type: 'divider', text: 'session cleared' + (event.user ? ' by ' + event.user : '') });
        break;
      case 'user_prompt':
        streaming = null;
        plan = null;
        items.push({ type: 'user', who: event.user, time: event.time, text: data.text });
        break;
      case 'update':
        switch (data.sessionUpdate) {
          case 'agent_message_chunk':
          case 'agent_thought_chunk': {
            const kind = data.sessionUpdate === 'agent_message_chunk' ? 'agent' : 'thought';
            if (!streaming || streaming.kind !== kind) {
              streaming = { type: 'message', kind, text: '' };
              items.push(streaming);
            }
            streaming.text += textOf(data.content);
            break;
          }
          case 'tool_call':
          case 'tool_call_update': {
            let card = toolCalls.get(data.toolCallId);
            if (!card) {
              card = { type: 'tool', title: '', status: '', output: null };
              toolCalls.set(data.toolCallId, card);
              items.push(card);
              streaming = null;
            }
            if (data.title) card.title = data.title;
            if (data.status) card.status = data.status;
            const output = toolOutput(data);
            if (output !== null) card.output = output;
            break;
          }
          case 'plan':
            if (!plan) {
              plan = { type: 'plan', entries: [] };
              items.push(plan);
              streaming = null;
            }
            plan.entries = data.entries || [];
            break;
          default: break; // updates the page does not show
        }
        break;
      case 'permission_request': {
        streaming = null;
        const card = {
          type: 'permission',
          title: (data.toolCall && data.toolCall.title) || data.toolCallId,
          options: data.options || [],
          result: '',
        };
        permissions.set(data.toolCallId, card);
        items.push(card);
        break;
      }
      case 'permission_resolved': {
        const card = permissions.get(data.toolCallId);
        if (!card) break;
        const chosen = card.options.find((option) => option.optionId === data.optionId);
        if (data.timedOut) card.result = 'Nobody answered in time: ' + (chosen ? chosen.name : 'cancelled');
        else if (data.outcome === 'selected') card.result = `${event.user || 'someone'} chose: ${chosen ? chosen.name : data.optionId}`;
        else card.result = 'Cancelled';
        break;
      }
      case 'turn_end':
        streaming = null;
        if (data.stopReason && data.stopReason !== 'end_turn') {
          items.push({ type: 'divider', text: 'turn ended: ' + data.stopReason });
        }
        break;
      case 'error':
        streaming = null;
        items.push({ type: 'error', text: data.message || 'Error' });
        break;
      default: break;
    }
  }
  return items;
}

function itemHtml(item) {
  switch (item.type) {
    case 'user': {
      const when = formatTime(item.time);
      const who = [item.who, when].filter(Boolean).join(' · ');
      return `<div class="msg user"><span class="who">${esc(who)}</span>${esc(item.text)}</div>`;
    }
    case 'message':
      return `<div class="msg ${item.kind}">${esc(item.text)}</div>`;
    case 'tool': {
      const status = item.status
        ? `<span class="status status-${cssToken(item.status)}">${esc(item.status.replace('_', ' '))}</span>` : '';
      const output = item.output !== null ? `<pre>${esc(item.output)}</pre>` : '';
      return `<div class="card tool"><div class="head">${status}<span class="title">${esc(item.title)}</span></div>${output}</div>`;
    }
    case 'plan': {
      const entries = item.entries
        .map((entry) => `<li class="${cssToken(entry.status)}">${esc(entry.content)}</li>`).join('');
      return `<div class="card plan"><div class="title">Plan</div><ol>${entries}</ol></div>`;
    }
    case 'permission': {
      const options = item.options.map((option) => esc(option.name)).join(' / ');
      return `<div class="card permission"><div class="title">Permission needed: ${esc(item.title)}</div>`
        + `<div class="options">${esc(options)}</div><div class="result">${esc(item.result)}</div></div>`;
    }
    case 'divider':
      return `<div class="divider">${esc(item.text)}</div>`;
    case 'error':
      return `<div class="msg error">${esc(item.text)}</div>`;
    default:
      return '';
  }
}

// session: the API's session object; events: everything received for it; context: who exports, when.
function buildTranscriptHtml(session, events, context) {
  const items = transcriptItems(events);
  const meta = [
    session.agent || 'agent',
    'workspace ' + session.workspace,
    'owner ' + session.owner,
    'session ' + session.id,
  ].join(' · ');
  const exported = `Exported ${formatTime(context.exportedAt)}${context.exportedBy ? ' by ' + context.exportedBy : ''}`
    + ` · ${events.length} events`;
  // The server keeps a limited number of events per session; say so when the start is missing.
  const truncated = events.length && events[0].seq > 1 && events[0].type !== 'cleared'
    ? '<p class="meta">The server no longer holds the first events of this session; the transcript starts later.</p>'
    : '';
  return '<!doctype html>\n<html lang="en"><head><meta charset="utf-8">'
    + '<meta http-equiv="Content-Security-Policy" content="default-src \'none\'; style-src \'unsafe-inline\'">'
    + '<meta name="viewport" content="width=device-width, initial-scale=1">'
    + `<title>${esc(session.title)} · bob-crew transcript</title><style>${TRANSCRIPT_CSS}</style></head><body><main>`
    + `<header><h1>${esc(session.title)}</h1><p class="meta">${esc(meta)}</p>`
    + `<p class="meta">${esc(exported)}</p>${truncated}</header>\n`
    + items.map(itemHtml).join('\n')
    + '\n</main></body></html>\n';
}

function transcriptFileName(session, when) {
  const slug = String(session.title || '').toLowerCase().replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '').slice(0, 60) || 'session';
  const stamp = when.toISOString().slice(0, 16).replace(/[-:]/g, '').replace('T', '-');
  return `${slug}-${stamp}.html.gz`;
}

async function gzipText(text) {
  const stream = new Blob([text], { type: 'text/html' }).stream().pipeThrough(new CompressionStream('gzip'));
  return new Response(stream).blob();
}

async function exportTranscript(session, events, exportedBy) {
  const now = new Date();
  const html = buildTranscriptHtml(session, events, { exportedAt: now, exportedBy });
  const blob = new Blob([await gzipText(html)], { type: 'application/gzip' });
  const link = document.createElement('a');
  link.href = URL.createObjectURL(blob);
  link.download = transcriptFileName(session, now);
  document.body.append(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(link.href), 10000);
}
