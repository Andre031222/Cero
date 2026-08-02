/* Strands — port a WebGL2 vanilla del componente React Bits (ogl) — cero dependencias.
   Efecto de hebras luminosas fluyendo. Colores de marca JxMVC.
   Degrada a fondo sólido si no hay WebGL2 o si prefers-reduced-motion. */
(function () {
  var canvas = document.getElementById('jxStrands');
  if (!canvas) return;

  var gl = canvas.getContext('webgl2', { alpha: true, premultipliedAlpha: true, antialias: true });
  if (!gl) { canvas.style.display = 'none'; return; }

  var VERT = '#version 300 es\n' +
    'in vec2 position;\n' +
    'void main(){ gl_Position = vec4(position, 0.0, 1.0); }';

  var FRAG = '#version 300 es\n' +
    'precision highp float;\n' +
    'uniform float uTime; uniform vec2 uResolution;\n' +
    'uniform vec3 uColors[8]; uniform int uColorCount; uniform int uStrandCount;\n' +
    'uniform float uSpeed,uAmplitude,uWaviness,uThickness,uGlow,uTaper,uSpread,uHueShift,uIntensity,uOpacity,uScale,uSaturation;\n' +
    'out vec4 fragColor;\n' +
    'const float PI = 3.14159265;\n' +
    'vec3 spectrum(float t){ return 0.5 + 0.5*cos(2.0*PI*(t+vec3(0.0,0.33,0.67))); }\n' +
    'vec3 samplePalette(float t){ t=fract(t); float s=t*float(uColorCount); int i=int(floor(s)); float b=fract(s); int n=i+1; if(n>=uColorCount)n=0; return mix(uColors[i],uColors[n],b); }\n' +
    'vec3 strandColor(float t){ if(uColorCount>0) return samplePalette(t); return spectrum(t); }\n' +
    'void main(){\n' +
    '  vec2 uv = (gl_FragCoord.xy - 0.5*uResolution)/uResolution.y;\n' +
    '  uv /= max(uScale, 0.0001);\n' +
    '  float e = 0.06 + uIntensity*0.94;\n' +
    '  float env = pow(max(cos(uv.x*PI*1.3),0.0), uTaper);\n' +
    '  vec3 col = vec3(0.0);\n' +
    '  for (int i=0;i<12;i++){\n' +
    '    if (i>=uStrandCount) break;\n' +
    '    float fi=float(i); float ph=fi*1.7*uSpread; float freq=(2.0+fi*0.35)*uWaviness; float spd=1.4+fi*1.2;\n' +
    '    float tt=uTime*uSpeed;\n' +
    '    float w = sin(uv.x*freq + tt*spd + ph)*0.60 + sin(uv.x*freq*1.1 - tt*spd*0.7 + ph*1.7)*0.40;\n' +
    '    float amp = (0.1+0.02*e)*env*uAmplitude; float y = w*amp;\n' +
    '    float d = abs(uv.y - y);\n' +
    '    float thick = (0.001+0.05*e)*(0.35+env)*uThickness;\n' +
    '    float g = thick/(d+thick*0.45); g=g*g;\n' +
    '    float h = fi/float(uStrandCount) + uv.x*0.30 + uTime*0.04 + uHueShift;\n' +
    '    col += strandColor(h)*g*env;\n' +
    '  }\n' +
    '  col *= 0.45 + 0.7*e;\n' +
    '  col = 1.0 - exp(-col*uGlow);\n' +
    '  float gray = dot(col, vec3(0.2126,0.7152,0.0722));\n' +
    '  col = max(mix(vec3(gray), col, uSaturation), 0.0);\n' +
    '  float lum = max(max(col.r,col.g),col.b);\n' +
    '  float alpha = clamp(lum,0.0,1.0)*uOpacity;\n' +
    '  fragColor = vec4(col*uOpacity, alpha);\n' +
    '}';

  function compile(type, src) {
    var s = gl.createShader(type);
    gl.shaderSource(s, src);
    gl.compileShader(s);
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) {
      console.warn('Strands shader:', gl.getShaderInfoLog(s));
    }
    return s;
  }

  var prog = gl.createProgram();
  gl.attachShader(prog, compile(gl.VERTEX_SHADER, VERT));
  gl.attachShader(prog, compile(gl.FRAGMENT_SHADER, FRAG));
  gl.bindAttribLocation(prog, 0, 'position');
  gl.linkProgram(prog);
  if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) { canvas.style.display = 'none'; return; }
  gl.useProgram(prog);

  var buf = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, buf);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
  gl.enableVertexAttribArray(0);
  gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);

  gl.enable(gl.BLEND);
  gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);

  function U(n) { return gl.getUniformLocation(prog, n); }
  var u = {
    time: U('uTime'), res: U('uResolution'), colors: U('uColors[0]'), colorCount: U('uColorCount'),
    strandCount: U('uStrandCount'), speed: U('uSpeed'), amp: U('uAmplitude'), wav: U('uWaviness'),
    thick: U('uThickness'), glow: U('uGlow'), taper: U('uTaper'), spread: U('uSpread'),
    hue: U('uHueShift'), intensity: U('uIntensity'), opacity: U('uOpacity'), scale: U('uScale'), sat: U('uSaturation')
  };

  function hexToRgb(h) {
    h = h.replace('#', '');
    return [parseInt(h.substr(0, 2), 16) / 255, parseInt(h.substr(2, 2), 16) / 255, parseInt(h.substr(4, 2), 16) / 255];
  }
  // Colores de marca JxMVC: azul, índigo, naranja
  var COLORS = ['#0071E3', '#5E5CE6', '#FF7A1A'];
  var pal = new Float32Array(8 * 3);
  for (var i = 0; i < 8; i++) {
    var c = hexToRgb(COLORS[Math.min(i, COLORS.length - 1)]);
    pal[i * 3] = c[0]; pal[i * 3 + 1] = c[1]; pal[i * 3 + 2] = c[2];
  }
  gl.uniform3fv(u.colors, pal);
  gl.uniform1i(u.colorCount, COLORS.length);
  gl.uniform1i(u.strandCount, 3);
  gl.uniform1f(u.speed, 0.5);
  gl.uniform1f(u.amp, 1.0);
  gl.uniform1f(u.wav, 1.0);
  gl.uniform1f(u.thick, 0.7);
  gl.uniform1f(u.glow, 2.6);
  gl.uniform1f(u.taper, 3.0);
  gl.uniform1f(u.spread, 1.0);
  gl.uniform1f(u.hue, 0.0);
  gl.uniform1f(u.intensity, 0.6);
  gl.uniform1f(u.opacity, 1.0);
  gl.uniform1f(u.scale, 1.5);
  gl.uniform1f(u.sat, 1.5);

  var dpr = Math.min(window.devicePixelRatio || 1, 2);
  function resize() {
    var w = canvas.clientWidth, h = canvas.clientHeight;
    if (!w || !h) return;
    canvas.width = Math.round(w * dpr);
    canvas.height = Math.round(h * dpr);
    gl.viewport(0, 0, canvas.width, canvas.height);
    gl.uniform2f(u.res, canvas.width, canvas.height);
  }
  window.addEventListener('resize', resize);
  resize();

  var reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  function draw(t) {
    gl.uniform1f(u.time, t * 0.001);
    gl.clearColor(0, 0, 0, 0);
    gl.clear(gl.COLOR_BUFFER_BIT);
    gl.drawArrays(gl.TRIANGLES, 0, 3);
    if (!reduce) requestAnimationFrame(draw);
  }
  requestAnimationFrame(draw);
})();
