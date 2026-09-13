" hive#rpc#: the HVCP v1 dispatch entry and verb implementations.
"
" hive calls only hive#rpc#dispatch(verb, params). It never throws: every call
" returns {'ok': value} or {'err': {'category': c, 'message': m}}.
" The verb list must equal hive-vim.protocol.verbs/catalogue.
"
" Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
" SPDX-License-Identifier: MIT

" ---------------------------------------------------------------------------
" Helpers
" ---------------------------------------------------------------------------

function! s:fail(category, message) abort
  throw 'hive:' . a:category . ':' . a:message
endfunction

function! s:bool(value) abort
  return a:value ? v:true : v:false
endfunction

function! s:err(category, message) abort
  return {'err': {'category': a:category, 'message': a:message}}
endfunction

function! s:need(params, key, type) abort
  if !has_key(a:params, a:key) || type(a:params[a:key]) != a:type
    call s:fail('invalid-params', 'missing or mistyped param: ' . a:key)
  endif
  return a:params[a:key]
endfunction

function! hive#rpc#buffer(nr) abort
  let l:name = bufname(a:nr)
  let l:buftype = getbufvar(a:nr, '&buftype')
  if l:name !=# '' && l:buftype ==# ''
    let l:name = fnamemodify(l:name, ':p')
  endif
  return {'nr': a:nr,
        \ 'name': l:name,
        \ 'modified': s:bool(getbufvar(a:nr, '&modified')),
        \ 'listed': s:bool(buflisted(a:nr)),
        \ 'filetype': getbufvar(a:nr, '&filetype'),
        \ 'buftype': l:buftype}
endfunction

" Exact buffer lookup by name. bufnr() treats its argument as a pattern, which
" would match the wrong buffer for partial names.
function! s:find_buffer(name) abort
  let l:full = fnamemodify(a:name, ':p')
  for l:info in getbufinfo()
    if l:info.name ==# a:name || l:info.name ==# l:full
          \ || bufname(l:info.bufnr) ==# a:name
      return l:info.bufnr
    endif
  endfor
  call s:fail('not-found', 'no buffer named ' . a:name)
endfunction

function! s:all_buffers() abort
  return filter(range(1, bufnr('$')), {_, nr -> bufexists(nr)})
endfunction

" ---------------------------------------------------------------------------
" Verbs
" ---------------------------------------------------------------------------

function! s:eval(params) abort
  let l:code = s:need(a:params, 'code', v:t_string)
  let l:mode = get(a:params, 'mode', 'expr')
  if l:mode ==# 'ex'
    return {'value': execute(l:code)}
  elseif l:mode ==# 'expr'
    return {'value': eval(l:code)}
  endif
  call s:fail('invalid-params', 'mode must be expr or ex')
endfunction

function! s:notify(params) abort
  let l:message = s:need(a:params, 'message', v:t_string)
  let l:level = get(a:params, 'level', 'info')
  let l:hl = {'info': 'None', 'warn': 'WarningMsg', 'error': 'ErrorMsg'}
  if !has_key(l:hl, l:level)
    call s:fail('invalid-params', 'level must be info, warn or error')
  endif
  if has('popupwin')
    call popup_notification(l:message, {'highlight': l:hl[l:level]})
  endif
  execute 'echohl' l:hl[l:level]
  echomsg l:message
  echohl None
  return {'shown': v:true}
endfunction

