'use strict';

const day = 86400;
const statusNames = {
  BACKLOG: 'Бэклог', DEVELOPMENT: 'Разработка', READY_FOR_QA: 'Готова к QA',
  IN_QA: 'На проверке', DONE: 'Готово'
};
const causeNames = {
  QA_INPUT: 'Ошибка запуска QA', ENVIRONMENT: 'Стенд', REPORT_CODE: 'Код отчёта',
  CI: 'CI', UNDER_REVIEW: 'На разборе'
};
const actionNames = { SERVICE_RESTART: 'Перезапуск сервиса', REDEPLOY: 'Повторное развёртывание' };
const dateFormat = new Intl.DateTimeFormat('ru-RU', {
  day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', timeZone: 'UTC'
});
const get = id => document.getElementById(id);

let queueData;
let failureData;
let selectedIssue;
let selectedFailure;
let requestId = 0;

function escape(text) {
  return String(text).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;').replaceAll("'", '&#39;');
}

function duration(seconds) {
  if (seconds === 0) return '0 мин';
  if (seconds < 60) return seconds + ' с';
  const minutes = Math.floor(seconds / 60);
  const hours = Math.floor(minutes / 60);
  return [hours ? hours + ' ч' : '', minutes % 60 ? minutes % 60 + ' мин' : ''].filter(Boolean).join(' ');
}

function moment(value) {
  return dateFormat.format(new Date(value));
}

function metric(label, value, note, icon, tone = '') {
  return `<article class="metric">
    <div class="metric-label"><span class="metric-icon ${tone}" aria-hidden="true">${icon}</span>${escape(label)}</div>
    <strong class="metric-value">${escape(value)}</strong>
    <span class="metric-note">${escape(note)}</span>
  </article>`;
}

function count(id, value) {
  get(id).textContent = value;
}

async function api(path) {
  const response = await fetch(path);
  if (!response.ok) throw new Error('HTTP ' + response.status);
  return response.json();
}

function refreshQueue() {
  if (!queueData) return;
  get('queue-metrics').innerHTML = [
    metric('Готовы к QA', queueData.ready, 'Текущая очередь', '✓', 'good'),
    metric('Ждут больше суток', queueData.overOneDay, 'По текущему заходу', '◷', 'warn'),
    metric('Возвраты в разработку', queueData.returnsToDevelopment, 'По истории загруженных задач', '↩')
  ].join('');
  count('queue-count', queueData.issues.length);
  get('queue-rows').innerHTML = queueData.issues.map(issue => {
    const age = escape(duration(issue.currentWait));
    return `<tr>
      <td><button class="select-row" type="button" data-issue="${escape(issue.key)}"
        aria-controls="issue-detail">${escape(issue.key)}</button><span class="row-name">${escape(issue.title)}</span></td>
      <td class="numeric">${issue.currentWait > day ? `<span class="age-alert">${age}</span>` : age}</td>
      <td class="numeric">${issue.readyVisits}</td>
    </tr>`;
  }).join('') || '<tr><td class="empty" colspan="3">В очереди пока пусто.</td></tr>';

  selectIssue(queueData.issues.find(issue => issue.key === selectedIssue)
    || queueData.issues.find(issue => issue.readyVisits > 1) || queueData.issues[0]);
}

function markSelected(container, attribute, key) {
  for (const button of get(container).querySelectorAll(`[data-${attribute}]`)) {
    const selected = button.dataset[attribute] === key;
    button.setAttribute('aria-pressed', String(selected));
    button.closest('tr').classList.toggle('selected', selected);
  }
}

function selectIssue(issue) {
  selectedIssue = issue?.key;
  markSelected('queue-rows', 'issue', selectedIssue);
  if (!issue) {
    get('issue-detail').innerHTML = '<p class="detail-intro">Когда появится задача, здесь будет её история.</p>';
    return;
  }

  const timeline = issue.timeline.map(step => `<li class="${step.current ? 'current' : ''}">
    <div class="step-title"><span>${escape(statusNames[step.status])}</span><span class="numeric">${escape(duration(step.seconds))}</span></div>
    <time class="step-date" datetime="${escape(step.start)}">${escape(moment(step.start))}${step.current ? ' · сейчас' : ''}</time>
  </li>`).join('');

  get('issue-detail').innerHTML = `
    <div class="detail-head"><span class="detail-key">${escape(issue.key)}</span><span class="pill ready">Готова к QA</span></div>
    <h2>${escape(issue.title)}</h2>
    <dl class="stats-list">
      <div><dt>Ожидание сейчас</dt><dd>${escape(duration(issue.currentWait))}</dd></div>
      <div><dt>Ожидание суммарно</dt><dd>${escape(duration(issue.totalWait))}</dd></div>
      <div><dt>На проверке суммарно</dt><dd>${escape(duration(issue.totalTesting))}</dd></div>
      <div><dt>Возвраты в разработку</dt><dd>${issue.returns}</dd></div>
    </dl>
    <h3>История статусов</h3>
    <ol class="timeline">${timeline}</ol>`;
}

