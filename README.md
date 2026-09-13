# hive-vim

Vim vessel addon for hive.

**Actions are standardized by [hive-vessel](../hive-vessel).** An addon emits an
op, hive-vessel lowers it for the `:vim-channel` dialect, and hive-vim executes
it. hive-vim is the Vim executor, and it adds what a single-connection executor
cannot: several Vim sessions, a handshake, reconnection and events.

```
addon op -> hive-vessel translator -> ["call" fn args] -> hive-vim -> Vim
```

**Queries** (buffers, current buffer, project root) are not actions. They stay on
the hive-spi editor port hive-vim registers under `:vim`, carried by **HVCP v1**
over the same channel. HVCP is this addon's transport and query layer, not a
competing standard: [docs/protocol.md](docs/protocol.md).

## Pieces

| Namespace / file | Role |
|---|---|
| `hive-vim.protocol.schema` | malli value objects for frames, handshake, envelope, verbs |
| `hive-vim.protocol.codec` | pure frame encode/classify, envelope -> Result |
| `hive-vim.protocol.verbs` | the verb catalogue and its projections |
| `hive-vim.transport` | TCP server, sessions, request/response correlation |
| `hive-vim.client` | `invoke!` a verb on a session |
| `hive-vim.editor.port` | hive-spi `IEditorPort` registered under `:vim` |
| `hive-vim.tools.vim` | the `vim` MCP tool |
| `hive-vim.vessel` | the hive-vessel target (`vessel-target`) and the IVessel descriptor |
| `hive-vim.addon` | `IAddon` record, manifest `hive.vim` |
| `vim/plugin/hive.vim`, `vim/autoload/hive/rpc.vim` | the Vim side |

## Vim setup

Put the `vim/` directory on Vim's runtimepath:

```vim
set runtimepath+=~/PP/hive/hive-vim/vim
```

The plugin reads the port from `~/.cache/hive/vim.port` (override with
`g:hive_port_file`), connects, and reconnects every 2 seconds when hive is not
reachable. `:HiveStatus` shows the connection.

## Tests

```sh
clojure -M:test                                  # unit + Vim e2e
clojure -Sdeps "$(cat local.deps.edn)" -M:test   # also the hive-vessel e2e
```

The end-to-end tests start a real Vim inside tmux and skip when vim or tmux is
missing. hive-vessel is unpublished, so it arrives through an untracked
`local.deps.edn`; without it, the vessel e2e skips too.
