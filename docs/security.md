# Production REPL security

A REPL on a production server is a power tool: it lets you inspect and fix a live
game without downtime. It is also, by nature, remote code execution. Skein's
production REPL is built to be safe **by default and by construction** — off
unless you turn it on, and impossible to expose to the network even when you do.
Read this page before enabling it anywhere real.

## The threat model

**An nREPL session is full control of the JVM.** Whoever can talk to the REPL
port can evaluate arbitrary Clojure — read and write any game state, touch the
file system and network, and run anything the server process can run. There is no
sandbox and there is not meant to be one; that is what makes it useful for
operators.

The security model therefore is not "restrict what the REPL can do." It is
**"only the operator can reach the REPL at all."** Two properties enforce that:

1. **Off by default in production** — nothing listens unless you opt in.
2. **Loopback-only, always** — the port can never be bound to a network-reachable
   address in production, so the only way in is from the machine itself.

Remote access is the operator's own encrypted channel — an **SSH tunnel** — never
an open port.

## Default behavior

| | Dev (`runClient` / `runServer`) | Production (real server jar) |
|---|---|---|
| REPL | **on** by default | **off** by default |
| Bind | `127.0.0.1` | `127.0.0.1` (loopback enforced) |
| Port | `7888` | `7888` when enabled |
| Enabling | automatic | explicit `enabled=true` required |

In dev the REPL is a convenience and is on. In production nothing listens until
you say so.

## Enabling it in production

Opt in with **either** a config file or a JVM system property.

Config file — `config/skein/nrepl.properties`:

```properties
enabled = true
# port = 7888              # optional; default 7888
# bind = 127.0.0.1         # optional; must stay loopback in production
# game-thread-eval = false # optional; run every eval on the game thread
```

Or a JVM property on the server launch command:

```sh
-Dskein.nrepl.enabled=true
```

When the production REPL activates, Skein prints a loud warning banner to the log
so an enabled REPL is never a silent surprise in an operator's server output.

## How the configuration resolves

Sources, highest precedence first:

1. **JVM system properties** — `skein.nrepl.enabled`, `skein.nrepl.port`,
   `skein.nrepl.bind`, `skein.nrepl.game-thread-eval`, and the kill switch
   `skein.nrepl.disabled`.
2. **`config/skein/nrepl.properties`** — keys `enabled`, `port`, `bind`,
   `game-thread-eval`.
3. **Defaults** — port `7888`, bind `127.0.0.1`, game-thread-eval off; enabled in
   dev, disabled in production.

A system property beats the file; the file beats the default.

### The kill switch always wins

`-Dskein.nrepl.disabled=true` (or `disabled=true` in the file) forces the REPL
**off**, overriding every `enabled` setting. Use it to guarantee no REPL on a
host regardless of what a config file says.

## Loopback is enforced, not merely defaulted

In production, if the resolved `bind` address is **not** a loopback address, Skein
treats the configuration as **invalid**: the REPL does not start and the reason
is logged. You cannot open the port to the network by editing a config value — the
adapter refuses. The error tells you to remove the bind override and use an SSH
tunnel instead.

This is deliberate. A loopback bind means the port is only reachable from
processes on the same machine, so an exposed REPL is not one config typo away.

## Reaching it remotely: SSH tunnel

Because the port is loopback-only, you connect from your workstation by
forwarding a local port over SSH to the server's loopback:

```sh
ssh -N -L 7888:127.0.0.1:7888 you@your-server
```

Then point your editor (or `clj -M -m nrepl.cmdline --connect --port 7888`) at
`localhost:7888` on your own machine. The traffic rides your existing SSH auth and
encryption; the game never opens a port to the world.

## Fail-closed

Every "not sure" path leaves the REPL **off**:

- Not explicitly enabled in production → off.
- A non-loopback bind in production → off, error logged.
- A malformed value (a non-numeric port, a non-boolean flag, an unresolvable
  bind address) → off, error logged.

A broken or ambiguous configuration never results in an open, unintended REPL.

## Operator checklist

- [ ] Leave the REPL off unless you actively need it.
- [ ] Enable it explicitly (`enabled=true`), and confirm the warning banner in
      the log.
- [ ] Never set `bind` to anything but a loopback address in production — the
      adapter will refuse, but don't try.
- [ ] Reach it only through an SSH tunnel; do not add a firewall rule to expose
      the port.
- [ ] Restrict who can SSH to the host and who can write to
      `config/skein/nrepl.properties` — both are equivalent to server access.
- [ ] Turn it back off (or use the `disabled` kill switch) when you're done.
