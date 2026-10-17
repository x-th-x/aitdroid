// The WebSocket protocol between the Android clients and the server: JSON text messages, each with a "type".
// Everything a client sends is validated here before the session logic sees it (types, ranges, lengths); nothing is trusted.

export const PROTOCOL_VERSION = 1;

export type Hero = "carnby" | "emily";
export type StartMode = "new" | "save";
export type SessionStatus = "waiting" | "ready" | "running" | "interrupted";

/** A place of the game the prototype supports (stage and room of the original game's room list; the attic is stage 0, room 0). */
export interface Area {
  stage: number;
  room: number;
}

// ------------------------------------------------------------------------------------------------ client -> server

export interface ListSessions { type: "list_sessions" }
export interface CreateSession { type: "create_session" }
export interface JoinSession { type: "join_session"; name: string }
export interface LeaveSession { type: "leave_session" }
/** A player whose connection dropped in a running game takes his place again with the token he got when he joined (within two minutes). */
export interface Resume { type: "resume"; name: string; token: string }
export interface SelectStart { type: "select_start"; mode: StartMode; saveId?: string }
export interface UploadSave { type: "upload_save"; saveId: string; label?: string; data: string }
export interface SetReady { type: "set_ready"; ready: boolean }
/**
 * The state of the sender's own hero, in the engine's own units: room-relative coordinates (the same x, y, z the game keeps in Actor.renderX/Y/Z; 1000 units are
 * about one metre), heading in 1/1024 turns (Actor.beta & 0x3FF), the current animation number of the hero's animation list.
 */
export interface StateMsg {
  type: "state";
  seq: number;
  t: number;
  hero: Hero;
  stage: number;
  room: number;
  x: number;
  y: number;
  z: number;
  beta: number;
  anim: number;
}
export interface Ping { type: "ping"; t: number }
/** The sender took an item (the number of the item's object in the game) out of the shared world: the other phone marks it as gone, but does not put it into its own bag. */
export interface ItemTaken { type: "item_taken"; obj: number }

/** The sender pushed a piece of furniture (an object of the game) to a new place, in the engine's room-relative coordinates. The last push wins on both phones. */
export interface ObjectMoved { type: "object_moved"; obj: number; x: number; y: number; z: number }

/** The sender put an item down (dropped it, or a script placed it): it lies at this place in the shared world now and can be picked up by both. */
export interface ItemPlaced { type: "item_placed"; obj: number; stage: number; room: number; x: number; y: number; z: number; alpha: number; beta: number; gamma: number }

/** The state of one prop or creature of the attic (a game object): where it is, which body and animation it shows. */
export interface PropState { obj: number; stage: number; room: number; active: boolean; body: number; anim: number; alpha: number; x: number; y: number; z: number; beta: number }
/** The creatures and the trapdoor lid are run by the host's phone only; the guest's phone shows them as puppets of what the host sends. The horse and the window may change on either phone. */
export const HOST_OBJECTS: readonly number[] = [8, 9, 21];
/** The creatures of the attic: the guest's blows and shots at them go to the host. */
export const CREATURE_OBJECTS: readonly number[] = [9, 21];
export interface Props { type: "props"; items: PropState[] }
/** The guest hit a creature (which the host runs): with this force. */
export interface CreatureHit { type: "creature_hit"; obj: number; force: number }
/** The host's creature hit the guest's hero. */
export interface Hurt { type: "hurt"; force: number; by: number }
/** The sender's hero died: the game is over for both. */
export interface HeroDied { type: "hero_died" }

/**
 * What the host's creatures and trapdoor do that the guest should see and hear: kind 0 sample (a), 1 sample then another (a, b), 2 music (a), 3 next music (a),
 * 4 camera target (a: the object the camera follows), 5 random frequency of samples (a), 6 sample then another, repeated (a, b).
 */
export interface Effect { type: "effect"; kind: number; a: number; b: number }
/** A variable of the game that the creatures' scripts keep (the count of creatures, variable 20) and the host's value of it. */
export interface WorldVar { type: "world_var"; index: number; value: number }
export const SHARED_VARS: readonly number[] = [20];

