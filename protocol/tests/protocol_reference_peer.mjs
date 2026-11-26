import crypto from 'node:crypto';
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import tls from 'node:tls';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import readline from 'node:readline';
import { fileURLToPath } from 'node:url';

export const CHUNK_MAGIC = 0x4C534431; // "LSD1"
export const EOF_MAGIC = 0x4C53454F;   // "LSEO"
export const MAX_CONTROL_FRAME_BYTES = 2 * 1024 * 1024;
export const MAX_CHUNK_BYTES = 512 * 1024;
export const SUPPORTED_VERSIONS = ['v1', 'v2'];

const WINDOWS_RESERVED_BASENAMES = new Set([
  'CON', 'PRN', 'AUX', 'NUL',
  'COM1', 'COM2', 'COM3', 'COM4', 'COM5', 'COM6', 'COM7', 'COM8', 'COM9',
  'LPT1', 'LPT2', 'LPT3', 'LPT4', 'LPT5', 'LPT6', 'LPT7', 'LPT8', 'LPT9'
]);

export function sanitizeSegmentPortable(rawSegment) {
  let seg = String(rawSegment)
    .replace(/[\u0000-\u001F\u007F]/g, '')
    .replace(/\.\./g, '')
    .replace(/[<>:"/|?*]/g, '_')
    .trim()
    .replace(/[. ]+$/, '');
  if (!seg || seg === '.' || seg === '..') return '';
  const baseName = seg.split('.')[0].toUpperCase();
  if (WINDOWS_RESERVED_BASENAMES.has(baseName)) {
    seg = `_${seg}`;
  }
  return seg;
}

export function sanitizeFileName(rawName) {
  const base = String(rawName).replace(/\\/g, '/').split('/').pop() ?? '';
  const clean = sanitizeSegmentPortable(base);
  return clean || `file_${Date.now()}`;
}

export function sanitizeRelativePath(rawRel) {
  if (!rawRel || String(rawRel).includes('\u0000')) return null;
  const normalized = String(rawRel).replace(/\\/g, '/').trim().replace(/^\/+/, '');
  if (!normalized) return null;
  const rawSegments = normalized.split('/').map((s) => s.trim()).filter(Boolean);
  if (rawSegments.length === 0) return null;
  const out = [];
  for (let i = 0; i < rawSegments.length; i++) {
    const seg = rawSegments[i];
    const isDrivePrefix = i === 0 && /^[A-Za-z]:/.test(seg);
    if (seg === '.' || seg === '..' || isDrivePrefix) {
      return null;
    }
    const clean = sanitizeSegmentPortable(seg);
    if (!clean) return null;
    out.push(clean);
  }
  return out.join('/');
}

export function negotiateVersion(peerVersion) {
  if (typeof peerVersion !== 'string') return null;
  const trimmed = peerVersion.trim();
  return SUPPORTED_VERSIONS.includes(trimmed) ? trimmed : null;
}

export function encodeControlFrame(messageObj) {
  const json = JSON.stringify(messageObj);
  const body = Buffer.from(json, 'utf8');
  if (body.length === 0 || body.length > MAX_CONTROL_FRAME_BYTES) {
    throw new Error(`Invalid outgoing control frame size: ${body.length}`);
  }
  const header = Buffer.allocUnsafe(4);
  header.writeInt32BE(body.length, 0);
  return Buffer.concat([header, body]);
}

export function encodeChunkFrame(fileIndex, offset, payloadBuf) {
  if (payloadBuf.length > MAX_CHUNK_BYTES) {
    throw new Error(`Chunk length ${payloadBuf.length} exceeds ${MAX_CHUNK_BYTES}`);
  }
  const header = Buffer.allocUnsafe(20);
  header.writeUInt32BE(CHUNK_MAGIC, 0);
  header.writeUInt32BE(fileIndex >>> 0, 4);
  header.writeBigUInt64BE(BigInt(offset), 8);
  header.writeUInt32BE(payloadBuf.length >>> 0, 16);
  return Buffer.concat([header, payloadBuf]);
}

export function encodeEofFrame(fileIndex, sha256DigestBuf) {
  if (sha256DigestBuf.length !== 32) {
    throw new Error(`SHA-256 digest must be 32 bytes, got ${sha256DigestBuf.length}`);
  }
  const frame = Buffer.allocUnsafe(40);
  frame.writeUInt32BE(EOF_MAGIC, 0);
  frame.writeUInt32BE(fileIndex >>> 0, 4);
  sha256DigestBuf.copy(frame, 8);
  return frame;
}

/**
 * Buffered socket reader supporting both JSON control frames and binary stream frames
 * without losing peeked bytes between sequential handshakes on the same socket.
 */
export class SocketFrameReader {
  constructor(socket) {
    this.socket = socket;
    this.buf = Buffer.alloc(0);
    this.ended = false;
    this.error = null;
    this.waiters = [];

    socket.on('data', (chunk) => {
      this.buf = this.buf.length === 0 ? chunk : Buffer.concat([this.buf, chunk]);
      this.#flush();
    });
    socket.on('end', () => {
      this.ended = true;
      this.#flush();
    });
    socket.on('close', () => {
      this.ended = true;
      this.#flush();
    });
    socket.on('error', (err) => {
      this.error = err;
      this.#flush();
    });
  }

  #flush() {
    while (this.waiters.length > 0) {
      const waiter = this.waiters[0];
      if (this.error) {
        this.waiters.shift();
        waiter.reject(this.error);
      } else if (this.buf.length >= waiter.n) {
        this.waiters.shift();
        const slice = this.buf.subarray(0, waiter.n);
        this.buf = this.buf.subarray(waiter.n);
        waiter.resolve(slice);
      } else if (this.ended) {
        this.waiters.shift();
        waiter.resolve(null);
      } else {
        break;
      }
    }
  }

  async readExact(n) {
    if (n === 0) return Buffer.alloc(0);
    if (this.error) throw this.error;
    if (this.buf.length >= n) {
      const slice = this.buf.subarray(0, n);
      this.buf = this.buf.subarray(n);
      return slice;
    }
    if (this.ended) return null;
    return await new Promise((resolve, reject) => {
      this.waiters.push({ n, resolve, reject });
    });
  }

  async readControlMessage() {
    const lenBuf = await this.readExact(4);
    if (!lenBuf) return null;
    const len = lenBuf.readInt32BE(0);
    if (len <= 0 || len > MAX_CONTROL_FRAME_BYTES) {
      const err = new Error(`Invalid control frame length: ${len}`);
      err.code = 'ERR_INVALID_FRAME';
      throw err;
    }
    const payloadBuf = await this.readExact(len);
    if (!payloadBuf) {
      const err = new Error('EOF while reading control frame body');
      err.code = 'ERR_INVALID_FRAME';
      throw err;
    }
    let msg;
    try {
      msg = JSON.parse(payloadBuf.toString('utf8'));
    } catch {
      const err = new Error('Malformed UTF-8 JSON control payload');
      err.code = 'ERR_INVALID_FRAME';
      throw err;
    }
    if (!msg || typeof msg !== 'object') {
      const err = new Error('Control message must be a JSON object');
      err.code = 'ERR_INVALID_FRAME';
      throw err;
    }
    if (!negotiateVersion(msg.version)) {
      const err = new Error(`Unsupported protocol version '${msg.version}'`);
      err.code = 'ERR_UNSUPPORTED_VERSION';
      err.requestedVersion = msg.version;
      throw err;
    }
    if (!msg.senderId || typeof msg.senderId !== 'string' || !msg.senderId.trim()) {
      const err = new Error('Missing or blank senderId');
      err.code = 'ERR_INVALID_FRAME';
      throw err;
    }
    if (typeof msg.timestamp === 'number' && msg.timestamp < 0) {
      const err = new Error('Negative timestamp');
      err.code = 'ERR_INVALID_FRAME';
      throw err;
    }
    return msg;
  }

  async readBinaryFrame() {
    const magicBuf = await this.readExact(4);
    if (!magicBuf) return { kind: 'EOF_STREAM' };
    const magic = magicBuf.readUInt32BE(0);
    if (magic === CHUNK_MAGIC) {
      const restHeader = await this.readExact(16);
      if (!restHeader) throw new Error('Truncated CHUNK header');
      const fileIndex = restHeader.readUInt32BE(0);
      const offset = Number(restHeader.readBigUInt64BE(4));
      const length = restHeader.readUInt32BE(12);
      if (length > MAX_CHUNK_BYTES) {
        throw new Error(`Chunk length ${length} exceeds MAX_CHUNK_BYTES`);
      }
      const data = await this.readExact(length);
      if (!data) throw new Error('Truncated CHUNK payload');
      return { kind: 'CHUNK', fileIndex, offset, data };
    } else if (magic === EOF_MAGIC) {
      const restEof = await this.readExact(36);
      if (!restEof) throw new Error('Truncated EOF frame');
      const fileIndex = restEof.readUInt32BE(0);
      const sha256Hex = restEof.subarray(4, 36).toString('hex');
      return { kind: 'EOF', fileIndex, sha256Hex };
    } else {
      throw new Error(`Unknown binary frame magic: 0x${magic.toString(16)}`);
    }
  }
}

export function generateSelfSignedEcIdentity(commonName = 'LocalDrop Reference Peer') {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'localdrop-ref-tls-'));
  const keyPath = path.join(tmpDir, 'key.pem');
  const certPath = path.join(tmpDir, 'cert.pem');
  execFileSync('openssl', [
    'req', '-x509',
    '-newkey', 'ec',
    '-pkeyopt', 'ec_paramgen_curve:P-256',
    '-keyout', keyPath,
    '-out', certPath,
    '-days', '30',
    '-nodes',
    '-subj', `/CN=${commonName}`
  ], { stdio: 'ignore' });
  const keyPem = fs.readFileSync(keyPath, 'utf8');
  const certPem = fs.readFileSync(certPath, 'utf8');
  fs.rmSync(tmpDir, { recursive: true, force: true });
  const x509 = new crypto.X509Certificate(certPem);
  const certFingerprintSha256 = x509.fingerprint256.replace(/:/g, '').toLowerCase();
  return { keyPem, certPem, certFingerprintSha256 };
}

