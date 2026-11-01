param(
    [Parameter(Position = 0)] [string] $Orden = 'ayuda',
    [Parameter(Position = 1, ValueFromRemainingArguments = $true)] [string[]] $Resto = @()
)

$ErrorActionPreference = 'Stop'
$Aqui    = Split-Path -Parent $MyInvocation.MyCommand.Path
$JavaDir = Join-Path $Aqui 'java'

$e = [char]27
$Vivo = $Host.UI.RawUI -and -not [Console]::IsOutputRedirected
if ($Vivo) {
    $Laton = if ($env:WT_SESSION -or $env:COLORTERM -in 'truecolor','24bit') { "$e[38;2;56;189;248m" } else { "$e[38;5;75m" }
    $Tenue = "$e[38;5;245m"; $Rojo = "$e[38;5;167m"; $Fin = "$e[0m"
}
else       { $Laton = ''; $Tenue = ''; $Rojo = ''; $Fin = '' }

function Azul([string] $t) { Write-Host "$Laton$t$Fin" }
function Gris([string] $t) { Write-Host "$Tenue$t$Fin" }
function Malo([string] $t) { Write-Host "$Rojo$t$Fin"; exit 1 }

function Mvn([string[]] $argumentos) {
    & cmd.exe /c 'mvn' @argumentos
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

function Requisitos {
    if (-not (Get-Command mvn  -ErrorAction SilentlyContinue)) { Malo 'falta Maven' }
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) { Malo 'falta un JDK' }
    $v = 0
    $linea = (& java -version 2>&1 | Select-Object -First 1)
    if ("$linea" -match '"(\d+)') { $v = [int]$Matches[1] }
    if ($v -lt 25) { Malo "Cero necesita Java 25 o superior (hilos virtuales); tienes $v" }
    if (JdkAfectado "$linea") {
        Write-Host "${Rojo}aviso: no se puede comprobar en una fuente primaria que el JDK $(("$linea" -split '"')[1]) traiga la corrección de CVE-2026-91403; actualiza a la última 25.0.x y mira https://openjdk.org/groups/vulnerability/advisories/2026-10-21$Fin"
    }
}

function JdkAfectado([string] $linea) {
    if ($linea -notmatch '"(\d+)(?:\.\d+)?(?:\.(\d+))?') { return $false }
    $parche = if ($Matches[2]) { [int]$Matches[2] } else { 0 }
    switch ([int]$Matches[1]) {
        25 { return $parche -le 5 }
        26 { return $true }
        27 { return $parche -le 1 }
        default { return $false }
    }
}

function Python {
    foreach ($nombre in 'python3', 'python', 'py') {
        if (Get-Command $nombre -ErrorAction SilentlyContinue) { return $nombre }
    }
    Malo 'falta Python 3: lo necesita `cero migrar`'
}

function Classpath([string] $modulo) {
    $jars = Get-ChildItem (Join-Path $JavaDir 'cero-*\target\cero-*.jar') -ErrorAction SilentlyContinue
    $partes = @((Join-Path $JavaDir "$modulo\target\classes")) + ($jars | ForEach-Object { $_.FullName })
    return ($partes -join ';')
}

$Implementaciones = @(
    'java|java|mvn java|mvn -B install||java -cp cero-http/target/classes;cero-http/target/test-classes cero.http.Conforme||'
    'rust|rust|cargo|cargo test --all && cargo clippy --all-targets -- -D warnings|cargo build --release --bin conforme|target\release\conforme.exe|cero-http\tests cero-data\tests|159'
    'c++|c++|cmake ctest|cmake -S . -B "%OBJ%" -DCMAKE_BUILD_TYPE=Release && cmake --build "%OBJ%" -j && ctest --test-dir "%OBJ%" --output-on-failure||"%OBJ%\conforme.exe"|pruebas|157'
)

$Codigo = if ($env:CERO_LANG) { $env:CERO_LANG } elseif ($env:LC_ALL) { $env:LC_ALL } elseif ($env:LANG) { $env:LANG } else { (Get-Culture).Name }
$Idioma = if ("$Codigo" -like 'es*') { 'es' } else { 'en' }

