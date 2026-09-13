" hive.vim: connect Vim to hive over HVCP v1 (docs/protocol.md).
"
" Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
" SPDX-License-Identifier: MIT

if exists('g:loaded_hive') || !has('channel') || !has('timers')
  finish
endif
let g:loaded_hive = 1

let g:hive_port_file = get(g:, 'hive_port_file', expand('~/.cache/hive/vim.port'))
let g:hive_reconnect_ms = get(g:, 'hive_reconnect_ms', 2000)

command! HiveStatus echo hive#status()
command! HiveConnect call hive#connect()
command! HiveDisconnect call hive#disconnect()

augroup hive
  autocmd!
  autocmd VimEnter * call hive#start()
  autocmd BufEnter * call hive#event('buf-enter')
  autocmd BufWritePost * call hive#event('buf-write')
  autocmd FocusGained * call hive#event('focus')
  autocmd VimLeavePre * call hive#event('vim-leave') | call hive#disconnect()
augroup END

if v:vim_did_enter
  call hive#start()
endif
