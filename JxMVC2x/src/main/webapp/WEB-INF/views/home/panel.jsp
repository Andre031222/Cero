<%@page contentType="text/html" pageEncoding="UTF-8"%>
<%@ taglib prefix="jx" uri="http://jxmvc/tags" %>
<%@ include file="/WEB-INF/views/shared/header.jspf" %>

<style>
  .jxp-tile { border:1px solid rgba(0,0,0,.08); border-radius:14px; padding:18px 20px; background:#FFFFFF; }
  .dark .jxp-tile { border-color:rgba(255,255,255,.09); background:#1C1C1E; }
  .jxp-tile b { display:block; font-size:26px; font-weight:700; letter-spacing:-.02em; color:#1D1D1F; font-variant-numeric:tabular-nums; }
  .dark .jxp-tile b { color:#F5F5F7; }
  .jxp-tile span { font-size:11px; font-weight:500; color:#6E6E73; }
  .dark .jxp-tile span { color:#8E8E93; }

  .jxp-card { border:1px solid rgba(0,0,0,.08); border-radius:16px; padding:22px; background:#FFFFFF; position:relative; }
  .dark .jxp-card { border-color:rgba(255,255,255,.09); background:#1C1C1E; }
  .jxp-card h3 { font-size:13px; font-weight:600; color:#1D1D1F; margin-bottom:14px; }
  .dark .jxp-card h3 { color:#F5F5F7; }

  .jxp-bar-track { height:8px; border-radius:4px; background:rgba(0,0,0,.05); overflow:hidden; }
  .dark .jxp-bar-track { background:rgba(255,255,255,.07); }
  .jxp-bar-fill { height:100%; border-radius:4px 4px 4px 4px; background:#0071E3; transition:width .3s cubic-bezier(.23,1,.32,1); min-width:2px; }

  .jxp-pulse { width:8px; height:8px; border-radius:50%; background:#30D158; display:inline-block; animation:jxpulse 1.6s ease-in-out infinite; }
  .dark .jxp-pulse { background:#27A24C; }
  @keyframes jxpulse { 0%,100% { opacity:1; } 50% { opacity:.35; } }

  .jxp-tip { position:absolute; pointer-events:none; opacity:0; transition:opacity .13s ease-out;
    background:rgba(29,29,31,.92); color:#F5F5F7; font:600 11px 'Space Grotesk',sans-serif;
    padding:6px 10px; border-radius:8px; white-space:nowrap; z-index:5; }

  .jxp-table th { font-size:10px; font-weight:600; text-transform:uppercase; letter-spacing:.06em; color:#6E6E73; text-align:right; padding:8px 12px; }
  .jxp-table th:first-child { text-align:left; }
  .dark .jxp-table th { color:#8E8E93; }
  .jxp-table td { font-size:12px; color:#1D1D1F; padding:8px 12px; text-align:right; font-variant-numeric:tabular-nums; border-top:1px solid rgba(0,0,0,.05); }
  .jxp-table td:first-child { text-align:left; font-family:'SF Mono','Menlo','Consolas',monospace; font-size:11.5px; }
  .dark .jxp-table td { color:#F5F5F7; border-top-color:rgba(255,255,255,.06); }

  @media (prefers-reduced-motion: reduce) {
    .jxp-pulse { animation:none; }
    .jxp-bar-fill { transition:none; }
  }
</style>

<section class="max-w-5xl mx-auto px-4 sm:px-6 py-12">
  <div class="mb-10">
    <h1 class="text-4xl font-bold tracking-tight mb-3 text-ink dark:text-[#f5f5f7]">
      Panel de observabilidad
      <span class="jxp-pulse ml-2" style="vertical-align:middle;"></span>
    </h1>
    <p class="text-base text-muted dark:text-[#86868b] leading-relaxed max-w-2xl">
      Métricas reales de <b>este sitio</b>, servidas por <code class="font-mono text-[13px]">JxMetrics</code> del propio framework
      y actualizadas cada 3 segundos. Sin agentes, sin APM externo, sin dependencias.
    </p>
  </div>

  <div class="grid grid-cols-2 lg:grid-cols-4 gap-3 mb-8">
    <div class="jxp-tile"><b id="jxpReq">—</b><span>Peticiones atendidas</span></div>
    <div class="jxp-tile"><b id="jxpAvg">—</b><span>Respuesta media</span></div>
    <div class="jxp-tile"><b id="jxpErr">—</b><span>Errores (4xx + 5xx)</span></div>
    <div class="jxp-tile"><b id="jxpUp">—</b><span>Uptime del panel</span></div>
  </div>

  <div class="grid lg:grid-cols-2 gap-4 mb-4">
    <div class="jxp-card">
      <h3>Respuestas por clase de estado</h3>
      <div class="flex items-center gap-6">
        <div style="position:relative;">
          <svg id="jxpDonut" width="180" height="180" viewBox="0 0 180 180" role="img" aria-label="Respuestas por clase de estado HTTP"></svg>
          <div id="jxpDonutTip" class="jxp-tip"></div>
        </div>
        <ul id="jxpDonutLegend" class="space-y-2 flex-1"></ul>
      </div>
    </div>

    <div class="jxp-card">
      <h3>Latencia media por sondeo · ms</h3>
      <svg id="jxpSpark" width="100%" height="180" role="img" aria-label="Latencia media del sitio a lo largo del tiempo"></svg>
      <p class="text-[10px] text-muted dark:text-[#636366] mt-2">Cada punto es la media acumulada al momento del sondeo. Ventana: últimos 40 sondeos.</p>
    </div>
  </div>

  <div class="jxp-card mb-4">
    <h3>Rutas más solicitadas</h3>
    <div id="jxpRoutes" class="space-y-3"></div>
  </div>

  <div class="jxp-card" style="overflow-x:auto;">
    <h3>Todas las rutas</h3>
    <table class="jxp-table w-full" style="border-collapse:collapse; min-width:560px;">
      <thead><tr><th>Ruta</th><th>Peticiones</th><th>Errores</th><th>Media ms</th><th>Mín ms</th><th>Máx ms</th></tr></thead>
      <tbody id="jxpTable"></tbody>
    </table>
  </div>

  <div class="mt-8">
    <a class="text-sm font-mono text-apple hover:underline" href="${pageContext.request.contextPath}/">Volver a inicio</a>
  </div>
</section>

<script>
(function() {
  var CTX = '${pageContext.request.contextPath}';
  var history = [];
  var MAX_POINTS = 40;

  function pal() {
    var dark = document.documentElement.classList.contains('dark');
    return {
      ok:   dark ? '#27A24C' : '#30D158',
      redir:'#0071E3',
      warn: dark ? '#CC7A00' : '#FF9500',
      err:  '#FF2D55',
      info: '#5E5CE6',
      line: '#0071E3',
      ink:  dark ? '#F5F5F7' : '#1D1D1F',
      sub:  dark ? '#8E8E93' : '#6E6E73',
      grid: dark ? 'rgba(255,255,255,.07)' : 'rgba(0,0,0,.07)',
      gap:  dark ? '#1C1C1E' : '#FFFFFF'
    };
  }

  function esc(s) {
    return String(s).replace(/[&<>"']/g, function(c) {
      return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];
    });
  }

  function fmtMs(ms) {
    if (ms < 1000) return ms + ' ms';
    return (ms / 1000).toFixed(ms < 10000 ? 1 : 0) + ' s';
  }

  function fmtUptime(ms) {
    var s = Math.floor(ms / 1000);
    if (s < 60)    return s + ' s';
    if (s < 3600)  return Math.floor(s / 60) + ' min';
    if (s < 86400) return (s / 3600).toFixed(1) + ' h';
    return (s / 86400).toFixed(1) + ' d';
  }

  function statusSeries(st) {
    var c = pal();
    return [
      {name:'2xx correctas',      n:st['2xx'] || 0, color:c.ok,   icon:'✓'},
      {name:'3xx redirecciones',  n:st['3xx'] || 0, color:c.redir,icon:'↪'},
      {name:'4xx cliente',        n:st['4xx'] || 0, color:c.warn, icon:'!'},
      {name:'5xx servidor',       n:st['5xx'] || 0, color:c.err,  icon:'✕'},
      {name:'1xx informativas',   n:st['1xx'] || 0, color:c.info, icon:'i'}
    ].filter(function(d){ return d.n > 0; });
  }

  function renderDonut(st) {
    var svg = document.getElementById('jxpDonut');
    var series = statusSeries(st);
    var c = pal();
    var total = series.reduce(function(a, d){ return a + d.n; }, 0);
    if (!total) { svg.innerHTML = '<text x="90" y="95" text-anchor="middle" fill="' + c.sub + '" style="font:500 12px sans-serif;">sin datos aún</text>'; return; }

    var cx = 90, cy = 90, R = 72, r = 46, a0 = -Math.PI / 2;
    var parts = [];
    series.forEach(function(d, i) {
      var frac = d.n / total;
      var a1 = a0 + frac * Math.PI * 2;
      if (frac >= 0.999) {
        parts.push('<circle cx="90" cy="90" r="' + ((R + r) / 2) + '" fill="none" stroke="' + d.color + '" stroke-width="' + (R - r) + '" data-i="' + i + '"></circle>');
      } else {
        var large = (a1 - a0) > Math.PI ? 1 : 0;
        parts.push('<path d="M ' + (cx + R * Math.cos(a0)) + ' ' + (cy + R * Math.sin(a0))
          + ' A ' + R + ' ' + R + ' 0 ' + large + ' 1 ' + (cx + R * Math.cos(a1)) + ' ' + (cy + R * Math.sin(a1))
          + ' L ' + (cx + r * Math.cos(a1)) + ' ' + (cy + r * Math.sin(a1))
          + ' A ' + r + ' ' + r + ' 0 ' + large + ' 0 ' + (cx + r * Math.cos(a0)) + ' ' + (cy + r * Math.sin(a0))
          + ' Z" fill="' + d.color + '" stroke="' + c.gap + '" stroke-width="2" data-i="' + i + '"></path>');
      }
      a0 = a1;
    });
    var okPct = Math.round((st['2xx'] || 0) / total * 100);
    parts.push('<text x="90" y="87" text-anchor="middle" fill="' + c.ink + '" style="font:700 22px \'Space Grotesk\',sans-serif;">' + okPct + '%</text>');
    parts.push('<text x="90" y="104" text-anchor="middle" fill="' + c.sub + '" style="font:500 10px \'Space Grotesk\',sans-serif;">correctas</text>');
    svg.innerHTML = parts.join('');

    document.getElementById('jxpDonutLegend').innerHTML = series.map(function(d) {
      return '<li class="flex items-center gap-2.5">'
        + '<span style="width:9px;height:9px;border-radius:3px;background:' + d.color + ';flex-shrink:0;"></span>'
        + '<span class="text-[12px] text-ink dark:text-[#F5F5F7] flex-1">' + d.icon + ' ' + d.name + '</span>'
        + '<span class="text-[12px] font-semibold text-ink dark:text-[#F5F5F7]" style="font-variant-numeric:tabular-nums;">' + d.n.toLocaleString() + '</span>'
        + '</li>';
    }).join('');

    var tip = document.getElementById('jxpDonutTip');
    svg.querySelectorAll('[data-i]').forEach(function(seg) {
      seg.addEventListener('mouseenter', function(ev) {
        var d = series[+seg.getAttribute('data-i')];
        tip.textContent = d.name + ' — ' + d.n.toLocaleString();
        tip.style.left = '20px'; tip.style.top = '-6px'; tip.style.opacity = '1';
      });
      seg.addEventListener('mouseleave', function(){ tip.style.opacity = '0'; });
    });
  }

  function renderSpark() {
    var svg = document.getElementById('jxpSpark');
    var c = pal();
    var W = svg.clientWidth || 400, H = 180;
    svg.setAttribute('viewBox', '0 0 ' + W + ' ' + H);
    if (history.length < 2) { svg.innerHTML = '<text x="' + (W/2) + '" y="95" text-anchor="middle" fill="' + c.sub + '" style="font:500 12px sans-serif;">recopilando datos…</text>'; return; }

    var PAD = 26, PB = 22;
    var vals = history.map(function(h){ return h.avg; });
    var max = Math.max.apply(null, vals) * 1.25 || 1;
    var pts = history.map(function(h, i) {
      var x = PAD + (W - PAD - 10) * (i / (MAX_POINTS - 1));
      var y = (H - PB) - (h.avg / max) * (H - PB - 14);
      return [x, y];
    });

    var parts = [];
    for (var g = 0; g <= 2; g++) {
      var gy = 14 + (H - PB - 14) / 2 * g;
      parts.push('<line x1="' + PAD + '" y1="' + gy + '" x2="' + (W - 10) + '" y2="' + gy + '" stroke="' + c.grid + '" stroke-dasharray="4 4"></line>');
      parts.push('<text x="' + (PAD - 5) + '" y="' + (gy + 3) + '" text-anchor="end" fill="' + c.sub + '" style="font:500 9px \'Space Grotesk\',sans-serif;">' + Math.round(max * (1 - g / 2)) + '</text>');
    }
    var line = pts.map(function(p, i){ return (i ? 'L' : 'M') + p[0].toFixed(1) + ' ' + p[1].toFixed(1); }).join(' ');
    var area = line + ' L' + pts[pts.length-1][0].toFixed(1) + ' ' + (H - PB) + ' L' + pts[0][0].toFixed(1) + ' ' + (H - PB) + ' Z';
    parts.push('<path d="' + area + '" fill="' + c.line + '" opacity="0.08"></path>');
    parts.push('<path d="' + line + '" fill="none" stroke="' + c.line + '" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"></path>');
    var last = pts[pts.length - 1];
    parts.push('<circle cx="' + last[0] + '" cy="' + last[1] + '" r="4" fill="' + c.line + '" stroke="' + c.gap + '" stroke-width="2"></circle>');
    parts.push('<text x="' + Math.min(last[0], W - 46) + '" y="' + Math.max(last[1] - 10, 12) + '" text-anchor="middle" fill="' + c.ink + '" style="font:700 11px \'Space Grotesk\',sans-serif;">' + history[history.length-1].avg + ' ms</text>');
    svg.innerHTML = parts.join('');
  }

  function renderRoutes(routes) {
    var keys = Object.keys(routes).sort(function(a, b){ return routes[b].requests - routes[a].requests; });
    var top = keys.slice(0, 8);
    var maxReq = top.length ? routes[top[0]].requests : 1;
    document.getElementById('jxpRoutes').innerHTML = top.map(function(k) {
      var r = routes[k];
      var pct = Math.max(r.requests / maxReq * 100, 1.5);
      return '<div>'
        + '<div class="flex items-baseline justify-between mb-1.5">'
        +   '<code class="text-[11.5px] font-mono text-ink dark:text-[#F5F5F7]">' + esc(k) + '</code>'
        +   '<span class="text-[11px] text-muted dark:text-[#8E8E93]" style="font-variant-numeric:tabular-nums;">'
        +     r.requests.toLocaleString() + ' req · ' + r.avgMs + ' ms med</span>'
        + '</div>'
        + '<div class="jxp-bar-track"><div class="jxp-bar-fill" style="width:' + pct + '%;"></div></div>'
        + '</div>';
    }).join('') || '<p class="text-[12px] text-muted dark:text-[#8E8E93]">sin datos aún</p>';
  }

  function renderTable(routes) {
    var keys = Object.keys(routes).sort(function(a, b){ return routes[b].requests - routes[a].requests; });
    document.getElementById('jxpTable').innerHTML = keys.map(function(k) {
      var r = routes[k];
      return '<tr><td>' + esc(k) + '</td><td>' + r.requests.toLocaleString() + '</td><td>' + r.errors.toLocaleString()
           + '</td><td>' + r.avgMs + '</td><td>' + r.minMs + '</td><td>' + r.maxMs + '</td></tr>';
    }).join('');
  }

  var lastData = null;

  function paint() {
    if (!lastData) return;
    var m = lastData.metrics;
    document.getElementById('jxpReq').textContent = m.totalRequests.toLocaleString();
    document.getElementById('jxpAvg').textContent = fmtMs(m.avgResponseMs);
    document.getElementById('jxpErr').textContent = m.totalErrors.toLocaleString();
    document.getElementById('jxpUp').textContent  = fmtUptime(lastData.uptimeMs);
    renderDonut(m.status || {});
    renderSpark();
    renderRoutes(m.routes || {});
    renderTable(m.routes || {});
  }

  function poll() {
    fetch(CTX + '/home/panel-data', {headers:{'Accept':'application/json'}})
      .then(function(r){ return r.json(); })
      .then(function(data) {
        lastData = data;
        history.push({avg: data.metrics.avgResponseMs, req: data.metrics.totalRequests});
        if (history.length > MAX_POINTS) history.shift();
        paint();
      })
      .catch(function(){});
  }

  poll();
  setInterval(poll, 3000);
  new MutationObserver(paint).observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });
  window.addEventListener('resize', renderSpark);
})();
</script>

<%@ include file="/WEB-INF/views/shared/footer.jspf" %>
