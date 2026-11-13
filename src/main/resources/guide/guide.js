(function () {
  var main = document.getElementById('main');
  var toc = document.getElementById('toc');
  var q = document.getElementById('q');
  var count = document.getElementById('count');
  var empty = document.getElementById('empty');
  var sections = Array.prototype.slice.call(document.querySelectorAll('section'));
  var rows = Array.prototype.slice.call(document.querySelectorAll('#errors tr.code-row'));
  var links = {};

  function lower(s) { try { return s.toLocaleLowerCase(document.documentElement.lang || 'tr'); } catch (e) { return s.toLowerCase(); } }

  var group = null;
  sections.forEach(function (s) {
    if (s.getAttribute('data-group') !== group) {
      group = s.getAttribute('data-group');
      var g = document.createElement('div');
      g.className = 'group';
      g.textContent = group;
      toc.appendChild(g);
    }
    var a = document.createElement('a');
    a.href = '#' + s.id;
    a.textContent = s.querySelector('h2').textContent;
    a.setAttribute('data-go', s.id);
    toc.appendChild(a);
    links[s.id] = a;
  });

  function filter() {
    var term = lower(q.value.trim());
    var shown = 0;
    sections.forEach(function (s) {
      var hit = !term || lower(s.textContent).indexOf(term) >= 0;
      s.classList.toggle('hidden', !hit);
      links[s.id].classList.toggle('hidden', !hit);
      if (hit) shown++;
    });
    rows.forEach(function (r) {
      var hit = !term || lower(r.textContent).indexOf(term) >= 0;
      r.classList.toggle('hidden', !hit);
    });
    Array.prototype.forEach.call(document.querySelectorAll('#errors tr.err-group'), function (g) {
      var n = g.nextElementSibling, any = false;
      while (n && !n.classList.contains('err-group')) { if (!n.classList.contains('hidden')) any = true; n = n.nextElementSibling; }
      g.classList.toggle('hidden', !any);
    });
    Array.prototype.forEach.call(toc.querySelectorAll('.group'), function (g) {
      var n = g.nextElementSibling, any = false;
      while (n && !n.classList.contains('group')) { if (!n.classList.contains('hidden')) any = true; n = n.nextElementSibling; }
      g.classList.toggle('hidden', !any);
    });
    empty.style.display = shown ? 'none' : 'block';
    count.textContent = term ? (shown ? shown + LABEL_FOUND : LABEL_NONE) : '';
  }
  q.addEventListener('input', filter);

  window.go = function (id) {
    if (!id) { main.scrollTop = 0; return; }
    var el = document.getElementById(id);
    if (!el) return;
    if (el.classList.contains('hidden') || (el.closest && el.closest('.hidden'))) { q.value = ''; filter(); }
    el.scrollIntoView({ block: 'start' });
    if (el.tagName === 'TR') {
      el.style.outline = '2px solid var(--accent)';
      setTimeout(function () { el.style.outline = ''; }, 2400);
    } else {
      el.classList.add('flash');
      setTimeout(function () { el.classList.remove('flash'); }, 2400);
    }
  };
  window.setTheme = function (dark) { document.documentElement.classList.toggle('dark', !!dark); };

  document.addEventListener('click', function (e) {
    var a = e.target.closest ? e.target.closest('[data-go]') : null;
    if (a) { e.preventDefault(); window.go(a.getAttribute('data-go')); }
  });
  document.addEventListener('keydown', function (e) {
    if ((e.ctrlKey || e.metaKey) && (e.key === 'f' || e.key === 'F')) { e.preventDefault(); q.focus(); q.select(); }
  });

  main.addEventListener('scroll', function () {
    var top = main.scrollTop + 40, current = null;
    sections.forEach(function (s) { if (!s.classList.contains('hidden') && s.offsetTop <= top) current = s.id; });
    Object.keys(links).forEach(function (k) { links[k].classList.toggle('active', k === current); });
  });
})();