$TextoEs = @{
    'ver-comprobando'   = 'comprobando {0}…'
    'ver-falta'         = 'falta {0}'
    'ver-sin-python'    = 'falta Python 3.12 o superior (python3.14, python3.12, python3 o py): lo que lo necesita sale ✗'
    'ver-python'        = 'python: {0} ({1})'
    'ver-ocupado'       = 'el puerto {0} ya está ocupado'
    'ver-no-arranca'    = 'el servidor no llegó a escuchar'
    'ver-sin-hallazgos' = 'sin hallazgos'
    'ver-hallazgos'     = '{0} líneas de hallazgos'
    'ver-ninguno'       = 'ningún comentario'
    'ver-comentarios'   = '{0} comentarios'
    'ver-pruebas'       = 'pruebas'
    'ver-n-pruebas'     = '{0} pruebas'
    'ver-sin-motores'   = 'motores reales sin probar'
    'ver-n-vectores'    = '{0} vectores'
    'ver-banco'         = 'banco'
    'ver-ataques'       = 'ataques'
    'ver-requisitos'    = 'requisitos'
    'ver-de-minimo'     = '{0} (mínimo {1})'
    'ver-sin-resumen'   = 'h2spec no dio resumen'
    'ver-detalle'       = 'lo último que dijo «{0}»:'
    'ver-todo-bien'     = 'todo está bien'
    'ver-fallan'        = '{0} comprobaciones fallan'
}

$TextoEn = @{
    'ver-comprobando'   = 'checking {0}…'
    'ver-falta'         = 'missing: {0}'
    'ver-sin-python'    = 'Python 3.12 or newer is missing (python3.14, python3.12, python3 or py): whatever needs it shows ✗'
    'ver-python'        = 'python: {0} ({1})'
    'ver-ocupado'       = 'port {0} is already in use'
    'ver-no-arranca'    = 'the server never started listening'
    'ver-sin-hallazgos' = 'no findings'
    'ver-hallazgos'     = '{0} lines of findings'
    'ver-ninguno'       = 'no comments'
    'ver-comentarios'   = '{0} comments'
    'ver-pruebas'       = 'tests'
    'ver-n-pruebas'     = '{0} tests'
    'ver-sin-motores'   = 'real engines untested'
    'ver-n-vectores'    = '{0} vectors'
    'ver-banco'         = 'bench'
    'ver-ataques'       = 'attacks'
    'ver-requisitos'    = 'requirements'
    'ver-de-minimo'     = '{0} (minimum {1})'
    'ver-sin-resumen'   = 'h2spec gave no summary'
    'ver-detalle'       = 'the last thing «{0}» said:'
    'ver-todo-bien'     = 'everything is fine'
    'ver-fallan'        = '{0} checks fail'
}

function T([string] $clave, [object[]] $valores = @()) {
    $texto = if ($Idioma -eq 'es') { $TextoEs[$clave] } else { $TextoEn[$clave] }
    if (-not $texto) { $texto = $TextoEs[$clave] }
    if (-not $texto) { $texto = $clave }
    return ($texto -f $valores)
}

$V = @{ Puerto = $(if ($env:CERO_PUERTO) { $env:CERO_PUERTO } else { '8777' }); Py = ''; Tmp = ''; Proceso = $null }
$Filas = New-Object System.Collections.ArrayList

function Anotar([string] $nombre, [string] $cifra, [bool] $ok, [string] $registro = '') {
    [void]$Filas.Add([pscustomobject]@{ Nombre = $nombre; Cifra = $cifra; Ok = $ok; Registro = $registro })
}

function BuscarPython {
    foreach ($nombre in 'python3.14', 'python3.13', 'python3.12', 'python3', 'python', 'py') {
        $orden = Get-Command $nombre -ErrorAction SilentlyContinue | Select-Object -First 1
        if (-not $orden) { continue }
        & $orden.Source -c 'import sys; sys.exit(sys.version_info < (3, 12))' 2>$null
        if ($LASTEXITCODE -eq 0) { return $orden.Source }
    }
    return ''
}

function Faltan([string[]] $herramientas) {
    $faltan = @()
    foreach ($h in $herramientas) {
        if ($h -eq 'python3') { if (-not $V.Py) { $faltan += 'python3.12' } }
        elseif (-not (Get-Command $h -ErrorAction SilentlyContinue)) { $faltan += $h }
    }
    return ($faltan -join ' ')
}

