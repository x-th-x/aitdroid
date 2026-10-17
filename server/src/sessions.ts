// The session logic of the server, with no sockets in it: it takes the messages of connections (by an id) and returns the messages to send.
// That keeps it testable, and keeps the rules (two slots, fixed heroes, the start agreement, what happens at a disconnect) in one place.
// The server is authoritative over these rules; it does not simulate the game. Each client moves its own hero and the server relays the state.

import { randomUUID } from "node:crypto";
import { Area, ClientMessage, ErrorCode, Hero, PlayerInfo, ServerMessage, SessionInfo, SessionStatus, StartMode, PROTOCOL_VERSION, HOST_OBJECTS } from "./protocol";
import { Config } from "./config";

export interface Outbound {
  to: string;
  msg: ServerMessage;
}

interface Selection {
  mode: StartMode;
  saveId?: string;
}

interface Player {
  connId: string;
  slot: 1 | 2;
  hero: Hero;
  connected: boolean;
  selection: Selection | null;
  ready: boolean;
  lastSeq: number;
  lastStateAt: number;
  lastPos: { x: number; y: number; z: number } | null;
  /** The place (stage/room) of the last state: the coordinates of another room are in another frame, so a change of room is no jump. */
  lastArea: string | null;
  silent: boolean;
  /** The secret with which this player takes his place again after a lost connection; null once he left on purpose. */
  token: string | null;
}

interface SavedGame {
  saveId: string;
  label: string;
  data: string;
}

interface Session {
  id: string;
  name: string;
  status: SessionStatus;
  players: [Player | null, Player | null];
  createdAt: number;
  lastActivity: number;
  interruptedAt: number | null;
  save: SavedGame | null;
}

const WORDS = ["Raven", "Willow", "Ember", "Lantern", "Cedar", "Hollow", "Thistle", "Marble", "Harbor", "Velvet", "Falcon", "Maple", "Ivory", "Copper", "Meadow", "Pewter", "Sparrow", "Granite", "Amber", "Juniper", "Walnut", "Quartz", "Badger", "Cobalt"];

/** The key under which a session name is found: case and spaces do not matter ("raven 27" finds "Raven-27"). */
export function nameKey(name: string): string {
  return name.trim().toLowerCase().replace(/\s+/g, "-");
}

const HEROES: [Hero, Hero] = ["carnby", "emily"];

export class SessionManager {
  private readonly sessions = new Map<string, Session>();
  private readonly byName = new Map<string, string>();
  private readonly byConn = new Map<string, { sessionId: string; slot: 1 | 2 }>();
  private readonly connections = new Set<string>();

  constructor(
    private readonly cfg: Config,
    private readonly now: () => number = Date.now,
    private readonly random: () => number = Math.random,
  ) {}

  // ------------------------------------------------------------------------------------------ connections

  connect(connId: string): Outbound[] {
    this.connections.add(connId);
    return [{ to: connId, msg: { type: "welcome", protocol: PROTOCOL_VERSION, serverTime: this.now(), areas: this.cfg.areas } }];
  }

  disconnect(connId: string): Outbound[] {
    this.connections.delete(connId);
    return this.leave(connId, "disconnected");
  }

  /** For the tests and the status page. */
  get sessionCount(): number {
    return this.sessions.size;
  }

  // ------------------------------------------------------------------------------------------ messages

  handle(connId: string, msg: ClientMessage): Outbound[] {
    if (!this.connections.has(connId)) return [];
    switch (msg.type) {
      case "ping":
        return [{ to: connId, msg: { type: "pong", t: msg.t, serverTime: this.now() } }];
      case "list_sessions":
        return [{ to: connId, msg: { type: "sessions", sessions: [...this.sessions.values()].filter((s) => s.status === "waiting").map((s) => ({ name: s.name, status: s.status, players: 1 })) } }];
      case "create_session":
        return this.create(connId);
      case "join_session":
        return this.join(connId, msg.name);
      case "resume":
        return this.resume(connId, msg.name, msg.token);
      case "leave_session":
        return this.byConn.has(connId) ? this.leave(connId, "left") : this.error(connId, "not_in_session", "you are not in a session", msg.type);
      case "select_start":
        return this.selectStart(connId, msg.mode, msg.saveId);
      case "upload_save":
        return this.uploadSave(connId, msg.saveId, msg.label ?? "", msg.data);
      case "set_ready":
        return this.setReady(connId, msg.ready);
      case "state":
        return this.state(connId, msg);
      case "latch":
      case "props":
      case "effect":
      case "world_snapshot":
      case "world_var":
      case "creature_hit":
      case "hurt":
      case "hero_died":
        return this.worldRelay(connId, msg);
      case "item_placed":
        return this.itemPlaced(connId, msg);
      case "object_moved":
        return this.objectMoved(connId, msg);
      case "item_taken":
        return this.itemTaken(connId, msg);
    }
  }

