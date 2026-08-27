// LuxCore — tema y seguimiento de lectura. Sin dependencias, como el framework.
(function () {
  'use strict';

  // ── tema: recuerda la elección, y si no hay ninguna sigue al sistema ──
  var CLAVE = 'lux-tema';
  var raiz = document.documentElement;

  function aplicar(tema) {
    if (tema === 'claro' || tema === 'oscuro') {
      raiz.setAttribute('data-theme', tema === 'oscuro' ? 'dark' : 'light');
    } else {
      raiz.removeAttribute('data-theme');
    }
  }

  function oscuroAhora() {
    var declarado = raiz.getAttribute('data-theme');
    if (declarado) {
      return declarado === 'dark';
    }
    return window.matchMedia('(prefers-color-scheme: dark)').matches;
  }

  try {
    aplicar(localStorage.getItem(CLAVE));
  } catch (sinAlmacen) {
    // navegación privada: se queda con la preferencia del sistema
  }

  document.addEventListener('DOMContentLoaded', function () {
    var boton = document.querySelector('.tema');
    if (boton) {
      var sincronizar = function () {
        boton.dataset.oscuro = oscuroAhora() ? 'si' : 'no';
        boton.setAttribute('aria-label',
          oscuroAhora() ? 'Cambiar a tema claro' : 'Cambiar a tema oscuro');
      };
      sincronizar();
      boton.addEventListener('click', function () {
        var siguiente = oscuroAhora() ? 'claro' : 'oscuro';
        aplicar(siguiente);
        try {
          localStorage.setItem(CLAVE, siguiente);
        } catch (sinAlmacen) {
          // sin persistencia, pero el cambio se aplica igual
        }
        sincronizar();
      });
    }

    // ── índice lateral: marca la sección que se está leyendo ──
    var enlaces = Array.prototype.slice.call(document.querySelectorAll('nav.indice a'));
    if (!enlaces.length || !('IntersectionObserver' in window)) {
      return;
    }

    var porId = {};
    enlaces.forEach(function (a) {
      var destino = a.getAttribute('href');
      if (destino && destino.charAt(0) === '#') {
        porId[destino.slice(1)] = a;
      }
    });

    var secciones = Array.prototype.slice.call(document.querySelectorAll('main section[id]'));
    if (!secciones.length) {
      return;
    }

    var visibles = Object.create(null);

    function marcar() {
      var actual = null;
      for (var i = 0; i < secciones.length; i++) {
        if (visibles[secciones[i].id]) {
          actual = secciones[i].id;
          break;
        }
      }
      enlaces.forEach(function (a) { a.classList.remove('activo'); });
      if (actual && porId[actual]) {
        porId[actual].classList.add('activo');
      }
    }

    var observador = new IntersectionObserver(function (entradas) {
      entradas.forEach(function (e) {
        if (e.isIntersecting) {
          visibles[e.target.id] = true;
        } else {
          delete visibles[e.target.id];
        }
      });
      marcar();
    }, { rootMargin: '-12% 0px -70% 0px' });

    secciones.forEach(function (s) { observador.observe(s); });
  });
})();

// ── pestañas de código y copiar al portapapeles ──
document.addEventListener('DOMContentLoaded', function () {
  document.querySelectorAll('.pestanas').forEach(function (grupo) {
    var botones = Array.prototype.slice.call(grupo.querySelectorAll('button[data-panel]'));
    botones.forEach(function (boton) {
      boton.addEventListener('click', function () {
        botones.forEach(function (otro) {
          var suyo = otro === boton;
          otro.setAttribute('aria-selected', String(suyo));
          var panel = document.getElementById(otro.dataset.panel);
          if (panel) { panel.hidden = !suyo; }
        });
      });
    });
  });

  document.querySelectorAll('[data-copiar]').forEach(function (caja) {
    var boton = caja.querySelector('.copiar');
    if (!boton) { return; }
    boton.addEventListener('click', function () {
      var texto = caja.dataset.copiar;
      var listo = function () {
        boton.dataset.copiado = 'si';
        setTimeout(function () { delete boton.dataset.copiado; }, 1400);
      };
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(texto).then(listo, function () {});
      } else {
        var campo = document.createElement('textarea');
        campo.value = texto;
        document.body.appendChild(campo);
        campo.select();
        try { document.execCommand('copy'); listo(); } catch (sinSoporte) {}
        document.body.removeChild(campo);
      }
    });
  });
});

// ── revelado al entrar en pantalla ──
// La clase .revelar la pone el JS, nunca el HTML: si el guion no corre, o si el visitante
// pidió menos movimiento, el contenido se queda visible en vez de desaparecer.
document.addEventListener('DOMContentLoaded', function () {
  var quieto = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  if (quieto || !('IntersectionObserver' in window)) { return; }

  var objetivos = Array.prototype.slice.call(
    document.querySelectorAll('main > section, .tarjetas, .envoltura, .codigo')
  ).filter(function (el) {
    // lo que ya se ve al cargar no se anima: aparecería y desaparecería bajo el pulgar
    return el.getBoundingClientRect().top > window.innerHeight * 0.9;
  });
  if (!objetivos.length) { return; }

  objetivos.forEach(function (el) { el.classList.add('revelar'); });

  var observador = new IntersectionObserver(function (entradas) {
    entradas.forEach(function (entrada) {
      if (!entrada.isIntersecting) { return; }
      entrada.target.classList.add('visible');
      observador.unobserve(entrada.target);
    });
  }, { rootMargin: '0px 0px -8% 0px', threshold: 0.06 });

  objetivos.forEach(function (el) { observador.observe(el); });
});
