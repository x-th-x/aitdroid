// The network side: an HTTP server (a health check for Render) with the WebSocket server on it. All rules are in SessionManager; this file reads text frames,
// validates them, hands them over, and sends what comes back.

import { randomUUID } from "node:crypto";
import http from "node:http";
import { WebSocket, WebSocketServer } from "ws";
import { Config } from "./config";
import { parseClientMessage } from "./protocol";
import { Outbound, SessionManager } from "./sessions";

export interface RunningServer {
  manager: SessionManager;
  /** Starts listening (port 0 = any free port) and gives the port. */
  listen(port?: number): Promise<number>;
  close(): Promise<void>;
}

const HEARTBEAT_MS = 15_000;

export function createServer(cfg: Config, now: () => number = Date.now): RunningServer {
  const manager = new SessionManager(cfg, now);
  const sockets = new Map<string, WebSocket>();

  const httpServer = http.createServer((req, res) => {
    // /health is what Render's health check asks; it also tells a person that the right service answers
    if (req.url === "/health" || req.url === "/") {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ status: "ok", service: "aitdroid-coop-server", sessions: manager.sessionCount }));
      return;
    }
    res.writeHead(404, { "Content-Type": "text/plain" });
    res.end("not found");
  });

  // one frame may carry a save point (base64), nothing else comes near that size
  const wss = new WebSocketServer({ server: httpServer, maxPayload: cfg.limits.maxSaveBase64 + 4096 });

  function send(out: Outbound[]): void {
    for (const o of out) {
      const ws = sockets.get(o.to);
      if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(o.msg));
    }
  }

  const perIp = new Map<string, number>();

  wss.on("connection", (ws, req) => {
    // the address of the player: behind Render's proxy it is the first entry of x-forwarded-for
    const forwarded = req.headers["x-forwarded-for"];
    const ip = (typeof forwarded === "string" && forwarded.split(",")[0].trim()) || req.socket.remoteAddress || "?";
    if (sockets.size >= cfg.maxConnections || (perIp.get(ip) ?? 0) >= cfg.maxConnectionsPerIp) {
      ws.send(JSON.stringify({ type: "error", code: "server_busy", message: "too many connections; try again later" }));
      ws.close(1013, "try again later");
      return;
    }
    perIp.set(ip, (perIp.get(ip) ?? 0) + 1);
    ws.once("close", () => { const n = (perIp.get(ip) ?? 1) - 1; if (n <= 0) perIp.delete(ip); else perIp.set(ip, n); });
    const id = randomUUID();
    sockets.set(id, ws);
    let alive = true;
    let windowStart = now();
    let windowCount = 0;
    ws.on("pong", () => { alive = true; });
    (ws as WebSocket & { heartbeat?: () => boolean }).heartbeat = () => {
      if (!alive) return false;
      alive = false;
      ws.ping();
      return true;
    };
    send(manager.connect(id));

    ws.on("message", (data, isBinary) => {
      // a flood is told off, and cut off when it goes on
      const t = now();
      if (t - windowStart >= 1000) { windowStart = t; windowCount = 0; }
      windowCount++;
      if (windowCount > cfg.maxMessagesPerSecond * 3) { ws.terminate(); return; }
      if (windowCount > cfg.maxMessagesPerSecond) {
        if (windowCount === cfg.maxMessagesPerSecond + 1) ws.send(JSON.stringify({ type: "error", code: "rate_limited", message: "too many messages per second" }));
        return;
      }
      if (isBinary) {
        ws.send(JSON.stringify({ type: "error", code: "bad_message", message: "only text messages are supported" }));
        return;
      }
      const parsed = parseClientMessage(data.toString(), cfg.limits);
      if (!parsed.ok) {
        ws.send(JSON.stringify({ type: "error", code: parsed.code, message: parsed.message, requestType: parsed.requestType }));
        return;
      }
      send(manager.handle(id, parsed.msg));
    });

    const gone = () => {
      if (!sockets.delete(id)) return;
      send(manager.disconnect(id));
    };
    ws.on("close", gone);
    ws.on("error", gone);
  });

  const housekeeping = setInterval(() => send(manager.tick()), 1000);
  const heartbeat = setInterval(() => {
    for (const ws of sockets.values()) {
      const beat = (ws as WebSocket & { heartbeat?: () => boolean }).heartbeat;
      if (beat && !beat()) ws.terminate(); // no answer to the last ping: the connection is dead
    }
  }, HEARTBEAT_MS);
  housekeeping.unref();
  heartbeat.unref();

  return {
    manager,
    listen: (port = cfg.port) =>
      new Promise((resolve, reject) => {
        httpServer.once("error", reject);
        httpServer.listen(port, cfg.host, () => {
          const address = httpServer.address();
          resolve(typeof address === "object" && address ? address.port : port);
        });
      }),
    close: () =>
      new Promise((resolve) => {
        clearInterval(housekeeping);
        clearInterval(heartbeat);
        for (const ws of sockets.values()) ws.terminate();
        wss.close(() => httpServer.close(() => resolve()));
      }),
  };
}