export function makeMessage({
  version = 'v2',
  type,
  senderId,
  receiverId = '',
  payload = {}
}) {
  return {
    version,
    type,
    messageId: crypto.randomUUID(),
    senderId,
    receiverId,
    timestamp: Date.now(),
    payload
  };
}

export function connectPeerSocket({ host, port, useTls = false, expectedCertFingerprint = null, timeoutMs = 8000 }) {
  return new Promise((resolve, reject) => {
    let settled = false;
    const done = (err, sock) => {
      if (settled) return;
      settled = true;
      if (err) reject(err);
      else resolve(sock);
    };

    const socket = useTls
      ? tls.connect({
          host,
          port,
          rejectUnauthorized: false,
          minVersion: 'TLSv1.2'
        })
      : net.createConnection({ host, port });

    socket.setTimeout(timeoutMs, () => {
      socket.destroy(new Error('Socket timeout'));
    });

    socket.once(useTls ? 'secureConnect' : 'connect', () => {
      if (useTls && expectedCertFingerprint) {
        const cert = socket.getPeerCertificate(false);
        const actual = String(cert?.fingerprint256 || '').replace(/:/g, '').toLowerCase();
        if (!actual || actual !== expectedCertFingerprint.toLowerCase()) {
          const fpErr = new Error(`TLS fingerprint mismatch: expected=${expectedCertFingerprint} actual=${actual}`);
          socket.destroy(fpErr);
          done(fpErr);
          return;
        }
      }
      socket.setTimeout(0);
      done(null, socket);
    });
    socket.once('error', (err) => done(err));
  });
}

