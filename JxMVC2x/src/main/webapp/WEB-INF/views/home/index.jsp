<%@page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@ include file="/WEB-INF/views/shared/header.jspf" %>

<link id="jxHljsTheme" rel="stylesheet" href="${pageContext.request.contextPath}/assets/css/hljs/dark.min.css">
<script>
/* Sintaxis del código: tema claro en modo claro, oscuro en modo oscuro. */
(function () {
  var ctx = '${pageContext.request.contextPath}';
  var link = document.getElementById('jxHljsTheme');
  function apply() {
    var dark = document.documentElement.classList.contains('dark');
    var href = ctx + '/assets/css/hljs/' + (dark ? 'dark' : 'light') + '.min.css';
    if (link.getAttribute('href') !== href) link.setAttribute('href', href);
  }
  apply();
  new MutationObserver(apply).observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });
})();
</script>
<script src="${pageContext.request.contextPath}/assets/js/highlight.min.js"></script>
<script src="${pageContext.request.contextPath}/assets/js/hljs-java.min.js"></script>
<script src="${pageContext.request.contextPath}/assets/js/hljs-properties.min.js"></script>
<script src="${pageContext.request.contextPath}/assets/js/hljs-xml.min.js"></script>

<style>
  .jx-win { background:#FFFFFF; border:1px solid rgba(0,0,0,0.10); border-radius:16px; overflow:hidden;
    box-shadow:0 1px 3px rgba(0,0,0,.05); }
  .dark .jx-win { background:#1C1C1E; border-color:rgba(255,255,255,0.09); box-shadow:none; }
  .jx-win-bar { display:flex; align-items:center; gap:6px; padding:11px 16px; border-bottom:1px solid rgba(0,0,0,0.07); }
  .dark .jx-win-bar { border-bottom-color:rgba(255,255,255,0.06); }
  .jxd-r { width:11px; height:11px; border-radius:50%; background:#FF5F57; flex-shrink:0; }
  .jxd-y { width:11px; height:11px; border-radius:50%; background:#FEBC2E; flex-shrink:0; }
  .jxd-g { width:11px; height:11px; border-radius:50%; background:#28C840; flex-shrink:0; }
  .jx-win-tab { padding:3px 10px; border-radius:6px; font-size:11px; font-family:monospace; color:#8a8a90; cursor:pointer; transition:background .13s,color .13s; user-select:none; }
  .dark .jx-win-tab { color:#636366; }
  .jx-win-tab.active { background:rgba(0,0,0,0.06); color:#1D1D1F; }
  .dark .jx-win-tab.active { background:rgba(255,255,255,0.09); color:#F5F5F7; }
  .jx-code-pane { padding:20px; font-family:'SF Mono','Menlo','Consolas',monospace; overflow-x:auto; min-height:270px; }
  .hljs { background:transparent !important; font-size:.715rem; line-height:1.72; }

  .jx-play-tab, .jx-chart-tab { padding:7px 16px; border-radius:9px; font-size:12px; font-weight:500; cursor:pointer; color:#6E6E73; transition:background .13s,color .13s; }
  .jx-play-tab.active, .jx-chart-tab.active { background:#0071E3; color:#fff; }
  .dark .jx-play-tab, .dark .jx-chart-tab { color:#8E8E93; }

  .jx-feat-card { position:relative; overflow:hidden; }
  .jx-feat-card::before {
    content:''; position:absolute; inset:0; pointer-events:none;
    background:radial-gradient(circle at var(--mx,50%) var(--my,50%), rgba(0,113,227,.08) 0%, transparent 55%);
    opacity:0; transition:opacity .25s;
  }
  .jx-feat-card:hover::before { opacity:1; }

  .jx-bar { height:6px; border-radius:3px; transition:width 1.3s cubic-bezier(.16,1,.3,1); width:0; }

  .jx-pipe-node { display:flex; flex-direction:column; align-items:center; gap:5px; flex-shrink:0;
    opacity:0; transform:translateY(8px); transition:opacity .35s ease,transform .35s ease; }
  .jx-pipe-node.jx-in { opacity:1; transform:none; }
  .jx-pipe-line { flex:1; height:2px; border-radius:1px; opacity:0; min-width:8px;
    transition:opacity .25s ease; }
  .jx-pipe-node.jx-in + .jx-pipe-line { opacity:1; }

  .jx-counter { font-variant-numeric:tabular-nums; }

  /* ── Hero rediseñado: escena geométrica de marca ── */
  /* Fondo por CSS propio: bg-white/bg-black NO están en el Tailwind precompilado. */
  .jx-hero { position:relative; overflow:hidden; background:#FFFFFF; }
  .dark .jx-hero { background:#000000; }
  /* Padding y layout en CSS propio: varias clases (pt-*, gap-8, w-[44%]) NO están en el Tailwind precompilado. */
  .jx-hero-wrap { padding-top:64px; padding-bottom:48px; }
  @media (min-width:640px)  { .jx-hero-wrap { padding-top:80px;  padding-bottom:60px; } }
  @media (min-width:1024px) { .jx-hero-wrap { padding-top:112px; padding-bottom:80px; } }
  .jx-hero-row { gap:32px; }
  .jx-hero-left, .jx-hero-right { width:100%; }
  @media (min-width:1024px) {
    .jx-hero-row { gap:44px; }
    .jx-hero-left  { width:44%; }
    .jx-hero-right { width:56%; }
  }
  .jx-hero-bg { position:absolute; inset:0; overflow:hidden; pointer-events:none; }
  .jx-hero-glow { position:absolute; border-radius:50%; filter:blur(96px); }
  .jx-hero-glow.g1 { width:520px; height:520px; top:-140px; right:-2%;
    background:radial-gradient(circle,#FF7A00 0%,transparent 68%); opacity:0; }
  .jx-hero-glow.g2 { width:460px; height:460px; bottom:-180px; right:26%;
    background:radial-gradient(circle,#5E5CE6 0%,transparent 66%); opacity:0; }
  /* Sin halo de fondo: blanco limpio en claro, negro puro en oscuro. */
  .dark .jx-hero-glow.g1 { opacity:0; }
  .dark .jx-hero-glow.g2 { opacity:0; }

  /* La columna de texto siempre por encima de la escena 3D (nunca la tapan formas/ventana). */
  .jx-hero-left { position:relative; z-index:5; }

  .jx-hero-title { font-weight:800; letter-spacing:-.025em; line-height:1.02; margin-bottom:20px; }
  .jx-grad2 { display:inline-block; font-size:clamp(2.9rem,6.6vw,4.6rem);
    background:linear-gradient(110deg,#FF6B00 0%,#FF2D55 28%,#0071E3 66%,#5E5CE6 100%);
    -webkit-background-clip:text; background-clip:text; -webkit-text-fill-color:transparent; }
  .jx-hero-sub { font-size:clamp(1.35rem,3.2vw,2.05rem); font-weight:700; letter-spacing:-.02em;
    color:#111; -webkit-text-fill-color:#111; }
  .dark .jx-hero-sub { color:#fff; -webkit-text-fill-color:#fff; }
  .jx-hero-desc { font-size:15px; line-height:1.62; max-width:400px; color:#57575c; margin-bottom:28px; }
  .dark .jx-hero-desc { color:#8E8E93; }

  .jx-hero-cta { display:flex; flex-wrap:wrap; gap:12px; }
  .jx-cta-primary { display:inline-block; padding:12px 26px; border-radius:999px; font-size:14px;
    font-weight:600; color:#fff; background:linear-gradient(120deg,#0071E3 0%,#4B47D6 100%);
    box-shadow:0 8px 24px rgba(75,71,214,.35); transition:transform .15s ease, box-shadow .15s ease; }
  .jx-cta-primary:hover { transform:translateY(-2px); box-shadow:0 12px 34px rgba(94,92,230,.48); }
  .jx-cta-primary:active { transform:translateY(0); }
  .jx-cta-ghost { display:inline-block; padding:12px 24px; border-radius:999px; font-size:14px;
    font-weight:600; color:#141416; -webkit-text-fill-color:#141416; border:1px solid rgba(0,0,0,.16);
    transition:background .15s ease, border-color .15s ease; }
  .dark .jx-cta-ghost { color:#fff; -webkit-text-fill-color:#fff; border-color:rgba(255,255,255,.24); }
  .jx-cta-ghost:hover { background:rgba(0,0,0,.05); border-color:rgba(0,0,0,.26); }
  .dark .jx-cta-ghost:hover { background:rgba(255,255,255,.08); border-color:rgba(255,255,255,.28); }

  .jx-stats { display:flex; flex-wrap:wrap; gap:9px; margin-top:30px; justify-content:center; }
  .jx-stat { padding:9px 15px; border-radius:15px; border:1px solid rgba(0,0,0,.08);
    background:rgba(0,0,0,.015); text-align:left; }
  .dark .jx-stat { border-color:rgba(255,255,255,.09); background:rgba(255,255,255,.03); }
  .jx-stat b { font-size:16px; font-weight:700; color:#111; -webkit-text-fill-color:#111; }
  .dark .jx-stat b { color:#fff; -webkit-text-fill-color:#fff; }
  .jx-stat .u { font-size:10px; font-weight:700;
    background:linear-gradient(120deg,#FF2D55,#5E5CE6); -webkit-background-clip:text; background-clip:text; -webkit-text-fill-color:transparent; }
  .jx-stat .l { display:block; font-size:9px; font-family:monospace; text-transform:uppercase;
    letter-spacing:.06em; color:#8a8a90; margin-top:2px; }
  .jx-counter { color:#111; -webkit-text-fill-color:#111; }
  .dark .jx-counter { color:#fff; -webkit-text-fill-color:#fff; }

  /* Terminal quick-start — limpio, responsive, sin sombra flotante */
  .jx-cmd { display:flex; align-items:center; gap:10px; margin-top:24px; max-width:400px;
    border:1px solid rgba(0,0,0,0.10); background:rgba(0,0,0,0.02); border-radius:12px; padding:11px 14px; }
  .dark .jx-cmd { border-color:rgba(255,255,255,0.10); background:rgba(255,255,255,0.04); }
  .jx-cmd-prompt { color:#0071E3; font-family:monospace; font-weight:700; font-size:14px; flex-shrink:0; }
  .jx-cmd-code { font-family:monospace; font-size:13px; color:#1D1D1F; flex:1 1 auto; min-width:0;
    overflow-x:auto; white-space:nowrap; text-align:left; }
  .dark .jx-cmd-code { color:#C8C8D0; }
  .jx-cmd-code::-webkit-scrollbar { height:0; }
  .jx-cmd-copy { color:#8a8a90; flex-shrink:0; transition:color .15s ease; }
  .jx-cmd-copy:hover { color:#1D1D1F; }
  .dark .jx-cmd-copy:hover { color:#fff; }

  /* Escena: code window plano (sin tilt ni animación) con formas angulares detrás */
  .jx-scene { position:relative; }
  .jx-win-3d { position:relative; z-index:2; box-shadow:0 10px 30px rgba(0,0,0,.07); }
  .dark .jx-win-3d { box-shadow:0 12px 34px rgba(0,0,0,.40); }

  /* Altura fija del área de código: cambiar Controller/Model/Config no re-ajusta el window */
  #jxHeroPane { height:520px; overflow-y:auto; }
  @media (max-width:640px) { #jxHeroPane { height:400px; } }
  .jx-code-pane { min-height:0; }

  .jx-shape { position:absolute; z-index:1; will-change:transform;
    animation:jxFloat 8s ease-in-out infinite; }
  .jx-shape.s-lg { width:150px; height:150px; }
  .jx-shape.s-md { width:108px; height:108px; }
  .jx-shape.s-sm { width:72px;  height:72px;  }
  .sh-tri     { clip-path:polygon(50% 3%, 97% 95%, 3% 95%); }
  .sh-diamond { clip-path:polygon(50% 0, 100% 50%, 50% 100%, 0 50%); }
  .sh-shard   { clip-path:polygon(50% 0, 100% 40%, 80% 100%, 20% 100%, 0 40%); }
  .sh-arrow   { clip-path:polygon(0 0, 60% 0, 100% 50%, 60% 100%, 0 100%, 36% 50%); }
  .jx-grad-or { background:linear-gradient(150deg,#FFB067,#FF6A00); filter:drop-shadow(0 14px 26px rgba(255,106,0,.36)); }
  .jx-grad-ro { background:linear-gradient(150deg,#409CFF,#0071E3); filter:drop-shadow(0 14px 26px rgba(0,113,227,.36)); }
  .jx-grad-vi { background:linear-gradient(150deg,#8E8CF0,#5E5CE6); filter:drop-shadow(0 14px 26px rgba(94,92,230,.36)); }
  @keyframes jxFloat {
    0%,100% { transform:translateY(0)    rotate(var(--r,0deg)); }
    50%     { transform:translateY(-9px) rotate(var(--r,0deg)); }
  }
  @media (max-width:640px) { .jx-shape { display:none; } }
</style>

<%-- ── Hero: escena geométrica de marca ─────────────────────────────── --%>
<section class="jx-hero bg-white dark:bg-[#000000]">
  <div class="jx-hero-bg" aria-hidden="true">
    <div class="jx-hero-glow g1"></div>
    <div class="jx-hero-glow g2"></div>
  </div>

  <div class="jx-hero-wrap relative max-w-5xl mx-auto px-4 sm:px-6">
    <div class="jx-hero-row flex flex-col lg:flex-row items-center">

      <%-- Left: headline --%>
      <div class="jx-hero-left text-center lg:text-left jx-reveal jx-delay-1">
        <h1 class="jx-hero-title">
          <span class="jx-grad2">JxMVC</span><br>
          <span class="jx-hero-sub">Lightning-X Framework</span>
        </h1>

        <p class="jx-hero-desc mx-auto lg:mx-0" data-i18n="hero.desc">
          Framework MVC para Jakarta EE. 253 KB, cero dependencias externas y arranque en 1.2 s.
        </p>

        <div class="jx-hero-cta justify-center lg:justify-start">
          <a href="${pageContext.request.contextPath}/downloads" class="jx-cta-primary" data-i18n="hero.dl">Descargar</a>
          <a href="${pageContext.request.contextPath}/docs" class="jx-cta-ghost" data-i18n="hero.docs">Documentación →</a>
        </div>

        <div class="jx-stats">
          <% String[][] metrics = {{"253","KB","Core"},{"0","deps","Runtime"},{"54","cls","Core"},{"1.2","s","Arranque"}}; %>
          <% for (String[] m : metrics) { %>
          <div class="jx-stat">
            <b><span class="jx-counter" data-target="<%= m[0].replace(".","") %>"><%= m[0] %></span><span class="u"> <%= m[1] %></span></b>
            <span class="l"><%= m[2] %></span>
          </div>
          <% } %>
        </div>

        <div class="jx-cmd mx-auto lg:mx-0">
          <span class="jx-cmd-prompt">$</span>
          <code id="jxHeroCmd" class="jx-cmd-code">mvn install -f JxMVC.Core/pom.xml</code>
          <button onclick="jxCopyCmd()" class="jx-cmd-copy" aria-label="Copiar comando">
            <svg id="jxCmdIco" class="w-4 h-4" fill="none" stroke="currentColor" stroke-width="1.8" viewBox="0 0 24 24">
              <rect x="9" y="9" width="13" height="13" rx="2"></rect><path d="M5 15H4a2 2 0 01-2-2V4a2 2 0 012-2h9a2 2 0 012 2v1"></path>
            </svg>
          </button>
        </div>
      </div>

      <%-- Right: escena con código real y formas angulares (paleta del logo) --%>
      <div class="jx-hero-right w-full jx-reveal jx-delay-2">
        <div class="jx-scene">
          <span class="jx-shape s-lg jx-grad-or sh-tri"     style="top:-13%; right:14%;  --r:-6deg; animation-delay:-.4s"></span>
          <span class="jx-shape s-md jx-grad-vi sh-arrow"   style="top:16%;  right:-13%; --r:0deg;  animation-delay:-2.1s"></span>
          <span class="jx-shape s-sm jx-grad-vi sh-shard"   style="top:46%;  right:-14%; --r:10deg; animation-delay:-1.2s"></span>
          <span class="jx-shape s-sm jx-grad-ro sh-diamond" style="top:74%;  right:-12%; --r:0deg;  animation-delay:-3.3s"></span>
          <div class="jx-win jx-win-3d">
          <div class="jx-win-bar">
            <span class="jxd-r"></span><span class="jxd-y"></span><span class="jxd-g"></span>
            <div class="ml-3 flex gap-1">
              <span class="jx-win-tab active" onclick="jxCodeTab('ctrl',this)">Controller</span>
              <span class="jx-win-tab" onclick="jxCodeTab('model',this)">Model</span>
              <span class="jx-win-tab" onclick="jxCodeTab('cfg',this)">Config</span>
            </div>
            <button onclick="jxCopyPane('jxHeroPane')" class="ml-auto text-[#636366] hover:text-[#8E8E93] transition-colors" title="Copiar">
              <svg id="jxHeroCopyIco" class="w-4 h-4" fill="none" stroke="currentColor" stroke-width="1.8" viewBox="0 0 24 24">
                <rect x="9" y="9" width="13" height="13" rx="2"></rect><path d="M5 15H4a2 2 0 01-2-2V4a2 2 0 012-2h9a2 2 0 012 2v1"></path>
              </svg>
            </button>
          </div>
          <div id="jxHeroPane">
            <div id="jxH-ctrl" class="jx-code-pane">
<pre><code class="language-java">@JxControllerMapping("api/persona")
public class PersonaController extends JxController {

    @JxGetMapping("{id}")
    public ActionResult get(@JxPathVar String id) {
        DBRow per = new PersonaModel().GetRow(
                "tblPersonas", "id = ?", id);
        if (per == null)
            return Json(GenApi.Error(404, "No encontrado"));

        return Json(GenApi.JsonStr(
            "id",     per.Get("id"),
            "nombre", per.GetString("Nombres"),
            "correo", per.GetString("Correo")
        ));
    }

    @JxPostMapping("save")
    @JxRequireRole("admin")
    @JxRateLimit(requests = 20, windowMs = 60_000)
    public ActionResult save() {
        String nombre = model.param("nombre");
        // validar + guardar
        return Json(GenApi.Ok("Guardado")).status(201);
    }
}</code></pre>
            </div>
            <div id="jxH-model" class="jx-code-pane hidden">
<pre><code class="language-java">public class PersonaModel extends JxDB {

    public PersonaModel() { super(); }

    public DBRowSet buscarPorApellido(String apellido) {
        return GetTable(
            "tblPersonas",
            "Apellidos LIKE ?",
            apellido + "%"
        );
    }

    public DBRow porCorreo(String correo) {
        return GetRow(
            "tblPersonas",
            "Correo = ? AND activo = 1",
            correo
        );
    }
}</code></pre>
            </div>
            <div id="jxH-cfg" class="jx-code-pane hidden">
<pre><code class="language-properties"># application.properties
jxmvc.db.url     = jdbc:postgresql://localhost:5432/miapp
jxmvc.db.user    = usuario
jxmvc.db.pass    = secreto
jxmvc.profiles.active = dev

# Pool de conexiones
jxmvc.pool.min     = 2
jxmvc.pool.max     = 20
jxmvc.pool.timeout = 30000

# Nivel de log
jxmvc.log.level  = INFO</code></pre>
            </div>
          </div>
        </div>
        </div><%-- cierra .jx-scene --%>
      </div>
    </div>
  </div>
</section>

<%-- ── Instalación: estilo terminal (Docker-like) ─────────────────────── --%>
<style>
  /* Terminal theme-aware: por defecto clara; .dark la oscurece. Compartida con el panel del pipeline. */
  .jx-term { --t-bg:#FBFCFE; --t-bar:#EEF2F8; --t-brd:rgba(0,0,0,.09); --t-bar-brd:rgba(0,0,0,.06);
    --t-prompt:#0071E3; --t-cmd:#1D1D1F; --t-key:#0071E3; --t-dim:#AEB6C6; --t-ok:#16A34A; --t-mut:#5B6675;
    --t-grid:rgba(0,113,227,.22); --t-shadow:0 20px 50px rgba(20,40,80,.13); }
  .dark .jx-term { --t-bg:#0B1020; --t-bar:#12182B; --t-brd:rgba(255,255,255,.08); --t-bar-brd:rgba(255,255,255,.06);
    --t-prompt:#5B8DEF; --t-cmd:#DCE3F2; --t-key:#7AA2F7; --t-dim:#3E4A66; --t-ok:#4ADE80; --t-mut:#8B9BB8;
    --t-grid:rgba(91,141,239,.22); --t-shadow:0 24px 60px rgba(0,0,0,.6); }

  .jx-inst-term { background:var(--t-bg); border:1px solid var(--t-brd); border-radius:16px;
    overflow:hidden; box-shadow:var(--t-shadow); font-family:'SF Mono','Menlo','Consolas',monospace; }
  .jx-inst-bar { display:flex; align-items:center; gap:7px; padding:12px 16px; background:var(--t-bar);
    border-bottom:1px solid var(--t-bar-brd); }
  .jx-inst-dot { width:12px; height:12px; border-radius:50%; }
  .jx-inst-body { padding:22px 24px; font-size:12.5px; line-height:1.85; }
  .jx-t-prompt { color:var(--t-prompt); } .jx-t-cmd { color:var(--t-cmd); } .jx-t-key { color:var(--t-key); }
  .jx-t-dim { color:var(--t-dim); } .jx-t-ok { color:var(--t-ok); } .jx-t-mut { color:var(--t-mut); }
  .jx-inst-grid { margin-top:16px; border:1px solid var(--t-grid); border-radius:12px;
    display:grid; grid-template-columns:1fr 1fr; }
  .jx-inst-cell { padding:16px 18px; }
  .jx-inst-cell + .jx-inst-cell { border-left:1px solid var(--t-grid); }
  @media (max-width:560px){ .jx-inst-grid { grid-template-columns:1fr; }
    .jx-inst-cell + .jx-inst-cell { border-left:none; border-top:1px solid var(--t-grid); } }
  .jx-inst-copy { display:inline-flex; align-items:center; gap:7px; padding:11px 22px; border-radius:999px;
    font-size:13px; font-weight:600; color:#fff; background:#0071E3; border:none; cursor:pointer;
    transition:transform .15s ease, box-shadow .15s ease, background .15s ease; box-shadow:0 8px 22px rgba(0,113,227,.32); }
  .jx-inst-copy:hover { transform:translateY(-2px); background:#0077ED; box-shadow:0 12px 30px rgba(0,113,227,.42); }
  .jx-inst-copy:active { transform:translateY(0); }
</style>
<section class="py-20 bg-white dark:bg-[#000000]" id="jxInstallSec">
  <div class="max-w-6xl mx-auto px-4 sm:px-6">
    <div class="grid lg:grid-cols-2 gap-12 items-center">

      <div>
        <h2 class="text-4xl sm:text-5xl font-bold leading-[1.05] tracking-tight mb-5">
          <span class="text-apple">Instálalo</span><span class="text-ink dark:text-white"> en segundos.</span>
        </h2>
        <p class="text-[15px] leading-relaxed text-muted dark:text-[#8E8E93] max-w-md mb-7">
          Un solo <code class="font-mono text-ink dark:text-[#F5F5F7]">mvn install</code> y ya tienes el framework
          en tu repositorio local. Sin CDNs, sin descargas transitivas, sin sorpresas: el JAR pesa
          <b class="text-ink dark:text-white">253 KB</b> y arrastra <b class="text-ink dark:text-white">cero</b> dependencias.
        </p>
        <div class="flex flex-wrap items-center gap-3">
          <button class="jx-inst-copy" onclick="jxCopyInstall(this)">
            <svg class="w-4 h-4" fill="none" stroke="currentColor" stroke-width="1.8" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z"/>
            </svg>
            <span id="jxInstCopyTxt">Copiar comando</span>
          </button>
          <a href="${pageContext.request.contextPath}/downloads" class="jx-cta-ghost">Generar proyecto →</a>
        </div>
      </div>

      <div class="jx-term jx-inst-term">
        <div class="jx-inst-bar">
          <span class="jx-inst-dot" style="background:#FF5F57"></span>
          <span class="jx-inst-dot" style="background:#FEBC2E"></span>
          <span class="jx-inst-dot" style="background:#28C840"></span>
          <span class="ml-2 text-[11px] jx-t-dim">jxmvc — install</span>
        </div>
        <div class="jx-inst-body">
          <div><span class="jx-t-prompt">$</span> <span class="jx-t-cmd">mvn install -f</span> <span class="jx-t-key">JxMVC.Core/pom.xml</span></div>
          <div class="jx-t-mut">[INFO] Building <span class="jx-t-key">jxmvc-core</span> 3.4.0</div>
          <div class="jx-t-mut">[INFO] Tests run: <span class="jx-t-ok">347</span>, Failures: 0, Errors: 0</div>
          <div class="jx-t-mut">[INFO] <span class="jx-t-ok">BUILD SUCCESS</span> <span class="jx-t-dim">·</span> 253 KB <span class="jx-t-dim">·</span> 0 deps externas</div>

          <div class="jx-inst-grid">
            <div class="jx-inst-cell">
              <p class="jx-t-key text-[12px] mb-2.5">✓ jxmvc-core instalado</p>
              <p class="jx-t-mut text-[11.5px] leading-relaxed">
                groupId <span class="jx-t-cmd">jxmvc</span><br>
                artifact <span class="jx-t-cmd">jxmvc-core</span><br>
                <span class="jx-t-dim">~/.m2/repository</span>
              </p>
            </div>
            <div class="jx-inst-cell">
              <p class="jx-t-key text-[12px] mb-2.5">Empieza aquí</p>
              <p class="jx-t-mut text-[11.5px] leading-relaxed">
                Genera un starter en <span class="jx-t-cmd">/downloads</span><br>
                y levántalo:<br>
                <span class="jx-t-prompt">$</span> <span class="jx-t-cmd">mvn package cargo:run</span>
              </p>
            </div>
          </div>
        </div>
      </div>

    </div>
  </div>
</section>
<script>
function jxCopyInstall(btn) {
  var cmd = 'mvn install -f JxMVC.Core/pom.xml';
  var done = function() {
    var t = document.getElementById('jxInstCopyTxt');
    if (!t) return; var prev = t.textContent; t.textContent = '¡Copiado!';
    setTimeout(function(){ t.textContent = prev; }, 1600);
  };
  if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(cmd).then(done, done);
  else { var ta=document.createElement('textarea'); ta.value=cmd; document.body.appendChild(ta); ta.select(); try{document.execCommand('copy');}catch(e){} document.body.removeChild(ta); done(); }
}
</script>

<%-- ── Features bento ─────────────────────────────────────────────────── --%>
<section class="py-20 bg-white dark:bg-[#000000]">
  <div class="max-w-6xl mx-auto px-4 sm:px-6">

    <div class="mb-12 jx-reveal jx-delay-1">
      <p class="text-[10px] font-mono uppercase tracking-[0.28em] text-muted dark:text-[#8E8E93] mb-3">Características</p>
      <h2 class="text-3xl sm:text-4xl font-bold text-ink dark:text-white leading-tight">Todo incluido.<br><span class="text-apple">Cero dependencias.</span></h2>
      <p class="text-[14px] text-muted dark:text-[#8E8E93] mt-3 max-w-xl">Un único JAR de 253 KB con todo lo que necesita una aplicación Jakarta EE real. Sin Spring, sin Hibernate, sin Jackson.</p>
    </div>

    <%-- Bento grid --%>
    <div class="grid grid-cols-2 lg:grid-cols-4 gap-3 jx-reveal jx-delay-2">

      <%-- Grande: Routing --%>
      <div class="jx-feat-card col-span-2 bg-[#F5F5F7] dark:bg-[#1C1C1E] border border-black/[0.06] dark:border-white/[0.07] rounded-2xl p-6 shadow-card hover:shadow-cardh transition-all" onmousemove="jxSpot(event,this)">
        <div class="w-10 h-10 rounded-xl flex items-center justify-center mb-4" style="background:#0071E31A">
          <svg class="w-5 h-5" fill="none" stroke="#0071E3" stroke-width="1.8" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" d="M9 20l-5.447-2.724A1 1 0 013 16.382V5.618a1 1 0 011.447-.894L9 7m0 13l6-3m-6 3V7m6 10l4.553 2.276A1 1 0 0021 18.382V7.618a1 1 0 00-.553-.894L15 4m0 13V4m0 0L9 7"/></svg>
        </div>
        <h3 class="text-[15px] font-semibold text-ink dark:text-white mb-2">Routing inteligente</h3>
        <p class="text-[12px] text-muted dark:text-[#8E8E93] leading-relaxed mb-4">Convención de nombres + anotaciones. Plantillas <code class="font-mono text-apple">{id}</code>, args posicionales, múltiples verbos HTTP.</p>
        <div class="flex flex-wrap gap-1.5">
          <% for (String t : new String[]{"@JxGetMapping","@JxPostMapping","@JxPutMapping","@JxDeleteMapping","{pathVar}"}) { %>
          <span class="text-[10px] font-mono px-2 py-0.5 rounded-md bg-apple/10 text-apple"><%= t %></span>
          <% } %>
        </div>
      </div>

      <%-- Pool --%>
      <div class="jx-feat-card bg-[#F5F5F7] dark:bg-[#1C1C1E] border border-black/[0.06] dark:border-white/[0.07] rounded-2xl p-5 shadow-card hover:shadow-cardh transition-all" onmousemove="jxSpot(event,this)">
        <div class="w-9 h-9 rounded-xl flex items-center justify-center mb-4" style="background:#0071E31A">
          <svg class="w-[18px] h-[18px]" fill="none" stroke="#0071E3" stroke-width="1.8" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" d="M4 7v10c0 2.21 3.582 4 8 4s8-1.79 8-4V7M4 7c0 2.21 3.582 4 8 4s8-1.79 8-4M4 7c0-2.21 3.582-4 8-4s8 1.79 8 4"/></svg>
        </div>
        <h3 class="text-[13px] font-semibold text-ink dark:text-white mb-1.5">Pool de conexiones</h3>
        <p class="text-[11.5px] text-muted dark:text-[#8E8E93] leading-relaxed">JxPool propio — keepalive, timeout, prewarm. PostgreSQL, MySQL, SQL Server.</p>
      </div>

      <%-- Virtual Threads --%>
      <div class="jx-feat-card bg-[#F5F5F7] dark:bg-[#1C1C1E] border border-black/[0.06] dark:border-white/[0.07] rounded-2xl p-5 shadow-card hover:shadow-cardh transition-all" onmousemove="jxSpot(event,this)">
        <div class="w-9 h-9 rounded-xl flex items-center justify-center mb-4" style="background:#5E5CE61A">
          <svg class="w-[18px] h-[18px]" fill="none" stroke="#5E5CE6" stroke-width="1.8" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" d="M13 10V3L4 14h7v7l9-11h-7z"/></svg>
        </div>
        <h3 class="text-[13px] font-semibold text-ink dark:text-white mb-1.5">Virtual Threads</h3>
        <p class="text-[11.5px] text-muted dark:text-[#8E8E93] leading-relaxed">Detectados automáticamente en Java 21+. Sin config. Máxima concurrencia sin bloqueos.</p>
      </div>

      <%-- Validación --%>
      <div class="jx-feat-card bg-[#F5F5F7] dark:bg-[#1C1C1E] border border-black/[0.06] dark:border-white/[0.07] rounded-2xl p-5 shadow-card hover:shadow-cardh transition-all" onmousemove="jxSpot(event,this)">
        <div class="w-9 h-9 rounded-xl flex items-center justify-center mb-4" style="background:#FF950018">
          <svg class="w-[18px] h-[18px]" fill="none" stroke="#FF9500" stroke-width="1.8" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z"/></svg>
        </div>
        <h3 class="text-[13px] font-semibold text-ink dark:text-white mb-1.5">Validación declarativa</h3>
        <p class="text-[11.5px] text-muted dark:text-[#8E8E93] leading-relaxed">@JxRequired, @JxEmail, @JxPattern, @JxLength, @JxRange, @JxUrl, @JxFuture.</p>
      </div>

      <%-- Auth + Seguridad --%>
      <div class="jx-feat-card bg-[#F5F5F7] dark:bg-[#1C1C1E] border border-black/[0.06] dark:border-white/[0.07] rounded-2xl p-5 shadow-card hover:shadow-cardh transition-all" onmousemove="jxSpot(event,this)">
        <div class="w-9 h-9 rounded-xl flex items-center justify-center mb-4" style="background:#FF2D5518">
          <svg class="w-[18px] h-[18px]" fill="none" stroke="#FF2D55" stroke-width="1.8" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" d="M9 12l2 2 4-4m5.618-4.016A11.955 11.955 0 0112 2.944a11.955 11.955 0 01-8.618 3.04A12.02 12.02 0 003 9c0 5.591 3.824 10.29 9 11.622 5.176-1.332 9-6.03 9-11.622 0-1.042-.133-2.052-.382-3.016z"/></svg>
        </div>
        <h3 class="text-[13px] font-semibold text-ink dark:text-white mb-1.5">Auth + Rate Limit</h3>
        <p class="text-[11.5px] text-muted dark:text-[#8E8E93] leading-relaxed">@JxRequireAuth, @JxRequireRole, @JxRateLimit por IP+ruta. Ventana deslizante.</p>
      </div>

      <%-- Cache --%>
      <div class="jx-feat-card bg-[#F5F5F7] dark:bg-[#1C1C1E] border border-black/[0.06] dark:border-white/[0.07] rounded-2xl p-5 shadow-card hover:shadow-cardh transition-all" onmousemove="jxSpot(event,this)">
        <div class="w-9 h-9 rounded-xl flex items-center justify-center mb-4" style="background:#FF950018">
          <svg class="w-[18px] h-[18px]" fill="none" stroke="#FF9500" stroke-width="1.8" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" d="M5 12h14M5 12a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v4a2 2 0 01-2 2M5 12a2 2 0 00-2 2v4a2 2 0 002 2h14a2 2 0 002-2v-4a2 2 0 00-2-2m-2-4h.01M17 16h.01"/></svg>
        </div>
        <h3 class="text-[13px] font-semibold text-ink dark:text-white mb-1.5">Caché en memoria</h3>
        <p class="text-[11.5px] text-muted dark:text-[#8E8E93] leading-relaxed">@JxCacheable y @JxCacheEvict con TTL por namespace. Sin Redis, sin Caffeine.</p>
      </div>

      <%-- Grande: Zero deps --%>
      <div class="jx-feat-card col-span-2 bg-gradient-to-br from-[#0071E3]/[0.07] to-transparent dark:from-[#0071E3]/[0.12] dark:to-transparent border border-apple/20 rounded-2xl p-6 shadow-card hover:shadow-cardh transition-all" onmousemove="jxSpot(event,this)">
        <div class="w-10 h-10 rounded-xl flex items-center justify-center mb-4" style="background:#FF2D5520">
          <svg class="w-5 h-5" fill="none" stroke="#FF2D55" stroke-width="1.8" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" d="M7 21a4 4 0 01-4-4V5a2 2 0 012-2h4a2 2 0 012 2v12a4 4 0 01-4 4zm0 0h12a2 2 0 002-2v-4a2 2 0 00-2-2h-2.343M11 7.343l1.657-1.657a2 2 0 012.828 0l2.829 2.829a2 2 0 010 2.828l-8.486 8.485M7 17h.01"/></svg>
        </div>
        <h3 class="text-[15px] font-semibold text-ink dark:text-white mb-2">253 KB. Cero dependencias en runtime.</h3>
        <p class="text-[12px] text-muted dark:text-[#8E8E93] leading-relaxed mb-4">21 anotaciones propias, scheduler cron, pool JDBC, JSON nativo, WebSocket, métricas y OpenAPI — todo en <strong class="text-ink dark:text-white">java.*</strong> puro.</p>
        <div class="flex gap-4 text-[11px]">
          <span class="flex items-center gap-1.5 text-muted dark:text-[#8E8E93]"><span class="w-1.5 h-1.5 rounded-full bg-[#30D158]"></span>Sin Jackson</span>
          <span class="flex items-center gap-1.5 text-muted dark:text-[#8E8E93]"><span class="w-1.5 h-1.5 rounded-full bg-[#30D158]"></span>Sin Hibernate</span>
          <span class="flex items-center gap-1.5 text-muted dark:text-[#8E8E93]"><span class="w-1.5 h-1.5 rounded-full bg-[#30D158]"></span>Sin Spring</span>
          <span class="flex items-center gap-1.5 text-muted dark:text-[#8E8E93]"><span class="w-1.5 h-1.5 rounded-full bg-[#30D158]"></span>Sin Guice</span>
        </div>
      </div>

      <%-- GenApi + JxDB --%>
      <div class="jx-feat-card bg-[#F5F5F7] dark:bg-[#1C1C1E] border border-black/[0.06] dark:border-white/[0.07] rounded-2xl p-5 shadow-card hover:shadow-cardh transition-all" onmousemove="jxSpot(event,this)">
        <div class="w-9 h-9 rounded-xl flex items-center justify-center mb-4" style="background:#FF950018">
          <svg class="w-[18px] h-[18px]" fill="none" stroke="#FF9500" stroke-width="1.8" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" d="M20 7l-8-4-8 4m16 0l-8 4m8-4v10l-8 4m0-10L4 7m8 4v10M4 7v10l8 4"/></svg>
        </div>
        <h3 class="text-[13px] font-semibold text-ink dark:text-white mb-1.5">GenApi + JxDB</h3>
        <p class="text-[11.5px] text-muted dark:text-[#8E8E93] leading-relaxed">DBRow sin POJOs. JsonStr, JsonArray, JsonPaged, nested. Respuestas JSON en 1 línea.</p>
      </div>

      <%-- Métricas + OpenAPI --%>
      <div class="jx-feat-card bg-[#F5F5F7] dark:bg-[#1C1C1E] border border-black/[0.06] dark:border-white/[0.07] rounded-2xl p-5 shadow-card hover:shadow-cardh transition-all" onmousemove="jxSpot(event,this)">
        <div class="w-9 h-9 rounded-xl flex items-center justify-center mb-4" style="background:#30D15818">
          <svg class="w-[18px] h-[18px]" fill="none" stroke="#30D158" stroke-width="1.8" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z"/></svg>
        </div>
        <h3 class="text-[13px] font-semibold text-ink dark:text-white mb-1.5">Métricas + OpenAPI</h3>
        <p class="text-[11.5px] text-muted dark:text-[#8E8E93] leading-relaxed">/jx/health, /jx/metrics, /jx/openapi listos sin configuración. Latencia por ruta.</p>
      </div>

    </div>
  </div>
</section>

<%-- ── Code playground ────────────────────────────────────────────────── --%>
<section class="py-20 bg-white dark:bg-[#000000]">
  <div class="max-w-6xl mx-auto px-4 sm:px-6">
    <div class="mb-10 jx-reveal jx-delay-1">
      <p class="text-[10px] font-mono uppercase tracking-[0.28em] text-muted dark:text-[#8E8E93] mb-3">Casos de uso</p>
      <h2 class="text-3xl sm:text-4xl font-bold text-ink dark:text-white leading-tight">Todo lo que necesitas<br><span class="text-apple">desde el día uno.</span></h2>
    </div>
    <div class="jx-reveal jx-delay-2">
      <div class="flex flex-wrap gap-2 mb-4">
        <button class="jx-play-tab active" onclick="jxPlay('rest',this)">REST API</button>
        <button class="jx-play-tab" onclick="jxPlay('ws',this)">WebSocket</button>
        <button class="jx-play-tab" onclick="jxPlay('sched',this)">Scheduler</button>
        <button class="jx-play-tab" onclick="jxPlay('val',this)">Validación</button>
        <button class="jx-play-tab" onclick="jxPlay('cache',this)">Caché</button>
        <button class="jx-play-tab" onclick="jxPlay('auth',this)">Auth</button>
      </div>
      <div class="jx-win">
        <div class="jx-win-bar">
          <span class="jxd-r"></span><span class="jxd-y"></span><span class="jxd-g"></span>
          <span id="jxPlayLbl" class="ml-4 text-[11px] font-mono text-[#636366]">ApiController.java</span>
          <button onclick="jxCopyPane('jxPlayPane')" class="ml-auto text-[#636366] hover:text-[#8E8E93] transition-colors">
            <svg id="jxPlayIco" class="w-4 h-4" fill="none" stroke="currentColor" stroke-width="1.8" viewBox="0 0 24 24">
              <rect x="9" y="9" width="13" height="13" rx="2"></rect><path d="M5 15H4a2 2 0 01-2-2V4a2 2 0 012-2h9a2 2 0 012 2v1"></path>
            </svg>
          </button>
        </div>
        <div id="jxPlayPane">
          <div id="jxP-rest" class="jx-code-pane">
<pre><code class="language-java">@JxControllerMapping("api/users")
public class UserController extends JxController {

    @JxGetMapping("{id}")
    @JxRequireAuth
    public ActionResult get(@JxPathVar String id) {
        DBRow user = new UserModel().GetRow("users", "id=?", id);
        if (user == null) return Json(GenApi.Error(404, "No encontrado"));
        return Json(GenApi.JsonStr(
            "id",    id,
            "email", user.GetString("email"),
            "role",  user.GetString("role")
        ));
    }

    @JxPostMapping("")
    @JxRateLimit(requests = 10, windowMs = 60_000)
    public ActionResult create() {
        String email = model.param("email");
        String pass  = model.param("password");
        new UserModel().insert(email, pass);
        return Json(GenApi.Ok("Creado")).status(201);
    }

    @JxDeleteMapping("{id}")
    @JxRequireRole("admin")
    public ActionResult delete(@JxPathVar String id) {
        new UserModel().delete("id=?", id);
        return Json(GenApi.Ok("Eliminado"));
    }
}</code></pre>
          </div>
          <div id="jxP-ws" class="jx-code-pane hidden">
<pre><code class="language-java">@JxWsEndpoint("/ws/chat")
public class ChatEndpoint extends JxWebSocket {

    @Override
    public void onConnect(Session session) {
        broadcast("sistema",
            session.getId() + " entró al chat");
    }

    @Override
    public void onMessage(Session session, String msg) {
        String user = (String)
            session.getUserProperties().get("user");
        broadcast("msg",
            "{\"user\":\"" + user + "\",\"text\":\"" + msg + "\"}");
    }

    @Override
    public void onClose(Session session) {
        broadcast("sistema",
            session.getId() + " salió");
    }

    @Override
    public void onError(Session session, Throwable err) {
        log.warn("WS error: {}", err.getMessage());
    }
}</code></pre>
          </div>
          <div id="jxP-sched" class="jx-code-pane hidden">
<pre><code class="language-java">@JxComponent
public class TareasProgramadas {

    // Cada día a las 2:00 AM
    @JxScheduled(cron = "0 0 2 * * *")
    public void limpiarSesiones() {
        int n = new SesionModel().deleteExpired();
        log.info("Sesiones expiradas eliminadas: {}", n);
    }

    // Cada 5 minutos
    @JxScheduled(fixedRate = 300_000)
    public void sincronizarCache() {
        JxCache.get("catalogo").evictAll();
    }

    // Una sola vez, 10 s después del arranque
    @JxScheduled(runOnce = true, initialDelay = 10_000)
    public void precalentarPool() {
        new ProductoModel().GetRow("productos", "1=1");
        log.info("Pool precalentado");
    }
}</code></pre>
          </div>
          <div id="jxP-val" class="jx-code-pane hidden">
<pre><code class="language-java">public class RegistroForm {

    @JxRequired @JxEmail
    private String email;

    @JxRequired
    @JxLength(min = 8, max = 64)
    @JxPattern(regex = "(?=.*[A-Z])(?=.*\\d).+",
               msg = "Requiere mayúscula y número")
    private String password;

    @JxRequired @JxLength(min = 2, max = 80)
    private String nombre;

    @JxRange(min = 18, max = 120)
    private int edad;

    @JxFuture
    private LocalDate vencimiento;

    @JxUrl
    private String sitioWeb;
}

@JxPostMapping("registro")
public ActionResult registrar(@JxValid RegistroForm form) {
    if (!model.isValid()) return Json(model.errors());
    return Json(GenApi.Ok("Registrado")).status(201);
}</code></pre>
          </div>

          <div id="jxP-cache" class="jx-code-pane hidden">
<pre><code class="language-java">@JxControllerMapping("api/catalogo")
public class CatalogoController extends JxController {

    @JxGetMapping("productos")
    @JxCacheable(value = "catalogo", ttl = 300_000)
    public ActionResult productos() {
        DBRowSet rows = new ProductoModel()
            .GetTable("productos", "activo = 1");
        return Json(GenApi.JsonArray(rows, "productos"));
    }

    @JxPostMapping("producto/save")
    @JxCacheEvict(value = "catalogo")
    public ActionResult guardar() {
        String nombre = model.param("nombre");
        new ProductoModel().insert(nombre);
        return Json(GenApi.Ok("Guardado")).status(201);
    }
}

// Sin Redis. Sin Caffeine.
// JxCache usa ConcurrentHashMap + TTL nativo.</code></pre>
          </div>

          <div id="jxP-auth" class="jx-code-pane hidden">
<pre><code class="language-java">@JxControllerMapping("api/admin")
@JxRequireAuth
public class AdminController extends JxController {

    @JxGetMapping("usuarios")
    @JxRequireRole("admin")
    public ActionResult listar() {
        DBRowSet rows = new UsuarioModel()
            .GetTable("usuarios", "activo = 1");
        return Json(GenApi.JsonArray(rows, "usuarios"));
    }

    @JxDeleteMapping("usuario/{id}")
    @JxRequireRole("superadmin")
    @JxRateLimit(requests = 5, windowMs = 60_000)
    public ActionResult eliminar(@JxPathVar String id) {
        new UsuarioModel().softDelete("id = ?", id);
        return Json(GenApi.Ok("Eliminado"));
    }
}

// Sesión: HttpSession estándar del servlet container.
// Sin JWT externo, sin OAuth lib. Cero dependencias.</code></pre>
          </div>

        </div>
      </div>
    </div>
  </div>
</section>

<%-- ── Pipeline visual ────────────────────────────────────────────────── --%>
<style>
  .jx-pipe-step { cursor:pointer; }
  .jx-pipe-step:hover .jx-ps-box { transform:translateY(-3px); box-shadow:0 8px 24px rgba(0,0,0,0.12); }
  .dark .jx-pipe-step:hover .jx-ps-box { box-shadow:0 8px 24px rgba(0,0,0,0.4); }
  .jx-ps-box { transition:transform .2s ease, box-shadow .2s ease; }
  .jx-pipe-detail { display:grid; grid-template-rows:0fr; opacity:0;
    transition:grid-template-rows .34s cubic-bezier(.16,1,.3,1), opacity .24s ease, margin-top .34s ease;
    margin-top:0; }
  .jx-pipe-detail > .jx-pipe-inner { overflow:hidden; min-height:0; }
  .jx-pipe-detail.open { grid-template-rows:1fr; opacity:1; margin-top:24px; }
</style>
<section class="py-20 bg-white dark:bg-[#000000]" id="jxPipeSec">
  <div class="max-w-6xl mx-auto px-4 sm:px-6">

    <div class="mb-12">
      <p class="text-[10px] font-mono uppercase tracking-[0.28em] text-muted dark:text-[#8E8E93] mb-3">Arquitectura</p>
      <h2 class="text-3xl sm:text-4xl font-bold text-ink dark:text-white leading-tight">15 etapas por cada request</h2>
      <p class="text-[14px] text-muted dark:text-[#8E8E93] mt-3 max-w-xl">Cada petición HTTP pasa por un pipeline controlado — sin magia, predecible y trazable. Haz clic en cualquier etapa para ver el detalle.</p>
    </div>

    <%
    String[][] pipe = {
      {"01","Endpoints",   "#0071E3","Rutas internas /jx/health, /jx/info, /jx/metrics, /jx/openapi. Resueltas antes de cualquier lógica de negocio."},
      {"02","Métricas",    "#30D158","Timer de latencia iniciado por ruta. Registra total de peticiones, errores 4xx/5xx y P95."},
      {"03","Rate limit",  "#FF2D55","@JxRateLimit — ventana deslizante por IP + ruta. Retorna 429 con Retry-After si se excede."},
      {"04","Routing",     "#0071E3","Convención / anotaciones / plantillas {var}. Selecciona controller + acción. 405 si verbo no coincide."},
      {"05","Perfil",      "#FF9500","@JxProfile — activa o desactiva el endpoint según entorno (dev/prod/test). 404 si no aplica."},
      {"06","Auth",        "#FF2D55","@JxRequireAuth y @JxRequireRole. Valida sesión y rol antes de instanciar el controller."},
      {"07","CORS",        "#5E5CE6","@JxCors global o por controller. Inyecta Access-Control-* y gestiona preflight OPTIONS."},
      {"08","Filtros",     "#FF9500","@JxFilter con fase BEFORE. Permite interceptar, modificar request o abortar con respuesta propia."},
      {"09","DI + Ctrl",   "#0071E3","Controller instanciado. @JxInject y @JxValue resueltos del registro de servicios."},
      {"10","Before",      "#5E5CE6","@JxBeforeAction — interceptores declarados en el controller, ejecutados antes de la acción."},
      {"11","ModelAttr",   "#FF9500","@JxModelAttr — atributos comunes inyectados al modelo antes de llamar la acción."},
      {"12","Invocación",  "#30D158","Acción ejecutada. @JxAsync (background thread), @JxRetry (reintentos con backoff), @JxCacheable."},
      {"13","After",       "#5E5CE6","@JxAfterAction + filtros AFTER. Post-procesamiento, auditoría, transformación de respuesta."},
      {"14","Render",      "#0071E3","Negociación de contenido: JSP, JSON, raw, redirect. Métricas finales registradas."},
    };
    %>

    <%-- Desktop: 2 filas horizontales con nodos --%>
    <div class="hidden md:block space-y-8">
      <div class="flex items-center gap-0">
        <% for (int i=0;i<7;i++) { String[] p=pipe[i]; %>
        <div class="jx-pipe-node jx-pipe-step flex-1 min-w-0 flex flex-col items-center gap-2" style="transition-delay:<%= i*60 %>ms" onclick="jxPipeToggle(<%= i %>)">
          <div class="jx-ps-box w-11 h-11 rounded-xl flex items-center justify-center text-[10px] font-mono font-bold text-white shrink-0 border-2 border-white/20" style="background:<%= p[2] %>"><%= p[0] %></div>
          <span class="text-[9px] font-mono text-muted dark:text-[#8E8E93] text-center leading-tight px-1"><%= p[1] %></span>
        </div>
        <% if(i<6) { %><div class="jx-pipe-line" style="background:linear-gradient(90deg,<%= pipe[i][2] %>,<%= pipe[i+1][2] %>)"></div><% } %>
        <% } %>
      </div>
      <div class="flex items-center gap-0">
        <% for (int i=7;i<14;i++) { String[] p=pipe[i]; %>
        <div class="jx-pipe-node jx-pipe-step flex-1 min-w-0 flex flex-col items-center gap-2" style="transition-delay:<%= i*60 %>ms" onclick="jxPipeToggle(<%= i %>)">
          <div class="jx-ps-box w-11 h-11 rounded-xl flex items-center justify-center text-[10px] font-mono font-bold text-white shrink-0 border-2 border-white/20" style="background:<%= p[2] %>"><%= p[0] %></div>
          <span class="text-[9px] font-mono text-muted dark:text-[#8E8E93] text-center leading-tight px-1"><%= p[1] %></span>
        </div>
        <% if(i<13) { %><div class="jx-pipe-line" style="background:linear-gradient(90deg,<%= pipe[i][2] %>,<%= pipe[i+1][2] %>)"></div><% } %>
        <% } %>
      </div>
    </div>

    <%-- Detail panel — estilo terminal, colapsa por completo cuando cierra --%>
    <div id="jxPipePanel" class="hidden md:grid jx-pipe-detail">
      <div class="jx-pipe-inner">
        <div class="jx-term rounded-2xl overflow-hidden border" id="jxPipeCard"
             style="background:var(--t-bg); border-color:var(--t-brd); box-shadow:var(--t-shadow)">
          <div class="flex items-center gap-2.5 px-4 py-2.5" style="background:var(--t-bar); border-bottom:1px solid var(--t-bar-brd)">
            <span class="w-3 h-3 rounded-full" style="background:#FF5F57"></span>
            <span class="w-3 h-3 rounded-full" style="background:#FEBC2E"></span>
            <span class="w-3 h-3 rounded-full" style="background:#28C840"></span>
            <span id="jxPipeTab" class="ml-2 text-[11px] font-mono jx-t-mut"></span>
          </div>
          <div class="px-6 py-5 font-mono">
            <div class="flex items-center gap-3 mb-2.5">
              <span id="jxPipeBadge" class="text-[10px] font-bold text-white px-2 py-0.5 rounded-md"></span>
              <span id="jxPipeTitle" class="text-[13px] font-semibold jx-t-cmd"></span>
              <span id="jxPipeStep" class="ml-auto text-[10px] jx-t-dim"></span>
            </div>
            <p class="text-[11px] mb-1.5 jx-t-dim">
              <span class="jx-t-prompt">$</span> jxmvc --trace <span id="jxPipeSlug" class="jx-t-key"></span>
            </p>
            <p id="jxPipeDesc" class="text-[12.5px] leading-relaxed jx-t-mut"></p>
          </div>
        </div>
      </div>
    </div>

    <%-- Mobile: lista vertical (sin jx-pipe-node: esa clase global fuerza columna/opacity y rompía el layout) --%>
    <div class="md:hidden space-y-2.5">
      <% for (String[] p : pipe) { %>
      <div class="flex items-start gap-3 rounded-xl bg-[#F5F5F7] dark:bg-[#1C1C1E] border border-black/[0.06] dark:border-white/[0.07] px-4 py-3">
        <span class="w-7 h-7 rounded-lg flex items-center justify-center text-[10px] font-mono font-bold text-white shrink-0 mt-0.5" style="background:<%= p[2] %>"><%= p[0] %></span>
        <div class="min-w-0 flex-1">
          <span class="block text-[13px] font-semibold text-ink dark:text-white"><%= p[1] %></span>
          <p class="text-[11.5px] text-muted dark:text-[#8E8E93] leading-snug mt-0.5"><%= p[3] %></p>
        </div>
      </div>
      <% } %>
    </div>

  </div>
</section>

<script>
var jxPipeData = [
  <% for (int i=0;i<pipe.length;i++) { %>{num:"<%= pipe[i][0] %>",name:"<%= pipe[i][1] %>",color:"<%= pipe[i][2] %>",desc:"<%= pipe[i][3].replace("\"","&quot;") %>"}<%= i<pipe.length-1?",":"" %>
  <% } %>
];
var jxPipeOpen = -1;
function jxPipeToggle(idx) {
  var panel = document.getElementById('jxPipePanel');
  if (jxPipeOpen === idx) {
    panel.classList.remove('open');
    jxPipeOpen = -1;
    return;
  }
  var d = jxPipeData[idx];
  document.getElementById('jxPipeBadge').textContent = d.num;
  document.getElementById('jxPipeBadge').style.background = d.color;
  document.getElementById('jxPipeTitle').textContent = d.name;
  document.getElementById('jxPipeDesc').textContent = d.desc;
  document.getElementById('jxPipeTab').textContent = 'pipeline · etapa ' + d.num + '/14';
  document.getElementById('jxPipeStep').textContent = '[' + d.num + '/14]';
  document.getElementById('jxPipeSlug').textContent = d.name.toLowerCase().replace(/[^a-z0-9]+/g,'-');
  var card = document.getElementById('jxPipeCard');
  if (card) card.style.boxShadow = '0 18px 44px ' + d.color + '33';
  panel.classList.add('open');
  jxPipeOpen = idx;
}
</script>

<%-- ── Comparativa columnas verticales ─────────────────────────────────── --%>
<section class="py-20 bg-white dark:bg-[#000000]" id="jxChartSec">
  <div class="max-w-5xl mx-auto px-4 sm:px-6">

    <div class="mb-4">
      <p class="text-[10px] font-mono uppercase tracking-[0.28em] text-muted dark:text-[#8E8E93] mb-3">Comparativa real · 5 frameworks</p>
      <h2 class="text-3xl sm:text-4xl font-bold text-ink dark:text-white leading-tight">El más ligero.<br><span class="text-apple">El único con cero dependencias.</span></h2>
    </div>

    <div class="flex flex-wrap gap-2.5 mt-5 mb-10">
      <span class="inline-flex items-center gap-1.5 px-3.5 py-1.5 rounded-full bg-apple/10 border border-apple/20 text-apple text-[11px] font-semibold">89× más pequeño que Spring Boot</span>
      <span class="inline-flex items-center gap-1.5 px-3.5 py-1.5 rounded-full bg-[#FF2D55]/10 border border-[#FF2D55]/20 text-[#FF2D55] text-[11px] font-semibold">0 deps en runtime</span>
      <span class="inline-flex items-center gap-1.5 px-3.5 py-1.5 rounded-full bg-[#30D158]/10 border border-[#30D158]/20 text-[#30D158] text-[11px] font-semibold">5× más rápido que Spring Boot</span>
    </div>

    <div class="flex gap-2 mb-8">
      <button class="jx-chart-tab active" onclick="jxChart('jar',this)">Tamaño JAR</button>
      <button class="jx-chart-tab" onclick="jxChart('dep',this)">Dependencias</button>
      <button class="jx-chart-tab" onclick="jxChart('start',this)">Arranque JVM</button>
    </div>

    <div class="bg-white dark:bg-[#1C1C1E] border border-black/[0.06] dark:border-white/[0.07] rounded-2xl p-8 shadow-card" style="position:relative;">
      <p class="text-[10px] font-mono uppercase tracking-widest text-muted dark:text-[#636366] mb-6" id="jxChartUnit"></p>

      <%-- Canvas del gráfico --%>
      <canvas id="jxCanvas" height="240" style="width:100%;display:block;"></canvas>
      <div id="jxChartTip" style="position:absolute; pointer-events:none; opacity:0; transition:opacity .13s ease-out;
           background:rgba(29,29,31,.92); color:#F5F5F7; font:600 11px 'Space Grotesk',sans-serif;
           padding:6px 10px; border-radius:8px; white-space:nowrap; z-index:5;"></div>

      <p class="text-[10px] text-muted dark:text-[#636366] mt-5 leading-relaxed" id="jxChartNote"></p>
    </div>
  </div>
</section>

<script>
var jxChartData = {
  jar: {
    unit: 'Tamaño del artefacto de producción — menor es mejor',
    note: 'JAR/WAR sin dependencias transitivas del servidor. JxMVC: 253 KB. Spring Boot uber-JAR incluye Tomcat embebido.',
    rows: [
      {name:'JxMVC 3.4',   val:'253 KB', raw:0.253, jx:true},
      {name:'Javalin 6',   val:'1.5 MB', raw:1.5,   jx:false},
      {name:'Quarkus 3',   val:'18 MB',  raw:18,    jx:false},
      {name:'Spring Boot', val:'20 MB',  raw:20,    jx:false},
      {name:'Micronaut 4', val:'25 MB',  raw:25,    jx:false},
    ]
  },
  dep: {
    unit: 'Dependencias en runtime — menor es mejor',
    note: 'JxMVC solo necesita el servlet container. Sin JAR externo en classpath de producción.',
    rows: [
      {name:'JxMVC 3.3',   val:'0',    raw:0,   jx:true},
      {name:'Javalin 6',   val:'~10',  raw:10,  jx:false},
      {name:'Quarkus 3',   val:'~80',  raw:80,  jx:false},
      {name:'Spring Boot', val:'~210', raw:210, jx:false},
      {name:'Micronaut 4', val:'~150', raw:150, jx:false},
    ]
  },
  start: {
    unit: 'Tiempo de arranque en JVM — segundos — menor es mejor',
    note: 'JVM estándar sin AOT. Quarkus y Micronaut requieren compilación nativa para sus cifras AOT más bajas.',
    rows: [
      {name:'Quarkus 3',   val:'0.3 s', raw:0.3, jx:false},
      {name:'Micronaut 4', val:'0.4 s', raw:0.4, jx:false},
      {name:'Javalin 6',   val:'0.5 s', raw:0.5, jx:false},
      {name:'JxMVC 3.3',   val:'1.2 s', raw:1.2, jx:true},
      {name:'Spring Boot', val:'6.0 s', raw:6.0, jx:false},
    ]
  }
};

var jxAnimFrame = null;

/* El color sigue al framework, no a la posición: mismo color en las 3 pestañas. */
var jxFwPalette = {
  light: {'JxMVC 3.3':'#0071E3','Javalin 6':'#FF9500','Quarkus 3':'#5E5CE6','Spring Boot':'#30D158','Micronaut 4':'#FF2D55'},
  dark:  {'JxMVC 3.3':'#0071E3','Javalin 6':'#CC7A00','Quarkus 3':'#5E5CE6','Spring Boot':'#27A24C','Micronaut 4':'#FF2D55'}
};

function jxFwColor(name, isDark) {
  return jxFwPalette[isDark ? 'dark' : 'light'][name] || (isDark ? '#48484A' : '#AEAEB2');
}

function jxChart(key, btn) {
  document.querySelectorAll('.jx-chart-tab').forEach(function(b){ b.classList.remove('active'); });
  btn.classList.add('active');
  jxRenderChart(key);
}

function jxRenderChart(key) {
  var data = jxChartData[key];
  document.getElementById('jxChartUnit').textContent = data.unit;
  document.getElementById('jxChartNote').textContent = data.note;

  var canvas = document.getElementById('jxCanvas');
  var dpr    = window.devicePixelRatio || 1;
  var W      = canvas.parentElement.clientWidth - 64;
  var H      = 240;
  canvas.width  = W * dpr;
  canvas.height = H * dpr;
  canvas.style.width  = W + 'px';
  canvas.style.height = H + 'px';
  var ctx = canvas.getContext('2d');
  ctx.scale(dpr, dpr);

  var isDark = document.documentElement.classList.contains('dark');
  var n      = data.rows.length;
  var PAD_L  = 8;
  var PAD_R  = 8;
  var PAD_B  = 48;
  var PAD_T  = 28;
  var chartH = H - PAD_B - PAD_T;
  var colW   = (W - PAD_L - PAD_R) / n;
  var barW   = Math.min(colW * 0.55, 72);

  var maxRaw = 0;
  data.rows.forEach(function(r){ if (r.raw > maxRaw) maxRaw = r.raw; });
  if (maxRaw === 0) maxRaw = 1;

  if (jxAnimFrame) cancelAnimationFrame(jxAnimFrame);

  var startTs = null;
  var DURATION = 1100;

  function ease(t) { return 1 - Math.pow(1 - t, 3); }

  function draw(ts) {
    if (!startTs) startTs = ts;
    var progress = Math.min((ts - startTs) / DURATION, 1);
    var ep = ease(progress);

    ctx.clearRect(0, 0, W, H);

    var gridColor = isDark ? 'rgba(255,255,255,0.07)' : 'rgba(0,0,0,0.07)';
    for (var g = 0; g <= 4; g++) {
      var gy = PAD_T + (chartH / 4) * g;
      ctx.beginPath();
      ctx.strokeStyle = gridColor;
      ctx.lineWidth = 1;
      ctx.setLineDash([4, 4]);
      ctx.moveTo(PAD_L, gy);
      ctx.lineTo(W - PAD_R, gy);
      ctx.stroke();
    }
    ctx.setLineDash([]);

    data.rows.forEach(function(row, i) {
      var cx   = PAD_L + colW * i + colW / 2;
      var x    = cx - barW / 2;
      var pct  = row.raw / maxRaw;
      var fullH = Math.max(pct * chartH, 2);
      var barH = fullH * ep;
      var y    = PAD_T + chartH - barH;

      var color = jxFwColor(row.name, isDark);

      if (row.jx) {
        var grd = ctx.createLinearGradient(0, y, 0, y + barH);
        grd.addColorStop(0, '#409CFF');
        grd.addColorStop(1, '#0071E3');
        ctx.fillStyle = grd;
      } else {
        ctx.fillStyle = color;
      }

      var r = Math.min(4, barW / 2);
      ctx.beginPath();
      ctx.moveTo(x + r, y);
      ctx.lineTo(x + barW - r, y);
      ctx.quadraticCurveTo(x + barW, y, x + barW, y + r);
      ctx.lineTo(x + barW, y + barH);
      ctx.lineTo(x, y + barH);
      ctx.lineTo(x, y + r);
      ctx.quadraticCurveTo(x, y, x + r, y);
      ctx.closePath();
      ctx.fill();
      ctx.shadowBlur = 0;

      if (progress > 0.6) {
        var valAlpha = Math.min((progress - 0.6) / 0.4, 1);
        ctx.globalAlpha = valAlpha;
        ctx.fillStyle = isDark ? '#F5F5F7' : '#1D1D1F';
        ctx.font = 'bold 11px "Space Grotesk", monospace';
        ctx.textAlign = 'center';
        ctx.fillText(row.val, cx, y - 7);
        ctx.globalAlpha = 1;
      }

      ctx.fillStyle = isDark ? (row.jx ? '#F5F5F7' : '#EBEBF599') : (row.jx ? '#1D1D1F' : '#1D1D1F99');
      ctx.font = (row.jx ? 'bold' : 'normal') + ' 11px "Space Grotesk", sans-serif';
      ctx.textAlign = 'center';
      ctx.fillText(row.name, cx, H - 10);

      jxBarRects[i] = {x:x, y:y, w:barW, h:barH, row:row};
    });

    if (progress < 1) jxAnimFrame = requestAnimationFrame(draw);
  }

  if (window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    draw(performance.now() + DURATION);
  } else {
    jxAnimFrame = requestAnimationFrame(draw);
  }
}

var jxBarRects = [];

document.addEventListener('DOMContentLoaded', function(){
  var canvas = document.getElementById('jxCanvas');
  var tip    = document.getElementById('jxChartTip');
  if (!canvas || !tip) return;
  canvas.addEventListener('mousemove', function(ev){
    var rect = canvas.getBoundingClientRect();
    var mx = ev.clientX - rect.left, my = ev.clientY - rect.top;
    var hit = null;
    jxBarRects.forEach(function(b){
      if (b && mx >= b.x - 6 && mx <= b.x + b.w + 6 && my >= b.y - 10 && my <= b.y + b.h) hit = b;
    });
    if (hit) {
      tip.textContent = hit.row.name + ' — ' + hit.row.val;
      tip.style.opacity = '1';
      tip.style.left = Math.min(mx + 14, rect.width - 130) + 'px';
      tip.style.top  = (hit.y - 8) + 'px';
      canvas.style.cursor = 'default';
    } else {
      tip.style.opacity = '0';
    }
  });
  canvas.addEventListener('mouseleave', function(){ tip.style.opacity = '0'; });
});

document.addEventListener('DOMContentLoaded', function(){
  var fired = false;
  var io2 = new IntersectionObserver(function(entries){
    if (entries[0].isIntersecting && !fired) {
      fired = true;
      jxRenderChart('jar');
    }
  }, {threshold: 0.2});
  var sec = document.getElementById('jxChartSec');
  if (sec) io2.observe(sec);
});

function jxRedrawActiveChart(){
  var active = document.querySelector('.jx-chart-tab.active');
  if (active && document.getElementById('jxChartSec')) {
    var key = active.getAttribute('onclick').match(/'(\w+)'/)[1];
    jxRenderChart(key);
  }
}
window.addEventListener('resize', jxRedrawActiveChart);
// Redibujar el canvas al cambiar de tema (el canvas no hereda CSS del tema).
new MutationObserver(jxRedrawActiveChart).observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });
</script>

<%-- ── El core por dentro: donut de módulos ───────────────────────────── --%>
<section class="py-20 bg-white dark:bg-[#000000]" id="jxDonutSec">
  <div class="max-w-5xl mx-auto px-4 sm:px-6">

    <div class="mb-10">
      <h2 class="text-3xl sm:text-4xl font-bold text-ink dark:text-white leading-tight">52 clases. 253 KB.<br><span class="text-apple">Todo el framework.</span></h2>
      <p class="text-[14px] text-muted dark:text-[#8E8E93] mt-3 max-w-xl">Sin dependencias que auditar ni versiones que conciliar: cada módulo está escrito dentro del propio JAR.</p>
    </div>

    <div class="grid lg:grid-cols-2 gap-8 items-center">
      <div class="flex justify-center" style="position:relative;">
        <svg id="jxDonut" width="300" height="300" viewBox="0 0 300 300" role="img"
             aria-label="Distribución de las 52 clases del core por módulo"></svg>
        <div id="jxDonutTip" style="position:absolute; pointer-events:none; opacity:0; transition:opacity .13s ease-out;
             background:rgba(29,29,31,.92); color:#F5F5F7; font:600 11px 'Space Grotesk',sans-serif;
             padding:6px 10px; border-radius:8px; white-space:nowrap; z-index:5;"></div>
      </div>

      <div>
        <ul id="jxDonutLegend" class="space-y-2.5"></ul>
        <p class="text-[10px] text-muted dark:text-[#636366] mt-6 leading-relaxed">
          Clases contadas en <code class="font-mono">jxmvc.core</code> de la v3.4.0.
          Métricas en vivo del propio sitio en el <a href="${pageContext.request.contextPath}/home/panel" class="text-apple hover:underline">panel de observabilidad</a>.
        </p>
      </div>
    </div>
  </div>
</section>

<script>
var jxDonutData = [
  {name:'Infraestructura y config', n:15, c:null},
  {name:'HTTP y routing',           n:12, c:0},
  {name:'Datos y transacciones',    n:8,  c:1},
  {name:'API, JSON y observabilidad',n:6, c:2},
  {name:'Tiempo real y tareas',     n:6,  c:3},
  {name:'Seguridad',                n:5,  c:4}
];

function jxDonutColors() {
  var dark = document.documentElement.classList.contains('dark');
  return {
    serie: dark ? ['#0071E3','#CC7A00','#27A24C','#5E5CE6','#FF2D55']
                : ['#0071E3','#FF9500','#30D158','#5E5CE6','#FF2D55'],
    neutro: dark ? '#48484A' : '#C7C7CC',
    gap:    dark ? '#000000' : '#FFFFFF',
    ink:    dark ? '#F5F5F7' : '#1D1D1F',
    sub:    dark ? '#8E8E93' : '#6E6E73'
  };
}

function jxRenderDonut() {
  var svg = document.getElementById('jxDonut');
  if (!svg) return;
  var col = jxDonutColors();
  var total = 0;
  jxDonutData.forEach(function(d){ total += d.n; });

  var cx = 150, cy = 150, R = 118, r = 74, a0 = -Math.PI / 2;
  var parts = [];
  jxDonutData.forEach(function(d, i) {
    var a1 = a0 + (d.n / total) * Math.PI * 2;
    var color = d.c === null ? col.neutro : col.serie[d.c];
    var large = (a1 - a0) > Math.PI ? 1 : 0;
    var p = ['M', cx + R * Math.cos(a0), cy + R * Math.sin(a0),
             'A', R, R, 0, large, 1, cx + R * Math.cos(a1), cy + R * Math.sin(a1),
             'L', cx + r * Math.cos(a1), cy + r * Math.sin(a1),
             'A', r, r, 0, large, 0, cx + r * Math.cos(a0), cy + r * Math.sin(a0),
             'Z'].join(' ');
    var mid = (a0 + a1) / 2;
    parts.push('<path d="' + p + '" fill="' + color + '" stroke="' + col.gap + '" stroke-width="2" '
             + 'data-i="' + i + '" data-mx="' + (cx + (R + r) / 2 * Math.cos(mid)) + '" data-my="' + (cy + (R + r) / 2 * Math.sin(mid)) + '" '
             + 'style="transition:opacity .13s ease-out;"></path>');
    a0 = a1;
  });
  parts.push('<text x="150" y="143" text-anchor="middle" fill="' + col.ink + '" style="font:700 30px \'Space Grotesk\',sans-serif;">' + total + '</text>');
  parts.push('<text x="150" y="166" text-anchor="middle" fill="' + col.sub + '" style="font:500 12px \'Space Grotesk\',sans-serif;">clases · 253 KB</text>');
  svg.innerHTML = parts.join('');

  var legend = document.getElementById('jxDonutLegend');
  legend.innerHTML = jxDonutData.map(function(d) {
    var color = d.c === null ? col.neutro : col.serie[d.c];
    var pct = Math.round(d.n / total * 100);
    return '<li class="flex items-center gap-3">'
         + '<span style="width:10px;height:10px;border-radius:3px;background:' + color + ';flex-shrink:0;"></span>'
         + '<span class="text-[13px] font-medium text-ink dark:text-[#F5F5F7] flex-1">' + d.name + '</span>'
         + '<span class="text-[13px] font-semibold text-ink dark:text-[#F5F5F7]" style="font-variant-numeric:tabular-nums;">' + d.n + '</span>'
         + '<span class="text-[11px] text-muted dark:text-[#8E8E93]" style="width:34px;text-align:right;font-variant-numeric:tabular-nums;">' + pct + '%</span>'
         + '</li>';
  }).join('');

  var tip = document.getElementById('jxDonutTip');
  svg.querySelectorAll('path').forEach(function(seg) {
    seg.addEventListener('mouseenter', function() {
      var d = jxDonutData[+seg.getAttribute('data-i')];
      svg.querySelectorAll('path').forEach(function(o){ o.style.opacity = o === seg ? '1' : '.45'; });
      tip.textContent = d.name + ' — ' + d.n + ' clases';
      var host = svg.parentElement.getBoundingClientRect();
      var box  = svg.getBoundingClientRect();
      tip.style.left = (box.left - host.left + (+seg.getAttribute('data-mx')) / 300 * box.width + 10) + 'px';
      tip.style.top  = (box.top - host.top + (+seg.getAttribute('data-my')) / 300 * box.height - 24) + 'px';
      tip.style.opacity = '1';
    });
    seg.addEventListener('mouseleave', function() {
      svg.querySelectorAll('path').forEach(function(o){ o.style.opacity = '1'; });
      tip.style.opacity = '0';
    });
  });
}

document.addEventListener('DOMContentLoaded', function() {
  jxRenderDonut();
  new MutationObserver(jxRenderDonut).observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });
});
</script>

<%-- ── Endpoints ───────────────────────────────────────────────────────── --%>
<section class="py-20 bg-white dark:bg-[#000000]">
  <div class="max-w-6xl mx-auto px-4 sm:px-6">

    <div class="mb-12 jx-reveal jx-delay-1">
      <p class="text-[10px] font-mono uppercase tracking-[0.28em] text-muted dark:text-[#8E8E93] mb-3">Endpoints del sistema</p>
      <h2 class="text-3xl sm:text-4xl font-bold text-ink dark:text-white leading-tight">Observabilidad<br><span class="text-apple">incluida.</span></h2>
      <p class="text-[14px] text-muted dark:text-[#8E8E93] mt-3 max-w-xl">Cuatro endpoints listos desde el arranque — sin configuración, sin dependencias externas.</p>
    </div>

    <div class="grid lg:grid-cols-2 gap-4 jx-reveal jx-delay-2">

      <%-- Lista de endpoints --%>
      <div class="bg-[#F5F5F7] dark:bg-[#1C1C1E] border border-black/[0.06] dark:border-white/[0.07] rounded-2xl overflow-hidden divide-y divide-black/[0.05] dark:divide-white/[0.05] shadow-card">
        <%
        String[][] eps = {
          {"/jx/health",  "Estado del pool, uptime y threads activos","#30D158","health"},
          {"/jx/info",    "Versión, perfil activo, Java y servidor","#0071E3","info"},
          {"/jx/metrics", "Latencia, peticiones totales y errores por ruta","#FF9500","metrics"},
          {"/jx/openapi", "Especificación OpenAPI 3.0 generada automáticamente","#5E5CE6","openapi"},
        };
        for (String[] ep : eps) {
        %>
        <button onclick="jxEpShow('<%= ep[3] %>', this)"
                class="jx-ep-row w-full flex items-center justify-between px-5 py-4 hover:bg-white/70 dark:hover:bg-white/[0.04] transition-colors text-left">
          <div class="flex items-center gap-4">
            <span class="w-2 h-2 rounded-full shrink-0" style="background:<%= ep[2] %>"></span>
            <span class="text-[10px] font-mono font-semibold text-apple bg-apple/10 px-2.5 py-1 rounded-lg">GET</span>
            <div>
              <code class="text-[13px] font-mono font-semibold text-ink dark:text-white"><%= ep[0] %></code>
              <p class="text-[11px] text-muted dark:text-[#8E8E93] mt-0.5"><%= ep[1] %></p>
            </div>
          </div>
          <a href="${pageContext.request.contextPath}<%= ep[0] %>" target="_blank"
             onclick="event.stopPropagation()"
             class="text-[11px] text-apple hover:underline font-mono shrink-0 ml-4">abrir →</a>
        </button>
        <% } %>
      </div>

      <%-- Preview JSON --%>
      <div class="jx-win h-full min-h-[280px]">
        <div class="jx-win-bar">
          <span class="jxd-r"></span><span class="jxd-y"></span><span class="jxd-g"></span>
          <span id="jxEpLbl" class="ml-4 text-[11px] font-mono text-[#636366]">GET /jx/health</span>
        </div>
        <div id="jxEpPane" class="jx-code-pane overflow-auto" style="min-height:240px">
<pre><code id="jxEpCode" class="language-json">{
  "status": "UP",
  "version": "3.4.0",
  "uptime": "00:04:12",
  "pool": {
    "active": 2,
    "idle": 8,
    "total": 10
  },
  "threads": {
    "active": 4,
    "virtual": true
  },
  "memory": {
    "usedMB": 48,
    "maxMB": 512
  }
}</code></pre>
        </div>
      </div>

    </div>
  </div>
</section>

<script>
var jxEpSamples = {
  health: {
    label: 'GET /jx/health',
    code: '{\n  "status": "UP",\n  "version": "3.4.0",\n  "uptime": "00:04:12",\n  "pool": {\n    "active": 2,\n    "idle": 8,\n    "total": 10\n  },\n  "threads": {\n    "active": 4,\n    "virtual": true\n  },\n  "memory": {\n    "usedMB": 48,\n    "maxMB": 512\n  }\n}'
  },
  info: {
    label: 'GET /jx/info',
    code: '{\n  "framework": "JxMVC",\n  "version": "3.4.0",\n  "profile": "prod",\n  "java": "21.0.3",\n  "server": "Apache Tomcat/10.1.20",\n  "startedAt": "2026-06-07T08:00:00Z",\n  "controllers": 6,\n  "routes": 24\n}'
  },
  metrics: {
    label: 'GET /jx/metrics',
    code: '{\n  "routes": [\n    {\n      "path": "GET /api/persona/{id}",\n      "requests": 14820,\n      "errors": 12,\n      "avgMs": 3.4,\n      "p95Ms": 8.1\n    },\n    {\n      "path": "POST /api/persona/save",\n      "requests": 3201,\n      "errors": 0,\n      "avgMs": 6.2,\n      "p95Ms": 14.5\n    }\n  ]\n}'
  },
  openapi: {
    label: 'GET /jx/openapi',
    code: '{\n  "openapi": "3.0.1",\n  "info": {\n    "title": "JxMVC API",\n    "version": "3.4.0"\n  },\n  "paths": {\n    "/api/persona/{id}": {\n      "get": {\n        "summary": "Obtener persona",\n        "parameters": [{\n          "name": "id",\n          "in": "path",\n          "required": true\n        }]\n      }\n    }\n  }\n}'
  }
};

function jxEpShow(key, btn) {
  var s = jxEpSamples[key];
  document.getElementById('jxEpLbl').textContent = s.label;
  var codeEl = document.getElementById('jxEpCode');
  codeEl.textContent = s.code;
  hljs.highlightElement(codeEl);
  document.querySelectorAll('.jx-ep-row').forEach(function(r){ r.classList.remove('bg-apple/5','dark:bg-apple/10'); });
  if (btn) btn.classList.add('bg-apple/5','dark:bg-apple/10');
}
</script>

<script>
/* ── Highlight.js ─────────────────────────────────────────────── */
document.querySelectorAll('pre code').forEach(function(el) {
  hljs.highlightElement(el);
});

/* ── Hero code tabs ───────────────────────────────────────────── */
var jxHeroPanes = {ctrl:'jxH-ctrl', model:'jxH-model', cfg:'jxH-cfg'};
function jxCodeTab(key, btn) {
  Object.keys(jxHeroPanes).forEach(function(k) {
    document.getElementById(jxHeroPanes[k]).classList.toggle('hidden', k !== key);
  });
  document.querySelectorAll('.jx-win-tab').forEach(function(b) { b.classList.remove('active'); });
  btn.classList.add('active');
}

/* ── Playground tabs ──────────────────────────────────────────── */
var jxPlayLabels = {rest:'ApiController.java', ws:'ChatEndpoint.java', sched:'TareasProgramadas.java', val:'RegistroForm.java', cache:'CatalogoController.java', auth:'AdminController.java'};
var jxPlayPanes  = {rest:'jxP-rest', ws:'jxP-ws', sched:'jxP-sched', val:'jxP-val', cache:'jxP-cache', auth:'jxP-auth'};
function jxPlay(key, btn) {
  Object.keys(jxPlayPanes).forEach(function(k) {
    document.getElementById(jxPlayPanes[k]).classList.toggle('hidden', k !== key);
  });
  document.querySelectorAll('.jx-play-tab').forEach(function(b) { b.classList.remove('active'); });
  btn.classList.add('active');
  document.getElementById('jxPlayLbl').textContent = jxPlayLabels[key] || '';
}

/* ── Copy helpers ─────────────────────────────────────────────── */
function jxCopyPane(paneId) {
  var pane = document.getElementById(paneId);
  if (!pane) return;
  var active = pane.querySelector('.jx-code-pane:not(.hidden), .jx-code-pane');
  if (!active) return;
  var text = (active.innerText || active.textContent).trim();
  navigator.clipboard.writeText(text);
}
function jxCopyCmd() {
  var el = document.getElementById('jxHeroCmd');
  if (!el) return;
  navigator.clipboard.writeText((el.innerText || el.textContent).trim()).then(function() {
    var ico = document.getElementById('jxCmdIco');
    if (!ico) return;
    ico.innerHTML = '<polyline points="20 6 9 17 4 12"></polyline>';
    ico.style.stroke = '#0071E3';
    setTimeout(function() {
      ico.innerHTML = '<rect x="9" y="9" width="13" height="13" rx="2"></rect><path d="M5 15H4a2 2 0 01-2-2V4a2 2 0 012-2h9a2 2 0 012 2v1"></path>';
      ico.style.stroke = '';
    }, 2000);
  });
}

/* ── Spotlight hover ──────────────────────────────────────────── */
function jxSpot(e, el) {
  var r = el.getBoundingClientRect();
  el.style.setProperty('--mx', ((e.clientX - r.left) / r.width * 100) + '%');
  el.style.setProperty('--my', ((e.clientY - r.top)  / r.height * 100) + '%');
}

/* ── IntersectionObserver: pipeline, barras, contadores ──────── */
var io = new IntersectionObserver(function(entries) {
  entries.forEach(function(entry) {
    if (!entry.isIntersecting) return;
    var el = entry.target;

    if (el.classList.contains('jx-pipe-node')) {
      el.classList.add('jx-in');
      io.unobserve(el);
    }
    if (el.classList.contains('jx-bar')) {
      el.style.width = el.dataset.w + '%';
      io.unobserve(el);
    }
    if (el.classList.contains('jx-counter')) {
      var target = parseInt(el.dataset.target, 10);
      if (isNaN(target) || target === 0) { io.unobserve(el); return; }
      var start = 0, dur = 900, step = 16;
      var timer = setInterval(function() {
        start += step;
        var progress = Math.min(start / dur, 1);
        var eased = 1 - Math.pow(1 - progress, 3);
        el.textContent = Math.round(eased * target);
        if (progress >= 1) { el.textContent = target; clearInterval(timer); }
      }, step);
      io.unobserve(el);
    }
  });
}, {threshold: 0.15});

document.querySelectorAll('.jx-pipe-node, .jx-bar, .jx-counter').forEach(function(el) {
  io.observe(el);
});
</script>

<%-- ── Banda Strands (efecto WebGL vanilla, colores de marca) ────────── --%>
<section class="jx-strands">
  <canvas id="jxStrands"></canvas>
  <div class="jx-strands-veil"></div>
  <div class="jx-strands-inner">
    <h2>Del cero al deploy en minutos</h2>
    <p>Genera tu proyecto Lux, compílalo con Maven y despliégalo en Tomcat. Un solo JAR, sin dependencias externas.</p>
    <div class="jx-strands-cta">
      <a href="${pageContext.request.contextPath}/downloads" class="jx-cta-primary">Descargar</a>
      <a href="${pageContext.request.contextPath}/docs" class="jx-cta-light">Ver documentación</a>
    </div>
  </div>
</section>
<style>
  .jx-strands { position:relative; overflow:hidden; background:#07080D; min-height:460px; }
  .jx-strands #jxStrands { position:absolute; inset:0; width:100%; height:100%; z-index:0; display:block; }
  .jx-strands-veil { position:absolute; inset:0; z-index:1; pointer-events:none;
    background:radial-gradient(ellipse at center, transparent 34%, rgba(7,8,13,.62) 100%); }
  .jx-strands-inner { position:relative; z-index:2; max-width:52rem; margin:0 auto; padding:120px 24px; text-align:center; }
  .jx-strands-inner h2 { font-size:clamp(2rem,5vw,3.2rem); font-weight:800; letter-spacing:-.03em; color:#fff; line-height:1.06; }
  .jx-strands-inner p { margin:16px auto 0; font-size:16px; line-height:1.6; color:rgba(255,255,255,.72); max-width:34rem; }
  .jx-strands-cta { margin-top:32px; display:flex; gap:12px; justify-content:center; flex-wrap:wrap; }
  .jx-cta-light { display:inline-block; padding:12px 24px; border-radius:999px; font-size:14px; font-weight:600;
    color:#fff; border:1px solid rgba(255,255,255,.25); transition:background .15s ease, border-color .15s ease; }
  .jx-cta-light:hover { background:rgba(255,255,255,.08); border-color:rgba(255,255,255,.42); }
</style>
<script src="${pageContext.request.contextPath}/assets/js/strands.js"></script>

<%@ include file="/WEB-INF/views/shared/footer.jspf" %>