function renderFailureReport() {
  const data = failureData;
  get('failure-metrics').innerHTML = [
    metric('Сбои отчётов', data.failed, `Из ${data.runs} прогонов за период`, '!'),
    metric('Ошибки запуска QA', data.qaFailures, 'Причина подтверждена', '✓', 'good'),
    metric('Повторы прогонов', data.retries, 'Связаны со сбоями за период', '↻'),
    metric('Операции на стенде из-за QA', data.qaImpact.restarts + data.qaImpact.redeploys,
      `Перезапуски: ${data.qaImpact.restarts} · развёртывания: ${data.qaImpact.redeploys}`, '◷', 'warn')
  ].join('');

  get('tester-rows').innerHTML = data.testers.map(person => `<tr>
    <td>${escape(person.name)}</td><td class="numeric">${person.failedReports}</td>
    <td class="numeric">${person.impact.restarts}</td><td class="numeric">${person.impact.redeploys}</td>
    <td class="numeric">${escape(duration(person.impact.workSeconds))}</td>
  </tr>`).join('') || '<tr><td class="empty" colspan="5">За период нет подтверждённых ошибок запуска QA.</td></tr>';

  renderFailureRows();
}

function causeBadge(cause) {
  const tone = cause === 'QA_INPUT' ? 'qa' : cause === 'UNDER_REVIEW' ? 'pending' : '';
  return `<span class="pill ${tone}">${escape(causeNames[cause])}</span>`;
}

function renderFailureRows() {
  if (!failureData) return;
  const cause = get('cause-filter').value;
  const rows = failureData.failures.filter(row => cause === 'all' || cause === row.cause);
  count('failure-count', rows.length);
  get('failure-rows').innerHTML = rows.map(row => `<tr>
    <td><button class="select-row" type="button" data-failure="${escape(row.run.id)}"
      aria-controls="failure-detail">#${escape(row.run.id)}</button><span class="row-name">${escape(row.run.issueKey)}</span></td>
    <td>${causeBadge(row.cause)}</td><td class="numeric">${row.retries.length}</td>
    <td class="numeric">${row.actions.length || '—'}</td>
  </tr>`).join('') || '<tr><td class="empty" colspan="4">Сбои по этому фильтру отсутствуют.</td></tr>';

  selectFailure(rows.find(row => row.run.id === selectedFailure) || rows[0]);
}

function selectFailure(row) {
  selectedFailure = row?.run.id;
  markSelected('failure-rows', 'failure', selectedFailure);
  if (!row) {
    get('failure-detail').innerHTML = '<p class="detail-intro">Выбери прогон, чтобы увидеть разбор.</p>';
    return;
  }

  const retries = row.retries.map(run => `<li><strong>#${escape(run.id)} · ${escape(run.startedBy)}</strong>
    <span>${escape(moment(run.startedAt))} · ${run.reportStatus === 'GENERATED' ? 'Отчёт сформирован' : 'Отчёт упал'}</span></li>`).join('');
  const actions = row.actions.map(action => `<li><strong>${escape(actionNames[action.type])} · ${escape(duration(action.seconds))}</strong>
    <span>${escape(action.id)} · прогон #${escape(action.runId)}</span><pre class="log">${escape(action.evidence)}</pre></li>`).join('');

  get('failure-detail').innerHTML = `
    <div class="detail-head"><span class="detail-key">#${escape(row.run.id)} · ${escape(row.run.issueKey)}</span>${causeBadge(row.cause)}</div>
    <h2>Разбор сбоя</h2>
    <p class="detail-intro">${escape(row.detail)}</p>
    <dl class="stats-list">
      <div><dt>Исходный запуск</dt><dd>${escape(row.run.startedBy)}</dd></div>
      ${row.actor ? `<div><dt>Чьё действие вызвало сбой</dt><dd>${escape(row.actor)}</dd></div>` : ''}
    </dl>
    ${row.evidence ? `<h3>Лог исходного прогона</h3><pre class="log">${escape(row.evidence)}</pre>` : ''}
    <h3>Повторные прогоны</h3>
    ${retries ? `<ul class="run-list">${retries}</ul>` : '<p class="detail-intro">Связанных повторов нет.</p>'}
    <h3>Операции на стенде</h3>
    ${actions ? `<ul class="run-list">${actions}</ul>` : '<p class="detail-intro">Связанных операций нет.</p>'}`;
}

async function loadQueue() {
  queueData = await api('/api/queue');
  get('project').textContent = queueData.project;
  get('snapshot').dateTime = queueData.asOf;
  get('snapshot').textContent = 'Срез · ' + moment(queueData.asOf) + ' UTC';
  refreshQueue();
}

async function loadFailures() {
  const id = ++requestId;
  const period = get('period').value;
  try {
    const data = await api('/api/failures?days=' + encodeURIComponent(period));
    if (id === requestId) {
      failureData = data;
      renderFailureReport();
      get('error').hidden = true;
    }
  } catch (error) {
    if (id === requestId) showError(error);
  }
}

function showError(error) {
  console.error(error);
  get('error').hidden = false;
}

function navigate() {
  const failures = location.hash === '#failures';
  get('queue').hidden = failures;
  get('failures').hidden = !failures;
  get('queue-link').setAttribute('aria-current', failures ? 'false' : 'page');
  get('failures-link').setAttribute('aria-current', failures ? 'page' : 'false');
  if (failures && !failureData) loadFailures();
}

get('queue-rows').addEventListener('click', event => {
  const button = event.target.closest('[data-issue]');
  if (button) selectIssue(queueData.issues.find(issue => issue.key === button.dataset.issue));
});
get('failure-rows').addEventListener('click', event => {
  const button = event.target.closest('[data-failure]');
  if (button) selectFailure(failureData.failures.find(row => row.run.id === button.dataset.failure));
});
get('cause-filter').addEventListener('change', renderFailureRows);
get('period').addEventListener('change', loadFailures);
get('reload').addEventListener('click', () => location.reload());
window.addEventListener('hashchange', navigate);
navigate();
loadQueue().catch(showError);