function resolveUniqueFile(dir, fileName) {
  const candidate0 = path.join(dir, fileName);
  if (!fs.existsSync(candidate0)) return candidate0;
  const dot = fileName.lastIndexOf('.');
  const base = dot > 0 ? fileName.slice(0, dot) : fileName;
  const ext = dot > 0 ? fileName.slice(dot) : '';
  for (let i = 1; i < 1000; i++) {
    const cand = path.join(dir, `${base} (${i})${ext}`);
    if (!fs.existsSync(cand)) return cand;
  }
  return path.join(dir, `${Date.now()}_${fileName}`);
}

export class ReferencePeerServer {
  constructor(options = {}) {
    this.deviceId = options.deviceId || 'tauri-ref-peer-01';
    this.deviceName = options.deviceName || 'Tauri Reference Peer';
    this.receiveDir = options.receiveDir || fs.mkdtempSync(path.join(os.tmpdir(), 'localdrop-ref-recv-'));
    this.tlsEnabled = options.tlsEnabled !== false;
    this.duplicatePolicy = options.duplicatePolicy || 'rename';
    this.sessionId = options.sessionId || crypto.randomUUID();
    this.pairingToken = options.pairingToken || 'ref_pair_token_123456';
    this.qrExpired = Boolean(options.qrExpired);
    this.consumedSessions = new Set();
    this.pairedTokens = new Map();
    this.seenAuthNonces = new Map();
    this.authFailures = new Map();
    this.identity = this.tlsEnabled ? generateSelfSignedEcIdentity(this.deviceName) : null;
    this.secureContext = this.identity
      ? tls.createSecureContext({ key: this.identity.keyPem, cert: this.identity.certPem })
      : null;
    this.server = null;
    this.port = 0;
    this.activeSockets = new Set();
    this.onEvent = options.onEvent || (() => {});
  }

  async start(listenPort = 0) {
    this.server = net.createServer((rawSocket) => this.#onRawConnection(rawSocket));
    await new Promise((resolve, reject) => {
      this.server.once('error', reject);
      this.server.listen(listenPort, '127.0.0.1', () => {
        this.port = this.server.address().port;
        resolve();
      });
    });
    return this.port;
  }

  async stop() {
    for (const sock of this.activeSockets) {
      try {
        sock.destroy();
      } catch {
        // ignore
      }
    }
    this.activeSockets.clear();
    if (this.server) {
      if (typeof this.server.closeAllConnections === 'function') {
        this.server.closeAllConnections();
      }
      await new Promise((resolve) => this.server.close(() => resolve()));
      this.server = null;
    }
  }

  #trackSocket(sock) {
    this.activeSockets.add(sock);
    sock.once('close', () => this.activeSockets.delete(sock));
    return sock;
  }

