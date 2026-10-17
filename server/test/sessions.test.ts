import { test } from "node:test";
import assert from "node:assert/strict";
import { SessionManager, Outbound, nameKey } from "../src/sessions";
import { testConfig } from "../src/config";
import { ClientMessage, ServerMessage } from "../src/protocol";

class Harness {
  time = 1_000_000;
  readonly m: SessionManager;
  readonly inbox = new Map<string, ServerMessage[]>();
  constructor(over = {}) {
    this.m = new SessionManager(testConfig(over), () => this.time);
  }
  connect(id: string) {
    this.inbox.set(id, []);
    this.collect(this.m.connect(id));
  }
  send(id: string, msg: ClientMessage): Outbound[] {
    const out = this.m.handle(id, msg);
    this.collect(out);
    return out;
  }
  drop(id: string): Outbound[] {
    const out = this.m.disconnect(id);
    this.collect(out);
    return out;
  }
  tick(): Outbound[] {
    const out = this.m.tick();
    this.collect(out);
    return out;
  }
  collect(out: Outbound[]) {
    for (const o of out) this.inbox.get(o.to)?.push(o.msg);
  }
  last<T extends ServerMessage["type"]>(id: string, type: T): Extract<ServerMessage, { type: T }> | undefined {
    const list = this.inbox.get(id) ?? [];
    for (let i = list.length - 1; i >= 0; i--) if (list[i].type === type) return list[i] as Extract<ServerMessage, { type: T }>;
    return undefined;
  }
  errorCode(out: Outbound[], to: string): string | undefined {
    const e = out.find((o) => o.to === to && o.msg.type === "error");
    return e && e.msg.type === "error" ? e.msg.code : undefined;
  }
}

/** Two connections, A hosts and B has joined. */
function pair(h = new Harness()) {
  h.connect("A");
  h.connect("B");
  h.send("A", { type: "create_session" });
  const name = h.last("A", "joined")!.name;
  h.send("B", { type: "join_session", name });
  return { h, name };
}

function startNew(h: Harness) {
  h.send("A", { type: "select_start", mode: "new" });
  h.send("B", { type: "select_start", mode: "new" });
  h.send("A", { type: "set_ready", ready: true });
  h.send("B", { type: "set_ready", ready: true });
}

const stateOf = (hero: "carnby" | "emily", seq: number, x = 3231, z = -1548, room = 0) => ({ type: "state" as const, seq, t: seq * 66, hero, stage: 0, room, x, y: 0, z, beta: 100, anim: 4 });

test("a session is created with a readable name and the host is Carnby", () => {
  const h = new Harness();
  h.connect("A");
  h.send("A", { type: "create_session" });
  const joined = h.last("A", "joined")!;
  assert.equal(joined.slot, 1);
  assert.equal(joined.hero, "carnby");
  assert.match(joined.name, /^[A-Z][a-z]+-\d{2}$/);
  assert.match(joined.sessionId, /^[0-9a-f-]{36}$/);
  const info = h.last("A", "session")!;
  assert.equal(info.status, "waiting");
  assert.equal(info.players.length, 1);
});

test("the second player finds the session, joins and is Emily; both see the same session id", () => {
  const { h, name } = pair();
  const a = h.last("A", "joined")!;
  const b = h.last("B", "joined")!;
  assert.equal(b.slot, 2);
  assert.equal(b.hero, "emily");
  assert.equal(a.sessionId, b.sessionId);
  assert.equal(h.last("A", "session")!.status, "ready");
  assert.equal(h.last("B", "session")!.players.length, 2);
  assert.equal(nameKey(name), nameKey(name.toUpperCase().replace("-", " ")));
});

test("only waiting sessions are listed", () => {
  const h = new Harness();
  h.connect("A"); h.connect("B"); h.connect("C");
  h.send("A", { type: "create_session" });
  h.send("C", { type: "list_sessions" });
  assert.equal(h.last("C", "sessions")!.sessions.length, 1);
  h.send("B", { type: "join_session", name: h.last("A", "joined")!.name });
  h.send("C", { type: "list_sessions" });
  assert.equal(h.last("C", "sessions")!.sessions.length, 0);
});