function Ejecutar([string] $dir, [string] $orden, [string] $registro) {
    Push-Location $dir
    try {
        & cmd.exe /c "$orden >> `"$registro`" 2>&1"
        return $LASTEXITCODE
    }
    finally { Pop-Location }
}

function Escucha {
    $c = New-Object Net.Sockets.TcpClient
    try { $c.Connect('127.0.0.1', [int]$V.Puerto); return $true }
    catch { return $false }
    finally { $c.Close() }
}

function ArrancarServidor([string] $dir, [string] $servidor, [string] $registro) {
    $V.Proceso = Start-Process cmd.exe -ArgumentList '/c', "$servidor $($V.Puerto) > `"$registro`" 2>&1" `
        -WorkingDirectory $dir -PassThru -WindowStyle Hidden
    for ($i = 0; $i -lt 30; $i++) {
        if (Escucha) { return $true }
        if ($V.Proceso.HasExited) { return $false }
        Start-Sleep -Seconds 1
    }
    return $false
}

function PararServidor {
    if ($V.Proceso -and -not $V.Proceso.HasExited) { & taskkill /PID $V.Proceso.Id /T /F *> $null }
    $V.Proceso = $null
}

function ConServidor([string] $nombre, [string] $dir, [string] $servidor, [string] $registro) {
    if (Escucha) { Anotar $nombre (T 'ver-ocupado' @($V.Puerto)) $false; return $false }
    if (-not (ArrancarServidor $dir $servidor $registro)) {
        PararServidor
        Anotar $nombre (T 'ver-no-arranca') $false $registro
        return $false
    }
    return $true
}

function ContarPruebas([string] $registro) {
    $n = 0
    foreach ($l in Get-Content $registro -ErrorAction SilentlyContinue) {
        if ($l -match '^  OK ') { $n++ }
        elseif ($l -match '^test result: .* (\d+) passed;') { $n += [int]$Matches[1] }
        elseif ($l -match 'tests passed, .* out of (\d+)') { $n += [int]$Matches[1] }
    }
    return $n
}

function CifraVectores([string] $registro) {
    $l = Get-Content $registro -ErrorAction SilentlyContinue | Where-Object { $_ -match ' vectores ·' } | Select-Object -Last 1
    if ($l -match '(\d+) vectores · (\d+) pasan') { return (T 'ver-n-vectores' @("$($Matches[2])/$($Matches[1])")) }
    return ''
}

function PasoOrden {
    Azul (T 'ver-comprobando' @('orden'))
    $f = Faltan @('git', 'sh', 'python3')
    if ($f) { Anotar 'orden' (T 'ver-falta' @($f)) $false }
    else {
        $registro = Join-Path $V.Tmp 'orden.log'
        New-Item -ItemType File -Path $registro -Force | Out-Null
        if ((Ejecutar $Aqui 'sh herramientas/verificar-orden.sh' $registro) -eq 0) {
            Anotar 'orden' (T 'ver-sin-hallazgos') $true
        } else {
            Anotar 'orden' (T 'ver-hallazgos' @((Get-Content $registro | Measure-Object).Count)) $false $registro
        }
    }

    Azul (T 'ver-comprobando' @('comentarios'))
    $f = Faltan @('python3')
    if ($f) { Anotar 'comentarios' (T 'ver-falta' @($f)) $false; return }
    $registro = Join-Path $V.Tmp 'comentarios.log'
    New-Item -ItemType File -Path $registro -Force | Out-Null
    $py = "`"$($V.Py)`""
    $orden = "$py herramientas/comentarios.py --verificar java rust c++ herramientas && $py -I herramientas/pruebas_comentarios.py"
    if ((Ejecutar $Aqui $orden $registro) -eq 0) {
        Anotar 'comentarios' (T 'ver-ninguno') $true
    } else {
        $n = (Get-Content $registro | Where-Object { $_ -match '^\S+:\d+' } | Measure-Object).Count
        Anotar 'comentarios' (T 'ver-comentarios' @($n)) $false $registro
    }
}

function PasoPruebas {
    foreach ($linea in $Implementaciones) {
        $p = $linea.Split('|')
        $nombre = $p[0]
        Azul (T 'ver-comprobando' @($nombre))
        $etiqueta = "$nombre · $(T 'ver-pruebas')"
        $f = Faltan ($p[2] -split ' ')
        if ($f) { Anotar $etiqueta (T 'ver-falta' @($f)) $false; continue }
        $registro = Join-Path $V.Tmp "$nombre-pruebas.log"
        New-Item -ItemType File -Path $registro -Force | Out-Null
        $env:OBJ = Join-Path $V.Tmp "obj-$nombre"
        $ok = ((Ejecutar (Join-Path $Aqui $p[1]) $p[3] $registro) -eq 0)
        if ($p[4] -and ((Ejecutar (Join-Path $Aqui $p[1]) $p[4] $registro) -ne 0)) { $ok = $false }
        $cifra = T 'ver-n-pruebas' @(ContarPruebas $registro)
        if (Select-String -Path $registro -Pattern 'no está accesible' -Quiet) { $cifra = "$cifra · $(T 'ver-sin-motores')" }
        Anotar $etiqueta $cifra $ok $(if ($ok) { '' } else { $registro })
    }
}

function PasoBanco {
    foreach ($linea in $Implementaciones) {
        $p = $linea.Split('|')
        $nombre = $p[0]; $dir = Join-Path $Aqui $p[1]; $servidor = $p[5]
        $env:OBJ = Join-Path $V.Tmp "obj-$nombre"

        $etiqueta = "$(T 'ver-banco') · $nombre"
        Azul (T 'ver-comprobando' @($etiqueta))
        $f = Faltan @('python3')
        if ($f) { Anotar $etiqueta (T 'ver-falta' @($f)) $false }
        else {
            $registro = Join-Path $V.Tmp "$nombre-banco.log"
            if (ConServidor $etiqueta $dir $servidor (Join-Path $V.Tmp "$nombre-servidor.log")) {
                $correr = Join-Path $Aqui 'spec\banco\correr.py'
                New-Item -ItemType File -Path $registro -Force | Out-Null
                $ok = ((Ejecutar $dir "`"$($V.Py)`" `"$correr`" 127.0.0.1 $($V.Puerto)" $registro) -eq 0)
                Anotar $etiqueta (CifraVectores $registro) $ok $(if ($ok) { '' } else { $registro })
                PararServidor
            }
        }

        $etiqueta = "$(T 'ver-ataques') · $nombre"
        Azul (T 'ver-comprobando' @($etiqueta))
        if ($f) { Anotar $etiqueta (T 'ver-falta' @($f)) $false; continue }
        $registro = Join-Path $V.Tmp "$nombre-ataques.log"
        New-Item -ItemType File -Path $registro -Force | Out-Null
        $ataques = Join-Path $Aqui 'spec\banco\ataques.py'
        $ok = ((Ejecutar $dir "`"$($V.Py)`" `"$ataques`" $servidor" $registro) -eq 0)
        Anotar $etiqueta (CifraVectores $registro) $ok $(if ($ok) { '' } else { $registro })
    }
}