  #onRawConnection(rawSocket) {
    this.#trackSocket(rawSocket);
    const onReadable = () => {
      const firstByte = rawSocket.read(1);
      if (firstByte === null) return; // Wait for next readable event
      rawSocket.removeListener('readable', onReadable);
      rawSocket.unshift(firstByte);

      if (firstByte[0] === 0x16) {
        if (!this.secureContext) {
          rawSocket.destroy();
          return;
        }
        const tlsSocket = this.#trackSocket(new tls.TLSSocket(rawSocket, {
          isServer: true,
          secureContext: this.secureContext,
          requestCert: false,
          rejectUnauthorized: false
        }));
        tlsSocket.on('error', () => {});
        this.#handleControlLoop(tlsSocket)
          .catch(() => {})
          .finally(() => {
            if (!tlsSocket.destroyed) tlsSocket.end();
          });
      } else {
        this.#handleControlLoop(rawSocket)
          .catch(() => {})
          .finally(() => {
            if (!rawSocket.destroyed) rawSocket.end();
          });
      }
    };
    rawSocket.on('error', () => {});
    rawSocket.on('readable', onReadable);
  }

  async #handleControlLoop(socket) {
    const reader = new SocketFrameReader(socket);
    while (!socket.destroyed) {
      let msg;
      try {
        msg = await reader.readControlMessage();
      } catch (err) {
        if (err.code === 'ERR_UNSUPPORTED_VERSION') {
          const errFrame = makeMessage({
            version: 'v2',
            type: 'ERROR',
            senderId: this.deviceId,
            payload: {
              code: 'ERR_UNSUPPORTED_VERSION',
              message: err.message,
              supportedVersions: SUPPORTED_VERSIONS
            }
          });
          socket.write(encodeControlFrame(errFrame));
        } else {
          const errFrame = makeMessage({
            version: 'v2',
            type: 'ERROR',
            senderId: this.deviceId,
            payload: {
              code: 'ERR_INVALID_FRAME',
              message: err.message || 'Malformed frame'
            }
          });
          socket.write(encodeControlFrame(errFrame));
        }
        socket.end();
        return;
      }

      if (!msg) {
        socket.end();
        return;
      }
      const version = negotiateVersion(msg.version) || 'v1';

      if (msg.type === 'HEARTBEAT') {
        socket.write(encodeControlFrame(makeMessage({
          version,
          type: 'HEARTBEAT',
          senderId: this.deviceId,
          receiverId: msg.senderId,
          payload: { pong: Date.now() }
        })));
      } else if (msg.type === 'PAIRING_REQUEST') {
        const resp = this.#handlePairingRequest(msg, version);
        socket.write(encodeControlFrame(resp));
      } else if (msg.type === 'AUTHENTICATION_REQUEST') {
        const resp = this.#handleAuthRequest(msg, version);
        socket.write(encodeControlFrame(resp));
      } else if (msg.type === 'TRANSFER_REQUEST') {
        const resp = makeMessage({
          version,
          type: 'TRANSFER_ACCEPTED',
          senderId: this.deviceId,
          receiverId: msg.senderId,
          payload: {
            sessionId: msg.payload?.sessionId || '',
            accepted: true,
            supportsResume: true,
            tlsSupported: this.tlsEnabled,
            expectCompletionAck: true
          }
        });
        socket.write(encodeControlFrame(resp));
      } else if (msg.type === 'TRANSFER_STARTED') {
        await this.#handleTransferStarted(msg, version, socket, reader);
        return;
      } else if (msg.type === 'CONNECTION_CLOSED') {
        socket.end();
        return;
      }
    }
  }

  #handlePairingRequest(msg, version) {
    const sid = msg.payload?.sessionId;
    const tok = msg.payload?.token;
    if (sid && this.consumedSessions.has(sid)) {
      return makeMessage({
        version,
        type: 'PAIRING_REJECTED',
        senderId: this.deviceId,
        receiverId: msg.senderId,
        payload: { code: 'ERR_PAIRING_REPLAY', reason: 'Pairing session already used' }
      });
    }
    if (this.qrExpired) {
      return makeMessage({
        version,
        type: 'PAIRING_REJECTED',
        senderId: this.deviceId,
        receiverId: msg.senderId,
        payload: { code: 'ERR_PAIRING_EXPIRED', reason: 'QR code expired' }
      });
    }
    if (sid !== this.sessionId || tok !== this.pairingToken) {
      return makeMessage({
        version,
        type: 'PAIRING_REJECTED',
        senderId: this.deviceId,
        receiverId: msg.senderId,
        payload: { code: 'ERR_AUTH_FAILED', reason: 'Invalid pairing token' }
      });
    }
    this.consumedSessions.add(sid);
    const mutualToken = crypto.randomBytes(24).toString('hex');
    this.pairedTokens.set(msg.senderId, mutualToken);
    this.onEvent({ event: 'PAIRED', peerId: msg.senderId });

    return makeMessage({
      version,
      type: 'PAIRING_ACCEPTED',
      senderId: this.deviceId,
      receiverId: msg.senderId,
      payload: {
        authToken: mutualToken,
        deviceName: this.deviceName,
        platform: 'linux',
        model: 'Reference Peer (Node.js)',
        protocolVersion: version,
        tlsSupported: this.tlsEnabled,
        ...(this.identity ? { certFingerprint: this.identity.certFingerprintSha256 } : {})
      }
    });
  }

  #handleAuthRequest(msg, version) {
    const peerId = msg.senderId;
    const now = Date.now();
    const lockInfo = this.authFailures.get(peerId);
    if (lockInfo && lockInfo.count >= 5 && now - lockInfo.lastFail < 60_000) {
      return makeMessage({
        version,
        type: 'ERROR',
        senderId: this.deviceId,
        receiverId: peerId,
        payload: { code: 'ERR_AUTH_LOCKOUT', message: 'Too many failed authentication attempts' }
      });
    }
    const ts = typeof msg.payload?.timestamp === 'number' ? msg.payload.timestamp : msg.timestamp;
    const nonce = msg.payload?.nonce;
    if (Math.abs(now - ts) > 300_000 || !nonce) {
      return makeMessage({
        version,
        type: 'ERROR',
        senderId: this.deviceId,
        receiverId: peerId,
        payload: { code: 'ERR_REPLAY_DETECTED', message: 'Timestamp stale or nonce missing' }
      });
    }
    const peerNonces = this.seenAuthNonces.get(peerId) || new Set();
    if (peerNonces.has(nonce)) {
      return makeMessage({
        version,
        type: 'ERROR',
        senderId: this.deviceId,
        receiverId: peerId,
        payload: { code: 'ERR_REPLAY_DETECTED', message: 'Nonce replay detected' }
      });
    }
    peerNonces.add(nonce);
    this.seenAuthNonces.set(peerId, peerNonces);

    const expected = this.pairedTokens.get(peerId);
    const provided = msg.payload?.token;
    const match = Boolean(expected && provided && expected.length === provided.length &&
      crypto.timingSafeEqual(Buffer.from(expected), Buffer.from(provided)));

    if (!match) {
      const next = { count: (lockInfo?.count || 0) + 1, lastFail: now };
      this.authFailures.set(peerId, next);
      return makeMessage({
        version,
        type: 'ERROR',
        senderId: this.deviceId,
        receiverId: peerId,
        payload: { code: 'ERR_AUTH_FAILED', message: 'Invalid auth token' }
      });
    }

    this.authFailures.delete(peerId);
    return makeMessage({
      version,
      type: 'AUTHENTICATION_RESPONSE',
      senderId: this.deviceId,
      receiverId: peerId,
      payload: { status: 'authenticated', protocolVersion: version }
    });
  }

  async #handleTransferStarted(msg, version, socket, reader) {
    const p = msg.payload || {};
    const sessionId = p.sessionId || '';
    const fileId = p.fileId || crypto.randomUUID();
    const safeName = sanitizeFileName(p.fileName || 'unnamed.bin');
    const safeRel = sanitizeRelativePath(p.relativePath);
    const expectCompletionAck = Boolean(p.expectCompletionAck);
    const isDirectory = Boolean(p.isDirectory);

    if (isDirectory) {
      if (safeRel) {
        fs.mkdirSync(path.join(this.receiveDir, safeRel), { recursive: true });
      }
      this.onEvent({ event: 'RECEIVED_DIR', fileId, relativePath: safeRel });
      if (expectCompletionAck) {
        socket.write(encodeControlFrame(makeMessage({
          version,
          type: 'TRANSFER_COMPLETED',
          senderId: this.deviceId,
          receiverId: msg.senderId,
          payload: { sessionId, fileId, bytesVerified: 0, isDirectory: true }
        })));
      }
      return;
    }

    if (p.supportsResume && p.expectReady) {
      socket.write(encodeControlFrame(makeMessage({
        version,
        type: 'TRANSFER_READY',
        senderId: this.deviceId,
        receiverId: msg.senderId,
        payload: { fileId, acceptedOffset: 0 }
      })));
    }

    const hash = crypto.createHash('sha256');
    const chunks = [];
    let bytesReceived = 0;

    while (true) {
      const frame = await reader.readBinaryFrame();
      if (frame.kind === 'CHUNK') {
        hash.update(frame.data);
        chunks.push(frame.data);
        bytesReceived += frame.data.length;
      } else if (frame.kind === 'EOF') {
        const actualSha = hash.digest('hex');
        if (frame.sha256Hex && actualSha.toLowerCase() !== frame.sha256Hex.toLowerCase()) {
          if (expectCompletionAck) {
            socket.write(encodeControlFrame(makeMessage({
              version,
              type: 'TRANSFER_FAILED',
              senderId: this.deviceId,
              receiverId: msg.senderId,
              payload: { sessionId, fileId, code: 'ERR_CHECKSUM_MISMATCH', reason: 'SHA-256 checksum mismatch' }
            })));
          }
          socket.end();
          return;
        }

        const relParent = safeRel && safeRel.includes('/')
          ? safeRel.slice(0, safeRel.lastIndexOf('/'))
          : '';
        const targetDir = relParent ? path.join(this.receiveDir, relParent) : this.receiveDir;
        fs.mkdirSync(targetDir, { recursive: true });

        let finalPath = path.join(targetDir, safeName);
        if (fs.existsSync(finalPath)) {
          if (this.duplicatePolicy === 'skip') {
            if (expectCompletionAck) {
              socket.write(encodeControlFrame(makeMessage({
                version,
                type: 'FILE_CONFLICT',
                senderId: this.deviceId,
                receiverId: msg.senderId,
                payload: { sessionId, fileId, requestedFileName: safeName, policyApplied: 'skip' }
              })));
              socket.write(encodeControlFrame(makeMessage({
                version,
                type: 'TRANSFER_COMPLETED',
                senderId: this.deviceId,
                receiverId: msg.senderId,
                payload: { sessionId, fileId, sha256: actualSha, bytesVerified: bytesReceived, skipped: true }
              })));
            }
            return;
          } else if (this.duplicatePolicy !== 'overwrite') {
            finalPath = resolveUniqueFile(targetDir, safeName);
          }
        }

        fs.writeFileSync(finalPath, Buffer.concat(chunks));
        const finalName = path.basename(finalPath);
        this.onEvent({
          event: 'RECEIVED_FILE',
          fileId,
          fileName: finalName,
          relativePath: safeRel,
          bytesVerified: bytesReceived,
          sha256: actualSha,
          savedPath: finalPath
        });

        if (expectCompletionAck) {
          if (finalName !== safeName) {
            socket.write(encodeControlFrame(makeMessage({
              version,
              type: 'FILE_CONFLICT',
              senderId: this.deviceId,
              receiverId: msg.senderId,
              payload: {
                sessionId,
                fileId,
                requestedFileName: safeName,
                resolvedFileName: finalName,
                policyApplied: this.duplicatePolicy
              }
            })));
          }
          socket.write(encodeControlFrame(makeMessage({
            version,
            type: 'TRANSFER_COMPLETED',
            senderId: this.deviceId,
            receiverId: msg.senderId,
            payload: {
              sessionId,
              fileId,
              sha256: actualSha,
              bytesVerified: bytesReceived,
              finalFileName: finalName,
              skipped: false
            }
          })));
        }
        return;
      } else {
        throw new Error('Connection closed before EOF frame');
      }
    }
  }
}

