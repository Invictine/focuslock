import { test } from 'node:test';
import assert from 'node:assert/strict';
import { errorMessage, tokenBelongsTo } from '../src/auth';

function token(subject: string) {
  return `eyJhbGciOiJub25lIn0.${Buffer.from(JSON.stringify({ sub: subject })).toString('base64url')}.c2ln`;
}

test('old-account tokens cannot authenticate a different account queue', () => {
  assert.equal(tokenBelongsTo(token('user_a'), 'user_a'), true);
  assert.equal(tokenBelongsTo(token('user_a'), 'user_b'), false);
  assert.equal(tokenBelongsTo(null, 'user_a'), false);
  assert.equal(tokenBelongsTo('bad token', 'user_a'), false);
});

test('errors redact bearer tokens and JWTs', () => {
  assert.equal(errorMessage(new Error('Bearer secret')), 'Bearer [redacted]');
  assert.equal(errorMessage(new Error(token('user_a'))), '[redacted]');
});