function PasoH2spec {
    foreach ($linea in $Implementaciones) {
        $p = $linea.Split('|')
        $nombre = $p[0]
        $etiqueta = "h2spec · $nombre"
        Azul (T 'ver-comprobando' @($etiqueta))
        $f = Faltan @('h2spec')
        if ($f) { Anotar $etiqueta (T 'ver-falta' @($f)) $false; continue }
        $env:OBJ = Join-Path $V.Tmp "obj-$nombre"
        $registro = Join-Path $V.Tmp "$nombre-h2spec.log"
        if (ConServidor $etiqueta (Join-Path $Aqui $p[1]) $p[5] (Join-Path $V.Tmp "$nombre-servidor-h2.log")) {
            New-Item -ItemType File -Path $registro -Force | Out-Null
            [void](Ejecutar $Aqui "h2spec -h 127.0.0.1 -p $($V.Puerto) --timeout 5" $registro)
            PararServidor
            $resumen = Get-Content $registro | Where-Object { $_ -match '^\d+ tests,' } | Select-Object -Last 1
            $fallos = if ("$resumen" -match ', (\d+) failed') { [int]$Matches[1] } else { 99 }
            if ($fallos -le 1) { Anotar $etiqueta $resumen $true }
            else { Anotar $etiqueta $(if ($resumen) { $resumen } else { T 'ver-sin-resumen' }) $false $registro }
        }
    }
}

function PasoRequisitos {
    foreach ($linea in $Implementaciones) {
        $p = $linea.Split('|')
        if (-not $p[7]) { continue }
        Azul (T 'ver-comprobando' @("$(T 'ver-requisitos') $($p[0])"))
        $archivos = foreach ($d in ($p[6] -split ' ')) { Get-ChildItem (Join-Path (Join-Path $Aqui $p[1]) $d) -Recurse -File }
        $n = ($archivos | Select-String -Pattern '(HTTP|OBS|RUT|SEG|SES|H2)[-_][0-9]{3}' -AllMatches |
              ForEach-Object { $_.Matches.Value.ToUpper().Replace('_', '-') } | Sort-Object -Unique | Measure-Object).Count
        Anotar "$(T 'ver-requisitos') · $($p[0])" (T 'ver-de-minimo' @($n, $p[7])) ($n -ge [int]$p[7])
    }
}

