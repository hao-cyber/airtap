const apple = /iPhone|iPad|iPod/.test(navigator.userAgent)
  || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
const android = /Android/.test(navigator.userAgent);
if (android) document.querySelector('.qr-row')?.classList.add('hidden');
if (apple) {
  const note = document.querySelector('.ios-note');
  if (note) note.hidden = false;
  document.querySelectorAll('[data-android-download]').forEach(link => {
    link.href = '#start';
    link.textContent = '查看安卓接入步骤';
  });
}
const page = location.pathname.replace(/\/$/, '').slice(1);
document.querySelector(`[data-nav="${page === 'help' ? 'help' : page === 'install' ? 'install' : 'none'}"]`)
  ?.setAttribute('aria-current', 'page');

const search = document.getElementById('help-query');
const answers = [...document.querySelectorAll('.help-group details')];
const groups = [...document.querySelectorAll('.help-group')];
const clearSearch = document.getElementById('clear-search');
function filterHelp() {
  const terms = search.value.trim().toLocaleLowerCase().split(/\s+/).filter(Boolean);
  let count = 0;
  answers.forEach(answer => {
    const text = answer.textContent.toLocaleLowerCase();
    answer.hidden = !terms.every(term => text.includes(term));
    if (!answer.hidden) count++;
  });
  groups.forEach(group => { group.hidden = ![...group.querySelectorAll('details')].some(answer => !answer.hidden); });
  document.getElementById('search-status').textContent = terms.length ? `找到 ${count} 个相关问题` : '';
  document.getElementById('search-empty').hidden = count !== 0;
  clearSearch.hidden = terms.length === 0;
}
if (search) {
  document.querySelector('.help-search').hidden = false;
  search.addEventListener('input', filterHelp);
  clearSearch.addEventListener('click', () => { search.value = ''; filterHelp(); search.focus(); });
}
function showHelpTarget() {
  let id;
  try { id = decodeURIComponent(location.hash.slice(1)); } catch { return; }
  const target = id ? document.getElementById(id) : null;
  if (!target) return;
  if (search && target.closest('.help-group')) { search.value = ''; filterHelp(); }
  if (target.tagName === 'DETAILS') target.open = true;
  target.scrollIntoView();
}
showHelpTarget();
window.addEventListener('hashchange', showHelpTarget);

document.querySelectorAll('[data-copy]').forEach(button => {
  button.addEventListener('click', async () => {
    const text = document.getElementById(button.dataset.copy).textContent.trim();
    const status = button.parentElement.querySelector('.copy-status');
    try {
      await navigator.clipboard.writeText(text);
      status.textContent = '已复制。回到你自己的 AI 对话粘贴即可。';
    } catch {
      status.textContent = '未能自动复制，请长按或选中上面的文字复制。';
    }
  });
});
