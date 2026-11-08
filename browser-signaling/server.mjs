// SPDX-License-Identifier: Apache-2.0
import { createServer } from 'node:http';
import { randomInt, randomUUID } from 'node:crypto';
import { WebSocket, WebSocketServer } from 'ws';

const PORT = Number(process.env.PORT ?? 8787);
const HOST = '0.0.0.0';
const MAX_SPEAKERS = 8;
const MAX_MESSAGE_BYTES = 16 * 1024;
const ROOM_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const ALLOWED_ORIGINS = new Set(
  (process.env.ALLOWED_ORIGINS ?? '')
    .split(',')
    .map((value) => value.trim())
    .filter(Boolean),
);
const rooms = new Map();
const clients = new Map();
const joinAttempts = new Map();

function json(response, status, value) {
  response.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store',
  });
  response.end(JSON.stringify(value));
}

const server = createServer((request, response) => {
  const pathname = new URL(request.url ?? '/', 'http://localhost').pathname;
  if (request.method === 'GET' && pathname === '/healthz') {
    return json(response, 200, {
      status: 'ok',
      service: 'sync-in-it-browser-signaling',
      activeRooms: rooms.size,
      connectedPeers: clients.size,
    });
  }
  return json(response, 404, { error: 'not_found' });
});

const webSocketServer = new WebSocketServer({
  noServer: true,
  maxPayload: MAX_MESSAGE_BYTES,
  perMessageDeflate: false,
});

function send(socket, value) {
  if (socket.readyState !== WebSocket.OPEN) return false;
  socket.send(JSON.stringify(value));
  return true;
}

function makeRoomCode() {
  let code = '';
  for (let index = 0; index < 8; index += 1) {
    code += ROOM_ALPHABET[randomInt(ROOM_ALPHABET.length)];
  }
  return code;
}

function originAllowed(origin) {
  if (ALLOWED_ORIGINS.size > 0) return ALLOWED_ORIGINS.has(origin ?? '');
  if (process.env.NODE_ENV === 'production') return false;
  if (!origin) return false;

  try {
    const host = new URL(origin).hostname;
    return host === 'localhost' || host === '127.0.0.1' ||
      /^10\./.test(host) || /^192\.168\./.test(host) ||
      /^172\.(1[6-9]|2\d|3[01])\./.test(host);
  } catch {
    return false;
  }
}

function sendError(socket, code, message) {
  send(socket, { type: 'error', code, message });
}

function takeJoinToken(ip) {
  const now = Date.now();
  const recent = (joinAttempts.get(ip) ?? []).filter((timestamp) => now - timestamp < 60_000);
  if (recent.length >= 20) {
    joinAttempts.set(ip, recent);
    return false;
  }
  recent.push(now);
  joinAttempts.set(ip, recent);
  return true;
}

function getRoomFor(client) {
  if (!client.roomCode) return null;
  return rooms.get(client.roomCode) ?? null;
}

function leaveRoom(socket, notify = true) {
  const client = clients.get(socket);
  if (!client?.roomCode) return;

  const room = rooms.get(client.roomCode);
  const previousCode = client.roomCode;
  client.roomCode = null;
  client.role = null;

  if (!room) return;
  if (room.host === socket) {
    for (const listener of room.listeners) {
      send(listener, { type: 'room-closed', roomCode: previousCode, reason: 'host-left' });
      const listenerState = clients.get(listener);
      if (listenerState) {
        listenerState.roomCode = null;
        listenerState.role = null;
      }
    }
    rooms.delete(previousCode);
    return;
  }

  room.listeners.delete(socket);
  if (notify && room.host.readyState === WebSocket.OPEN) {
    send(room.host, {
      type: 'peer-left',
      peerId: client.peerId,
      roomCode: previousCode,
    });
  }
  if (room.listeners.size === 0 && room.host.readyState !== WebSocket.OPEN) {
    rooms.delete(previousCode);
  }
}

function sendSignal(socket, message) {
  const client = clients.get(socket);
  const room = getRoomFor(client);
  if (!client || !room || !client.role) {
    sendError(socket, 'not_in_room', 'Join a room before sending peer signaling data.');
    return;
  }

  const target = clients.get(
    [...clients.keys()].find((candidate) => clients.get(candidate)?.peerId === message.targetPeerId),
  );
  if (!target || target.roomCode !== client.roomCode) {
    sendError(socket, 'peer_not_found', 'The target peer is no longer in this room.');
    return;
  }

  const targetSocket = [...clients.entries()].find(([, value]) => value === target)?.[0];
  if (!targetSocket) {
    sendError(socket, 'peer_not_found', 'The target peer is no longer connected.');
    return;
  }

  const isAllowedPair = client.role === 'host'
    ? room.listeners.has(targetSocket)
    : room.host === targetSocket;
  if (!isAllowedPair) {
    sendError(socket, 'invalid_route', 'Signaling can only pass between the Host and a Speaker.');
    return;
  }

  const payload = message.payload;
  if (!payload || typeof payload !== 'object') {
    sendError(socket, 'invalid_signal', 'The signaling payload is invalid.');
    return;
  }

  if (payload.kind === 'description') {
    const description = payload.description;
    if (!description || !['offer', 'answer'].includes(description.type) ||
        typeof description.sdp !== 'string' || description.sdp.length > 12_000) {
      sendError(socket, 'invalid_description', 'The SDP description is invalid or too large.');
      return;
    }
  } else if (payload.kind === 'candidate') {
    const candidate = payload.candidate;
    if (candidate !== null &&
        (!candidate || typeof candidate !== 'object' ||
         typeof candidate.candidate !== 'string' || candidate.candidate.length > 3_000)) {
      sendError(socket, 'invalid_candidate', 'The ICE candidate is invalid or too large.');
      return;
    }
  } else {
    sendError(socket, 'invalid_signal', 'Unknown signaling payload type.');
    return;
  }

  send(targetSocket, {
    type: 'signal',
    fromPeerId: client.peerId,
    payload,
  });
}

