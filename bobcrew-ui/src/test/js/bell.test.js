// Checks which events ring the attention bell:  node bobcrew-ui/src/test/js/bell.test.js
// Needs Node 18 or newer. Maven runs it in the test phase (exec-maven-plugin in bobcrew-ui/pom.xml); -DskipTests skips it.
const fs = require('fs'), vm = require('vm'), path = require('path'), assert = require('assert');
const ctx = vm.createContext({});
vm.runInContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/bell.js'), 'utf8'), ctx);

const ev = (type, seq) => ({ type, seq });

// the events that need the user ring, once the bell is on (the tab in front or not: it is not asked)
for (const type of ['turn_end', 'permission_request', 'error']) {
  assert.strictEqual(ctx.shouldRing(ev(type, 11), true, 10), true, type);
}

// everything else is silent
for (const type of ['update', 'status', 'user_prompt', 'permission_resolved', 'cleared', 'file_uploaded', 'shared']) {
  assert.strictEqual(ctx.shouldRing(ev(type, 11), true, 10), false, type);
}

// off: silent
assert.strictEqual(ctx.shouldRing(ev('turn_end', 11), false, 10), false);

// replayed history (seq up to the baseline) is silent, the next event is not
assert.strictEqual(ctx.shouldRing(ev('turn_end', 10), true, 10), false);
assert.strictEqual(ctx.shouldRing(ev('turn_end', 3), true, 10), false);
assert.strictEqual(ctx.shouldRing(ev('turn_end', 11), true, 10), true);
assert.strictEqual(ctx.shouldRing(ev('turn_end', 1), true, 0), true);

// no baseline (it could not be read): silent rather than ringing for history
assert.strictEqual(ctx.shouldRing(ev('turn_end', 11), true, null), false);
assert.strictEqual(ctx.shouldRing(ev('turn_end', 11), true, undefined), false);

// malformed events do not throw
assert.strictEqual(ctx.shouldRing(null, true, 10), false);
assert.strictEqual(ctx.shouldRing({ type: 'turn_end' }, true, 10), false);

// without audio support the functions do nothing
assert.strictEqual(vm.runInContext('window = {}; prepareBell()', ctx), false);
assert.doesNotThrow(() => ctx.playBell());

console.log('bell.test.js: ok');
