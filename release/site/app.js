const apple = /iPhone|iPad|iPod/.test(navigator.userAgent);
const android = /Android/.test(navigator.userAgent);
if (android) document.querySelector('.qr-row').classList.add('hidden');
if (apple) {
  document.querySelector('.ios-note').hidden = false;
  document.querySelectorAll('.button').forEach(a => { a.href = '#start'; a.textContent = '查看安卓手机安装步骤'; });
}
function showHelpTarget() {
  // A direct help link opens only its answer; first-time visitors start with the steps.
  const target = location.hash ? document.getElementById(location.hash.slice(1))
    : /^\/help\/?$/.test(location.pathname) ? document.getElementById('start') : null;
  if (!target) return;
  if (target.tagName === 'DETAILS') target.open = true;
  target.scrollIntoView();
}
showHelpTarget();
window.addEventListener('hashchange', showHelpTarget);
