(ns skein.core
  "Skein core-lib: декларативный registry DSL, event-обёртки и interop
  helpers.

  M4:
  - `(register! {:blocks {...} :items {...}})` — сбор деклараций и флаш
    в правильной registry-фазе;
  - event-обёртки над Fabric API callbacks, регистрирующие var, а не fn
    (hot reload хендлеров из REPL);
  - `(client)`, `(server)`, `(player ...)`, `(on-client ...)`, `(on-server ...)`.")