export async function sendFileFromPeer({
  host,
  port,
  senderId,
  senderName = 'Tauri Reference Peer',
  receiverId = 'android-peer',
  fileName,
  fileBuffer,
  relativePath = null,
  mimeType = 'application/octet-stream',
  useTls = false,
  expectedCertFingerprint = null,
  version = 'v2',
  corruptEofChecksum = false,
  expectCompletionAck = true
}) {
  const sessionId = crypto.randomUUID();
  const fileId = crypto.randomUUID();

  // Step 1: TRANSFER_REQUEST handshake
  const reqSock = await connectPeerSocket({ host, port, useTls, expectedCertFingerprint });
  const reqReader = new SocketFrameReader(reqSock);
  reqSock.write(encodeControlFrame(makeMessage({
    version,
    type: 'TRANSFER_REQUEST',
    senderId,
    receiverId,
    payload: {
      sessionId,
      senderName,
      senderPlatform: 'linux',
      senderIp: '127.0.0.1',
      senderPort: 53317,
      totalSize: fileBuffer.length,
      totalBytes: fileBuffer.length,
      fileCount: 1,
      tlsPreferred: useTls,
      supportsResume: true,
      files: [{
        fileId,
        fileName,
        fileSize: fileBuffer.length,
        mimeType,
        ...(relativePath ? { relativePath } : {})
      }]
    }
  })));
  const acceptMsg = await reqReader.readControlMessage();
  reqSock.end();

  if (!acceptMsg || acceptMsg.type !== 'TRANSFER_ACCEPTED') {
    throw new Error(`Transfer rejected: ${JSON.stringify(acceptMsg)}`);
  }

  // Step 2: Data stream socket
  const dataSock = await connectPeerSocket({ host, port, useTls, expectedCertFingerprint });
  const dataReader = new SocketFrameReader(dataSock);
  dataSock.write(encodeControlFrame(makeMessage({
    version,
    type: 'TRANSFER_STARTED',
    senderId,
    receiverId,
    payload: {
      sessionId,
      fileId,
      fileIndex: 0,
      fileName,
      fileSize: fileBuffer.length,
      mimeType,
      senderName,
      supportsResume: true,
      expectReady: true,
      expectCompletionAck,
      ...(relativePath ? { relativePath } : {})
    }
  })));

  const readyMsg = await dataReader.readControlMessage();
  let startOffset = 0;
  if (readyMsg && readyMsg.type === 'TRANSFER_READY') {
    startOffset = readyMsg.payload?.acceptedOffset || 0;
  }

  const chunkSize = 64 * 1024;
  for (let offset = startOffset; offset < fileBuffer.length; offset += chunkSize) {
    const slice = fileBuffer.subarray(offset, Math.min(offset + chunkSize, fileBuffer.length));
    dataSock.write(encodeChunkFrame(0, offset, slice));
  }

  const shaDigest = crypto.createHash('sha256').update(fileBuffer).digest();
  if (corruptEofChecksum) {
    shaDigest[0] ^= 0xFF;
  }
  dataSock.write(encodeEofFrame(0, shaDigest));

  const acks = [];
  if (expectCompletionAck) {
    while (true) {
      const ack = await dataReader.readControlMessage();
      if (!ack) break;
      acks.push(ack);
      if (ack.type === 'TRANSFER_COMPLETED' || ack.type === 'TRANSFER_FAILED' || ack.type === 'ERROR') {
        break;
      }
    }
  }
  dataSock.end();
  return { sessionId, fileId, readyMsg, acks, expectedSha256: crypto.createHash('sha256').update(fileBuffer).digest('hex') };
}

