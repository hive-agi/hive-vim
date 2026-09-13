" hive#: connection lifecycle, handshake and events for HVCP v1.
"
" Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
" SPDX-License-Identifier: MIT

let s:protocol = [1, 0]
let s:ch = v:null
let s:session = ''
let s:state = 'disconnected'
let s:last_error = ''
let s:timer = -1

function! s:open() abort
  return type(s:ch) == v:t_channel && ch_status(s:ch) ==# 'open'
endfunction

function! s:port() abort
  if !filereadable(g:hive_port_file)
    return ''
  endif
  let l:lines = readfile(g:hive_port_file, '', 1)
  return empty(l:lines) ? '' : trim(l:lines[0])
endfunction

function! s:on_close(channel) abort
  let s:session = ''
  let s:state = 'disconnected'
endfunction

function! hive#hello() abort
  return {'protocol': s:protocol,
        \ 'client': 'vim',
        \ 'vim_version': v:version,
        \ 'pid': getpid(),
        \ 'cwd': getcwd(),
        \ 'verbs': hive#rpc#verbs()}
endfunction

function! hive#protocol() abort
  return copy(s:protocol)
endfunction

function! hive#connect() abort
  if s:open()
    return v:true
  endif
  let l:port = s:port()
  if l:port !~# '^\d\+$'
    let s:state = 'no-port'
    return v:false
  endif
  let s:ch = ch_open('localhost:' . l:port,
        \ {'mode': 'json', 'waittime': 200, 'close_cb': function('s:on_close')})
  if ch_status(s:ch) !=# 'open'
    let s:state = 'unreachable'
    return v:false
  endif
  let l:reply = ch_evalexpr(s:ch, ['hello', hive#hello()], {'timeout': 2000})
  if type(l:reply) == v:t_dict && get(l:reply, 'accepted', v:false) is v:true
    let s:session = l:reply.session
    let s:state = 'connected'
    let s:last_error = ''
    return v:true
  endif
  let s:last_error = type(l:reply) == v:t_dict
        \ ? get(l:reply, 'reason', 'rejected') : 'no hello reply'
  let s:state = 'rejected'
  call ch_close(s:ch)
  return v:false
endfunction

function! hive#disconnect() abort
  if s:open()
    call ch_close(s:ch)
  endif
  let s:session = ''
  let s:state = 'disconnected'
endfunction

function! hive#tick() abort
  if !s:open()
    call hive#connect()
  endif
endfunction

function! hive#start() abort
  if s:timer == -1
    let s:timer = timer_start(g:hive_reconnect_ms, {-> hive#tick()}, {'repeat': -1})
  endif
  call hive#tick()
endfunction

function! hive#connected() abort
  return s:open() && s:session !=# ''
endfunction

function! hive#event(type) abort
  if !hive#connected()
    return
  endif
  call ch_sendexpr(s:ch, ['event',
        \ {'type': a:type, 'buffer': hive#rpc#buffer(bufnr('%'))}])
endfunction

function! hive#status() abort
  let l:text = 'hive: ' . s:state
  if s:session !=# ''
    let l:text .= ' (' . s:session . ')'
  endif
  if s:last_error !=# ''
    let l:text .= ' last error: ' . s:last_error
  endif
  return l:text
endfunction