test("a session name is found whatever the case or spacing", () => {
  const h = new Harness();
  h.connect("A"); h.connect("B");
  h.send("A", { type: "create_session" });
  const name = h.last("A", "joined")!.name;
  h.send("B", { type: "join_session", name: "  " + name.toLowerCase().replace("-", " ") + " " });
  assert.equal(h.last("B", "joined")!.slot, 2);
});

test("a third player is rejected", () => {
  const { h, name } = pair();
  h.connect("C");
  const out = h.send("C", { type: "join_session", name });
  assert.equal(h.errorCode(out, "C"), "session_full");
});

test("an unknown session gives a clear error", () => {
  const h = new Harness();
  h.connect("A");
  assert.equal(h.errorCode(h.send("A", { type: "join_session", name: "Nobody-00" }), "A"), "unknown_session");
});

test("somebody who is in a session cannot create or join another", () => {
  const { h, name } = pair();
  assert.equal(h.errorCode(h.send("A", { type: "create_session" }), "A"), "already_in_session");
  assert.equal(h.errorCode(h.send("B", { type: "join_session", name }), "B"), "already_in_session");
});

test("leaving without being in a session is an error, not a crash", () => {
  const h = new Harness();
  h.connect("A");
  assert.equal(h.errorCode(h.send("A", { type: "leave_session" }), "A"), "not_in_session");
  assert.equal(h.errorCode(h.send("A", { type: "set_ready", ready: true }), "A"), "not_in_session");
  assert.equal(h.errorCode(h.send("A", stateOf("carnby", 1)), "A"), "not_in_session");
});

test("messages of unknown connections are ignored", () => {
  const h = new Harness();
  assert.deepEqual(h.m.handle("ghost", { type: "create_session" }), []);
});

// ------------------------------------------------------------------------------------------ the start agreement

test("the game does not start by itself; different choices block it", () => {
  const { h } = pair();
  h.send("A", { type: "select_start", mode: "new" });
  assert.equal(h.last("A", "session")!.status, "ready");
  h.send("A", { type: "upload_save", saveId: "s1", label: "x", data: "AAAA" });
  h.send("B", { type: "select_start", mode: "save", saveId: "s1" });
  const info = h.last("A", "session")!;
  assert.equal(info.selectionsMatch, false);
  assert.match(info.mismatch ?? "", /different/);
  const out = h.send("A", { type: "set_ready", ready: true });
  assert.equal(h.errorCode(out, "A"), "selection_mismatch");
  assert.equal(h.last("A", "start"), undefined);
});

test("both choose a new game and both are ready: the game starts for both with the same configuration", () => {
  const { h } = pair();
  startNew(h);
  const a = h.last("A", "start")!;
  const b = h.last("B", "start")!;
  assert.deepEqual(a, b);
  assert.equal(a.mode, "new");
  assert.equal(a.saveData, null);
  assert.deepEqual(a.area, { stage: 0, room: 0 });
  assert.equal(h.last("A", "session")!.status, "running");
});

test("ready needs a choice, and a changed choice cancels the agreement", () => {
  const { h } = pair();
  assert.equal(h.errorCode(h.send("A", { type: "set_ready", ready: true }), "A"), "wrong_state");
  h.send("A", { type: "select_start", mode: "new" });
  h.send("B", { type: "select_start", mode: "new" });
  h.send("A", { type: "set_ready", ready: true });
  h.send("B", { type: "select_start", mode: "new" }); // chooses again: A's readiness is gone
  const info = h.last("A", "session")!;
  assert.equal(info.players.every((p) => !p.ready), true);
  assert.equal(h.last("A", "start"), undefined);
});

