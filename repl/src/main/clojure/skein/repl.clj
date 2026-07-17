(ns skein.repl
  "nREPL lifecycle for dev and (strictly opt-in) production servers.

  M2: start/stop поверх gradle-конфига (default port 7888), eval в
  отдельном треде, helpers `on-client`/`on-server`, opt-in middleware
  для диспатча на game thread.")