/**
 * The host's view of the shared world, sent every ten seconds so that a difference between the two phones does not last: one entry per object that was ever taken, put
 * down or pushed. state: 0 in its place, 1 put down by a hero, 2 taken (in somebody's bag).
 */
export interface WorldEntry { obj: number; stage: number; room: number; state: number; x: number; y: number; z: number; beta: number }
export interface WorldSnapshot { type: "world_snapshot"; items: WorldEntry[] }

/**
 * A variable of the game that went from 0 to a value and stayed there (a door unlocked, a switch thrown): the other phone sets it too when it is still 0 there. Variables
 * that the player's own script keeps (his state) are never sent. Either player may send it.
 */
export interface Latch { type: "latch"; index: number; value: number; old: number }
/** A sound of the sender's hero (a footstep, a door, a find): the other phone plays it when it shows that hero in the same room. */
export interface HeroSound { type: "sound"; n: number }

export type ClientMessage = HeroSound | Latch | Resume | WorldSnapshot | Effect | WorldVar | Props | CreatureHit | Hurt | HeroDied | ItemPlaced | ObjectMoved | ListSessions | CreateSession | JoinSession | LeaveSession | SelectStart | UploadSave | SetReady | StateMsg | Ping | ItemTaken;

// ------------------------------------------------------------------------------------------------ server -> client

export interface PlayerInfo {
  slot: 1 | 2;
  hero: Hero;
  connected: boolean;
  selection: { mode: StartMode; saveId?: string } | null;
  ready: boolean;
}

export interface SessionInfo {
  sessionId: string;
  name: string;
  status: SessionStatus;
  players: PlayerInfo[];
  /** Both players chose, and chose the same: only then can they get ready. */
  selectionsMatch: boolean;
  /** Why the game cannot start yet (null when it can, or when nobody chose anything yet). */
  mismatch: string | null;
  offeredSave: { saveId: string; label: string; bytes: number } | null;
}

export type ServerMessage =
  | { type: "welcome"; protocol: number; serverTime: number; areas: Area[] }
  | { type: "sessions"; sessions: { name: string; status: SessionStatus; players: number }[] }
  | { type: "joined"; sessionId: string; name: string; slot: 1 | 2; hero: Hero; token: string }
  | ({ type: "session" } & SessionInfo)
  | { type: "start"; sessionId: string; mode: StartMode; saveId: string | null; saveData: string | null; area: Area; serverTime: number }
  | { type: "remote_state"; seq: number; t: number; serverTime: number; hero: Hero; stage: number; room: number; x: number; y: number; z: number; beta: number; anim: number }
  | { type: "item_taken"; hero: Hero; obj: number }
  | { type: "props"; hero: Hero; items: PropState[] }
  | { type: "latch"; index: number; value: number; old: number }
  | { type: "sound"; n: number }
  | { type: "world_snapshot"; items: WorldEntry[] }
  | { type: "effect"; kind: number; a: number; b: number }
  | { type: "world_var"; index: number; value: number }
  | { type: "creature_hit"; obj: number; force: number }
  | { type: "hurt"; force: number; by: number }
  | { type: "hero_died"; hero: Hero }
  | { type: "item_placed"; hero: Hero; obj: number; stage: number; room: number; x: number; y: number; z: number; alpha: number; beta: number; gamma: number }
  | { type: "object_moved"; hero: Hero; obj: number; x: number; y: number; z: number }
  | { type: "peer"; slot: 1 | 2; hero: Hero; state: "connected" | "disconnected" | "left" | "silent" | "active" }
  | { type: "closed"; reason: string }
  | { type: "error"; code: ErrorCode; message: string; requestType?: string }
  | { type: "pong"; t: number; serverTime: number };

export type ErrorCode =
  | "bad_message"
  | "unknown_type"
  | "rate_limited"
  | "not_in_session"
  | "already_in_session"
  | "unknown_session"
  | "session_full"
  | "session_not_joinable"
  | "wrong_state"
  | "not_host"
  | "wrong_hero"
  | "area_not_allowed"
  | "implausible_state"
  | "save_too_large"
  | "save_missing"
  | "selection_mismatch"
  | "bad_token"
  | "server_busy";

