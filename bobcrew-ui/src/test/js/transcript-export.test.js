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
  ev(15, 'shared', { users: ['bob', evil] }, 'alice'),
  ev(16, 'shared', { users: [] }, 'alice'),
  ev(17, 'secrets_refreshed', { names: ['GH_TOKEN', evil] }, 'alice'),
];
const session = { id: 'abc', title: 'My <b>session</b> / x', agent: 'Bob 2.1.0', image: 'bobcrew/bob-<base>:latest', owner: 'alice' };
const html = ctx.buildTranscriptHtml(session, events, { exportedAt: new Date('2026-11-13T11:00:00Z'), exportedBy: 'me' });
assert(!/<script|<img|<b>|<\/b>|onclick="/.test(html.replace(/<meta[^>]*>/g, '')), 'unescaped markup');
assert(html.includes('&lt;script&gt;alert(1)'), 'escaped text present');
assert(html.includes('Content-Security-Policy'));
assert(html.includes('image bobcrew/bob-&lt;base&gt;:latest'), 'the image is shown, escaped');
assert.strictEqual((html.match(/class="msg thought"/g) || []).length, 1, 'thought chunks merge');
assert(html.includes('>Hello &lt;script'), 'agent chunks merge');
assert.strictEqual((html.match(/card plan/g) || []).length, 1, 'one plan card');
assert(html.includes('<li class="completed">a</li>') && !html.includes('in_progress">&lt;'), 'plan replaced');
assert(html.includes('alice chose: Allow'));
assert(html.includes('alice shared the session with bob, &lt;script&gt;'), 'sharing shown, escaped');
assert(html.includes('alice stopped sharing the session'));
assert(html.includes('alice refreshed the session&#39;s secrets: GH_TOKEN, &lt;script&gt;')
  || html.includes("alice refreshed the session's secrets: GH_TOKEN, &lt;script&gt;"), 'secrets refresh shown, escaped');
assert(html.includes('turn ended: cancelled'));
assert.strictEqual(ctx.turnEndText('end_turn'), 'turn ended', 'a normal turn end is marked too');
assert.strictEqual(ctx.turnEndText(undefined), 'turn ended');
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

// every box carries when it happened as its tooltip (the time of the event that made it, to the second, UTC)
const tipped = (html.match(/title="2026-11-13 10:00:00 UTC"/g) || []).length;
assert.strictEqual(tipped, 11, 'user, thought, agent, tool, plan, permission, turn end, error, three divider boxes');
for (const cls of ['msg user', 'msg thought', 'msg agent', 'card tool', 'card plan', 'card permission', 'divider', 'msg error']) {
  assert(html.includes(`<div class="${cls}" title="2026-11-13 10:00:00 UTC"`), cls + ' has its time');
}
assert(!html.includes('title="">'), 'no empty tooltip');
const later = ctx.buildTranscriptHtml(session, [{ ...ev(1, 'update', { sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: 'a' } }), time: '2026-11-13T10:05:09Z' },
  { ...ev(2, 'update', { sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: 'b' } }), time: '2026-11-13T10:07:00Z' }],
  { exportedAt: new Date(), exportedBy: '' });
assert(later.includes('title="2026-11-13 10:05:09 UTC">ab<'), 'a message that grows from chunks keeps the time of its first');
assert.strictEqual(ctx.formatTimeFull('nonsense'), '');

// the live page's tooltips
assert.strictEqual(ctx.ageText(0), 'just now');
assert.strictEqual(ctx.ageText(4999), 'just now');
assert.strictEqual(ctx.ageText(-3000), 'just now', 'a clock a little behind the server');
assert.strictEqual(ctx.ageText(5000), '5 s ago');
assert.strictEqual(ctx.ageText(59999), '59 s ago');
assert.strictEqual(ctx.ageText(5 * 60000), '5 min ago');
assert.strictEqual(ctx.ageText((2 * 60 + 5) * 60000), '2 h 5 min ago');
assert.strictEqual(ctx.ageText((3 * 24 + 4) * 3600000), '3 d 4 h ago');
assert(ctx.timeTitle('2026-11-13T10:00:00Z', Date.parse('2026-11-13T10:05:00Z')).endsWith(' · 5 min ago'));
assert.strictEqual(ctx.timeTitle('nonsense', 0), '');

// the up arrow in an empty input box brings back the user's own last prompt
const prompts = [ev(1, 'user_prompt', { text: 'first' }, 'alice'), ev(2, 'user_prompt', { text: 'second' }, 'alice'),
  ev(3, 'user_prompt', { text: "bob's" }, 'bob'), ev(4, 'turn_end', {}), ev(5, 'user_prompt', { text: '' }, 'alice')];
