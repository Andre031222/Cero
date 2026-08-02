/**
 * Config de build de Tailwind para JxMVC.
 * Genera un CSS estático (solo las clases usadas) que se empaqueta dentro del WAR,
 * reemplazando el Play CDN (compilaba en el navegador en cada carga).
 *
 * Compilar:
 *   npx tailwindcss@3 -c JxMVC2x/tailwind.config.js \
 *       -i JxMVC2x/tailwind.input.css \
 *       -o JxMVC2x/src/main/webapp/assets/css/tailwind.css --minify
 */
module.exports = {
  darkMode: 'class',
  content: [
    './JxMVC2x/src/main/webapp/**/*.jsp',
    './JxMVC2x/src/main/webapp/**/*.jspf',
  ],
  theme: {
    extend: {
      fontFamily: { sans: ['Space Grotesk', 'ui-sans-serif', 'system-ui', 'sans-serif'] },
      colors: {
        apple:   '#0071E3',
        ink:     '#1D1D1F',
        muted:   '#6E6E73',
        surface: '#F5F5F7',
        jxo:     '#FF6B00',
        jxr:     '#FF2D55',
        jxv:     '#5E5CE6',
      },
      boxShadow: {
        card:  '0 1px 3px rgba(0,0,0,0.06), 0 6px 20px rgba(0,0,0,0.06)',
        cardh: '0 2px 8px rgba(0,0,0,0.08), 0 16px 40px rgba(0,0,0,0.10)',
        nav:   '0 1px 0 rgba(0,0,0,0.06)',
      },
    },
  },
};