  // ------------------------------------------------------------------------------------------ create and join

  private create(connId: string): Outbound[] {
    if (this.byConn.has(connId)) return this.error(connId, "already_in_session", "you are already in a session; leave it first", "create_session");
    const t = this.now();
    const name = this.freshName();
    const session: Session = { id: randomUUID(), name, status: "waiting", players: [this.newPlayer(connId, 1, t), null], createdAt: t, lastActivity: t, interruptedAt: null, save: null };
    this.sessions.set(session.id, session);
    this.byName.set(nameKey(name), session.id);
    this.byConn.set(connId, { sessionId: session.id, slot: 1 });
    return [{ to: connId, msg: { type: "joined", sessionId: session.id, name, slot: 1, hero: "carnby", token: session.players[0]!.token! } }, ...this.broadcast(session)];
  }

  private join(connId: string, name: string): Outbound[] {
    if (this.byConn.has(connId)) return this.error(connId, "already_in_session", "you are already in a session; leave it first", "join_session");
    const id = this.byName.get(nameKey(name));
    const session = id ? this.sessions.get(id) : undefined;
    if (!session) return this.error(connId, "unknown_session", "there is no session with that name", "join_session");
    if (session.status === "running" || session.status === "interrupted") return this.error(connId, "session_not_joinable", "that session has already started; nobody can join it", "join_session");
    if (session.players[1] !== null) return this.error(connId, "session_full", "that session already has two players", "join_session");
    const t = this.now();
    session.players[1] = this.newPlayer(connId, 2, t);
    session.status = "ready";
    session.lastActivity = t;
    this.byConn.set(connId, { sessionId: session.id, slot: 2 });
    const host = session.players[0]!;
    return [
      { to: connId, msg: { type: "joined", sessionId: session.id, name: session.name, slot: 2, hero: "emily", token: session.players[1]!.token! } },
      { to: host.connId, msg: { type: "peer", slot: 2, hero: "emily", state: "connected" } },
      ...this.broadcast(session),
    ];
  }

  /** A player whose connection dropped takes his place again: the game goes on where it was (both phones kept it), and each phone sends the world again. */
  private resume(connId: string, name: string, token: string): Outbound[] {
    if (this.byConn.has(connId)) return this.error(connId, "already_in_session", "you are already in a session; leave it first", "resume");
    const id = this.byName.get(nameKey(name));
    const session = id ? this.sessions.get(id) : undefined;
    if (!session) return this.error(connId, "unknown_session", "that game is not there any more", "resume");
    const player = session.players.find((p) => p !== null && p.token !== null && p.token === token) ?? null;
    if (!player || (session.status !== "interrupted" && session.status !== "running")) return this.error(connId, "bad_token", "that game cannot be resumed", "resume");
    const out: Outbound[] = [];
    if (player.connected && player.connId !== connId) {
      // the old connection is half dead (the network changed): the new one replaces it
      out.push({ to: player.connId, msg: { type: "closed", reason: "you connected again from somewhere else" } });
      this.byConn.delete(player.connId);
    }
    const t = this.now();
    player.connId = connId;
    player.connected = true;
    player.lastSeq = -1;
    player.lastPos = null;
    player.lastArea = null;
    player.silent = false;
    player.lastStateAt = t;
    session.lastActivity = t;
    this.byConn.set(connId, { sessionId: session.id, slot: player.slot });
    const other = session.players[player.slot === 1 ? 1 : 0];
    if (other?.connected) {
      session.status = "running";
      session.interruptedAt = null;
      out.push({ to: other.connId, msg: { type: "peer", slot: player.slot, hero: player.hero, state: "connected" } });
    } else {
      session.status = "interrupted"; // the other one is still away
      session.interruptedAt = t;
    }
    out.push({ to: connId, msg: { type: "joined", sessionId: session.id, name: session.name, slot: player.slot, hero: player.hero, token } });
    return [...out, ...this.broadcast(session)];
  }

