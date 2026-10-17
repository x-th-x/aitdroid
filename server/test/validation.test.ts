import { test } from "node:test";
import assert from "node:assert/strict";
import { parseClientMessage } from "../src/protocol";

const state = { type: "state", seq: 1, t: 1000, hero: "carnby", stage: 0, room: 0, x: 3231, y: 0, z: -1548, beta: 512, anim: 4 };

function bad(text: string, code?: string) {
  const r = parseClientMessage(text);
  assert.equal(r.ok, false, text);
  if (!r.ok && code) assert.equal(r.code, code, text);
}

test("valid messages are accepted", () => {
  for (const m of [{ type: "list_sessions" }, { type: "create_session" }, { type: "leave_session" }, { type: "join_session", name: "Raven-27" }, { type: "select_start", mode: "new" }, { type: "select_start", mode: "save", saveId: "slot1_abc" }, { type: "set_ready", ready: true }, { type: "ping", t: 5 }, state, { type: "upload_save", saveId: "s1", label: "Slot 1", data: "AAECAw==" }]) {
    const r = parseClientMessage(JSON.stringify(m));
    assert.equal(r.ok, true, JSON.stringify(m));
  }
});

test("malformed input is refused without throwing", () => {
  bad("not json", "bad_message");
  bad("[]", "bad_message");
  bad("null", "bad_message");
  bad("42", "bad_message");
  bad("{}", "bad_message");
  bad(JSON.stringify({ type: 5 }), "bad_message");
  bad(JSON.stringify({ type: "fly_to_the_moon" }), "unknown_type");
});

test("fields are checked", () => {
  bad(JSON.stringify({ type: "join_session" }), "bad_message");
  bad(JSON.stringify({ type: "join_session", name: "" }), "bad_message");
  bad(JSON.stringify({ type: "join_session", name: "x".repeat(33) }), "bad_message");
  bad(JSON.stringify({ type: "select_start", mode: "cheat" }), "bad_message");
  bad(JSON.stringify({ type: "select_start", mode: "save" }), "bad_message");
  bad(JSON.stringify({ type: "select_start", mode: "save", saveId: "../../etc/passwd" }), "bad_message");
  bad(JSON.stringify({ type: "set_ready", ready: "yes" }), "bad_message");
  bad(JSON.stringify({ type: "ping", t: -1 }), "bad_message");
});

test("states are checked field by field", () => {
  bad(JSON.stringify({ ...state, seq: -1 }), "bad_message");
  bad(JSON.stringify({ ...state, seq: 1.5 }), "bad_message");
  bad(JSON.stringify({ ...state, seq: "1" }), "bad_message");
  bad(JSON.stringify({ ...state, hero: "zombie" }), "bad_message");
  bad(JSON.stringify({ ...state, x: 1e9 }), "bad_message");
  bad(JSON.stringify({ ...state, z: null }), "bad_message");
  bad(JSON.stringify({ ...state, beta: 1024 }), "bad_message");
  bad(JSON.stringify({ ...state, beta: -1 }), "bad_message");
  bad(JSON.stringify({ ...state, anim: 99999 }), "bad_message");
  bad(JSON.stringify({ ...state, stage: 300 }), "bad_message");
  const missing: Record<string, unknown> = { ...state };
  delete missing.room;
  bad(JSON.stringify(missing), "bad_message");
});

test("a save point has to be base64 and not too big", () => {
  bad(JSON.stringify({ type: "upload_save", saveId: "s1", data: "not base64!!" }), "bad_message");
  bad(JSON.stringify({ type: "upload_save", saveId: "s1", data: "" }), "bad_message");
  bad(JSON.stringify({ type: "upload_save", saveId: "s1", data: "A".repeat(400_001) }), "save_too_large");
  bad(JSON.stringify({ type: "upload_save", saveId: "s1", data: "AAAA", label: 5 }), "bad_message");
});

test("extra fields are dropped, not passed on", () => {
  const r = parseClientMessage(JSON.stringify({ ...state, admin: true, hero: "carnby" }));
  assert.equal(r.ok, true);
  if (r.ok) assert.equal("admin" in r.msg, false);
});

test("item_taken needs an object number in range", () => {
  assert.equal(parseClientMessage('{"type":"item_taken","obj":5}').ok, true);
  assert.equal(parseClientMessage('{"type":"item_taken","obj":-1}').ok, false);
  assert.equal(parseClientMessage('{"type":"item_taken","obj":1.5}').ok, false);
  assert.equal(parseClientMessage('{"type":"item_taken"}').ok, false);
});

