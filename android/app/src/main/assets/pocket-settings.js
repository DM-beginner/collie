(function (connection) {
  // This adds one entry to Collie's existing Settings index. It exposes no Java bridge or token.
  if (window.__colliePocketSettings) {
    window.__colliePocketSettings.connection = connection;
    window.__colliePocketSettings.render();
    return;
  }
  var state = { connection: connection, panel: null };
  function icon(paths) {
    var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('width', '20');
    svg.setAttribute('height', '20');
    svg.setAttribute('fill', 'none');
    svg.setAttribute('stroke', 'currentColor');
    svg.setAttribute('stroke-width', '2');
    svg.setAttribute('stroke-linecap', 'round');
    svg.setAttribute('stroke-linejoin', 'round');
    svg.setAttribute('aria-hidden', 'true');
    svg.style.cssText = 'flex-shrink:0;color:var(--muted-foreground)';
    paths.forEach(function (value) {
      var path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
      path.setAttribute('d', value);
      svg.appendChild(path);
    });
    return svg;
  }
  state.render = function () {
    if (location.pathname !== '/settings') {
      if (state.panel) { state.panel.remove(); state.panel = null; }
      return;
    }
    if (state.panel && state.panel.isConnected) return;
    var main = document.querySelector('main');
    var card = main && main.querySelector('[data-slot="card"]');
    if (!card) return; // Wait for React's Settings index, including after a client-side navigation.
    var panel = document.createElement('section');
    panel.setAttribute('aria-label', '电脑连接');
    panel.setAttribute('data-slot', 'card');
    panel.className = card.className;
    panel.style.padding = '0';
    panel.style.gap = '0';
    var link = document.createElement('a');
    link.setAttribute('href', 'collie-pocket://settings/connection');
    link.setAttribute('role', 'button');
    link.setAttribute('aria-label', '电脑连接');
    link.style.cssText = 'display:flex;align-items:center;gap:12px;min-height:76px;padding:16px;text-decoration:none;color:inherit;text-align:left';
    link.appendChild(icon(['M4 3h16a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1Z', 'M8 21h8', 'M12 17v4']));
    var words = document.createElement('div');
    words.style.cssText = 'flex:1;min-width:0';
    var title = document.createElement('div');
    title.textContent = '电脑连接';
    title.style.cssText = 'font-size:14px;font-weight:500';
    var detail = document.createElement('div');
    detail.textContent = state.connection.name + ' · ' + state.connection.address;
    detail.style.cssText = 'font-size:12px;color:var(--muted-foreground);overflow:hidden;text-overflow:ellipsis;white-space:nowrap';
    words.appendChild(title);
    words.appendChild(detail);
    link.appendChild(words);
    link.appendChild(icon(['m9 18 6-6-6-6']));
    panel.appendChild(link);
    state.panel = panel;
    card.parentNode.insertBefore(panel, card);
  };
  window.__colliePocketSettings = state;
  var scheduled = false;
  new MutationObserver(function () {
    if (scheduled) return;
    scheduled = true;
    requestAnimationFrame(function () { scheduled = false; state.render(); });
  }).observe(document.documentElement, { childList: true, subtree: true });
  state.render();
})
