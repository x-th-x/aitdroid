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

test("an interrupted session ends when both left on purpose, and by time", () => {
  const { h } = pair();
  startNew(h);
  h.send("A", { type: "leave_session" });
  h.send("B", { type: "leave_session" });
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
  const h = new Harness({ maxSessions: 1000 });
  const names = new Set<string>();
  for (let i = 0; i < 300; i++) {
    h.connect("c" + i);
    h.send("c" + i, { type: "create_session" });
    names.add(nameKey(h.last("c" + i, "joined")!.name));
  }
  assert.equal(names.size, 300);
});

test("a taken item goes to the other player, not back to the sender", () => {
  const { h } = pair();
  startNew(h);
  const out = h.send("A", { type: "item_taken", obj: 12 });
  assert.equal(out.length, 1);
  assert.equal(out[0].to, "B");
  const r = h.last("B", "item_taken")!;
  assert.equal(r.obj, 12);
  assert.equal(r.hero, "carnby");
  assert.equal(h.last("A", "item_taken"), undefined);
  // the other player took it at the same moment: that is told too (each phone decides what to do with it)
  assert.equal(h.send("B", { type: "item_taken", obj: 12 }).length, 1);
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

const prop = (obj: number) => ({ obj, stage: 0, room: 0, active: true, body: 24, anim: 23, alpha: 0, x: 100, y: 0, z: 200, beta: 300 });

test("the host's creatures and the trapdoor go to the guest; the guest may not send them", () => {
  const { h } = pair();
  startNew(h);
  const out = h.send("A", { type: "props", items: [prop(21), prop(9), prop(8)] });
  assert.equal(out.length, 1);
  assert.equal(out[0].to, "B");
  assert.equal(h.last("B", "props")!.items.length, 3);
  assert.equal(h.errorCode(h.send("B", { type: "props", items: [prop(21)] }), "B"), "not_host");
});

test("the horse and the window may change on either phone", () => {
  const { h } = pair();
  startNew(h);
  h.send("B", { type: "props", items: [prop(6), prop(7)] });
  assert.equal(h.last("A", "props")!.items[0].obj, 6);
  h.send("A", { type: "props", items: [prop(7)] });
  assert.equal(h.last("B", "props")!.items[0].obj, 7);
});

test("a creature hit goes from the guest to the host only; a hurt from the host to the guest only", () => {
  const { h } = pair();
  startNew(h);
  const out = h.send("B", { type: "creature_hit", obj: 21, force: 5 });
  assert.equal(out[0].to, "A");
  assert.equal(h.last("A", "creature_hit")!.force, 5);
  assert.equal(h.errorCode(h.send("A", { type: "creature_hit", obj: 21, force: 5 }), "A"), "wrong_hero");
  h.send("A", { type: "hurt", force: 1, by: 21 });
  assert.equal(h.last("B", "hurt")!.by, 21);
  assert.equal(h.errorCode(h.send("B", { type: "hurt", force: 1, by: 21 }), "B"), "not_host");
});

test("a death is told to the other player, from either side", () => {
  const { h } = pair();
  startNew(h);
  h.send("B", { type: "hero_died" });
  assert.equal(h.last("A", "hero_died")!.hero, "emily");
  assert.equal(h.last("A", "hero_died")!.cause, 0);
  h.send("A", { type: "hero_died", cause: 1, by: 21 });
  assert.equal(h.last("B", "hero_died")!.hero, "carnby");
  assert.equal(h.last("B", "hero_died")!.cause, 1);
  assert.equal(h.last("B", "hero_died")!.by, 21);
});

test("the end of the game is told to the other player, from either side", () => {
  const { h } = pair();
  startNew(h);
  h.send("A", { type: "game_end" });
  assert.equal(h.last("B", "game_end")!.hero, "carnby");
  h.send("B", { type: "game_end" });
  assert.equal(h.last("A", "game_end")!.hero, "emily");
});

test("an item that was used up is told to the other player, from either side, and only while the game runs", () => {
  const { h } = pair();
  assert.equal(h.errorCode(h.send("A", { type: "item_gone", obj: 12 }), "A"), "wrong_state");
  startNew(h);
  h.send("A", { type: "item_gone", obj: 12 });
  assert.equal(h.last("B", "item_gone")!.obj, 12);
  assert.equal(h.last("B", "item_gone")!.hero, "carnby");
  h.send("B", { type: "item_gone", obj: 40 });
  assert.equal(h.last("A", "item_gone")!.hero, "emily");
  assert.equal(h.last("A", "item_gone")!.obj, 40);
});

test("props, creature hits, hurts and deaths are refused before the game runs", () => {
  const { h } = pair();
  assert.equal(h.errorCode(h.send("A", { type: "props", items: [prop(6)] }), "A"), "wrong_state");
  assert.equal(h.errorCode(h.send("A", { type: "hero_died" }), "A"), "wrong_state");
  assert.equal(h.errorCode(h.send("A", { type: "game_end" }), "A"), "wrong_state");
});

test("the host's effects and shared variables go to the guest; the guest may not send them", () => {
  const { h } = pair();
  startNew(h);
  h.send("A", { type: "effect", kind: 4, a: 9, b: 0 });
  assert.equal(h.last("B", "effect")!.a, 9);
  h.send("A", { type: "world_var", index: 20, value: 1 });
  assert.equal(h.last("B", "world_var")!.value, 1);
  assert.equal(h.errorCode(h.send("B", { type: "effect", kind: 0, a: 1, b: 0 }), "B"), "not_host");
  assert.equal(h.errorCode(h.send("B", { type: "world_var", index: 20, value: 1 }), "B"), "not_host");
});

test("the host's world snapshot goes to the guest; the guest may not send one", () => {
  const { h } = pair();
  startNew(h);
  const entry = { obj: 12, stage: 0, room: 0, state: 1, x: 10, y: 0, z: 20, beta: 5 };
  h.send("A", { type: "world_snapshot", items: [entry] });
  assert.equal(h.last("B", "world_snapshot")!.items[0].obj, 12);
  assert.equal(h.errorCode(h.send("B", { type: "world_snapshot", items: [entry] }), "B"), "not_host");
});

function tokenOf(h: Harness, id: string): string {
  return h.last(id, "joined")!.token;
}

test("a player whose connection dropped takes his place again with his token", () => {
  const { h, name } = pair();
  startNew(h);
  const tokenB = tokenOf(h, "B");
  h.drop("B");
  assert.equal(h.last("A", "session")!.status, "interrupted");
  h.connect("B2");
  h.send("B2", { type: "resume", name, token: tokenB });
  assert.equal(h.last("B2", "joined")!.hero, "emily");
  assert.equal(h.last("A", "peer")!.state, "connected");
  assert.equal(h.last("A", "session")!.status, "running");
  // the state of the returned hero goes to the other phone again, with a fresh counter
  h.send("B2", stateOf("emily", 1, 3000, -1000));
  assert.equal(h.last("A", "remote_state")!.seq, 1);
});

test("a wrong token, a player who left on purpose, and a waiting session cannot resume", () => {
  const { h, name } = pair();
  startNew(h);
  const tokenB = tokenOf(h, "B");
  h.drop("B");
  h.connect("X");
  assert.equal(h.errorCode(h.send("X", { type: "resume", name, token: "not-the-token-0000" }), "X"), "bad_token");
  h.send("A", { type: "leave_session" });
  h.connect("B3");
  assert.notEqual(h.errorCode(h.send("B3", { type: "resume", name, token: tokenB }), "B3"), undefined);
});

test("both players may drop at the same time and come back", () => {
  const { h, name } = pair();
  startNew(h);
  const tokenA = tokenOf(h, "A");
  const tokenB = tokenOf(h, "B");
  h.drop("A");
  h.drop("B");
  assert.equal(h.m.sessionCount, 1);
  h.connect("A2");
  h.connect("B2");
  h.send("A2", { type: "resume", name, token: tokenA });
  assert.equal(h.last("A2", "session")!.status, "interrupted");
  h.send("B2", { type: "resume", name, token: tokenB });
  assert.equal(h.last("B2", "session")!.status, "running");
  assert.equal(h.last("A2", "session")!.status, "running");
});

test("a new connection replaces a half dead one of the same player", () => {
  const { h, name } = pair();
  startNew(h);
  const tokenB = tokenOf(h, "B");
  h.connect("B2");
  h.send("B2", { type: "resume", name, token: tokenB });
  assert.equal(h.last("B", "closed")!.reason.includes("again"), true);
  h.send("B2", stateOf("emily", 1, 3000, -1000));
  assert.equal(h.last("A", "remote_state")!.hero, "emily");
  // the old connection closing later changes nothing
  h.drop("B");
  assert.equal(h.last("A", "session")!.status, "running");
});

test("an interrupted game that nobody resumes is closed after the keep time", () => {
  const { h } = pair();
  startNew(h);
  h.drop("A");
  h.drop("B");
  h.time += 3 * 60 * 1000;
  h.tick();
  assert.equal(h.m.sessionCount, 0);
});

test("a latched variable goes to the other player, from either side", () => {
  const { h } = pair();
  startNew(h);
  h.send("B", { type: "latch", index: 74, value: 1, old: 0 });
  assert.equal(h.last("A", "latch")!.index, 74);
  h.send("A", { type: "latch", index: 16, value: 5, old: 0 });
  assert.equal(h.last("B", "latch")!.value, 5);
  assert.equal(h.errorCode(h.send("A", { type: "latch", index: 5, value: 1, old: 0 }), "A") === undefined, true);
});

test("any room is allowed when the server is set to all areas, and a change of room is no jump", () => {
  const h = new Harness({ allAreas: true });
  h.connect("A"); h.connect("B");
  h.send("A", { type: "create_session" });
  const name = h.last("A", "joined")!.name;
  h.send("B", { type: "join_session", name });
  h.send("A", { type: "select_start", mode: "new" });
  h.send("B", { type: "select_start", mode: "new" });
  h.send("A", { type: "set_ready", ready: true });
  h.send("B", { type: "set_ready", ready: true });
  assert.equal(h.errorCode(h.send("A", { ...stateOf("carnby", 1, 100, 100), stage: 1, room: 6 }), "A"), undefined);
  // the next room has other coordinates: far from the last place, and fine
  assert.equal(h.errorCode(h.send("A", { ...stateOf("carnby", 2, 90000, 90000), stage: 1, room: 7 }), "A"), undefined);
  // inside one room the speed rule still holds
  assert.equal(h.errorCode(h.send("A", { ...stateOf("carnby", 3, -90000, -90000), stage: 1, room: 7 }), "A"), "implausible_state");
});

test("a hero's sound goes to the other player, both ways", () => {
  const { h } = pair();
  startNew(h);
  h.send("A", { type: "sound", n: 37, then: 12, loop: true });
  assert.equal(h.last("B", "sound")!.n, 37);
  assert.equal(h.last("B", "sound")!.then, 12);
  h.send("B", { type: "sound", n: 5, then: -1, loop: false });
  assert.equal(h.last("A", "sound")!.n, 5);
  assert.equal(h.last("A", "sound")!.n === 37, false);
});

test("a latch carries the value it replaces", () => {
  const { h } = pair();
  startNew(h);
  h.send("A", { type: "latch", index: 80, value: 2, old: 1 });
  const l = h.last("B", "latch")!;
  assert.deepEqual([l.index, l.value, l.old], [80, 2, 1]);
});

test("somebody who leaves on purpose is told as left, a lost connection as disconnected", () => {
  const a = pair();
  startNew(a.h);
  a.h.send("B", { type: "leave_session" });
  assert.equal(a.h.last("A", "peer")!.state, "left");
  const b = pair();
  startNew(b.h);
  b.h.drop("B");
  assert.equal(b.h.last("A", "peer")!.state, "disconnected");
});

test("the server takes only so many sessions", () => {
  const h = new Harness({ maxSessions: 1 });
  h.connect("A"); h.connect("B");
  h.send("A", { type: "create_session" });
  assert.equal(h.errorCode(h.send("B", { type: "create_session" }), "B"), "server_busy");
});
