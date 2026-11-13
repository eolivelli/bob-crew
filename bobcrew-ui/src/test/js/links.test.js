// Checks which parts of a text become links:  node bobcrew-ui/src/test/js/links.test.js
// Needs Node 18 or newer. Maven runs it in the test phase (exec-maven-plugin in bobcrew-ui/pom.xml); -DskipTests skips it.
const fs = require('fs'), vm = require('vm'), path = require('path'), assert = require('assert');
const ctx = vm.createContext({ URL, Map });
vm.runInContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/links.js'), 'utf8'), ctx);

// the links found in a text, as [text, href] pairs; also checks that no text is lost
function links(text) {
  const segments = Array.from(ctx.linkSegments(text));
  assert.strictEqual(segments.map((s) => s.text).join(''), text, 'segments must add up to the input');
  return segments.filter((s) => s.href).map((s) => [s.text, s.href]);
}

assert.deepStrictEqual(links('see https://example.com/a?b=1&c=2#d now'),
  [['https://example.com/a?b=1&c=2#d', 'https://example.com/a?b=1&c=2#d']]);
assert.deepStrictEqual(links('Open https://example.com.'), [['https://example.com', 'https://example.com/']]);
assert.deepStrictEqual(links('(see https://example.com/x), or https://b.org/y!'),
  [['https://example.com/x', 'https://example.com/x'], ['https://b.org/y', 'https://b.org/y']]);
assert.deepStrictEqual(links('[docs](https://example.com/docs)'), [['https://example.com/docs', 'https://example.com/docs']]);
assert.deepStrictEqual(links('https://en.wikipedia.org/wiki/Foo_(bar)'),
  [['https://en.wikipedia.org/wiki/Foo_(bar)', 'https://en.wikipedia.org/wiki/Foo_(bar)']]);
assert.deepStrictEqual(links('<https://example.com/a>'), [['https://example.com/a', 'https://example.com/a']]);
assert.deepStrictEqual(links('a https://x.org/1\nb https://y.org/2'),
  [['https://x.org/1', 'https://x.org/1'], ['https://y.org/2', 'https://y.org/2']]);

// file:// URLs
assert.deepStrictEqual(links('see file:///workspace/output.txt now'),
  [['file:///workspace/output.txt', 'file:///workspace/output.txt']]);
assert.deepStrictEqual(links('(see file://report.pdf)'),
  [['file://report.pdf', 'file://report.pdf']]);
assert.deepStrictEqual(links('[download](file:///workspace/data.json)'),
  [['file:///workspace/data.json', 'file:///workspace/data.json']]);
assert.deepStrictEqual(links('file://my-doc.docx.'),
  [['file://my-doc.docx', 'file://my-doc.docx']]);

// unsupported schemes and empty file URLs
for (const text of ['http://example.com', 'ftp://example.com', 'javascript:alert(1)', 'www.example.com', 'example.com',
  'data:text/html,<b>x</b>', 'HTTPS://EXAMPLE.COM', 'https://', 'https:// nothing', 'see https:///x', 'file://', 'file:///']) {
  assert.deepStrictEqual(links(text), [], text);
}
// a scheme hidden inside an https URL does not matter: the whole thing is one https link
assert.deepStrictEqual(links('https://example.com/?u=javascript:alert(1)').map((l) => l[0]),
  ['https://example.com/?u=javascript:alert(1)']);
assert.deepStrictEqual(links(''), []);
console.log('links: all ok');
