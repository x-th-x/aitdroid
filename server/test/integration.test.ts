import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { WebSocket } from "ws";
import { createServer, RunningServer } from "../src/server";
import { testConfig } from "../src/config";
import { ServerMessage } from "../src/protocol";

// A real server on a free port and real WebSocket clients: the whole path, from the frame on the wire to the answer.

let server: RunningServer;
let port = 0;

before(async () => {
  server = createServer(testConfig({ maxMessagesPerSecond: 40 }));
  port = await server.listen(0);
});

after(async () => {
  await server.close();
});

class Client {
  readonly messages: ServerMessage[] = [];
  private waiters: { type: string; resolve: (m: ServerMessage) => void }[] = [];
  private constructor(readonly ws: WebSocket) {
    ws.on("message", (data) => {
      const msg = JSON.parse(data.toString()) as ServerMessage;
      this.messages.push(msg);
      this.waiters = this.waiters.filter((w) => (w.type === msg.type ? (w.resolve(msg), false) : true));
    });
  }
  static async open(): Promise<Client> {
    const ws = new WebSocket("ws://127.0.0.1:" + port);
    const c = new Client(ws);
    await new Promise<void>((resolve, reject) => { ws.once("open", () => resolve()); ws.once("error", reject); });
    await c.next("welcome");
    return c;
  }
  send(m: object): void { this.ws.send(JSON.stringify(m)); }
  /** The next message of that type for which [ok] holds (earlier ones of that type are skipped). */
  async until<T extends ServerMessage["type"]>(type: T, ok: (m: Extract<ServerMessage, { type: T }>) => boolean): Promise<Extract<ServerMessage, { type: T }>> {
    for (let i = 0; i < 50; i++) {
      const m = await this.next(type);
      if (ok(m)) return m;
    }
    throw new Error("no matching " + type);
  }
  /** The next message of that type (one that has already arrived and was not taken yet counts). */
  next<T extends ServerMessage["type"]>(type: T, timeoutMs = 2000): Promise<Extract<ServerMessage, { type: T }>> {
    const i = this.messages.findIndex((m) => m.type === type);
    if (i >= 0) return Promise.resolve(this.messages.splice(i, 1)[0] as Extract<ServerMessage, { type: T }>);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error("no " + type + " within " + timeoutMs + " ms")), timeoutMs);
      this.waiters.push({ type, resolve: (m) => { clearTimeout(timer); const j = this.messages.indexOf(m); if (j >= 0) this.messages.splice(j, 1); resolve(m as Extract<ServerMessage, { type: T }>); } });
    });
  }
  close(): void { this.ws.close(); }
}

const state = (hero: string, seq: number, x = 3231, z = -1548) => ({ type: "state", seq, t: seq * 66, hero, stage: 0, room: 0, x, y: 0, z, beta: 100, anim: 4 });

test("the health check answers", async () => {
  const res = await fetch("http://127.0.0.1:" + port + "/health");
  assert.equal(res.status, 200);
  const body = (await res.json()) as { status: string };
  assert.equal(body.status, "ok");
});

test("two clients create, join, agree, start, and exchange hero states; a third is rejected", async () => {
  const a = await Client.open();
  const b = await Client.open();
  a.send({ type: "create_session" });
  const joinedA = await a.next("joined");
  assert.equal(joinedA.hero, "carnby");

  b.send({ type: "list_sessions" });
  const list = await b.next("sessions");
  assert.equal(list.sessions.some((s) => s.name === joinedA.name), true);
  b.send({ type: "join_session", name: joinedA.name });
  const joinedB = await b.next("joined");
  assert.equal(joinedB.hero, "emily");
  assert.equal(joinedB.sessionId, joinedA.sessionId);

  const c = await Client.open();
  c.send({ type: "join_session", name: joinedA.name });
  const full = await c.next("error");
  assert.equal(full.code, "session_full");

  a.send({ type: "select_start", mode: "new" });
  b.send({ type: "select_start", mode: "new" });
  // a choice cancels the readiness of both, so they get ready only after both choices have been seen (as a person would)
  await a.until("session", (s) => s.selectionsMatch);
  await b.until("session", (s) => s.selectionsMatch);
  a.send({ type: "set_ready", ready: true });
  b.send({ type: "set_ready", ready: true });
  const startA = await a.next("start");
  const startB = await b.next("start");
  assert.deepEqual(startA, startB);

  // a running session takes nobody new
  c.send({ type: "join_session", name: joinedA.name });
  assert.equal((await c.next("error")).code, "session_not_joinable");

  // simultaneous states in both directions
  a.send(state("carnby", 1, 3300, -1500));
  b.send(state("emily", 1, 3000, -1000));
  const seenByB = await b.next("remote_state");
  const seenByA = await a.next("remote_state");
  assert.equal(seenByB.hero, "carnby");
  assert.equal(seenByB.x, 3300);
  assert.equal(seenByA.hero, "emily");
  assert.equal(seenByA.z, -1000);

  // impersonation is refused and does not reach the other client
  a.send(state("emily", 2));
  assert.equal((await a.next("error")).code, "wrong_hero");

  // the other player drops out: the remaining one is told
  b.close();
  const peer = await a.until("peer", (p) => p.state === "disconnected");
  assert.equal(peer.state, "disconnected");
  assert.equal((await a.until("session", (x) => x.status === "interrupted")).status, "interrupted");
  a.close();
  c.close();
});

