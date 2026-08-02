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
