// Checks the theme choice and how it is stored:  node bobcrew-ui/src/test/js/theme.test.js
// Needs Node 18 or newer. Maven runs it in the test phase (exec-maven-plugin in bobcrew-ui/pom.xml); -DskipTests skips it.
const fs = require('fs'), vm = require('vm'), path = require('path'), assert = require('assert');
const ctx = vm.createContext({});
vm.runInContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/theme.js'), 'utf8'), ctx);

const memory = (initial) => {
  const data = new Map(Object.entries(initial || {}));
  return { getItem: (k) => (data.has(k) ? data.get(k) : null), setItem: (k, v) => data.set(k, String(v)), removeItem: (k) => data.delete(k), data };
};
const root = () => {
  const attributes = new Map();
  return { setAttribute: (k, v) => attributes.set(k, v), removeAttribute: (k) => attributes.delete(k), getAttribute: (k) => attributes.get(k) };
};

// only light and dark are choices of their own; anything else follows the system
assert.strictEqual(ctx.themeChoice('light'), 'light');
assert.strictEqual(ctx.themeChoice('dark'), 'dark');
for (const other of ['system', '', 'Dark', 'blue', null, undefined]) assert.strictEqual(ctx.themeChoice(other), 'system', String(other));

// nothing stored: system; stored values come back; unknown ones are ignored
assert.strictEqual(ctx.readThemeChoice(memory()), 'system');
assert.strictEqual(ctx.readThemeChoice(memory({ 'bobcrew.theme': 'dark' })), 'dark');
assert.strictEqual(ctx.readThemeChoice(memory({ 'bobcrew.theme': 'light' })), 'light');
assert.strictEqual(ctx.readThemeChoice(memory({ 'bobcrew.theme': 'neon' })), 'system');

// saving: light and dark are kept, system removes the value
const store = memory();
ctx.saveThemeChoice('dark', store);
assert.strictEqual(store.data.get('bobcrew.theme'), 'dark');
ctx.saveThemeChoice('light', store);
assert.strictEqual(store.data.get('bobcrew.theme'), 'light');
ctx.saveThemeChoice('system', store);
assert.strictEqual(store.data.has('bobcrew.theme'), false);
ctx.saveThemeChoice('neon', store);
assert.strictEqual(store.data.has('bobcrew.theme'), false);

// storage that refuses (private mode, policy) does not throw, and reads as system
const refusing = { getItem() { throw new Error('denied'); }, setItem() { throw new Error('denied'); }, removeItem() { throw new Error('denied'); } };
assert.strictEqual(ctx.readThemeChoice(refusing), 'system');
assert.doesNotThrow(() => ctx.saveThemeChoice('dark', refusing));
assert.doesNotThrow(() => ctx.saveThemeChoice('system', refusing));

// applying: data-theme for light and dark, none for system
const element = root();
ctx.applyTheme('dark', element);
assert.strictEqual(element.getAttribute('data-theme'), 'dark');
ctx.applyTheme('light', element);
assert.strictEqual(element.getAttribute('data-theme'), 'light');
ctx.applyTheme('system', element);
assert.strictEqual(element.getAttribute('data-theme'), undefined);
ctx.applyTheme('neon', element);
assert.strictEqual(element.getAttribute('data-theme'), undefined);

console.log('theme.test.js: ok');