// ---------------------------------------------------------------------------
// Headless --serve CLI mode (used by Phase9CrossPlatformInteropTest.kt)
// ---------------------------------------------------------------------------
const isMainModule = process.argv[1] && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url));
const isServeMode = isMainModule && process.argv.includes('--serve');

if (isServeMode) {
  const args = process.argv.slice(2);
  const getArg = (name, defVal = undefined) => {
    const idx = args.indexOf(name);
    return idx >= 0 && idx + 1 < args.length ? args[idx + 1] : defVal;
  };
  const port = parseInt(getArg('--port', '0'), 10);
  const tlsEnabled = getArg('--tls', 'true') !== 'false';
  const sessionId = getArg('--session-id', crypto.randomUUID());
  const pairingToken = getArg('--pairing-token', 'ref_pair_token_123456');
  const receiveDir = getArg('--receive-dir', fs.mkdtempSync(path.join(os.tmpdir(), 'localdrop-cli-recv-')));
  const duplicatePolicy = getArg('--duplicate-policy', 'rename');

  const server = new ReferencePeerServer({
    tlsEnabled,
    sessionId,
    pairingToken,
    receiveDir,
    duplicatePolicy,
    onEvent: (ev) => process.stdout.write(`${JSON.stringify(ev)}\n`)
  });

  const boundPort = await server.start(port);
  process.stdout.write(`${JSON.stringify({
    event: 'READY',
    port: boundPort,
    deviceId: server.deviceId,
    deviceName: server.deviceName,
    tlsEnabled,
    certFingerprint: server.identity?.certFingerprintSha256 || null,
    sessionId,
    pairingToken,
    receiveDir
  })}\n`);

  const rl = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
  rl.on('line', async (line) => {
    const trimmed = line.trim();
    if (!trimmed) return;
    let cmd;
    try {
      cmd = JSON.parse(trimmed);
    } catch {
      return;
    }
    try {
      if (cmd.cmd === 'quit') {
        await server.stop();
        process.exit(0);
      } else if (cmd.cmd === 'send') {
        const data = Buffer.from(cmd.base64Data, 'base64');
        const res = await sendFileFromPeer({
          host: cmd.host || '127.0.0.1',
          port: cmd.port,
          senderId: cmd.senderId || server.deviceId,
          senderName: cmd.senderName || server.deviceName,
          receiverId: cmd.receiverId || 'android-peer',
          fileName: cmd.fileName || 'from_node.bin',
          fileBuffer: data,
          relativePath: cmd.relativePath || null,
          mimeType: cmd.mimeType || 'application/octet-stream',
          useTls: Boolean(cmd.useTls),
          expectedCertFingerprint: cmd.expectedFp || null,
          version: cmd.version || 'v2',
          corruptEofChecksum: Boolean(cmd.corruptEofChecksum),
          expectCompletionAck: cmd.expectCompletionAck !== false
        });
        process.stdout.write(`${JSON.stringify({ event: 'CMD_RESULT', id: cmd.id, ok: true, result: res })}\n`);
      } else if (cmd.cmd === 'send_raw_control') {
        const sock = await connectPeerSocket({
          host: cmd.host || '127.0.0.1',
          port: cmd.port,
          useTls: Boolean(cmd.useTls),
          expectedCertFingerprint: cmd.expectedFp || null
        });
        const reader = new SocketFrameReader(sock);
        sock.write(encodeControlFrame(cmd.message));
        const reply = await reader.readControlMessage();
        sock.end();
        process.stdout.write(`${JSON.stringify({ event: 'CMD_RESULT', id: cmd.id, ok: true, reply })}\n`);
      }
    } catch (err) {
      process.stdout.write(`${JSON.stringify({ event: 'CMD_RESULT', id: cmd.id, ok: false, error: err.message })}\n`);
    }
  });
}