function handleMessage(socket, message) {
  const client = clients.get(socket);
  if (!client || !message || typeof message !== 'object' || typeof message.type !== 'string') {
    sendError(socket, 'invalid_message', 'The message must be a JSON object with a type.');
    return;
  }

  if (message.type === 'create-room') {
    if (client.roomCode) {
      sendError(socket, 'already_in_room', 'Leave the current room before creating another.');
      return;
    }

    let roomCode = makeRoomCode();
    while (rooms.has(roomCode)) roomCode = makeRoomCode();

    const room = {
      code: roomCode,
      host: socket,
      listeners: new Set(),
      createdAt: Date.now(),
    };
    rooms.set(roomCode, room);
    client.roomCode = roomCode;
    client.role = 'host';
    send(socket, {
      type: 'room-created',
      roomCode,
      peerId: client.peerId,
      maxSpeakers: MAX_SPEAKERS,
    });
    return;
  }

  if (message.type === 'join-room') {
    if (client.roomCode) {
      sendError(socket, 'already_in_room', 'Leave the current room before joining another.');
      return;
    }
    if (!takeJoinToken(client.ip)) {
      sendError(socket, 'rate_limited', 'Too many join attempts. Wait a minute and try again.');
      return;
    }

    const roomCode = typeof message.roomCode === 'string'
      ? message.roomCode.trim().toUpperCase()
      : '';
    const room = rooms.get(roomCode);
    if (!/^[A-Z2-9]{8}$/.test(roomCode) || !room || room.host.readyState !== WebSocket.OPEN) {
      sendError(socket, 'room_not_found', 'That room code is invalid or the Host is offline.');
      return;
    }
    if (room.listeners.size >= MAX_SPEAKERS) {
      sendError(socket, 'room_full', `This preview supports up to ${MAX_SPEAKERS} Speakers.`);
      return;
    }

    room.listeners.add(socket);
    client.roomCode = roomCode;
    client.role = 'speaker';
    send(socket, {
      type: 'room-joined',
      roomCode,
      peerId: client.peerId,
      hostPeerId: clients.get(room.host)?.peerId,
    });
    send(room.host, {
      type: 'peer-joined',
      peerId: client.peerId,
      speakerCount: room.listeners.size,
    });
    return;
  }

  if (message.type === 'signal') {
    sendSignal(socket, message);
    return;
  }

  if (message.type === 'leave-room') {
    leaveRoom(socket);
    send(socket, { type: 'left-room' });
    return;
  }

  sendError(socket, 'unknown_message', 'That signaling message type is not supported.');
}

server.on('upgrade', (request, socket, head) => {
  const pathname = new URL(request.url ?? '/', 'http://localhost').pathname;
  const origin = request.headers.origin;
  if (pathname !== '/signal' || !originAllowed(origin)) {
    socket.write('HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n');
    socket.destroy();
    return;
  }

  webSocketServer.handleUpgrade(request, socket, head, (webSocket) => {
    webSocketServer.emit('connection', webSocket, request);
  });
});

webSocketServer.on('connection', (socket, request) => {
  const client = {
    peerId: randomUUID(),
    roomCode: null,
    role: null,
    ip: request.socket.remoteAddress ?? 'unknown',
    alive: true,
  };
  clients.set(socket, client);

  send(socket, { type: 'connected', peerId: client.peerId });

  socket.on('pong', () => {
    client.alive = true;
  });

  socket.on('message', (raw) => {
    try {
      const message = JSON.parse(raw.toString());
      handleMessage(socket, message);
    } catch {
      sendError(socket, 'invalid_json', 'The signaling message must contain valid JSON.');
    }
  });

  socket.on('close', () => {
    leaveRoom(socket);
    clients.delete(socket);
  });

  socket.on('error', () => {
    leaveRoom(socket);
    clients.delete(socket);
  });
});

const heartbeat = setInterval(() => {
  for (const [socket, client] of clients) {
    if (!client.alive) {
      socket.terminate();
      leaveRoom(socket);
      clients.delete(socket);
      continue;
    }
    client.alive = false;
    socket.ping();
  }
}, 30_000);

heartbeat.unref();

server.listen(PORT, HOST, () => {
  process.stdout.write(`Sync In It browser signaling listening on ${HOST}:${PORT}\n`);
});

function shutdown() {
  clearInterval(heartbeat);
  for (const socket of clients.keys()) socket.close(1001, 'server-shutdown');
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(1), 5_000).unref();
}

process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);
