import assert from 'node:assert/strict';
import '../src/matcher.js';
const matcher = globalThis.FocusLockMatcher;
assert.equal(matcher.matchesAny('https://news.example.com/article', ['example.com']), true);
assert.equal(matcher.matchesAny('https://notexample.com/', ['example.com']), false);
assert.equal(matcher.matchesAny('https://example.com.evil.test/', ['example.com']), false);
assert.equal(matcher.matchesAny('https://example.com./', ['example.com']), true);
assert.equal(matcher.domainOf('https://www.Example.com./'), 'example.com');
assert.equal(matcher.matchesAny('https://example.com/', [null, {}, 12]), false);
assert.equal(matcher.matchesAny('https://example.com/', {}), false);
const regex = matcher.compilePattern('/Example\\.com/g');
for (let i = 0; i < 4; i++) assert.equal(regex.test('https://Example.com/'), true);
assert.equal(regex.test('https://example.com/'), false, 'Raw regex retains explicit case sensitivity');
assert.equal(matcher.matchesAny('https://example.com/Path', ['example.com/path']), true);

// Shared domain parsing for the Boundaries and Permalock add flows.
assert.equal(matcher.parseDomainInput(' https://www.Example.com/path?q=1 ').value, 'example.com');
assert.equal(matcher.parseDomainInput('*.Example.com').value, '*.example.com');
assert.equal(matcher.parseDomainInput('example.com:8443').value, 'example.com');
assert.ok(matcher.parseDomainInput('chrome://extensions').error, 'Internal addresses are rejected');
assert.ok(matcher.parseDomainInput('not a domain').error, 'Spaces are rejected');
assert.ok(matcher.parseDomainInput('').error, 'Empty input is rejected');
assert.ok(matcher.parseDomainInput('localhost').error, 'A bare host without a dot is rejected');

// Permalock domains match the exact host and every subdomain, but never a suffix trick.
assert.equal(matcher.matchesAny('https://example.com/', ['example.com']), true);
assert.equal(matcher.matchesAny('https://a.b.example.com/x', ['example.com']), true);
assert.equal(matcher.matchesAny('https://example.com.evil.test/', ['example.com']), false);
console.log('extension matcher regression tests passed');
