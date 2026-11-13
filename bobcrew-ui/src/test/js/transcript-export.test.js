// Checks the transcript export without a browser:  node bobcrew-ui/src/test/js/transcript-export.test.js
// Needs Node 18 or newer. Maven runs it in the test phase (exec-maven-plugin in bobcrew-ui/pom.xml); -DskipTests skips it.
const fs = require('fs'), vm = require('vm'), zlib = require('zlib'), assert = require('assert');
const ctx = vm.createContext({ Blob, Response, CompressionStream, URL, setTimeout, Map, Date, String });
vm.runInContext(fs.readFileSync(require('path').join(__dirname, '../../main/resources/static/transcript-export.js'), 'utf8'), ctx);
const evil = '<script>alert(1)</script><img src=x onerror=alert(2)>"\'&';
const ev = (seq, type, data, user) => ({ seq, time: '2026-11-13T10:00:00Z', type, user, data });
const events = [
  ev(1, 'user_prompt', { text: evil }, 'eve<b>'),
  ev(2, 'update', { sessionUpdate: 'agent_thought_chunk', content: { type: 'text', text: 'think ' } }),
  ev(3, 'update', { sessionUpdate: 'agent_thought_chunk', content: { type: 'text', text: 'more' } }),
  ev(4, 'update', { sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: 'Hel' } }),
  ev(5, 'update', { sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: 'lo ' + evil } }),
  ev(6, 'update', { sessionUpdate: 'tool_call', toolCallId: 't1', title: 'Run <ls>', status: 'in_progress' }),
  ev(7, 'update', { sessionUpdate: 'tool_call_update', toolCallId: 't1', status: 'completed" onclick="x',
    content: [{ type: 'content', content: { type: 'text', text: 'out ' + evil } }, { type: 'diff', path: '/a', newText: 'n' }] }),
  ev(8, 'update', { sessionUpdate: 'plan', entries: [{ content: evil, status: 'in_progress' }] }),
  ev(9, 'update', { sessionUpdate: 'plan', entries: [{ content: 'a', status: 'completed' }, { content: 'b', status: 'pending' }] }),
  ev(10, 'permission_request', { toolCallId: 't2', toolCall: { title: evil }, options: [{ optionId: 'o1', name: 'Allow' }, { optionId: 'o2', name: 'Reject' }] }),
  ev(11, 'permission_resolved', { toolCallId: 't2', outcome: 'selected', optionId: 'o1' }, 'alice'),
  ev(12, 'turn_end', { stopReason: 'cancelled' }),
  ev(13, 'error', { message: evil }),
  ev(14, 'status', { status: 'IDLE' }),
];
const session = { id: 'abc', title: 'My <b>session</b> / x', agent: 'Bob 2.1.0', workspace: 'demo', owner: 'alice' };
const html = ctx.buildTranscriptHtml(session, events, { exportedAt: new Date('2026-11-13T11:00:00Z'), exportedBy: 'me' });
assert(!/<script|<img|<b>|<\/b>|onclick="/.test(html.replace(/<meta[^>]*>/g, '')), 'unescaped markup');
assert(html.includes('&lt;script&gt;alert(1)'), 'escaped text present');
assert(html.includes('Content-Security-Policy'));
assert.strictEqual((html.match(/class="msg thought"/g) || []).length, 1, 'thought chunks merge');
assert(html.includes('>Hello &lt;script'), 'agent chunks merge');
assert.strictEqual((html.match(/card plan/g) || []).length, 1, 'one plan card');
assert(html.includes('<li class="completed">a</li>') && !html.includes('in_progress">&lt;'), 'plan replaced');
assert(html.includes('alice chose: Allow'));
assert(html.includes('turn ended: cancelled'));
assert(html.includes('status-completedonclickx') , 'status token sanitised');
assert(html.includes('out &lt;script&gt;') && html.includes('--- /a'));
assert(!html.includes('The server no longer holds'));
const cut = ctx.buildTranscriptHtml(session, events.slice(3), { exportedAt: new Date(), exportedBy: '' });
assert(cut.includes('The server no longer holds'));
assert.strictEqual(ctx.transcriptFileName(session, new Date('2026-11-13T11:05:00Z')), 'my-b-session-b-x-20261113-1105.html.gz');
assert.strictEqual(ctx.transcriptFileName({ title: '' }, new Date('2026-11-13T11:05:00Z')), 'session-20261113-1105.html.gz');
// a cleared session: only what comes after the clear is shown, and it is not reported as truncated
const afterClear = ctx.buildTranscriptHtml(session, [ev(20, 'cleared', {}, 'bob<i>'),
  ev(21, 'user_prompt', { text: 'fresh' }, 'alice'), ev(22, 'update', { sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: 'ok' } })],
  { exportedAt: new Date(), exportedBy: '' });
assert(afterClear.includes('session cleared by bob&lt;i&gt;') && afterClear.includes('fresh'));
assert(!afterClear.includes('The server no longer holds'));
const midClear = ctx.buildTranscriptHtml(session, [...events, ev(30, 'cleared', {}, 'x'), ev(31, 'user_prompt', { text: 'later' }, 'x')],
  { exportedAt: new Date(), exportedBy: '' });
assert(!midClear.includes('hello') && !midClear.includes('Plan') && midClear.includes('later'), 'history before a clear is dropped');

ctx.gzipText(html).then(async (blob) => {
  const back = zlib.gunzipSync(Buffer.from(await blob.arrayBuffer())).toString('utf8');
  assert.strictEqual(back, html);
  
  console.log('all ok; html', html.length, 'gz', blob.size);
});