test("object_moved needs an object number and whole coordinates", () => {
  assert.equal(parseClientMessage('{"type":"object_moved","obj":4,"x":10,"y":0,"z":-5}').ok, true);
  assert.equal(parseClientMessage('{"type":"object_moved","obj":4,"x":10,"y":0}').ok, false);
  assert.equal(parseClientMessage('{"type":"object_moved","obj":4,"x":1e9,"y":0,"z":0}').ok, false);
  assert.equal(parseClientMessage('{"type":"object_moved","obj":-1,"x":1,"y":0,"z":0}').ok, false);
});

test("item_placed needs a place and angles in range", () => {
  const ok = '{"type":"item_placed","obj":3,"stage":0,"room":0,"x":1,"y":0,"z":2,"alpha":0,"beta":100,"gamma":0}';
  assert.equal(parseClientMessage(ok).ok, true);
  assert.equal(parseClientMessage(ok.replace('"beta":100', '"beta":2000')).ok, false);
  assert.equal(parseClientMessage(ok.replace('"room":0,', '')).ok, false);
});

test("props, creature_hit, hurt and hero_died are checked", () => {
  const item = '{"obj":9,"stage":0,"room":0,"active":true,"body":24,"anim":23,"alpha":0,"x":1,"y":0,"z":2,"beta":5}';
  assert.equal(parseClientMessage('{"type":"props","items":[' + item + ']}').ok, true);
  assert.equal(parseClientMessage('{"type":"props","items":[]}').ok, false);
  assert.equal(parseClientMessage('{"type":"props","items":[' + Array(9).fill(item).join(",") + ']}').ok, false);
  assert.equal(parseClientMessage('{"type":"props","items":[' + item.replace('"active":true', '"active":1') + ']}').ok, false);
  assert.equal(parseClientMessage('{"type":"creature_hit","obj":21,"force":5}').ok, true);
  assert.equal(parseClientMessage('{"type":"creature_hit","obj":4,"force":5}').ok, false);
  assert.equal(parseClientMessage('{"type":"creature_hit","obj":21,"force":500}').ok, false);
  assert.equal(parseClientMessage('{"type":"hurt","force":1,"by":21}').ok, true);
  assert.equal(parseClientMessage('{"type":"hurt","force":-1,"by":21}').ok, false);
  assert.equal(parseClientMessage('{"type":"hero_died"}').ok, true);
});

test("effect and world_var are checked", () => {
  assert.equal(parseClientMessage('{"type":"effect","kind":2,"a":1,"b":-1}').ok, true);
  assert.equal(parseClientMessage('{"type":"effect","kind":9,"a":1,"b":-1}').ok, false);
  assert.equal(parseClientMessage('{"type":"world_var","index":20,"value":2}').ok, true);
  assert.equal(parseClientMessage('{"type":"world_var","index":21,"value":2}').ok, false);
  assert.equal(parseClientMessage('{"type":"world_var","index":20,"value":99999}').ok, false);
});

test("world_snapshot is checked", () => {
  const e = '{"obj":12,"stage":0,"room":0,"state":1,"x":1,"y":0,"z":2,"beta":5}';
  assert.equal(parseClientMessage('{"type":"world_snapshot","items":[' + e + ']}').ok, true);
  assert.equal(parseClientMessage('{"type":"world_snapshot","items":[]}').ok, false);
  assert.equal(parseClientMessage('{"type":"world_snapshot","items":[' + e.replace('"state":1', '"state":3') + ']}').ok, false);
  assert.equal(parseClientMessage('{"type":"world_snapshot","items":[' + Array(41).fill(e).join(",") + ']}').ok, false);
});

test("resume needs a name and a token", () => {
  assert.equal(parseClientMessage('{"type":"resume","name":"Raven-27","token":"123e4567-e89b-12d3-a456-426614174000"}').ok, true);
  assert.equal(parseClientMessage('{"type":"resume","name":"Raven-27","token":"short"}').ok, false);
  assert.equal(parseClientMessage('{"type":"resume","token":"123e4567-e89b-12d3-a456-426614174000"}').ok, false);
});

test("latch needs a variable number and a value other than 0", () => {
  assert.equal(parseClientMessage('{"type":"latch","index":74,"value":1}').ok, true);
  assert.equal(parseClientMessage('{"type":"latch","index":74,"value":0}').ok, false);
  assert.equal(parseClientMessage('{"type":"latch","index":300,"value":1}').ok, false);
});