function! s:status(params) abort
  return {'vim_version': v:version,
        \ 'pid': getpid(),
        \ 'cwd': getcwd(),
        \ 'mode': mode(),
        \ 'buffer': hive#rpc#buffer(bufnr('%'))}
endfunction

function! s:capabilities(params) abort
  let l:features = {}
  for l:feature in ['channel', 'job', 'terminal', 'popupwin', 'timers', 'python3', 'lua']
    let l:features[l:feature] = s:bool(has(l:feature))
  endfor
  return {'protocol': hive#protocol(),
        \ 'verbs': hive#rpc#verbs(),
        \ 'features': l:features}
endfunction

function! s:buffers(params) abort
  return map(filter(s:all_buffers(), {_, nr -> buflisted(nr)}),
        \ {_, nr -> hive#rpc#buffer(nr)})
endfunction

function! s:current(params) abort
  return extend(hive#rpc#buffer(bufnr('%')), {'line': line('.'), 'col': col('.')})
endfunction

function! s:buffer_info(params) abort
  let l:nr = s:find_buffer(s:need(a:params, 'buffer_name', v:t_string))
  let l:info = getbufinfo(l:nr)[0]
  return extend(hive#rpc#buffer(l:nr),
        \ {'line_count': l:info.linecount, 'windows': l:info.windows})
endfunction

function! s:special_buffers(params) abort
  return map(filter(s:all_buffers(), {_, nr -> getbufvar(nr, '&buftype') !=# ''}),
        \ {_, nr -> hive#rpc#buffer(nr)})
endfunction

function! s:switch(params) abort
  let l:nr = s:find_buffer(s:need(a:params, 'buffer', v:t_string))
  execute 'hide buffer' l:nr
  return hive#rpc#buffer(bufnr('%'))
endfunction

function! s:find(params) abort
  let l:file = s:need(a:params, 'file', v:t_string)
  execute 'hide edit' fnameescape(l:file)
  return hive#rpc#buffer(bufnr('%'))
endfunction

function! s:save(params) abort
  let l:all = get(a:params, 'all', v:false)
  if l:all is v:true
    let l:names = map(filter(s:all_buffers(),
          \ {_, nr -> buflisted(nr) && getbufvar(nr, '&modified')
          \           && getbufvar(nr, '&buftype') ==# '' && bufname(nr) !=# ''}),
          \ {_, nr -> fnamemodify(bufname(nr), ':p')})
    silent wall
    return {'saved': l:names}
  endif
  if &buftype !=# ''
    call s:fail('unsupported', 'buffer is not a file buffer: ' . &buftype)
  endif
  if expand('%') ==# ''
    call s:fail('invalid-params', 'buffer has no file name')
  endif
  silent write
  return {'saved': [expand('%:p')]}
endfunction

function! s:goto_line(params) abort
  let l:line = s:need(a:params, 'line', v:t_number)
  if l:line < 1 || l:line > line('$')
    call s:fail('invalid-params', 'line out of range 1..' . line('$'))
  endif
  call cursor(l:line, 1)
  return {'line': line('.')}
endfunction

function! s:insert(params) abort
  let l:text = s:need(a:params, 'text', v:t_string)
  if !&modifiable
    call s:fail('unsupported', 'buffer is not modifiable')
  endif
  let l:lnum = line('.')
  let l:col = col('.')
  let l:current = getline(l:lnum)
  let l:before = strpart(l:current, 0, l:col - 1)
  let l:after = strpart(l:current, l:col - 1)
  let l:parts = split(l:text, "\n", 1)
  let l:end_col = len(l:parts[-1]) + (len(l:parts) == 1 ? len(l:before) : 0) + 1
  let l:parts[0] = l:before . l:parts[0]
  let l:parts[-1] = l:parts[-1] . l:after
  call setline(l:lnum, l:parts[0])
  if len(l:parts) > 1
    call append(l:lnum, l:parts[1:])
  endif
  let l:end_line = l:lnum + len(l:parts) - 1
  call cursor(l:end_line, l:end_col)
  return {'line': l:end_line, 'col': l:end_col}
endfunction

function! s:recent(params) abort
  let l:files = map(copy(v:oldfiles), {_, f -> expand(f)})
  return filter(l:files, {_, f -> filereadable(f)})[:49]
endfunction

function! s:root() abort
  let l:dir = expand('%:p:h')
  if l:dir ==# ''
    let l:dir = getcwd()
  endif
  let l:git_dir = finddir('.git', l:dir . ';')
  if l:git_dir !=# ''
    return fnamemodify(l:git_dir, ':p:h:h')
  endif
  let l:git_file = findfile('.git', l:dir . ';')
  if l:git_file !=# ''
    return fnamemodify(l:git_file, ':p:h')
  endif
  return v:null
endfunction

function! s:project_root(params) abort
  return {'root': s:root()}
endfunction

function! s:context(params) abort
  return {'status': s:status({}),
        \ 'buffers': s:buffers({}),
        \ 'project_root': s:root(),
        \ 'tabs': tabpagenr('$'),
        \ 'windows': winnr('$')}
endfunction

" ---------------------------------------------------------------------------
" Dispatch
" ---------------------------------------------------------------------------

let s:handlers = {
      \ 'eval': function('s:eval'),
      \ 'notify': function('s:notify'),
      \ 'status': function('s:status'),
      \ 'capabilities': function('s:capabilities'),
      \ 'buffers': function('s:buffers'),
      \ 'current': function('s:current'),
      \ 'buffer-info': function('s:buffer_info'),
      \ 'special-buffers': function('s:special_buffers'),
      \ 'switch': function('s:switch'),
      \ 'find': function('s:find'),
      \ 'save': function('s:save'),
      \ 'goto-line': function('s:goto_line'),
      \ 'insert': function('s:insert'),
      \ 'recent': function('s:recent'),
      \ 'project-root': function('s:project_root'),
      \ 'context': function('s:context')}

let s:verbs = ['eval', 'notify', 'status', 'capabilities', 'buffers', 'current',
      \ 'buffer-info', 'special-buffers', 'switch', 'find', 'save', 'goto-line',
      \ 'insert', 'recent', 'project-root', 'context']

function! hive#rpc#verbs() abort
  return copy(s:verbs)
endfunction

function! hive#rpc#dispatch(verb, params) abort
  if type(a:verb) != v:t_string || !has_key(s:handlers, a:verb)
    return s:err('unknown-verb', 'unknown verb: ' . string(a:verb))
  endif
  let l:params = type(a:params) == v:t_dict ? a:params : {}
  try
    let l:value = s:handlers[a:verb](l:params)
    call json_encode(l:value)
    return {'ok': l:value}
  catch /^hive:/
    let l:match = matchlist(v:exception, '^hive:\([a-z-]\+\):\(.*\)$')
    return s:err(l:match[1], l:match[2])
  catch
    return s:err('vim-error', v:exception . ' at ' . v:throwpoint)
  endtry
endfunction