test("malformed and unsupported frames get an error and do not break the connection", async () => {
  const a = await Client.open();
  a.ws.send("this is not json");
  assert.equal((await a.next("error")).code, "bad_message");
  a.ws.send(JSON.stringify({ type: "dance" }));
  assert.equal((await a.next("error")).code, "unknown_type");
  a.ws.send(Buffer.from([1, 2, 3]));
  assert.equal((await a.next("error")).code, "bad_message");
  a.send({ type: "ping", t: 7 });
  const pong = await a.next("pong");
  assert.equal(pong.t, 7);
  a.close();
});

test("a flood of messages is told off", async () => {
  const a = await Client.open();
  for (let i = 0; i < 80; i++) a.send({ type: "ping", t: i });
  const e = await a.next("error");
  assert.equal(e.code, "rate_limited");
  a.close();
});

async function startedPair(c: { open: () => Promise<Client> }): Promise<{ a: Client; b: Client; name: string; tokenB: string }> {
  const a = await c.open();
  const b = await c.open();
  a.send({ type: "create_session" });
  const joinedA = await a.next("joined");
  b.send({ type: "join_session", name: joinedA.name });
  const joinedB = await b.next("joined");
  a.send({ type: "select_start", mode: "new" });
  b.send({ type: "select_start", mode: "new" });
  await a.until("session", (s) => s.selectionsMatch);
  await b.until("session", (s) => s.selectionsMatch);
  a.send({ type: "set_ready", ready: true });
  b.send({ type: "set_ready", ready: true });
  await a.next("start");
  await b.next("start");
  return { a, b, name: joinedA.name, tokenB: joinedB.token };
}

test("a dropped player comes back over a new connection with his token and the game runs on", async () => {
  const { a, b, name, tokenB } = await startedPair(Client);
  b.ws.terminate(); // the connection is lost without a goodbye
  assert.equal((await a.until("session", (s) => s.status === "interrupted")).status, "interrupted");
  const b2 = await Client.open();
  b2.send({ type: "resume", name, token: tokenB });
  assert.equal((await b2.next("joined")).hero, "emily");
  assert.equal((await a.until("session", (s) => s.status === "running")).status, "running");
  b2.send(state("emily", 1, 3000, -1000));
  assert.equal((await a.next("remote_state")).x, 3000);
  a.send(state("carnby", 1, 3300, -1500));
  assert.equal((await b2.next("remote_state")).x, 3300);
  a.close();
  b2.close();
});

test("a wrong token does not take the place, and after the keep time the interrupted game is gone", async () => {
  const quick = createServer(testConfig({ interruptedKeepMs: 1500 }));
  const quickPort = await quick.listen(0);
  const open = async (): Promise<Client> => {
    const ws = new WebSocket("ws://127.0.0.1:" + quickPort);
    const c = new (Client as unknown as { new (w: WebSocket): Client })(ws);
    await new Promise<void>((resolve, reject) => { ws.once("open", () => resolve()); ws.once("error", reject); });
    await c.next("welcome");
    return c;
  };
  try {
    const { a, b, name, tokenB } = await startedPair({ open });
    b.ws.terminate();
    await a.until("session", (s) => s.status === "interrupted");
    const thief = await open();
    thief.send({ type: "resume", name, token: "not-the-token" });
    assert.equal((await thief.next("error")).code, "bad_token");
    // nobody comes back: the game is closed after the keep time
    const closed = await a.next("closed", 6000);
    const late = await open();
    late.send({ type: "resume", name, token: tokenB });
    const err = await late.next("error", 3000);
    assert.equal(err.code, "unknown_session");
    assert.equal(closed.reason, "the interrupted game was closed");
    a.close(); thief.close(); late.close();
  } finally {
    await quick.close();
  }
});
