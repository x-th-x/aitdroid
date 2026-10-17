import { loadConfig } from "./config";
import { createServer } from "./server";

const cfg = loadConfig();
const server = createServer(cfg);

server
  .listen()
  .then((port) => {
    console.log("aitdroid co-op server listening on " + cfg.host + ":" + port + " (areas: " + cfg.areas.map((a) => a.stage + "/" + a.room).join(", ") + ")");
  })
  .catch((e) => {
    console.error("could not start:", e);
    process.exit(1);
  });

// Render stops a service with SIGTERM: close the connections, then leave. A restart loses all sessions on purpose (nothing is stored).
for (const signal of ["SIGTERM", "SIGINT"] as const) {
  process.on(signal, () => {
    console.log(signal + ": shutting down");
    server.close().then(() => process.exit(0));
  });
}