// ---------------------------------------------------------------------------
// Standalone Node.js test suite (`node --test protocol/tests/protocol_reference_peer.mjs`)
// ---------------------------------------------------------------------------
if (!isServeMode) {
  const { describe, it } = await import('node:test');

  const protocolDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
  const schemaPath = path.join(protocolDir, 'localdrop-protocol.schema.json');
  const fixturesDir = path.join(protocolDir, 'fixtures');

  describe('LocalDrop Shared Protocol Schema & Fixtures', () => {
    it('schema defines all required enums, framing constants, and defs', () => {
      const schema = JSON.parse(fs.readFileSync(schemaPath, 'utf8'));
      assert.deepEqual(schema.$defs.ProtocolVersion.enum, ['v1', 'v2']);
      assert.ok(schema.$defs.MessageType.enum.includes('FILE_CONFLICT'));
      assert.ok(schema.$defs.ErrorCode.enum.includes('ERR_UNSUPPORTED_VERSION'));
      assert.ok(schema.$defs.ErrorCode.enum.includes('ERR_PAIRING_REPLAY'));
      assert.equal(schema['x-framing'].binaryStreamFrame.chunkMagicAscii, 'LSD1');
      assert.equal(schema['x-framing'].binaryStreamFrame.eofMagicAscii, 'LSEO');
    });

    it('validates all canonical golden fixtures against schema constraints', () => {
      const schema = JSON.parse(fs.readFileSync(schemaPath, 'utf8'));
      const validTypes = new Set(schema.$defs.MessageType.enum);
      const validVersions = new Set(schema.$defs.ProtocolVersion.enum);
      const validErrorCodes = new Set(schema.$defs.ErrorCode.enum);

      const pairing = JSON.parse(fs.readFileSync(path.join(fixturesDir, 'pairing_and_auth.json'), 'utf8'));
      assert.equal(pairing.qrPayloadV2.protocolVersion, 'v2');
      assert.ok(pairing.pairingUriV2.startsWith('localdrop://pair?'));
      for (const msg of [pairing.pairingRequest, pairing.pairingAccepted, pairing.authenticationRequest, pairing.authenticationResponse]) {
        assert.ok(validVersions.has(msg.version));
        assert.ok(validTypes.has(msg.type));
        assert.ok(msg.senderId.length > 0);
      }

      const xfer = JSON.parse(fs.readFileSync(path.join(fixturesDir, 'transfer_lifecycle.json'), 'utf8'));
      for (const msg of Object.values(xfer)) {
        assert.ok(validVersions.has(msg.version));
        assert.ok(validTypes.has(msg.type));
        assert.ok(msg.messageId.length > 0);
      }

      const errs = JSON.parse(fs.readFileSync(path.join(fixturesDir, 'error_responses.json'), 'utf8'));
      for (const msg of Object.values(errs)) {
        assert.ok(validTypes.has(msg.type));
        assert.ok(['ERROR', 'TRANSFER_FAILED', 'PAIRING_REJECTED'].includes(msg.type));
        assert.ok(validErrorCodes.has(msg.payload.code));
      }
    });
  });

  describe('Reference Peer Wire Interoperability (TCP + TLS Pinned)', () => {
    it('supports pairing, replay rejection, version negotiation, pinned TLS transfer, and checksum errors', async () => {
      const recvDir = fs.mkdtempSync(path.join(os.tmpdir(), 'localdrop-ref-test-'));
      const peer = new ReferencePeerServer({
        tlsEnabled: true,
        receiveDir: recvDir,
        sessionId: 'sess-ref-1',
        pairingToken: 'secret-qr-tok-999'
      });
      const port = await peer.start(0);
      const fp = peer.identity.certFingerprintSha256;

      try {
        // 1. Cert fingerprint mismatch MUST reject TLS handshake
        await assert.rejects(() => connectPeerSocket({
          host: '127.0.0.1',
          port,
          useTls: true,
          expectedCertFingerprint: '0'.repeat(64)
        }));

        // 2. Unsupported version v99 returns structured ERR_UNSUPPORTED_VERSION
        {
          const s = await connectPeerSocket({ host: '127.0.0.1', port, useTls: true, expectedCertFingerprint: fp });
          const r = new SocketFrameReader(s);
          s.write(encodeControlFrame(makeMessage({ version: 'v99', type: 'HEARTBEAT', senderId: 'android-1' })));
          const errMsg = await r.readControlMessage();
          assert.equal(errMsg.type, 'ERROR');
          assert.equal(errMsg.payload.code, 'ERR_UNSUPPORTED_VERSION');
          assert.deepEqual(errMsg.payload.supportedVersions, ['v1', 'v2']);
          s.end();
        }

        // 3. Single-use QR pairing & replay rejection
        {
          const s = await connectPeerSocket({ host: '127.0.0.1', port, useTls: true, expectedCertFingerprint: fp });
          const r = new SocketFrameReader(s);
          s.write(encodeControlFrame(makeMessage({
            type: 'PAIRING_REQUEST',
            senderId: 'android-1',
            receiverId: peer.deviceId,
            payload: { sessionId: 'sess-ref-1', token: 'secret-qr-tok-999', deviceName: 'Pixel' }
          })));
          const acc = await r.readControlMessage();
          assert.equal(acc.type, 'PAIRING_ACCEPTED');
          assert.equal(acc.payload.certFingerprint, fp);

          // Replay same QR session on same or new socket
          s.write(encodeControlFrame(makeMessage({
            type: 'PAIRING_REQUEST',
            senderId: 'android-attacker',
            receiverId: peer.deviceId,
            payload: { sessionId: 'sess-ref-1', token: 'secret-qr-tok-999' }
          })));
          const rej = await r.readControlMessage();
          assert.equal(rej.type, 'PAIRING_REJECTED');
          assert.equal(rej.payload.code, 'ERR_PAIRING_REPLAY');
          s.end();
        }

        // 4. Send file over TLS with relative path & Windows illegal chars + receive TRANSFER_COMPLETED ack
        const payloadData = crypto.randomBytes(300 * 1024);
        const sendRes = await sendFileFromPeer({
          host: '127.0.0.1',
          port,
          senderId: 'android-1',
          receiverId: peer.deviceId,
          fileName: 'bad<name>:test.bin',
          relativePath: 'Docs/Sub/bad<name>:test.bin',
          fileBuffer: payloadData,
          useTls: true,
          expectedCertFingerprint: fp
        });
        const doneAck = sendRes.acks.find((a) => a.type === 'TRANSFER_COMPLETED');
        assert.ok(doneAck);
        assert.equal(doneAck.payload.sha256, sendRes.expectedSha256);
        assert.equal(doneAck.payload.finalFileName, 'bad_name__test.bin');
        assert.ok(fs.existsSync(path.join(recvDir, 'Docs/Sub/bad_name__test.bin')));

        // 5. Corrupt EOF SHA-256 produces TRANSFER_FAILED ERR_CHECKSUM_MISMATCH
        const badRes = await sendFileFromPeer({
          host: '127.0.0.1',
          port,
          senderId: 'android-1',
          receiverId: peer.deviceId,
          fileName: 'corrupt.bin',
          fileBuffer: Buffer.from('hello world'),
          useTls: true,
          expectedCertFingerprint: fp,
          corruptEofChecksum: true
        });
        const failAck = badRes.acks.find((a) => a.type === 'TRANSFER_FAILED');
        assert.ok(failAck);
        assert.equal(failAck.payload.code, 'ERR_CHECKSUM_MISMATCH');
      } finally {
        await peer.stop();
        fs.rmSync(recvDir, { recursive: true, force: true });
      }
    });
  });
}