assert.strictEqual(ctx.lastPromptOf(prompts, 'alice'), 'second');
assert.strictEqual(ctx.lastPromptOf(prompts, 'bob'), "bob's");
assert.strictEqual(ctx.lastPromptOf(prompts, 'carol'), null);
assert.strictEqual(ctx.lastPromptOf([], 'alice'), null);
assert.strictEqual(ctx.lastPromptOf([ev(1, 'cleared', {}, 'alice')], 'alice'), null);

// uploads and the clone show as one-line dividers, escaped
const workspace = ctx.buildTranscriptHtml({ ...session, capabilities: ['docker', '<x>'], repository: { url: 'https://github.com/a/<b>', branch: 'main' } }, [
  ev(1, 'repository_cloned', { url: 'https://github.com/a/<b>', branch: 'main', path: '/opt/workspace/b' }),
  ev(2, 'file_uploaded', { path: '/opt/workspace/<x>.txt', size: 2048 }, 'alice')], { exportedAt: new Date(), exportedBy: '' });
assert(workspace.includes('cloned https://github.com/a/&lt;b&gt; (branch main) into /opt/workspace/b'));
assert(workspace.includes('alice uploaded /opt/workspace/&lt;x&gt;.txt (2.0 KiB)'));
assert(workspace.includes('repository https://github.com/a/&lt;b&gt; (main)'));
assert(workspace.includes('capabilities docker, &lt;x&gt;'), 'capabilities are shown, escaped');
assert(!html.includes('capabilities'));

// a resumed session says how it was resumed, and by whom (escaped in the export)
assert.strictEqual(ctx.resumedText({ user: 'alice', data: { from: 'crash' } }),
  'alice restarted the session after its agent stopped; the conversation goes on');
assert.strictEqual(ctx.resumedText({ user: 'bob', data: { from: 'archive' } }),
  'bob resumed the session from its archive, in a new container');
assert.strictEqual(ctx.resumedText({ data: {} }), 'someone resumed the session');
const resumedHtml = ctx.buildTranscriptHtml({ title: 't', id: 'x', owner: 'o', image: 'i' },
  [ev(1, 'resumed', { from: 'archive' }, evil)], { user: 'u', when: new Date('2026-11-13T10:00:00Z') });
assert(resumedHtml.includes('resumed the session from its archive'));
assert(!resumedHtml.includes('<script>alert(1)'), 'the user name is escaped');

// a compaction says who asked for it, with their instructions, then that the agent goes on from the summary
assert.strictEqual(ctx.compactingText({ user: 'alice', data: { instructions: 'keep the tests' } }),
  'alice asked to compact the conversation; the agent writes a summary (instructions: keep the tests)');
assert.strictEqual(ctx.compactingText({ data: {} }), 'someone asked to compact the conversation; the agent writes a summary');
assert.strictEqual(ctx.compactedText({ user: 'bob' }),
  'conversation compacted by bob: the agent goes on from the summary above');
const compactedHtml = ctx.buildTranscriptHtml({ title: 't', id: 'x', owner: 'o', image: 'i' }, [
  ev(1, 'compacting', { instructions: '<b>keep</b>' }, 'alice'),
  ev(2, 'update', { sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: 'Summary: all' } }),
  ev(3, 'turn_end', { stopReason: 'end_turn' }),
  ev(4, 'compacted', { summaryLength: 12 }, 'alice')], { user: 'u', when: new Date('2026-11-13T10:00:00Z') });
assert(compactedHtml.includes('asked to compact the conversation'));
assert(compactedHtml.includes('Summary: all'));
assert(compactedHtml.includes('conversation compacted by alice'));
assert(!compactedHtml.includes('<b>keep</b>'), 'the instructions are escaped');

// what a copy button puts on the clipboard for a box of several parts
assert.strictEqual(ctx.joinParts(['Run tests', null, '', '  \n', 'mvn test\n', undefined, 'BUILD SUCCESS\n\n']),
  'Run tests\n\nmvn test\n\nBUILD SUCCESS');
assert.strictEqual(ctx.joinParts(['  indented stays', 0]), '  indented stays\n\n0');
assert.strictEqual(ctx.joinParts([]), '');

ctx.gzipText(html).then(async (blob) => {
  const back = zlib.gunzipSync(Buffer.from(await blob.arrayBuffer())).toString('utf8');
  assert.strictEqual(back, html);
  
  console.log('all ok; html', html.length, 'gz', blob.size);
});