  // ------------------------------------------------------------------------------------------ the start agreement

  private selectStart(connId: string, mode: StartMode, saveId: string | undefined): Outbound[] {
    const found = this.find(connId);
    if (!found) return this.error(connId, "not_in_session", "you are not in a session", "select_start");
    const { session, player } = found;
    if (session.status !== "waiting" && session.status !== "ready") return this.error(connId, "wrong_state", "the game has already started", "select_start");
    if (mode === "save") {
      if (!session.save) return this.error(connId, "save_missing", "no save point is offered: the host has to upload one first", "select_start");
      if (saveId !== session.save.saveId) return this.error(connId, "save_missing", "that save point is not the one the host offered", "select_start");
    }
    player.selection = mode === "save" ? { mode, saveId } : { mode };
    // a changed choice means nobody has agreed to the new one yet
    for (const p of session.players) if (p) p.ready = false;
    session.lastActivity = this.now();
    return this.broadcast(session);
  }

  private uploadSave(connId: string, saveId: string, label: string, data: string): Outbound[] {
    const found = this.find(connId);
    if (!found) return this.error(connId, "not_in_session", "you are not in a session", "upload_save");
    const { session, player } = found;
    if (player.slot !== 1) return this.error(connId, "not_host", "only the host (player 1) can offer a save point", "upload_save");
    if (session.status !== "waiting" && session.status !== "ready") return this.error(connId, "wrong_state", "the game has already started", "upload_save");
    session.save = { saveId, label, data };
    // a new save point: everybody who had chosen a save has to choose again, and nobody is ready any more
    for (const p of session.players) {
      if (!p) continue;
      if (p.selection?.mode === "save") p.selection = null;
      p.ready = false;
    }
    session.lastActivity = this.now();
    return this.broadcast(session);
  }

  private setReady(connId: string, ready: boolean): Outbound[] {
    const found = this.find(connId);
    if (!found) return this.error(connId, "not_in_session", "you are not in a session", "set_ready");
    const { session, player } = found;
    if (session.status !== "ready") return this.error(connId, "wrong_state", session.status === "waiting" ? "the second player has not joined yet" : "the game has already started", "set_ready");
    if (ready) {
      if (!player.selection) return this.error(connId, "wrong_state", "choose how to start first (new game or save point)", "set_ready");
      const mismatch = this.mismatch(session);
      if (mismatch) return this.error(connId, "selection_mismatch", mismatch, "set_ready");
    }
    player.ready = ready;
    session.lastActivity = this.now();
    const both = session.players.every((p) => p !== null && p.ready);
    if (!both) return this.broadcast(session);
    return this.start(session);
  }

  private start(session: Session): Outbound[] {
    const t = this.now();
    session.status = "running";
    session.lastActivity = t;
    const first = session.players[0]!;
    const mode = first.selection!.mode;
    const save = mode === "save" ? session.save : null;
    const area: Area = this.cfg.areas[0];
    for (const p of session.players) {
      if (!p) continue;
      p.lastStateAt = t;
      p.lastSeq = -1;
      p.lastPos = null;
      p.silent = false;
    }
    const out: Outbound[] = [];
    for (const p of session.players) {
      if (!p) continue;
      out.push({ to: p.connId, msg: { type: "start", sessionId: session.id, mode, saveId: save ? save.saveId : null, saveData: save ? save.data : null, area, serverTime: t } });
    }
    return [...out, ...this.broadcast(session)];
  }

  /** Why the two players cannot start yet (null if they can, or if one of them has not chosen). */
  private mismatch(session: Session): string | null {
    const a = session.players[0];
    const b = session.players[1];
    if (!a || !b || !a.selection || !b.selection) return null;
    if (a.selection.mode !== b.selection.mode) return "the players chose different ways to start";
    if (a.selection.mode === "save" && a.selection.saveId !== b.selection.saveId) return "the players chose different save points";
    return null;
  }