// ------------------------------------------------------------------------------------------------ validation

export interface Limits {
  maxCoord: number;
  maxSaveBase64: number;
  maxNameLength: number;
}

export const DEFAULT_LIMITS: Limits = { maxCoord: 500_000, maxSaveBase64: 400_000, maxNameLength: 32 };

export type ParseResult = { ok: true; msg: ClientMessage } | { ok: false; code: ErrorCode; message: string; requestType?: string };

const ID_PATTERN = /^[A-Za-z0-9_-]{1,64}$/;
const BASE64_PATTERN = /^[A-Za-z0-9+/]*={0,2}$/;

function fail(code: ErrorCode, message: string, requestType?: string): ParseResult {
  return { ok: false, code, message, requestType };
}

function isInt(v: unknown, min: number, max: number): v is number {
  return typeof v === "number" && Number.isInteger(v) && v >= min && v <= max;
}

function isNum(v: unknown, min: number, max: number): v is number {
  return typeof v === "number" && Number.isFinite(v) && v >= min && v <= max;
}

/** Parses and validates one text message of a client. Never throws. */
export function parseClientMessage(raw: string, limits: Limits = DEFAULT_LIMITS): ParseResult {
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch {
    return fail("bad_message", "the message is not valid JSON");
  }
  if (typeof value !== "object" || value === null || Array.isArray(value)) return fail("bad_message", "the message must be a JSON object");
  const m = value as Record<string, unknown>;
  const type = m.type;
  if (typeof type !== "string") return fail("bad_message", "the message has no type");
  switch (type) {
    case "list_sessions":
    case "create_session":
    case "leave_session":
      return { ok: true, msg: { type } };
    case "join_session": {
      if (typeof m.name !== "string" || m.name.trim().length < 1 || m.name.length > limits.maxNameLength) return fail("bad_message", "name must be a string of 1 to " + limits.maxNameLength + " characters", type);
      return { ok: true, msg: { type, name: m.name } };
    }
    case "resume": {
      if (typeof m.name !== "string" || m.name.trim().length < 1 || m.name.length > limits.maxNameLength) return fail("bad_message", "name must be a string of 1 to " + limits.maxNameLength + " characters", type);
      if (typeof m.token !== "string" || !/^[A-Za-z0-9-]{8,64}$/.test(m.token)) return fail("bad_message", "token is missing or malformed", type);
      return { ok: true, msg: { type, name: m.name, token: m.token } };
    }
    case "select_start": {
      if (m.mode !== "new" && m.mode !== "save") return fail("bad_message", "mode must be \"new\" or \"save\"", type);
      if (m.mode === "save") {
        if (typeof m.saveId !== "string" || !ID_PATTERN.test(m.saveId)) return fail("bad_message", "a save start needs a saveId (letters, digits, - and _, at most 64)", type);
        return { ok: true, msg: { type, mode: "save", saveId: m.saveId } };
      }
      return { ok: true, msg: { type, mode: "new" } };
    }
    case "upload_save": {
      if (typeof m.saveId !== "string" || !ID_PATTERN.test(m.saveId)) return fail("bad_message", "saveId is missing or malformed", type);
      if (typeof m.data !== "string" || m.data.length === 0) return fail("bad_message", "data (base64) is missing", type);
      if (m.data.length > limits.maxSaveBase64) return fail("save_too_large", "the save is larger than " + limits.maxSaveBase64 + " characters of base64", type);
      if (!BASE64_PATTERN.test(m.data)) return fail("bad_message", "data is not base64", type);
      let label = "";
      if (m.label !== undefined) {
        if (typeof m.label !== "string" || m.label.length > 64) return fail("bad_message", "label must be a string of at most 64 characters", type);
        label = m.label;
      }
      return { ok: true, msg: { type, saveId: m.saveId, label, data: m.data } };
    }
    case "set_ready": {
      if (typeof m.ready !== "boolean") return fail("bad_message", "ready must be true or false", type);
      return { ok: true, msg: { type, ready: m.ready } };
    }
    case "state": {
      if (!isInt(m.seq, 0, 2_000_000_000)) return fail("bad_message", "seq must be a non-negative integer", type);
      if (!isNum(m.t, 0, 1e15)) return fail("bad_message", "t must be a non-negative number (milliseconds)", type);
      if (m.hero !== "carnby" && m.hero !== "emily") return fail("bad_message", "hero must be \"carnby\" or \"emily\"", type);
      if (!isInt(m.stage, 0, 20) || !isInt(m.room, 0, 100)) return fail("bad_message", "stage and room must be small non-negative integers", type);
      const c = limits.maxCoord;
      if (!isInt(m.x, -c, c) || !isInt(m.y, -c, c) || !isInt(m.z, -c, c)) return fail("bad_message", "x, y and z must be integers within +-" + c, type);
      if (!isInt(m.beta, 0, 1023)) return fail("bad_message", "beta must be an integer from 0 to 1023", type);
      if (!isInt(m.anim, -1, 2000)) return fail("bad_message", "anim must be an integer from -1 to 2000", type);
      return { ok: true, msg: { type, seq: m.seq, t: m.t, hero: m.hero, stage: m.stage, room: m.room, x: m.x, y: m.y, z: m.z, beta: m.beta, anim: m.anim } };
    }
    case "item_taken": {
      if (!isInt(m.obj, 0, 1000)) return fail("bad_message", "obj must be an integer from 0 to 1000", type);
      return { ok: true, msg: { type, obj: m.obj } };
    }
    case "props": {
      if (!Array.isArray(m.items) || m.items.length < 1 || m.items.length > 8) return fail("bad_message", "items must be a list of 1 to 8 entries", type);
      const c = limits.maxCoord;
      const items: PropState[] = [];
      for (const raw of m.items) {
        if (typeof raw !== "object" || raw === null) return fail("bad_message", "an entry of items is not an object", type);
        const e = raw as Record<string, unknown>;
        if (!isInt(e.obj, 0, 1000) || !isInt(e.stage, -1, 20) || !isInt(e.room, -1, 100) || typeof e.active !== "boolean") return fail("bad_message", "obj, stage, room or active of an entry is wrong", type);
        if (!isInt(e.body, -1, 2000) || !isInt(e.anim, -1, 2000) || !isInt(e.alpha, 0, 1023) || !isInt(e.beta, 0, 1023)) return fail("bad_message", "body, anim, alpha or beta of an entry is wrong", type);
        if (!isInt(e.x, -c, c) || !isInt(e.y, -c, c) || !isInt(e.z, -c, c)) return fail("bad_message", "x, y and z of an entry must be integers within +-" + c, type);
        items.push({ obj: e.obj, stage: e.stage, room: e.room, active: e.active, body: e.body, anim: e.anim, alpha: e.alpha, x: e.x, y: e.y, z: e.z, beta: e.beta });
      }
      return { ok: true, msg: { type, items } };
    }
    case "latch": {
      if (!isInt(m.index, 0, 255) || !isInt(m.value, -32768, 32767) || m.value === 0) return fail("bad_message", "index (0 to 255) and a value other than 0 (16 bit) are needed", type);
      let old = 0;
      if (m.old !== undefined) {
        if (!isInt(m.old, -32768, 32767)) return fail("bad_message", "old must be a 16 bit integer", type);
        old = m.old;
      }
      return { ok: true, msg: { type, index: m.index, value: m.value, old } };
    }
    case "sound": {
      if (!isInt(m.n, 0, 5000)) return fail("bad_message", "n must be an integer from 0 to 5000", type);
      return { ok: true, msg: { type, n: m.n } };
    }
    case "world_snapshot": {
      if (!Array.isArray(m.items) || m.items.length < 1 || m.items.length > 40) return fail("bad_message", "items must be a list of 1 to 40 entries", type);
      const c = limits.maxCoord;
      const items: WorldEntry[] = [];
      for (const raw of m.items) {
        if (typeof raw !== "object" || raw === null) return fail("bad_message", "an entry of items is not an object", type);
        const e = raw as Record<string, unknown>;
        if (!isInt(e.obj, 0, 1000) || !isInt(e.stage, -1, 20) || !isInt(e.room, -1, 100) || !isInt(e.state, 0, 2) || !isInt(e.beta, 0, 1023)) return fail("bad_message", "an entry has a wrong obj, stage, room, state or beta", type);
        if (!isInt(e.x, -c, c) || !isInt(e.y, -c, c) || !isInt(e.z, -c, c)) return fail("bad_message", "x, y and z of an entry must be integers within +-" + c, type);
        items.push({ obj: e.obj, stage: e.stage, room: e.room, state: e.state, x: e.x, y: e.y, z: e.z, beta: e.beta });
      }
      return { ok: true, msg: { type, items } };
    }
    case "effect": {
      if (!isInt(m.kind, 0, 6) || !isInt(m.a, -1, 5000) || !isInt(m.b, -1, 5000)) return fail("bad_message", "kind (0 to 6), a and b (-1 to 5000) are needed", type);
      return { ok: true, msg: { type, kind: m.kind, a: m.a, b: m.b } };
    }
    case "world_var": {
      if (!isInt(m.index, 0, 1000) || !SHARED_VARS.includes(m.index)) return fail("bad_message", "that variable is not shared", type);
      if (!isInt(m.value, -32768, 32767)) return fail("bad_message", "value must be a 16 bit integer", type);
      return { ok: true, msg: { type, index: m.index, value: m.value } };
    }
    case "creature_hit": {
      if (!isInt(m.obj, 0, 1000) || !CREATURE_OBJECTS.includes(m.obj)) return fail("bad_message", "obj is not a creature", type);
      if (!isInt(m.force, 0, 200)) return fail("bad_message", "force must be an integer from 0 to 200", type);
      return { ok: true, msg: { type, obj: m.obj, force: m.force } };
    }
    case "hurt": {
      if (!isInt(m.force, 0, 200) || !isInt(m.by, 0, 1000)) return fail("bad_message", "force (0 to 200) and by (an object) are needed", type);
      return { ok: true, msg: { type, force: m.force, by: m.by } };
    }
    case "hero_died":
      return { ok: true, msg: { type } };
    case "item_placed": {
      if (!isInt(m.obj, 0, 1000)) return fail("bad_message", "obj must be an integer from 0 to 1000", type);
      if (!isInt(m.stage, 0, 20) || !isInt(m.room, 0, 100)) return fail("bad_message", "stage and room must be small non-negative integers", type);
      const c = limits.maxCoord;
      if (!isInt(m.x, -c, c) || !isInt(m.y, -c, c) || !isInt(m.z, -c, c)) return fail("bad_message", "x, y and z must be integers within +-" + c, type);
      if (!isInt(m.alpha, 0, 1023) || !isInt(m.beta, 0, 1023) || !isInt(m.gamma, 0, 1023)) return fail("bad_message", "alpha, beta and gamma must be integers from 0 to 1023", type);
      return { ok: true, msg: { type, obj: m.obj, stage: m.stage, room: m.room, x: m.x, y: m.y, z: m.z, alpha: m.alpha, beta: m.beta, gamma: m.gamma } };
    }
    case "object_moved": {
      if (!isInt(m.obj, 0, 1000)) return fail("bad_message", "obj must be an integer from 0 to 1000", type);
      const c = limits.maxCoord;
      if (!isInt(m.x, -c, c) || !isInt(m.y, -c, c) || !isInt(m.z, -c, c)) return fail("bad_message", "x, y and z must be integers within +-" + c, type);
      return { ok: true, msg: { type, obj: m.obj, x: m.x, y: m.y, z: m.z } };
    }
    case "ping": {
      if (!isNum(m.t, 0, 1e15)) return fail("bad_message", "t must be a non-negative number", type);
      return { ok: true, msg: { type, t: m.t } };
    }
    default:
      return fail("unknown_type", "unsupported message type: " + type.slice(0, 40));
  }
}
