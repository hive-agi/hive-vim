# hive-vim

Vim vessel addon for hive. hive controls Vim through **HVCP v1**, the hive Vim
Control Protocol, carried over Vim's built-in JSON channel. Spec:
[docs/protocol.md](docs/protocol.md).

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
clojure -M:test
```

The end-to-end test starts a real Vim inside tmux and skips when either is
missing.
