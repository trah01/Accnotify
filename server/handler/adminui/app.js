'use strict';
const $ = id => document.getElementById(id);
const state = { loggedIn: false, view: 'overview', page: 1, limit: 50, query: '', devicesTotal: 0, deviceRequest: 0, selected: new Set(), recipientPage: 0, recipientTotal: 0, recipientLoaded: 0, recipientsBusy: false, total: 0, logCursor: -1, logInstance: '', logPaused: false, logsBusy: false, jobTimer: null, jobID: '', sending: false };
const number = value => Number(value || 0).toLocaleString('zh-CN');
const date = value => new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(value));
function notice(message, error = false) { $('notice').textContent = message; $('notice').classList.toggle('error', error); $('notice').hidden = !message; }
function showLogin() {
  state.loggedIn = false;
  $('login').hidden = false; $('app').hidden = true; $('boot-status').hidden = true;
  clearTimeout(state.jobTimer);
  state.logCursor = -1; state.logInstance = ''; $('log-content').textContent = '';
}
async function api(path, options = {}) {
  const headers = { ...options.headers };
  if (options.body) headers['Content-Type'] = 'application/json';
  const response = await fetch('/admin/api' + path, { ...options, headers, credentials: 'same-origin', cache: 'no-store' });
  let data;
  try { data = await response.json(); } catch (_) { throw new Error('后台响应异常，请检查服务状态'); }
  if (!response.ok) {
    if (response.status === 401 && path !== '/login') showLogin();
    const error = new Error(data.error || '请求失败'); error.data = data; error.status = response.status; throw error;
  }
  return data;
}
function updated() { $('updated').textContent = '更新于 ' + new Date().toLocaleTimeString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }); }
function showApp() {
  state.loggedIn = true; $('login').hidden = true; $('app').hidden = false; $('boot-status').hidden = true;
  $('password').value = ''; $('login-error').hidden = true;
  const savedJob = sessionStorage.getItem('accnotify_admin_job');
  if (savedJob) { state.jobID = savedJob; pollJob(); }
}
async function loadDashboard() {
  const data = await api('/dashboard');
  state.total = data.devices;
  const fields = { 'stat-devices': data.devices, 'stat-online': data.online_devices, 'stat-new': data.today_devices, 'stat-messages': data.messages, 'stat-today': data.today_messages, 'stat-pending': data.pending_messages };
  for (const [id, value] of Object.entries(fields)) $(id).textContent = number(value);
  $('stat-yesterday').textContent = '昨日 ' + number(data.yesterday_messages);
  $('all-recipient').textContent = '向全部 ' + number(state.total) + ' 台已注册设备发送，离线设备将在重连后接收。';
  const rows = $('trend-rows'); rows.replaceChildren();
  const max = Math.max(1, ...data.days.map(day => day.messages));
  for (const day of data.days) {
    const tr = document.createElement('tr');
    const label = document.createElement('td'); label.textContent = day.date; tr.append(label);
    const messages = document.createElement('td'); const cell = document.createElement('div'); cell.className = 'trend-cell';
    const count = document.createElement('span'); count.textContent = number(day.messages);
    const bar = document.createElement('meter'); bar.min = 0; bar.max = max; bar.value = day.messages; bar.setAttribute('aria-label', day.date + ' 推送 ' + day.messages + ' 条');
    cell.append(count, bar); messages.append(cell); tr.append(messages);
    const devices = document.createElement('td'); devices.textContent = number(day.devices); tr.append(devices); rows.append(tr);
  }
  updated();
}
function addCell(row, text, className = '') { const cell = document.createElement('td'); cell.textContent = text; cell.className = className; row.append(cell); return cell; }
async function loadDevices() {
  const request = ++state.deviceRequest;
  const params = new URLSearchParams({ page: state.page, limit: state.limit, q: state.query });
  const data = await api('/devices?' + params);
  if (request !== state.deviceRequest) return;
  state.devicesTotal = data.total;
  const rows = $('device-rows'); rows.replaceChildren();
  $('device-total').textContent = '共 ' + number(data.total) + ' 台设备';
  $('device-empty').hidden = data.devices.length !== 0;
  for (const device of data.devices) {
    const row = document.createElement('tr');
    const name = addCell(row, device.name || '未命名设备', 'device-name');
    const hint = document.createElement('small'); hint.textContent = '#' + device.id + ' · ' + (device.platform === 'pc' ? '电脑' : '手机') + ' · ' + (device.endpoint_id || device.key_hint); name.append(hint);
    addCell(row, device.push_group_id ? '#' + device.push_group_id + ' · ' + device.push_key_hint : '—');
    const connection = addCell(row, device.online ? '在线' : '离线'); connection.className = device.online ? 'status online' : 'status';
    const key = addCell(row, device.has_public_key ? '已上报' : '未上报'); if (!device.has_public_key) key.className = 'status missing';
    addCell(row, date(device.created_at)); addCell(row, date(device.last_seen));
    const action = document.createElement('td'); const send = document.createElement('button'); send.textContent = '发送通知';
    send.setAttribute('aria-label', '向设备 ' + (device.name || device.id) + ' 发送通知'); send.disabled = state.sending;
    send.addEventListener('click', () => {
      state.selected = new Set([device.id]); updateRecipientSelection();
      document.querySelector('input[name=target][value=devices]').checked = true; targetChanged(); switchView('push'); $('push-body').focus();
    }); action.append(send); row.append(action); rows.append(row);
  }
  renderPages(); updated();
}
function renderPages() {
  const pages = Math.max(1, Math.ceil(state.devicesTotal / state.limit));
  const nav = $('page-buttons'); nav.replaceChildren();
  const add = (label, page, disabled = false) => {
    const button = document.createElement('button'); button.textContent = label; button.disabled = disabled;
    if (page === state.page && /^\d+$/.test(label)) { button.className = 'current'; button.setAttribute('aria-current', 'page'); }
    button.addEventListener('click', () => { state.page = page; loadDevices().catch(error => notice(error.message, true)); }); nav.append(button);
  };
  add('上一页', state.page - 1, state.page <= 1);
  const visible = [...new Set([1, state.page - 1, state.page, state.page + 1, pages])].filter(page => page >= 1 && page <= pages).sort((a, b) => a - b);
  let previous = 0;
  for (const page of visible) { if (previous && page - previous > 1) { const dots = document.createElement('span'); dots.textContent = '…'; nav.append(dots); } add(String(page), page); previous = page; }
  add('下一页', state.page + 1, state.page >= pages);
}
async function refresh() {
  if (state.view === 'overview' || state.view === 'push') await loadDashboard();
  if (state.view === 'push') await loadRecipients(true);
  if (state.view === 'devices') await loadDevices();
  if (state.view === 'logs') await loadLogs(true);
}
function switchView(view) {
  state.view = view; notice('');
  const titles = { overview: '统计面板', devices: '设备列表', push: '消息推送', logs: '运行日志' };
  for (const key of Object.keys(titles)) $(key).hidden = key !== view;
  for (const button of document.querySelectorAll('[data-view]')) { const selected = button.dataset.view === view; button.classList.toggle('selected', selected); if (selected) button.setAttribute('aria-current', 'page'); else button.removeAttribute('aria-current'); }
  $('view-title').textContent = titles[view];
  refresh().catch(error => notice(error.message, true));
}
function updateRecipientSelection() {
  $('recipient-count').textContent = '已选 ' + number(state.selected.size) + ' 台';
  for (const input of document.querySelectorAll('#recipient-options input')) input.checked = state.selected.has(Number(input.value));
}
async function loadRecipients(reset = false) {
  if (state.recipientsBusy) return;
  if (!reset && state.recipientPage && state.recipientLoaded >= state.recipientTotal) return;
  state.recipientsBusy = true;
  const more = $('recipient-more');
  more.hidden = false; more.disabled = true; more.textContent = '正在加载…';
  $('recipient-list').setAttribute('aria-busy', 'true');
  try {
    const page = reset ? 1 : state.recipientPage + 1;
    const data = await api('/devices?' + new URLSearchParams({ page, limit: 100 }));
    if (reset) { $('recipient-options').replaceChildren(); state.recipientLoaded = 0; $('recipient-list').scrollTop = 0; }
    state.recipientPage = page; state.recipientTotal = data.total;
    state.recipientLoaded += data.devices.length;
    for (const device of data.devices) {
      const row = document.createElement('label'); row.className = 'recipient-option';
      const input = document.createElement('input'); input.type = 'checkbox'; input.value = device.id;
      input.checked = state.selected.has(device.id); input.disabled = state.sending;
      input.addEventListener('change', () => { if (input.checked) state.selected.add(device.id); else state.selected.delete(device.id); updateRecipientSelection(); });
      const name = document.createElement('span'); name.className = 'recipient-name'; name.textContent = device.name || '未命名设备';
      const hint = document.createElement('small'); hint.textContent = '#' + device.id + ' · ' + (device.platform === 'pc' ? '电脑' : '手机') + ' · ' + (device.endpoint_id || device.key_hint) + (device.has_public_key ? '' : ' · 未上报公钥'); name.append(hint);
      const status = document.createElement('span'); status.className = device.online ? 'status online' : 'status'; status.textContent = device.online ? '在线' : '离线';
      row.append(input, name, status); $('recipient-options').append(row);
    }
    updateRecipientSelection();
    more.textContent = data.total ? '加载更多设备' : '暂无设备';
    more.hidden = data.total > 0 && state.recipientLoaded >= data.total;
  } catch (error) { more.textContent = '加载失败，点击重试'; throw error; }
  finally { state.recipientsBusy = false; more.disabled = state.sending || state.recipientTotal === 0 && more.textContent === '暂无设备'; $('recipient-list').setAttribute('aria-busy', 'false'); }
}
function targetChanged() { const all = document.querySelector('input[name=target]:checked').value === 'all'; $('device-selector').hidden = all; $('all-recipient').hidden = !all; }
function pushOperationChanged() {
  const operation = $('push-operation').value;
  const deleting = operation === 'delete';
  $('push-id').required = operation !== 'send';
  $('push-id').placeholder = operation === 'send' ? '可选，相同 ID 会替换原消息' : '填写原消息的 ID';
  $('push-id-hint').hidden = operation === 'send';
  $('push-id-hint').textContent = deleting ? '删除所选设备上此 ID 的历史消息和通知。' : '按完整内容替换；需保留的标题、分组和图片等请一并填写。';
  $('push-content').hidden = deleting;
  $('push-content').disabled = state.sending || deleting;
  $('push-body').required = !deleting;
  $('send-button').textContent = state.sending ? '正在提交…' : ({ send: '发送通知', update: '更新消息', delete: '删除消息' })[operation];
}
function lockPush(lock) {
  state.sending = lock;
  for (const input of document.querySelectorAll('#push-form input, #push-form textarea, #push-form select, #push-form button')) input.disabled = lock;
  pushOperationChanged();
}
function confirmAll() {
  const action = ({ send: '发送通知', update: '更新消息', delete: '删除消息' })[$('push-operation').value];
  $('confirm-title').textContent = '向全部设备' + action + '？';
  $('confirm-submit').textContent = '确认' + action;
  $('confirm-text').textContent = '本次操作涉及 ' + number(state.total) + ' 台设备，请确认消息内容和接收范围。';
  const dialog = $('confirm-dialog'); dialog.returnValue = 'cancel'; dialog.showModal();
  return new Promise(resolve => dialog.addEventListener('close', () => resolve(dialog.returnValue === 'send'), { once: true }));
}
async function pollJob() {
  if (!state.loggedIn || !state.jobID) return;
  lockPush(true); $('push-result').hidden = false;
  try {
    const job = await api('/push/' + encodeURIComponent(state.jobID));
    $('push-status').textContent = job.status === 'completed' ? (job.failed ? '发送完成，部分设备提交失败' : '发送完成') : '正在发送 ' + number(job.processed) + ' / ' + number(job.total);
    $('push-progress').max = job.total || 1; $('push-progress').value = job.processed;
    $('push-counts').textContent = '已提交 ' + number(job.submitted) + '，在线投递队列 ' + number(job.online) + '，离线待投递 ' + number(job.offline) + '，失败 ' + number(job.failed) + '。提交成功不代表用户已阅读。';
    $('push-failures').replaceChildren();
    for (const failure of job.failures) { const item = document.createElement('li'); item.textContent = '设备 #' + failure.device_id + '：' + failure.error; $('push-failures').append(item); }
    if (job.failed > job.failures.length) { const item = document.createElement('li'); item.textContent = '仅展示前 ' + job.failures.length + ' 条失败原因'; $('push-failures').append(item); }
    if (job.status === 'completed') { sessionStorage.removeItem('accnotify_admin_job'); state.jobID = ''; lockPush(false); }
    else state.jobTimer = setTimeout(pollJob, 1500);
  } catch (error) {
    notice(error.message, true);
    if (error.status === 404) { sessionStorage.removeItem('accnotify_admin_job'); state.jobID = ''; lockPush(false); $('push-status').textContent = '发送记录已失效'; }
    else if (state.loggedIn) state.jobTimer = setTimeout(pollJob, 4000);
  }
}
async function loadLogs(force = false) {
  if (state.logsBusy || (!force && state.logPaused)) return;
  state.logsBusy = true;
  try {
    let data = await api('/logs?cursor=' + state.logCursor);
    if (state.logInstance && data.instance !== state.logInstance) { state.logCursor = -1; $('log-content').textContent = ''; data = await api('/logs?cursor=-1'); }
    state.logInstance = data.instance; state.logCursor = data.cursor;
    const area = $('log-content'); const atBottom = area.scrollTop + area.clientHeight >= area.scrollHeight - 36;
    area.textContent = (area.textContent + data.text).slice(-1024 * 1024);
    if (atBottom) area.scrollTop = area.scrollHeight;
    $('log-state').textContent = state.logPaused ? '已暂停' : (data.truncated ? '实时更新 · 较早日志已移出缓冲' : '实时更新');
    updated();
  } finally { state.logsBusy = false; }
}
$('login-form').addEventListener('submit', async event => {
  event.preventDefault(); const button = event.submitter; button.disabled = true; button.textContent = '正在登录…';
  try { await api('/login', { method: 'POST', body: JSON.stringify({ username: $('username').value, password: $('password').value }) }); showApp(); await refresh(); }
  catch (error) { $('login-error').textContent = error.message; $('login-error').hidden = false; }
  finally { button.disabled = false; button.textContent = '登录'; }
});
$('logout').addEventListener('click', async () => { try { await api('/logout', { method: 'POST' }); showLogin(); } catch (error) { notice(error.message, true); } });
for (const button of document.querySelectorAll('[data-view]')) button.addEventListener('click', () => switchView(button.dataset.view));
$('refresh').addEventListener('click', async () => { $('refresh').disabled = true; try { await refresh(); notice(''); } catch (error) { notice(error.message, true); } finally { $('refresh').disabled = false; } });
$('search-form').addEventListener('submit', event => { event.preventDefault(); state.query = $('device-search').value.trim(); state.page = 1; loadDevices().catch(error => notice(error.message, true)); });
$('page-limit').addEventListener('change', () => { state.limit = Number($('page-limit').value); state.page = 1; loadDevices().catch(error => notice(error.message, true)); });
$('recipient-more').addEventListener('click', () => loadRecipients().catch(error => notice(error.message, true)));
$('recipient-list').addEventListener('scroll', () => {
  const list = $('recipient-list');
  if (list.scrollTop + list.clientHeight >= list.scrollHeight - 60 && state.recipientLoaded < state.recipientTotal) loadRecipients().catch(error => notice(error.message, true));
});
for (const input of document.querySelectorAll('input[name=target]')) input.addEventListener('change', targetChanged);
$('push-operation').addEventListener('change', pushOperationChanged);
$('push-form').addEventListener('submit', async event => {
  event.preventDefault(); if (state.sending) return; notice('');
  const target = document.querySelector('input[name=target]:checked').value;
  if (target === 'devices' && !state.selected.size) { notice('请勾选接收设备', true); return; }
  const operation = $('push-operation').value;
  const messageID = $('push-id').value;
  if (operation !== 'send' && !messageID.trim()) { notice('请填写原消息的 ID', true); return; }
  if (new TextEncoder().encode(messageID).length > 256) { notice('消息 ID 不能超过 256 字节', true); return; }
  if (operation !== 'delete' && !$('push-body').value.trim()) { notice('请填写消息正文', true); return; }
  const body = { target, device_ids: [...state.selected], confirm_all: target === 'all', id: messageID };
  if (operation === 'delete') body.delete = '1';
  else Object.assign(body, { title: $('push-title').value, [$('push-format').value]: $('push-body').value, group: $('push-group').value, url: $('push-url').value, image: $('push-image').value });
  lockPush(true);
  try {
    if (target === 'all') { await loadDashboard(); if (!(await confirmAll())) { lockPush(false); return; } }
    const result = await api('/push', { method: 'POST', body: JSON.stringify(body) });
    state.jobID = result.job_id; sessionStorage.setItem('accnotify_admin_job', state.jobID); pollJob();
  } catch (error) {
    if (error.data?.job_id) { state.jobID = error.data.job_id; sessionStorage.setItem('accnotify_admin_job', state.jobID); pollJob(); }
    else lockPush(false);
    notice(error.message, true);
  }
});
$('pause-logs').addEventListener('click', () => { state.logPaused = !state.logPaused; $('pause-logs').textContent = state.logPaused ? '继续' : '暂停'; $('log-state').textContent = state.logPaused ? '已暂停' : '实时更新'; if (!state.logPaused) loadLogs().catch(error => notice(error.message, true)); });
$('download-logs').addEventListener('click', () => { const url = URL.createObjectURL(new Blob([$('log-content').textContent], { type: 'text/plain;charset=utf-8' })); const anchor = document.createElement('a'); anchor.href = url; anchor.download = 'Accnotify-运行日志-' + new Date().toISOString().slice(0, 10) + '.log'; anchor.click(); setTimeout(() => URL.revokeObjectURL(url), 1000); });
setInterval(() => { if (state.loggedIn && state.view === 'logs' && !document.hidden) loadLogs().catch(error => notice(error.message, true)); }, 2000);
setInterval(() => { if (state.loggedIn && state.view === 'overview' && !document.hidden) loadDashboard().catch(error => notice(error.message, true)); }, 30000);
(async () => { try { await loadDashboard(); showApp(); } catch (error) { showLogin(); if (!error.message.includes('先登录')) { $('login-error').textContent = error.message; $('login-error').hidden = false; } } })();
