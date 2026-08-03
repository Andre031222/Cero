// LuxCore — guía de instalación animada. Se activa solo si la página tiene terminal.
(function () {
  'use strict';
  if (!document.getElementById('pantalla')) { return; }

  var GIRO = ['⠋','⠙','⠹','⠸','⠼','⠴','⠦','⠧','⠇','⠏'];

  var ASCII = [
    '        ·  |  ·',
    '   \\    ·     ·    /',
    ' —    ·   ███   ·    —',
    '   /    ·     ·    \\',
    '        ·  |  ·'
  ];

  var textos = {
    es: {
      titulo: 'Instalación',
      subtitulo: 'De cero a un servidor respondiendo, en cuatro órdenes.',
      repetir: 'Repetir',
      nota: 'Requisitos: <code>git</code>, <code>Maven</code> y un <code>JDK 21</code> o superior. Nada más — LuxCore no tiene dependencias externas.',
      pasos: [
        { n: '01', que: 'Clonar', cuanto: '~2 s' },
        { n: '02', que: 'Compilar y probar', cuanto: '~90 s' },
        { n: '03', que: 'Arrancar', cuanto: '10 ms' },
        { n: '04', que: 'Comprobar', cuanto: 'al instante' }
      ]
    },
    en: {
      titulo: 'Installation',
      subtitulo: 'From nothing to a running server, in four commands.',
      repetir: 'Replay',
      nota: 'You need <code>git</code>, <code>Maven</code> and a <code>JDK 21</code> or newer. That is all — LuxCore has no external dependencies.',
      pasos: [
        { n: '01', que: 'Clone', cuanto: '~2 s' },
        { n: '02', que: 'Build and test', cuanto: '~90 s' },
        { n: '03', que: 'Start', cuanto: '10 ms' },
        { n: '04', que: 'Check', cuanto: 'instant' }
      ]
    }
  };

  // Las órdenes y la salida del programa van tal cual salen en una terminal real.
  // Solo se traducen los comentarios, que son los que guían.
  function guion(idioma) {
    var es = idioma === 'es';
    return [
      { t: 'cm', v: es ? '# 1 — traer el código' : '# 1 — get the source', paso: 0 },
      { t: 'cmd', v: 'git clone https://github.com/Andre031222/LuxCore.git' },
      { t: 'out', v: "Cloning into 'LuxCore'...", cls: 'tenue' },
      { t: 'giro', v: es ? 'Recibiendo objetos' : 'Receiving objects', dur: 900,
        fin: 'remote: Enumerating objects: 486, done.', cls: 'tenue' },
      { t: 'barra', etiqueta: 'Receiving objects', dur: 1100, cola: '486/486, 1.31 MiB | 4.2 MiB/s' },
      { t: 'blank' },

      { t: 'cmd', v: 'cd LuxCore/java', ruta: '~/LuxCore/java' },
      { t: 'blank' },

      { t: 'cm', v: es ? '# 2 — compilar los ocho módulos y correr las pruebas'
                       : '# 2 — build the eight modules and run the tests', paso: 1 },
      { t: 'cmd', v: 'mvn install' },
      { t: 'out', v: '[INFO] Reactor Summary for LuxCore 0.2.0:', cls: 'tenue' },
      { t: 'giro', v: 'lux-http', dur: 1000, fin: '[INFO] lux-http .............. SUCCESS [ 01:24 min]   238 ✓' },
      { t: 'giro', v: 'lux-core', dur: 800,  fin: '[INFO] lux-core .............. SUCCESS [   12.1 s]   459 ✓' },
      { t: 'giro', v: 'lux-view', dur: 550,  fin: '[INFO] lux-view .............. SUCCESS [    4.1 s]    88 ✓' },
      { t: 'giro', v: 'lux-data', dur: 500,  fin: '[INFO] lux-data .............. SUCCESS [    9.7 s]   294 ✓' },
      { t: 'giro', v: 'lux-adapter-servlet', dur: 400, fin: '[INFO] lux-adapter-servlet ... SUCCESS [    1.9 s]    35 ✓' },
      { t: 'giro', v: 'ejemplo',  dur: 400,  fin: '[INFO] ejemplo ............... SUCCESS [    1.4 s]    43 ✓' },
      { t: 'giro', v: 'lux-launcher', dur: 400, fin: '[INFO] lux-launcher .......... SUCCESS [    3.2 s]    10 ✓' },
      { t: 'giro', v: 'lux-web',  dur: 500,  fin: '[INFO] lux-web ............... SUCCESS [    2.6 s]    74 ✓' },
      { t: 'blank' },
      { t: 'out', v: '[INFO] BUILD SUCCESS', cls: 'ok' },
      { t: 'contar', hasta: 1262, dur: 900,
        plantilla: es ? '        {n} pruebas · 0 fallos · 0 dependencias · 308 KB'
                      : '        {n} tests · 0 failures · 0 dependencies · 308 KB', cls: 'ok' },
      { t: 'blank' },

      { t: 'cm', v: es ? '# 3 — levantar la aplicación de ejemplo' : '# 3 — start the example app', paso: 2 },
      { t: 'cmd', v: './lux fatjar ejemplo && java -jar ejemplo.jar' },
      { t: 'ascii' },
      { t: 'out', v: 'lux · http://0.0.0.0:8080 · 9 rutas · 10 ms', cls: 'destacado' },
      { t: 'blank' },

      { t: 'cm', v: es ? '# 4 — comprobar que responde' : '# 4 — check that it answers', paso: 3 },
      { t: 'cmd', v: 'curl localhost:8080/api/tareas' },
      { t: 'out', v: '{"data":[],"page":1,"size":20,"total":0}', cls: 'dato' },
      { t: 'blank' },
      { t: 'cmd', v: "curl -X POST -H 'Content-Type: application/json' \\" },
      { t: 'cmd2', v: '     -d \'{"titulo":"Probar LuxCore","prioridad":"alta"}\' \\' },
      { t: 'cmd2', v: '     localhost:8080/api/tareas' },
      { t: 'out', v: '{"id":1,"titulo":"Probar LuxCore","prioridad":"alta","completada":false}', cls: 'dato' },
      { t: 'blank' },
      { t: 'out', v: es ? '# listo. sin contenedor, sin web.xml, sin dependencias.'
                        : '# done. no container, no web.xml, no dependencies.', cls: 'ok' }
    ];
  }

  var pantalla = document.getElementById('pantalla');
  var rutaBarra = document.getElementById('rutaBarra');
  var contenedorPasos = document.getElementById('pasos');
  var btnEs = document.getElementById('btnEs');
  var btnEn = document.getElementById('btnEn');
  var btnRepetir = document.getElementById('btnRepetir');

  var idioma = 'es';
  var tiempos = [];
  var intervalos = [];
  var reducido = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  function limpiar() {
    tiempos.forEach(clearTimeout); tiempos = [];
    intervalos.forEach(clearInterval); intervalos = [];
    pantalla.textContent = '';
  }
  function esperar(ms, fn) { tiempos.push(setTimeout(fn, ms)); }
  function abajo() { pantalla.scrollTop = pantalla.scrollHeight; }

  function nuevaLinea(clase) {
    var l = document.createElement('span');
    l.className = 'linea' + (clase ? ' ' + clase : '');
    pantalla.appendChild(l);
    return l;
  }

  function pintarPasos() {
    contenedorPasos.innerHTML = '';
    textos[idioma].pasos.forEach(function (p) {
      var caja = document.createElement('div');
      caja.className = 'paso';
      caja.innerHTML = '<div class="n">' + p.n + '</div><div class="que">' + p.que +
                       '</div><div class="cuanto">' + p.cuanto + '</div>';
      contenedorPasos.appendChild(caja);
    });
  }

  function marcarPaso(indice) {
    Array.prototype.forEach.call(contenedorPasos.children, function (caja, i) {
      caja.dataset.activo = i === indice ? 'si' : (i < indice ? 'hecho' : 'no');
    });
  }

  function aplicarIdioma() {
    var t = textos[idioma];
    document.getElementById('nota').innerHTML = t.nota;
    btnRepetir.textContent = t.repetir;
    btnEs.setAttribute('aria-pressed', String(idioma === 'es'));
    btnEn.setAttribute('aria-pressed', String(idioma === 'en'));
    pintarPasos();
  }

  function escribir(destino, texto, alTerminar) {
    var i = 0;
    (function paso() {
      if (i >= texto.length) { abajo(); if (alTerminar) alTerminar(); return; }
      destino.textContent += texto.charAt(i++);
      abajo();
      tiempos.push(setTimeout(paso, 19 + (i % 3) * 4));
    })();
  }

  function girar(linea, etiqueta, dur, fin, cls) {
    var f = 0;
    var id = setInterval(function () {
      linea.textContent = ' ' + GIRO[f++ % GIRO.length] + '  ' + etiqueta;
      abajo();
    }, 70);
    intervalos.push(id);
    esperar(dur, function () {
      clearInterval(id);
      linea.className = 'linea' + (cls ? ' ' + cls : '');
      linea.textContent = fin;
      abajo();
    });
  }

  function barrear(linea, etiqueta, dur, cola) {
    var ANCHO = 22, t0 = Date.now();
    var id = setInterval(function () {
      var p = Math.min(1, (Date.now() - t0) / dur);
      var lleno = Math.round(p * ANCHO);
      linea.textContent = ' ' + etiqueta + ': ' +
        '█'.repeat(lleno) + '░'.repeat(ANCHO - lleno) +
        ' ' + String(Math.round(p * 100)).padStart(3) + '%';
      abajo();
      if (p >= 1) {
        clearInterval(id);
        linea.textContent = ' ' + etiqueta + ': 100% (' + cola + '), done.';
      }
    }, 45);
    intervalos.push(id);
  }

  function contar(linea, hasta, dur, plantilla) {
    var t0 = Date.now();
    var id = setInterval(function () {
      var p = Math.min(1, (Date.now() - t0) / dur);
      var n = Math.round(p * p * (3 - 2 * p) * hasta);
      linea.textContent = plantilla.replace('{n}', n);
      abajo();
      if (p >= 1) { clearInterval(id); linea.textContent = plantilla.replace('{n}', hasta); }
    }, 45);
    intervalos.push(id);
  }

  function pintarAscii(stagger) {
    ASCII.forEach(function (fila, i) {
      esperar(stagger ? i * 65 : 0, function () {
        nuevaLinea('marca-ascii').textContent = fila;
        abajo();
      });
    });
  }

  function reproducir() {
    limpiar();
    rutaBarra.textContent = '~/proyectos';
    marcarPaso(-1);

    var pasos = guion(idioma);

    if (reducido) {
      pasos.forEach(function (p) {
        if (p.t === 'blank') { nuevaLinea().textContent = ' '; return; }
        if (p.t === 'ascii') { ASCII.forEach(function (f) { nuevaLinea('marca-ascii').textContent = f; }); return; }
        if (p.t === 'giro') { nuevaLinea(p.cls).textContent = p.fin; return; }
        if (p.t === 'barra') { nuevaLinea('tenue').textContent = ' ' + p.etiqueta + ': 100% (' + p.cola + '), done.'; return; }
        if (p.t === 'contar') { nuevaLinea(p.cls).textContent = p.plantilla.replace('{n}', p.hasta); return; }
        if (p.t === 'cm') { nuevaLinea('tenue').textContent = p.v; return; }
        if (p.t === 'cmd' || p.t === 'cmd2') {
          var l = nuevaLinea();
          if (p.t === 'cmd') { var pr = document.createElement('span'); pr.className = 'prompt'; pr.textContent = '$ '; l.appendChild(pr); }
          var c = document.createElement('span'); c.textContent = p.v; l.appendChild(c);
          if (p.ruta) rutaBarra.textContent = p.ruta;
          return;
        }
        nuevaLinea(p.cls).textContent = p.v;
      });
      marcarPaso(3);
      abajo();
      return;
    }

    var retraso = 300;

    pasos.forEach(function (p) {
      if (p.paso !== undefined) {
        (function (i) { esperar(retraso, function () { marcarPaso(i); }); })(p.paso);
      }

      switch (p.t) {
        case 'blank':
          esperar(retraso, function () { nuevaLinea().textContent = ' '; abajo(); });
          retraso += 85;
          break;

        case 'cm':
          (function (v) { esperar(retraso, function () { nuevaLinea('tenue').textContent = v; abajo(); }); })(p.v);
          retraso += 400;
          break;

        case 'cmd':
        case 'cmd2':
          (function (v, conPrompt, ruta) {
            esperar(retraso, function () {
              var l = nuevaLinea();
              if (conPrompt) {
                var pr = document.createElement('span');
                pr.className = 'prompt'; pr.textContent = '$ ';
                l.appendChild(pr);
              }
              var cuerpo = document.createElement('span');
              l.appendChild(cuerpo);
              var cur = document.createElement('i');
              cur.className = 'cursor';
              l.appendChild(cur);
              escribir(cuerpo, v, function () {
                cur.remove();
                if (ruta) rutaBarra.textContent = ruta;
              });
            });
          })(p.v, p.t === 'cmd', p.ruta);
          retraso += 240 + p.v.length * 21;
          break;

        case 'giro':
          (function (etiqueta, dur, fin, cls) {
            esperar(retraso, function () { girar(nuevaLinea('tenue'), etiqueta, dur, fin, cls); });
          })(p.v, p.dur, p.fin, p.cls);
          retraso += p.dur + 110;
          break;

        case 'barra':
          (function (etiqueta, dur, cola) {
            esperar(retraso, function () { barrear(nuevaLinea('tenue'), etiqueta, dur, cola); });
          })(p.etiqueta, p.dur, p.cola);
          retraso += p.dur + 140;
          break;

        case 'contar':
          (function (hasta, dur, plantilla, cls) {
            esperar(retraso, function () { contar(nuevaLinea(cls), hasta, dur, plantilla); });
          })(p.hasta, p.dur, p.plantilla, p.cls);
          retraso += p.dur + 160;
          break;

        case 'ascii':
          (function () { esperar(retraso, function () { pintarAscii(true); }); })();
          retraso += ASCII.length * 65 + 180;
          break;

        default:
          (function (v, cls) {
            esperar(retraso, function () { nuevaLinea(cls).textContent = v; abajo(); });
          })(p.v, p.cls);
          retraso += 125;
      }
    });
  }

  btnEs.addEventListener('click', function () { if (idioma !== 'es') { idioma = 'es'; aplicarIdioma(); reproducir(); } });
  btnEn.addEventListener('click', function () { if (idioma !== 'en') { idioma = 'en'; aplicarIdioma(); reproducir(); } });
  btnRepetir.addEventListener('click', reproducir);

  aplicarIdioma();
  reproducir();
})();