  // ------------------------------------------------------------------------------------------ the state of the heroes

  private state(connId: string, m: Extract<ClientMessage, { type: "state" }>): Outbound[] {
    const found = this.find(connId);
    if (!found) return this.error(connId, "not_in_session", "you are not in a session", "state");
    const { session, player } = found;
    if (session.status !== "running") return this.error(connId, "wrong_state", "the game is not running", "state");
    // the server decides who controls which hero: a client can only report its own
    if (m.hero !== player.hero) return this.error(connId, "wrong_hero", "you control " + player.hero + ", not " + m.hero, "state");
    if (!this.cfg.allAreas && !this.cfg.areas.some((a) => a.stage === m.stage && a.room === m.room)) return this.error(connId, "area_not_allowed", "the room " + m.stage + "/" + m.room + " is not supported in the prototype", "state");
    // an old or repeated state is dropped without a fuss (the network may reorder)
    if (m.seq <= player.lastSeq) return [];
    const t = this.now();
    const area = m.stage + "/" + m.room;
    if (player.lastPos && player.lastArea === area) {
      const dtMs = Math.max(1, t - player.lastStateAt);
      const dist = Math.hypot(m.x - player.lastPos.x, m.z - player.lastPos.z);
      // a generous allowance on top of the speed, so that a burst after a stall (several states at once) passes
      if (dist > (this.cfg.maxSpeed * dtMs) / 1000 + 3000) return this.error(connId, "implausible_state", "the position moved too far since the last state", "state");
    }
    player.lastSeq = m.seq;
    player.lastPos = { x: m.x, y: m.y, z: m.z };
    player.lastArea = area;
    player.lastStateAt = t;
    session.lastActivity = t;
    const out: Outbound[] = [];
    const other = session.players[player.slot === 1 ? 1 : 0];
    if (player.silent) {
      player.silent = false;
      if (other?.connected) out.push({ to: other.connId, msg: { type: "peer", slot: player.slot, hero: player.hero, state: "active" } });
    }
    if (other?.connected) {
      out.push({ to: other.connId, msg: { type: "remote_state", seq: m.seq, t: m.t, serverTime: t, hero: player.hero, stage: m.stage, room: m.room, x: m.x, y: m.y, z: m.z, beta: m.beta, anim: m.anim } });
    }
    return out;
  }

  // ------------------------------------------------------------------------------------------ the shared world

  /** An item was taken: the other player is told (every time: a repeated message is how a phone repairs a difference between the two worlds). */
  private itemTaken(connId: string, m: Extract<ClientMessage, { type: "item_taken" }>): Outbound[] {
    const found = this.find(connId);
    if (!found) return this.error(connId, "not_in_session", "you are not in a session", "item_taken");
    const { session, player } = found;
    if (session.status !== "running") return this.error(connId, "wrong_state", "the game is not running", "item_taken");
    session.lastActivity = this.now();
    const other = session.players[player.slot === 1 ? 1 : 0];
    if (!other?.connected) return [];
    return [{ to: other.connId, msg: { type: "item_taken", hero: player.hero, obj: m.obj } }];
  }

  /**
   * The host runs the creatures: the guest sends their states never, the host sends the guest's blows' effects never. Props, creature hits, hurts and deaths go to the
   * other player only, and only while the game runs.
   */
  private worldRelay(connId: string, m: Extract<ClientMessage, { type: "latch" | "props" | "effect" | "world_snapshot" | "world_var" | "creature_hit" | "hurt" | "hero_died" }>): Outbound[] {
    const found = this.find(connId);
    if (!found) return this.error(connId, "not_in_session", "you are not in a session", m.type);
    const { session, player } = found;
    if (session.status !== "running") return this.error(connId, "wrong_state", "the game is not running", m.type);
    const isHost = player.slot === 1;
    if (m.type === "props" && !isHost && m.items.some((i) => HOST_OBJECTS.includes(i.obj))) return this.error(connId, "not_host", "only the host runs the creatures and the trapdoor", m.type);
    if ((m.type === "effect" || m.type === "world_var" || m.type === "world_snapshot") && !isHost) return this.error(connId, "not_host", "only the host runs the creatures", m.type);
    if (m.type === "hurt" && !isHost) return this.error(connId, "not_host", "only the host decides who is hurt", m.type);
    if (m.type === "creature_hit" && isHost) return this.error(connId, "wrong_hero", "the host runs the creatures itself", m.type);
    session.lastActivity = this.now();
    const other = session.players[isHost ? 1 : 0];
    if (!other?.connected) return [];
    if (m.type === "props") return [{ to: other.connId, msg: { type: "props", hero: player.hero, items: m.items } }];
    if (m.type === "hero_died") return [{ to: other.connId, msg: { type: "hero_died", hero: player.hero } }];
    return [{ to: other.connId, msg: m }];
  }