function Tabla {
    Write-Host ''
    $fallos = 0
    foreach ($f in $Filas) {
        if ($f.Ok) { $marca = "$Laton✓$Fin" } else { $marca = "$Rojo✗$Fin"; $fallos++ }
        Write-Host ('  {0,-26}  {1,-46}  {2}' -f $f.Nombre, $f.Cifra, $marca)
    }
    Write-Host ''
    foreach ($f in $Filas) {
        if (-not $f.Ok -and $f.Registro -and (Test-Path $f.Registro)) {
            Write-Host "$Rojo$(T 'ver-detalle' @($f.Nombre))$Fin"
            @(Get-Content $f.Registro | Where-Object { $_ -match 'XX |FALLA|FAILED|panicked' } | Select-Object -First 10) + @(Get-Content $f.Registro -Tail 8) |
                ForEach-Object { Write-Host "    $_" }
        }
    }
    if ($fallos -eq 0) { Azul (T 'ver-todo-bien') } else { Write-Host "$Rojo$(T 'ver-fallan' @($fallos))$Fin" }
    return $fallos
}

function Verificar {
    $V.Tmp = Join-Path ([IO.Path]::GetTempPath()) ("cero-" + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $V.Tmp | Out-Null
    try {
        $V.Py = BuscarPython
        if ($V.Py) { Gris (T 'ver-python' @($V.Py, (& $V.Py -c 'import sys; print("%d.%d.%d" % sys.version_info[:3])'))) }
        else { Write-Host "$Rojo$(T 'ver-sin-python')$Fin" }
        PasoOrden
        PasoPruebas
        PasoBanco
        PasoH2spec
        PasoRequisitos
        return [int](Tabla)
    }
    finally {
        PararServidor
        Remove-Item -Recurse -Force $V.Tmp -ErrorAction SilentlyContinue
    }
}

switch -Regex ($Orden) {

    '^(--version|-V|version)$' {
        $pom = Select-String -Path (Join-Path $JavaDir 'pom.xml') -Pattern '<version>(.+?)</version>' | Select-Object -First 1
        Write-Host ('cero ' + $pom.Matches[0].Groups[1].Value)
        Write-Host 'Cero · por Andre Solorzano'
    }

    '^(run|correr)$' {
        Requisitos
        $puerto = if ($Resto.Count -gt 0) { $Resto[0] } else { '8080' }
        Azul 'compilando la aplicación de ejemplo…'
        Mvn @('-B', '-q', '-f', (Join-Path $JavaDir 'pom.xml'), '-DskipTests', 'install')
        Azul "arrancando en http://localhost:$puerto"
        & java -cp (Classpath 'ejemplo') 'ejemplo.App' $puerto
    }

    '^(test|probar)$' {
        Requisitos
        $pom = Join-Path $JavaDir 'pom.xml'
        if ($Resto.Count -gt 0) {
            Azul "probando $($Resto[0])"
            Mvn @('-B', '-q', '-f', $pom, '-pl', $Resto[0], '-am', 'test')
        } else {
            Azul 'corriendo todas las pruebas'
            Mvn @('-B', '-q', '-f', $pom, 'test')
        }
    }

    '^(install|instalar)$' {
        Requisitos
        Azul 'compilando, probando e instalando en ~\.m2'
        Mvn @('-B', '-f', (Join-Path $JavaDir 'pom.xml'), 'install')
    }

    '^(package|empaquetar)$' {
        Requisitos
        Azul 'empaquetando…'
        Mvn @('-B', '-q', '-f', (Join-Path $JavaDir 'pom.xml'), '-DskipTests', 'package')
        Write-Host ''
        Write-Host ('  {0,-22} {1,10}' -f 'MÓDULO', 'TAMAÑO')
        $total = 0
        foreach ($m in 'cero-http', 'cero-core', 'cero-data', 'cero-adapter-servlet') {
            $jar = Get-ChildItem (Join-Path $JavaDir "$m\target\$m-*.jar") -ErrorAction SilentlyContinue |
                   Select-Object -First 1
            if (-not $jar) { continue }
            $total += $jar.Length
            Write-Host ('  {0,-22} {1,7} KB' -f $m, [int]($jar.Length / 1KB))
        }
        Write-Host ('  {0,-22} {1,7} KB' -f 'total', [int]($total / 1KB))
    }

    '^(new|nuevo)$' {
        Requisitos
        if ($Resto.Count -eq 0) { Malo 'dime el nombre:  cero nuevo mi-app [grupo] [motor]' }
        Mvn @('-B', '-q', '-f', (Join-Path $JavaDir 'pom.xml'), '-DskipTests', 'install')
        Azul "creando $($Resto[0])…"
        & java -cp (Join-Path $JavaDir 'cero-launcher\target\classes') 'cero.launcher.Nuevo' @Resto
    }

    '^fatjar$' {
        Requisitos
        $modulo = if ($Resto.Count -gt 0) { $Resto[0] } else { 'ejemplo' }
        $main = if ($Resto.Count -gt 1) { $Resto[1] } else {
            switch ($modulo) {
                'ejemplo' { 'ejemplo.App' }
                default   { Malo 'dime la clase principal:  cero fatjar <modulo> <clase>' }
            }
        }
        Mvn @('-B', '-q', '-f', (Join-Path $JavaDir 'pom.xml'), '-DskipTests', 'install')
        Mvn @('-B', '-q', '-f', (Join-Path $JavaDir "$modulo\pom.xml"),
              'dependency:build-classpath', '-Dmdep.outputFile=target/classpath.txt')
        $destino = Join-Path $Aqui "$modulo.jar"
        $partes = (Get-Content (Join-Path $JavaDir "$modulo\target\classpath.txt")) -split ';'
        & java -cp (Join-Path $JavaDir 'cero-launcher\target\classes') 'cero.launcher.Packager' `
              '--main' $main '--out' $destino (Join-Path $JavaDir "$modulo\target\classes") @partes
        Azul "arráncalo con:  java -jar $modulo.jar"
    }

    '^(clean|limpiar)$' {
        Azul 'borrando lo generado…'
        Mvn @('-B', '-q', '-f', (Join-Path $JavaDir 'pom.xml'), 'clean')
        Gris 'listo'
    }

    '^(status|estado)$' {
        $pom = Select-String -Path (Join-Path $JavaDir 'pom.xml') -Pattern '<version>(.+?)</version>' |
               Select-Object -First 1
        $version = if ($pom) { $pom.Matches[0].Groups[1].Value } else { '—' }
        $fuentes = Get-ChildItem -Path $JavaDir -Recurse -Filter *.java -ErrorAction SilentlyContinue |
                   Where-Object { $_.FullName -match '\\src\\main\\' }
        Write-Host ('  {0,-16} {1}' -f 'versión', $version)
        Write-Host ('  {0,-16} {1}' -f 'java', ((& java -version 2>&1 | Select-Object -First 1) -split '"')[1])
        Write-Host ('  {0,-16} {1} clases' -f 'código', $fuentes.Count)
        Write-Host ('  {0,-16} {1}' -f 'sistema', "Windows $([Environment]::OSVersion.Version.Major)")
    }

    '^migraciones$' {
        Requisitos
        if ($Resto.Count -eq 0 -or $Resto[0] -ne '--verificar') {
            Malo 'uso:  cero migraciones --verificar [directorio] [url-jdbc]'
        }
        $dir = if ($Resto.Count -gt 1) { $Resto[1] } else { 'db\migraciones' }
        if (-not (Test-Path -PathType Container $dir)) { Malo "no existe el directorio de migraciones $dir" }
        Mvn @('-B', '-q', '-f', (Join-Path $JavaDir 'pom.xml'), '-DskipTests', '-pl', 'cero-launcher', '-am', 'install')
        $driver = if ($env:CERO_JDBC_JAR) { $env:CERO_JDBC_JAR } else {
            (Get-ChildItem (Join-Path $HOME '.m2\repository\com\h2database\h2\*\h2-*.jar') -ErrorAction SilentlyContinue |
             Select-Object -First 1).FullName
        }
        if (-not $driver) { Malo 'hace falta un driver JDBC: $env:CERO_JDBC_JAR = C:\ruta\driver.jar' }
        $cp = @('cero-launcher', 'cero-data', 'cero-core' |
                ForEach-Object { Join-Path $JavaDir "$_\target\classes" }) + $driver -join ';'
        $argumentos = @($dir); if ($Resto.Count -gt 2) { $argumentos += $Resto[2] }
        & java -cp $cp 'cero.launcher.Migraciones' @argumentos
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }

    '^(migrar|migrate)$' {
        if ($Resto.Count -eq 0) { Malo 'uso:  cero migrar <ruta-de-la-app> [--probar]' }
        $destino = $Resto[0]
        if (-not (Test-Path -PathType Container $destino)) { Malo "no existe la carpeta $destino" }
        $ensayo = (($Resto -contains '--probar') -or ($Resto -contains '--dry-run'))

        if (-not $ensayo -and (Get-Command git -ErrorAction SilentlyContinue)) {
            $sucio = $null
            try {
                $enGit = & git -C $destino rev-parse --git-dir 2>$null
                if ($LASTEXITCODE -eq 0 -and $enGit) { $sucio = & git -C $destino status --porcelain }
            } catch {
                $sucio = $null
            }
            if ($sucio) {
                Malo "el árbol de git de $destino tiene cambios sin guardar. Commitea o guarda antes: la migración usa git como red de seguridad."
            }
        }

        $guion = Join-Path $JavaDir 'cero-launcher\src\main\resources\migrar.py'
        if ($ensayo) { & (Python) $guion $destino '--probar' } else { & (Python) $guion $destino }
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }

    '^cache$' {
        $m2 = Join-Path $HOME '.m2\repository\dev\ginit\cero'
        if ($Resto -contains '--purge') {
            if (Test-Path $m2) { Remove-Item -Recurse -Force $m2 }
            Gris 'artefactos de cero eliminados; la próxima compilación los rehace'
        } else {
            Write-Host ('  {0,-40} {1,10}' -f 'UBICACIÓN', 'TAMAÑO')
            foreach ($d in @($m2) + (Get-ChildItem (Join-Path $JavaDir '*\target') -Directory -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })) {
                if (-not (Test-Path $d)) { continue }
                $kb = [int](((Get-ChildItem $d -Recurse -File -ErrorAction SilentlyContinue |
                        Measure-Object -Property Length -Sum).Sum) / 1KB)
                Write-Host ('  {0,-40} {1,7} KB' -f $d.Replace($Aqui, '.'), $kb)
            }
            Write-Host ''
            Gris '  cero cache --purge    vacía los artefactos de cero en ~\.m2'
            Gris '  cero clean            borra los target\ del proyecto'
        }
    }

    '^(verificar|verify)$' {
        if ((Verificar) -gt 0) { exit 1 }
    }

    default {
        Azul 'cero — órdenes de Cero'
        Write-Host ''
        Write-Host ('  {0,-24} {1}' -f 'cero new <nombre>',    'crea un proyecto nuevo y listo para arrancar')
        Write-Host ('  {0,-24} {1}' -f 'cero run [puerto]',    'arranca la aplicación de ejemplo')
        Write-Host ('  {0,-24} {1}' -f 'cero test [módulo]',   'corre las pruebas')
        Write-Host ('  {0,-24} {1}' -f 'cero package',         'genera los JAR y muestra sus tamaños')
        Write-Host ('  {0,-24} {1}' -f 'cero fatjar [módulo]', 'un solo jar ejecutable con java -jar')
        Write-Host ('  {0,-24} {1}' -f 'cero install',         'compila, prueba e instala en ~\.m2')
        Write-Host ('  {0,-24} {1}' -f 'cero clean',           'borra lo generado')
        Write-Host ('  {0,-24} {1}' -f 'cero cache',           'qué ocupa la caché; con --purge la vacía')
        Write-Host ('  {0,-24} {1}' -f 'cero status',          'resumen del proyecto')
        Write-Host ('  {0,-24} {1}' -f 'cero migraciones',     'con --verificar, prueba las migraciones en una base desechable')
        Write-Host ('  {0,-24} {1}' -f 'cero migrar <ruta>',   'migra una aplicación anterior a Cero 0.6.0')
        Write-Host ('  {0,-24} {1}' -f 'cero verificar',       'lo mismo que CI, en una orden: ✓ o ✗ por comprobación')
        Write-Host ''
        Gris '  `cero dist` arma los paquetes de la descarga y solo está en macOS y Linux:'
        Gris '  lo corre quien publica una versión, y comprueba el paquete desempaquetándolo.'
    }
}
