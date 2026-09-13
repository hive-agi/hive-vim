# HVCP v1: hive Vim Control Protocol

> **Scope (2026-09-13).** Editor ACTIONS are standardized by
> [hive-vessel](../../hive-vessel): an op is lowered for the `:vim-channel`
> dialect and executed by `hive-vim.vessel/vessel-target`, over the transport
> described here. HVCP is hive-vim's transport plus its QUERY layer (the
> hive-spi editor port under `:vim`). It is not a competing editor standard;
> see decision 20260913080051-08ee41bc.

HVCP is how hive queries and drives a Vim instance beneath that standard. It
sits on top of Vim's built-in JSON channel (`:help channel`) and adds a fixed
dispatch entry, a handshake, a verb catalogue and an event stream.

The Clojure source of truth is `hive-vim.protocol.*` (`schema`, `codec`,
`verbs`); this document describes it and must not contradict it.

## Roles

- **hive** is the TCP server (`hive-vim.transport`). It listens on
  `localhost`, writes the chosen port to the port file
  (default `~/.cache/hive/vim.port`), and accepts any number of Vim sessions.
- **Vim** is the client (`vim/plugin/hive.vim`). It reads the port file, opens
  a channel with `ch_open("localhost:<port>", {"mode": "json"})`, and
  reconnects on a timer.

## L0: wire

Every message is one JSON array on its own line. Vim terminates each channel
message with a newline and JSON escapes newlines inside strings, so a frame is
exactly one line; hive writes frames the same way. Frames are classified by
their first element:

| Frame | Direction | Meaning |
|---|---|---|
| `["call", fn, args, -id]` | hive -> Vim | call a Vim function, reply expected |
| `["call", fn, args]` | hive -> Vim | call, no reply |
| `["expr", expr, -id]` / `["expr", expr]` | hive -> Vim | evaluate an expression |
| `["ex", cmd]`, `["normal", keys]`, `["redraw", ""]` | hive -> Vim | fire and forget |
| `[-id, result]` | Vim -> hive | response to a hive request (id < 0) |
| `[n, [method, params]]` | Vim -> hive | Vim request (n > 0), hive replies `[n, reply]` |

hive allocates request ids as strictly decreasing negative integers per session.

## L1: dispatch envelope

hive invokes every verb the same way:

```json
["call", "hive#rpc#dispatch", ["<verb>", {<params>}], -17]
```

`hive#rpc#dispatch` never throws. It always returns one of:

```json
{"ok": <value>}
{"err": {"category": "<category>", "message": "<text>"}}
```

Error categories (closed set): `invalid-params`, `unknown-verb`, `vim-error`,
`not-found`, `unsupported`, `timeout`, `disconnected`, `protocol-mismatch`,
`no-session`. The last four are produced on the hive side.

**Rule:** hive never builds Vimscript source by string concatenation. Parameters
travel as JSON arguments. The one escape hatch is the `eval` verb, whose `code`
parameter is Vimscript the caller wrote on purpose.

Timeouts: default 5000 ms, maximum 30000 ms, per request.

## L2: handshake

Right after connecting, Vim sends a request:

```json
[1, ["hello", {"protocol": [1, 0], "client": "vim", "vim_version": 901,
               "pid": 1234, "cwd": "/path", "verbs": ["buffers", "..."]}]]
```

hive replies:

```json
[1, {"accepted": true, "session": "vim-3", "protocol": [1, 0]}]
[1, {"accepted": false, "reason": "protocol major 2 unsupported"}]
```

A connection that does not send `hello` within 5000 ms is closed. A major
version mismatch is rejected. Minor versions are compatible.

## L3: verb catalogue

Defined once in `hive-vim.protocol.verbs`. Each verb carries a params schema, a
result schema and the hive-spi editor surface/method it backs. The MCP `vim`
tool, the `IEditorPort` reification and the Vim-side conformance check
(`hive#rpc#verbs()` must equal the catalogue) are all projections of it.

| Verb | SPI method | Params |
|---|---|---|
| `eval` | `editor-eval` | `code`, `mode` (`expr` or `ex`) |
| `notify` | `editor-notify` | `message`, `level` |
| `status` | `editor-status` | none |
| `capabilities` | `editor-capabilities` | none |
| `buffers` | `list-buffers` | none |
| `current` | `current-buffer` | none |
| `buffer-info` | `buffer-info` | `buffer_name` |
| `special-buffers` | `special-buffers` | none |
| `switch` | `switch-buffer` | `buffer` |
| `find` | `find-file` | `file` |
| `save` | `save-buffers` | `all` |
| `goto-line` | `goto-line` | `line` |
| `insert` | `insert-text` | `text` |
| `recent` | `recent-files` | none |
| `project-root` | `project-root` | none |
| `context` | `editor-context` | none |

Terminal verbs back hive-addon's `ITerminalAddon` (surface `:terminal`), plus
`terminal-read`. A terminal is a hidden `:terminal` buffer named `hive:<id>`, so
hive-vessel's `:ui/send-to-terminal` reaches it by that name as well.

| Verb | ITerminalAddon method | Params |
|---|---|---|
| `terminal-spawn` | `terminal-spawn!` | `id`, `cmd` (argv), `cwd`, `env` |
| `terminal-dispatch` | `terminal-dispatch!` | `id`, `text` (sent followed by Enter) |
| `terminal-status` | `terminal-status` | `id` -> `running` or `finished` |
| `terminal-kill` | `terminal-kill!` | `id` (kills the job, wipes the buffer) |
| `terminal-interrupt` | `terminal-interrupt!` | `id` (sends CTRL-C) |
| `terminal-read` | none | `id` -> visible lines |

An unknown id is `not-found`; input to a finished terminal is `unsupported`;
spawning an id whose terminal is still running is `invalid-params`.

## L4: events

Vim reports editor activity as requests:

```json
[7, ["event", {"type": "buf-enter", "buffer": {...}}]]
```

Event types: `buf-enter`, `buf-write`, `focus`, `vim-leave`. hive replies
`[7, "ok"]` and forwards the event to its injected emit function. The most
recently active session becomes the default target for verbs.

## IAddon composition

hive.vim is a hive-vessel vessel IAddon. It extends nothing by editing a host
or a sibling: its registry is hive-vessel's standard translators plus the
`:vessel/translators` hook of every addon the mount injects under
`:mount/dependencies`, and everything it offers is an IAddon hook
(`hive-vim.addon/hook-keys`), present only while the addon is active.

| Hook | Shape | Used for |
|---|---|---|
| `:vessel/target` | `(fn [])` | the hive-vessel `:vim-channel` target |
| `:vessel/instance` | `(fn [])` | hive-addon IVessel with `:editor` and `:terminal` |
| `:vessel/dispatch!` | `(fn [op-or-ops])` | plan and run hive-vessel ops on Vim |
| `:vessel/register-translators!` | `(fn [translators])` | extend this vessel's registry at runtime |
| `:vim/editor-port` | `(fn [])` | the hive-spi editor port |
| `:vim/terminal` | `(fn [])` | the ITerminalAddon running lings in Vim |
| `:vim/register-listener!` | `(fn [id f])` | `(f event payload)` for `:vim/connected`, `:vim/event`, `:vim/disconnected` |
| `:vim/unregister-listener!` | `(fn [id])` | stop receiving |

`:vim/emit-fn` in addon config receives the same events as listeners.