  /** An item was put down: it is in the world again (so taking it later is relayed again), and the other player is told where it lies. */
  private itemPlaced(connId: string, m: Extract<ClientMessage, { type: "item_placed" }>): Outbound[] {
    const found = this.find(connId);
    if (!found) return this.error(connId, "not_in_session", "you are not in a session", "item_placed");
    const { session, player } = found;
    if (session.status !== "running") return this.error(connId, "wrong_state", "the game is not running", "item_placed");
    session.lastActivity = this.now();
    const other = session.players[player.slot === 1 ? 1 : 0];
    if (!other?.connected) return [];
    return [{ to: other.connId, msg: { type: "item_placed", hero: player.hero, obj: m.obj, stage: m.stage, room: m.room, x: m.x, y: m.y, z: m.z, alpha: m.alpha, beta: m.beta, gamma: m.gamma } }];
  }

  /** A piece of furniture was pushed: relayed to the other player as it is (the last push wins). */
  private objectMoved(connId: string, m: Extract<ClientMessage, { type: "object_moved" }>): Outbound[] {
    const found = this.find(connId);
    if (!found) return this.error(connId, "not_in_session", "you are not in a session", "object_moved");
    const { session, player } = found;
    if (session.status !== "running") return this.error(connId, "wrong_state", "the game is not running", "object_moved");
    session.lastActivity = this.now();
    const other = session.players[player.slot === 1 ? 1 : 0];
    if (!other?.connected) return [];
    return [{ to: other.connId, msg: { type: "object_moved", hero: player.hero, obj: m.obj, x: m.x, y: m.y, z: m.z } }];
  }

  // ------------------------------------------------------------------------------------------ leaving, disconnects, time

  /** A player leaves (by message or by losing the connection). What follows depends on the state of the session. */
  private leave(connId: string, how: "left" | "disconnected"): Outbound[] {
    const found = this.find(connId);
    if (!found) return [];
    const { session, player } = found;
    this.byConn.delete(connId);
    const otherSlot = player.slot === 1 ? 1 : 0;
    const other = session.players[otherSlot];
    const out: Outbound[] = [];
    const t = this.now();
    session.lastActivity = t;

    if (session.status === "waiting") {
      // only the host was there
      this.remove(session);
      return out;
    }
    if (session.status === "ready") {
      if (player.slot === 1) {
        // the host is gone: nobody else could run the session
        if (other) {
          out.push({ to: other.connId, msg: { type: "closed", reason: "the host left the session" } });
          this.byConn.delete(other.connId);
        }
        this.remove(session);
        return out;
      }
      // the second player is gone: the session waits for another one, with the choices reset
      session.players[1] = null;
      session.status = "waiting";
      const host = session.players[0]!;
      host.selection = null;
      host.ready = false;
      out.push({ to: host.connId, msg: { type: "peer", slot: 2, hero: "emily", state: "disconnected" } });
      return [...out, ...this.broadcast(session)];
    }
    if (session.status === "running") {
      // the game is interrupted and the other player is told; the one who left can take his place again for a while (see resume), unless he left on purpose
      player.connected = false;
      if (how === "left") player.token = null;
      session.status = "interrupted";
      session.interruptedAt = t;
      if (other?.connected) {
        out.push({ to: other.connId, msg: { type: "peer", slot: player.slot, hero: player.hero, state: "disconnected" } });
        out.push(...this.broadcast(session));
      }
      return out;
    }
    // interrupted: when both are away, the session waits for them to come back (until it expires), unless somebody left on purpose and nobody is there any more
    player.connected = false;
    if (how === "left") player.token = null;
    session.interruptedAt = t;
    if (!other || (!other.connected && (other.token === null || player.token === null))) this.remove(session);
    return out;
  }