test("a shared save point: the host offers it, the guest must pick the same one, both get the same bytes", () => {
  const { h } = pair();
  h.send("A", { type: "select_start", mode: "save", saveId: "s1" }); // not offered yet
  assert.equal(h.last("A", "session")!.players[0].selection, null);
  h.send("A", { type: "upload_save", saveId: "s1", label: "Slot 1", data: "QUJDREVGRw==" });
  assert.equal(h.last("B", "session")!.offeredSave!.saveId, "s1");
  const wrong = h.send("B", { type: "select_start", mode: "save", saveId: "other" });
  assert.equal(h.errorCode(wrong, "B"), "save_missing");
  h.send("A", { type: "select_start", mode: "save", saveId: "s1" });
  h.send("B", { type: "select_start", mode: "save", saveId: "s1" });
  assert.equal(h.last("A", "session")!.selectionsMatch, true);
  h.send("A", { type: "set_ready", ready: true });
  h.send("B", { type: "set_ready", ready: true });
  const a = h.last("A", "start")!;
  const b = h.last("B", "start")!;
  assert.equal(a.saveId, "s1");
  assert.equal(a.saveData, "QUJDREVGRw==");
  assert.deepEqual(a, b);
});

test("only the host can offer a save point; a new offer resets the save choices", () => {
  const { h } = pair();
  assert.equal(h.errorCode(h.send("B", { type: "upload_save", saveId: "s1", data: "AAAA" }), "B"), "not_host");
  h.send("A", { type: "upload_save", saveId: "s1", data: "AAAA" });
  h.send("A", { type: "select_start", mode: "save", saveId: "s1" });
  h.send("B", { type: "select_start", mode: "save", saveId: "s1" });
  h.send("A", { type: "upload_save", saveId: "s2", data: "BBBB" });
  const info = h.last("A", "session")!;
  assert.equal(info.players.every((p) => p.selection === null && !p.ready), true);
  assert.equal(info.offeredSave!.saveId, "s2");
});

test("nothing can be changed once the game runs", () => {
  const { h } = pair();
  startNew(h);
  assert.equal(h.errorCode(h.send("A", { type: "select_start", mode: "new" }), "A"), "wrong_state");
  assert.equal(h.errorCode(h.send("A", { type: "upload_save", saveId: "s1", data: "AAAA" }), "A"), "wrong_state");
  assert.equal(h.errorCode(h.send("A", { type: "set_ready", ready: false }), "A"), "wrong_state");
});

// ------------------------------------------------------------------------------------------ states

test("a running session cannot accept another player", () => {
  const { h, name } = pair();
  startNew(h);
  h.connect("C");
  assert.equal(h.errorCode(h.send("C", { type: "join_session", name }), "C"), "session_not_joinable");
});

test("each hero's state goes to the other player only", () => {
  const { h } = pair();
  startNew(h);
  const out = h.send("A", stateOf("carnby", 1, 3300, -1500));
  assert.equal(out.length, 1);
  assert.equal(out[0].to, "B");
  const r = h.last("B", "remote_state")!;
  assert.equal(r.hero, "carnby");
  assert.equal(r.x, 3300);
  assert.equal(r.seq, 1);
  assert.equal(h.last("A", "remote_state"), undefined);
  h.send("B", stateOf("emily", 1, 3000, -1000));
  assert.equal(h.last("A", "remote_state")!.hero, "emily");
});

test("a client cannot move the other player's hero", () => {
  const { h } = pair();
  startNew(h);
  assert.equal(h.errorCode(h.send("A", stateOf("emily", 1)), "A"), "wrong_hero");
  assert.equal(h.errorCode(h.send("B", stateOf("carnby", 1)), "B"), "wrong_hero");
  assert.equal(h.last("B", "remote_state"), undefined);
  assert.equal(h.last("A", "remote_state"), undefined);
});

test("states are refused before the game runs, outside the supported room, and when they jump", () => {
  const { h } = pair();
  assert.equal(h.errorCode(h.send("A", stateOf("carnby", 1)), "A"), "wrong_state");
  startNew(h);
  assert.equal(h.errorCode(h.send("A", stateOf("carnby", 1, 3231, -1548, 3)), "A"), "area_not_allowed");
  h.send("A", stateOf("carnby", 2, 3231, -1548));
  h.time += 100;
  assert.equal(h.errorCode(h.send("A", stateOf("carnby", 3, 60000, -1548)), "A"), "implausible_state");
  h.time += 5000; // after a long pause the same distance is fine
  assert.equal(h.errorCode(h.send("A", stateOf("carnby", 4, 3231 + 20000, -1548)), "A"), undefined);
});

