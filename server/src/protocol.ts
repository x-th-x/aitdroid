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

export type ClientMessage = ListSessions | CreateSession | JoinSession | LeaveSession | SelectStart | UploadSave | SetReady | StateMsg | Ping;

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
  | { type: "joined"; sessionId: string; name: string; slot: 1 | 2; hero: Hero }
  | ({ type: "session" } & SessionInfo)
  | { type: "start"; sessionId: string; mode: StartMode; saveId: string | null; saveData: string | null; area: Area; serverTime: number }
  | { type: "remote_state"; seq: number; t: number; serverTime: number; hero: Hero; stage: number; room: number; x: number; y: number; z: number; beta: number; anim: number }
  | { type: "peer"; slot: 1 | 2; hero: Hero; state: "connected" | "disconnected" | "silent" | "active" }
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
  | "selection_mismatch";

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
    case "ping": {
      if (!isNum(m.t, 0, 1e15)) return fail("bad_message", "t must be a non-negative number", type);
      return { ok: true, msg: { type, t: m.t } };
    }
    default:
      return fail("unknown_type", "unsupported message type: " + type.slice(0, 40));
  }
}