  /** Time based housekeeping; the server calls it about once a second. */
  tick(): Outbound[] {
    const t = this.now();
    const out: Outbound[] = [];
    for (const session of [...this.sessions.values()]) {
      if (session.status === "running") {
        for (const p of session.players) {
          if (!p || !p.connected || p.silent) continue;
          if (t - p.lastStateAt > this.cfg.silentAfterMs) {
            p.silent = true;
            const other = session.players[p.slot === 1 ? 1 : 0];
            if (other?.connected) out.push({ to: other.connId, msg: { type: "peer", slot: p.slot, hero: p.hero, state: "silent" } });
          }
        }
      } else if (session.status === "interrupted") {
        if (session.interruptedAt !== null && t - session.interruptedAt > this.cfg.interruptedKeepMs) out.push(...this.expire(session, "the interrupted game was closed"));
      } else if (t - session.lastActivity > this.cfg.idleSessionMs) {
        out.push(...this.expire(session, "the session was idle for too long"));
      }
    }
    return out;
  }

  private expire(session: Session, reason: string): Outbound[] {
    const out: Outbound[] = [];
    for (const p of session.players) {
      if (p && p.connected) out.push({ to: p.connId, msg: { type: "closed", reason } });
      if (p) this.byConn.delete(p.connId);
    }
    this.remove(session);
    return out;
  }

  // ------------------------------------------------------------------------------------------ helpers

  private newPlayer(connId: string, slot: 1 | 2, t: number): Player {
    return { connId, slot, hero: HEROES[slot - 1], connected: true, selection: null, ready: false, lastSeq: -1, lastStateAt: t, lastPos: null, lastArea: null, silent: false, token: randomUUID() };
  }

  private find(connId: string): { session: Session; player: Player } | null {
    const at = this.byConn.get(connId);
    if (!at) return null;
    const session = this.sessions.get(at.sessionId);
    const player = session?.players[at.slot - 1];
    if (!session || !player) return null;
    return { session, player };
  }

  private remove(session: Session): void {
    this.sessions.delete(session.id);
    if (this.byName.get(nameKey(session.name)) === session.id) this.byName.delete(nameKey(session.name));
  }

  private freshName(): string {
    for (let tries = 0; tries < 200; tries++) {
      const digits = tries < 100 ? 2 : 4;
      const n = Math.floor(this.random() * 10 ** digits).toString().padStart(digits, "0");
      const name = WORDS[Math.floor(this.random() * WORDS.length)] + "-" + n;
      if (!this.byName.has(nameKey(name))) return name;
    }
    // practically unreachable; keeps the names unique in any case
    return "Session-" + randomUUID().slice(0, 8);
  }

  private info(session: Session): SessionInfo {
    const players: PlayerInfo[] = [];
    for (const p of session.players) if (p) players.push({ slot: p.slot, hero: p.hero, connected: p.connected, selection: p.selection, ready: p.ready });
    const a = session.players[0];
    const b = session.players[1];
    const match = !!a && !!b && !!a.selection && !!b.selection && this.mismatch(session) === null;
    return {
      sessionId: session.id,
      name: session.name,
      status: session.status,
      players,
      selectionsMatch: match,
      mismatch: this.mismatch(session),
      offeredSave: session.save ? { saveId: session.save.saveId, label: session.save.label, bytes: Math.floor((session.save.data.length * 3) / 4) } : null,
    };
  }

  private broadcast(session: Session): Outbound[] {
    const msg: ServerMessage = { type: "session", ...this.info(session) };
    const out: Outbound[] = [];
    for (const p of session.players) if (p && p.connected) out.push({ to: p.connId, msg });
    return out;
  }

  private error(connId: string, code: ErrorCode, message: string, requestType?: string): Outbound[] {
    return [{ to: connId, msg: { type: "error", code, message, requestType } }];
  }
}