test("old and repeated states are dropped silently", () => {
  const { h } = pair();
  startNew(h);
  assert.equal(h.send("A", stateOf("carnby", 5)).length, 1);
  assert.equal(h.send("A", stateOf("carnby", 5)).length, 0);
  assert.equal(h.send("A", stateOf("carnby", 4)).length, 0);
  assert.equal(h.send("A", stateOf("carnby", 6)).length, 1);
});

test("a player who sends nothing is reported as silent, and as active again when states come back", () => {
  const { h } = pair();
  startNew(h);
  h.send("A", stateOf("carnby", 1));
  h.time += 6000;
  h.send("A", stateOf("carnby", 2)); // A talks, B does not
  const out = h.tick();
  const silent = out.find((o) => o.msg.type === "peer" && o.msg.state === "silent");
  assert.equal(silent?.to, "A"); // A is told that Emily is silent
  assert.equal(h.tick().some((o) => o.msg.type === "peer"), false); // only once
  h.send("B", stateOf("emily", 1));
  const active = h.last("A", "peer")!;
  assert.equal(active.state, "active");
});

// ------------------------------------------------------------------------------------------ leaving and failing

test("the host leaves a session that waits: it is gone", () => {
  const h = new Harness();
  h.connect("A"); h.connect("B");
  h.send("A", { type: "create_session" });
  const name = h.last("A", "joined")!.name;
  h.drop("A");
  assert.equal(h.m.sessionCount, 0);
  assert.equal(h.errorCode(h.send("B", { type: "join_session", name }), "B"), "unknown_session");
});

test("the second player leaves before the start: the session waits again, choices are reset", () => {
  const { h, name } = pair();
  h.send("A", { type: "select_start", mode: "new" });
  h.send("B", { type: "select_start", mode: "new" });
  h.send("B", { type: "leave_session" });
  const info = h.last("A", "session")!;
  assert.equal(info.status, "waiting");
  assert.equal(info.players.length, 1);
  assert.equal(info.players[0].selection, null);
  assert.equal(h.last("A", "peer")!.state, "disconnected");
  h.connect("C");
  h.send("C", { type: "join_session", name });
  assert.equal(h.last("C", "joined")!.hero, "emily");
});

test("the host leaves before the start: the guest is told and is free to go on", () => {
  const { h } = pair();
  h.drop("A");
  assert.equal(h.last("B", "closed")?.reason, "the host left the session");
  assert.equal(h.m.sessionCount, 0);
  assert.equal(h.errorCode(h.send("B", { type: "leave_session" }), "B"), "not_in_session");
  h.send("B", { type: "create_session" }); // can start over
  assert.equal(h.last("B", "joined")!.slot, 1);
});

test("a disconnect while the game runs interrupts it; nobody can take the place", () => {
  const { h, name } = pair();
  startNew(h);
  h.drop("B");
  assert.equal(h.last("A", "peer")!.state, "disconnected");
  assert.equal(h.last("A", "session")!.status, "interrupted");
  h.connect("C");
  assert.equal(h.errorCode(h.send("C", { type: "join_session", name }), "C"), "session_not_joinable");
  // the remaining player may not send states into a dead game
  assert.equal(h.errorCode(h.send("A", stateOf("carnby", 1)), "A"), "wrong_state");
  // and leaves; the session is gone
  h.send("A", { type: "leave_session" });
  assert.equal(h.m.sessionCount, 0);
});

test("an interrupted session ends when both are gone, and by time", () => {
  const { h } = pair();
  startNew(h);
  h.drop("A");
  h.drop("B");
  assert.equal(h.m.sessionCount, 0);
  const g = pair(new Harness());
  startNew(g.h);
  g.h.drop("B");
  g.h.time += 3 * 60 * 1000;
  const out = g.h.tick();
  assert.equal(out.some((o) => o.msg.type === "closed"), true);
  assert.equal(g.h.m.sessionCount, 0);
});

