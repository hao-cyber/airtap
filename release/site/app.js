const apple = /iPhone|iPad|iPod/.test(navigator.userAgent)
  || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
const android = /Android/.test(navigator.userAgent);
if (android) document.querySelector('.qr-row')?.classList.add('hidden');
if (apple) {
  const note = document.querySelector('.ios-note');
  if (note) note.hidden = false;
  document.querySelectorAll('[data-android-download]').forEach(link => {
    link.href = '#start';
    link.textContent = '查看安卓安装步骤';
  });
}
function showHelpTarget() {
  // Help permalinks open the relevant answer; /help begins at the setup steps.
  const target = location.hash ? document.getElementById(location.hash.slice(1))
    : /^\/help\/?$/.test(location.pathname) ? document.getElementById('start') : null;
  if (!target) return;
  if (target.tagName === 'DETAILS') target.open = true;
  target.scrollIntoView();
}
showHelpTarget();
window.addEventListener('hashchange', showHelpTarget);
