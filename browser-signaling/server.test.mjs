// SPDX-License-Identifier: Apache-2.0
import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { setTimeout as delay } from 'node:timers/promises';
import WebSocket from 'ws';

const port = 19_000 + Math.floor(Math.random() * 10_000);
const origin = 'http://localhost:5173';
const server = spawn(process.execPath, ['server.mjs'], {
  cwd: new URL('.', import.meta.url),
  env: { ...process.env, PORT: String(port), NODE_ENV: 'test' },
  stdio: ['ignore', 'pipe', 'pipe'],
});

let serverOutput = '';
server.stdout.on('data', (chunk) => { serverOutput += chunk.toString(); });
server.stderr.on('data', (chunk) => { serverOutput += chunk.toString(); });

async function waitForServer() {
  const deadline = Date.now() + 5_000;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(`http://127.0.0.1:${port}/healthz`);
      if (response.ok) return;
    } catch {
      await delay(50);
    }
  }
  throw new Error(`Signaling server did not start. Output: ${serverOutput}`);
}

function connect() {
  const socket = new WebSocket(`ws://127.0.0.1:${port}/signal`, { origin });
  const messages = [];
  socket.on('message', (raw) => {
    try { messages.push(JSON.parse(raw.toString())); } catch { /* test assertion catches absence */ }
  });
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('WebSocket connection timed out')), 5_000);
    socket.once('open', () => {
      clearTimeout(timeout);
      resolve({ socket, messages });
    });
    socket.once('error', (error) => {
      clearTimeout(timeout);
      reject(error);
    });
  });
}

async function nextMessage(client, type, predicate = () => true) {
  const existing = client.messages.find((message) => message.type === type && predicate(message));
  if (existing) return existing;
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      cleanup();
      reject(new Error(`Timed out waiting for ${type}. Received: ${JSON.stringify(client.messages)}`));
    }, 3_000);
    const listener = (raw) => {
      let message;
      try { message = JSON.parse(raw.toString()); } catch { return; }
      if (message.type === type && predicate(message)) {
        cleanup();
        resolve(message);
      }
    };
    const cleanup = () => {
      clearTimeout(timeout);
      client.socket.off('message', listener);
    };
    client.socket.on('message', listener);
  });
}

function send(client, message) {
  client.socket.send(JSON.stringify(message));
}

async function close(client) {
  if (!client || client.socket.readyState === WebSocket.CLOSED) return;
  client.socket.close();
  await Promise.race([once(client.socket, 'close'), delay(1_000)]);
}

test.before(async () => {
  await waitForServer();
});

test.after(async () => {
  server.kill('SIGTERM');
  await Promise.race([once(server, 'exit'), delay(2_000)]);
  if (!server.killed) server.kill('SIGKILL');
});

test('health endpoint reports empty room and peer counts', async () => {
  const response = await fetch(`http://127.0.0.1:${port}/healthz`);
  assert.equal(response.status, 200);
  assert.equal(response.headers.get('cache-control'), 'no-store');
  assert.deepEqual(await response.json(), {
    status: 'ok',
    service: 'sync-in-it-browser-signaling',
    activeRooms: 0,
    connectedPeers: 0,
  });
});

test('routes valid signaling only between peers in the same room', async (t) => {
  const host = await connect();
  const speaker = await connect();
  const otherHost = await connect();
  const outsider = await connect();
  t.after(async () => Promise.all([close(host), close(speaker), close(otherHost), close(outsider)]));

  send(host, { type: 'create-room' });
  const created = await nextMessage(host, 'room-created');
  send(otherHost, { type: 'create-room' });
  const otherCreated = await nextMessage(otherHost, 'room-created');

  send(speaker, { type: 'join-room', roomCode: created.roomCode });
  const joined = await nextMessage(speaker, 'room-joined');
  assert.equal(joined.hostPeerId, created.peerId);
  await nextMessage(host, 'peer-joined');

  const payload = {
    kind: 'description',
    description: { type: 'offer', sdp: 'v=0\\r\\n' },
  };
  send(host, { type: 'signal', targetPeerId: joined.peerId, payload });
  const forwarded = await nextMessage(speaker, 'signal');
  assert.equal(forwarded.fromPeerId, created.peerId);
  assert.deepEqual(forwarded.payload, payload);

  send(outsider, { type: 'signal', targetPeerId: joined.peerId, payload });
  assert.equal((await nextMessage(outsider, 'error')).code, 'not_in_room');

  send(host, { type: 'signal', targetPeerId: otherCreated.peerId, payload });
  assert.equal((await nextMessage(host, 'error')).code, 'peer_not_found');

  send(host, {
    type: 'signal',
    targetPeerId: joined.peerId,
    payload: { kind: 'description', description: { type: 'answer', sdp: 'x'.repeat(12_001) } },
  });
  assert.equal((await nextMessage(host, 'error')).code, 'invalid_description');
});

test('host disconnect closes its room and releases speakers', async (t) => {
  const host = await connect();
  const speaker = await connect();
  t.after(async () => Promise.all([close(host), close(speaker)]));

  send(host, { type: 'create-room' });
  const created = await nextMessage(host, 'room-created');
  send(speaker, { type: 'join-room', roomCode: created.roomCode });
  await nextMessage(speaker, 'room-joined');

  await close(host);
  const closed = await nextMessage(speaker, 'room-closed');
  assert.equal(closed.reason, 'host-left');

  send(speaker, { type: 'create-room' });
  const recreated = await nextMessage(speaker, 'room-created');
  assert.match(recreated.roomCode, /^[A-Z2-9]{8}$/);
});

test('rejects invalid origins before WebSocket upgrade', async () => {
  const socket = new WebSocket(`ws://127.0.0.1:${port}/signal`, { origin: 'https://attacker.invalid' });
  const [error] = await Promise.race([
    once(socket, 'error'),
    delay(2_000).then(() => { throw new Error('Expected origin rejection'); }),
  ]);
  assert.ok(error);
  assert.equal(socket.readyState === WebSocket.CLOSED || socket.readyState === WebSocket.CLOSING, true);
});
