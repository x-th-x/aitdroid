import { Area, DEFAULT_LIMITS, Limits } from "./protocol";

/** Everything the server can be configured with; the values come from environment variables (see README). */
export interface Config {
  port: number;
  host: string;
  /** The places of the game that the prototype supports (stage/room). A client may only report states inside them. */
  areas: Area[];
  /** Every place of the game is allowed (ALLOWED_AREAS=*); [areas] then only holds the place a new game starts in (the attic). */
  allAreas: boolean;
  limits: Limits;
  /** The fastest a hero may move between two reported states, in engine units per second (a plausibility check, not an anti-cheat). */
  maxSpeed: number;
  /** A player who sends no state for this long while the game runs is reported as silent to the other one. */
  silentAfterMs: number;
  /** Waiting or ready sessions nobody touched for this long are removed. */
  idleSessionMs: number;
  /** An interrupted session is removed after this long. */
  interruptedKeepMs: number;
  /** Messages per second one connection may send before it is told off. */
  maxMessagesPerSecond: number;
  /** How many sessions, connections, and connections of one address the server takes at the same time. */
  maxSessions: number;
  maxConnections: number;
  maxConnectionsPerIp: number;
}

function intEnv(env: NodeJS.ProcessEnv, name: string, fallback: number): number {
  const raw = env[name];
  if (raw === undefined || raw === "") return fallback;
  const n = Number(raw);
  if (!Number.isInteger(n) || n < 0) throw new Error(name + " must be a non-negative integer, got \"" + raw + "\"");
  return n;
}

/** "0/0,1/6" -> [{stage:0,room:0},{stage:1,room:6}] */
export function parseAreas(text: string): Area[] {
  if (text.trim() === "*") return [{ stage: 0, room: 0 }];
  const out: Area[] = [];
  for (const part of text.split(",")) {
    const m = /^\s*(\d{1,2})\/(\d{1,3})\s*$/.exec(part);
    if (!m) throw new Error("ALLOWED_AREAS entry \"" + part + "\" is not of the form stage/room");
    out.push({ stage: Number(m[1]), room: Number(m[2]) });
  }
  if (out.length === 0) throw new Error("ALLOWED_AREAS is empty");
  return out;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  return {
    // Render gives the port in PORT; 8080 is the default for local development
    port: intEnv(env, "PORT", 8080),
    host: env.HOST && env.HOST !== "" ? env.HOST : "0.0.0.0",
    // the attic is stage 0, room 0 of the game's room list (AtticGame starts there)
    areas: parseAreas(env.ALLOWED_AREAS && env.ALLOWED_AREAS !== "" ? env.ALLOWED_AREAS : "*"),
    allAreas: !env.ALLOWED_AREAS || env.ALLOWED_AREAS === "" || env.ALLOWED_AREAS.trim() === "*",
    limits: { ...DEFAULT_LIMITS, maxSaveBase64: intEnv(env, "MAX_SAVE_BASE64", DEFAULT_LIMITS.maxSaveBase64) },
    maxSpeed: intEnv(env, "MAX_SPEED", 20000),
    silentAfterMs: intEnv(env, "SILENT_AFTER_MS", 5000),
    idleSessionMs: intEnv(env, "IDLE_SESSION_MS", 30 * 60 * 1000),
    interruptedKeepMs: intEnv(env, "INTERRUPTED_KEEP_MS", 2 * 60 * 1000),
    maxMessagesPerSecond: intEnv(env, "MAX_MESSAGES_PER_SECOND", 120),
    maxSessions: intEnv(env, "MAX_SESSIONS", 100),
    maxConnections: intEnv(env, "MAX_CONNECTIONS", 300),
    maxConnectionsPerIp: intEnv(env, "MAX_CONNECTIONS_PER_IP", 8),
  };
}

export function testConfig(over: Partial<Config> = {}): Config {
  // the tests of the rooms rule run in the attic only, unless a test says otherwise
  return { ...loadConfig({}), allAreas: false, areas: [{ stage: 0, room: 0 }], ...over };
}