test("idle sessions expire", () => {
  const h = new Harness();
  h.connect("A");
  h.send("A", { type: "create_session" });
  h.time += 31 * 60 * 1000;
  const out = h.tick();
  assert.equal(out.some((o) => o.to === "A" && o.msg.type === "closed"), true);
  assert.equal(h.m.sessionCount, 0);
});

test("a server restart has no sessions: nothing is restored", () => {
  const first = pair();
  startNew(first.h);
  // a restarted server is a new manager
  const restarted = new Harness();
  restarted.connect("A");
  assert.equal(restarted.errorCode(restarted.send("A", { type: "join_session", name: first.name }), "A"), "unknown_session");
  assert.equal(restarted.errorCode(restarted.send("A", stateOf("carnby", 1)), "A"), "not_in_session");
});

test("session names stay unique among active sessions", () => {
  const h = new Harness();
  const names = new Set<string>();
  for (let i = 0; i < 300; i++) {
    h.connect("c" + i);
    h.send("c" + i, { type: "create_session" });
    names.add(nameKey(h.last("c" + i, "joined")!.name));
  }
  assert.equal(names.size, 300);
});

test("a taken item goes to the other player once, not back to the sender", () => {
  const { h } = pair();
  startNew(h);
  const out = h.send("A", { type: "item_taken", obj: 12 });
  assert.equal(out.length, 1);
  assert.equal(out[0].to, "B");
  const r = h.last("B", "item_taken")!;
  assert.equal(r.obj, 12);
  assert.equal(r.hero, "carnby");
  assert.equal(h.last("A", "item_taken"), undefined);
  // the same item again (both took it at the same moment): nothing more is relayed
  assert.equal(h.send("B", { type: "item_taken", obj: 12 }).length, 0);
});

test("a taken item is refused before the game runs", () => {
  const { h } = pair();
  assert.equal(h.errorCode(h.send("A", { type: "item_taken", obj: 3 }), "A"), "wrong_state");
});

test("a pushed object goes to the other player only, as it is", () => {
  const { h } = pair();
  startNew(h);
  const out = h.send("B", { type: "object_moved", obj: 4, x: 2100, y: 0, z: 4500 });
  assert.equal(out.length, 1);
  assert.equal(out[0].to, "A");
  const r = h.last("A", "object_moved")!;
  assert.deepEqual([r.hero, r.obj, r.x, r.z], ["emily", 4, 2100, 4500]);
  assert.equal(h.last("B", "object_moved"), undefined);
});

test("a pushed object is refused before the game runs", () => {
  const { h } = pair();
  assert.equal(h.errorCode(h.send("A", { type: "object_moved", obj: 4, x: 0, y: 0, z: 0 }), "A"), "wrong_state");
});

test("a placed item goes to the other player and can be taken (and relayed) again", () => {
  const { h } = pair();
  startNew(h);
  h.send("A", { type: "item_taken", obj: 12 });
  const out = h.send("A", { type: "item_placed", obj: 12, stage: 0, room: 0, x: 2000, y: 0, z: 3000, alpha: 0, beta: 512, gamma: 0 });
  assert.equal(out.length, 1);
  assert.equal(out[0].to, "B");
  const r = h.last("B", "item_placed")!;
  assert.deepEqual([r.hero, r.obj, r.x, r.z, r.beta], ["carnby", 12, 2000, 3000, 512]);
  assert.equal(h.last("A", "item_placed"), undefined);
  // after it was put down, taking it again is news again
  assert.equal(h.send("B", { type: "item_taken", obj: 12 }).length, 1);
});

test("a placed item is refused before the game runs", () => {
  const { h } = pair();
  assert.equal(h.errorCode(h.send("A", { type: "item_placed", obj: 1, stage: 0, room: 0, x: 0, y: 0, z: 0, alpha: 0, beta: 0, gamma: 0 }), "A"), "wrong_state");
});
