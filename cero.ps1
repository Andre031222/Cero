<#
    cero — órdenes de Cero en Windows. La misma lista que el guion `cero` de macOS y Linux.
    Sin dependencias más allá de Maven y un JDK 25.
#>
param(
    [Parameter(Position = 0)] [string] $Orden = 'ayuda',
    [Parameter(Position = 1, ValueFromRemainingArguments = $true)] [string[]] $Resto = @()
)

$ErrorActionPreference = 'Stop'
$Aqui    = Split-Path -Parent $MyInvocation.MyCommand.Path
$JavaDir = Join-Path $Aqui 'java'

$e = [char]27
$Vivo = $Host.UI.RawUI -and -not [Console]::IsOutputRedirected
if ($Vivo) { $Laton = "$e[38;5;205m"; $Tenue = "$e[38;5;245m"; $Rojo = "$e[38;5;167m"; $Fin = "$e[0m" }
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
    # 25 y no 21: el pom compila con `release 25`, así que dejar pasar un 21 solo cambia este
    # mensaje por el de javac, que no dice qué hacer. Los instaladores de docs/ ya piden 25.
    if ($v -lt 25) { Malo "Cero necesita Java 25 o superior (hilos virtuales); tienes $v" }
}

# Python solo lo pide `migrar`; el resto de órdenes no lo necesita, así que no entra en
# Requisitos.
function Python {
    foreach ($nombre in 'python3', 'python', 'py') {
        if (Get-Command $nombre -ErrorAction SilentlyContinue) { return $nombre }
    }
    Malo 'falta Python 3: lo necesita `cero migrar`'
}

# El classpath de un módulo ya compilado: sus clases más las de los cero-*.
function Classpath([string] $modulo) {
    $jars = Get-ChildItem (Join-Path $JavaDir 'cero-*\target\cero-*.jar') -ErrorAction SilentlyContinue
    $partes = @((Join-Path $JavaDir "$modulo\target\classes")) + ($jars | ForEach-Object { $_.FullName })
    return ($partes -join ';')
}

switch -Regex ($Orden) {

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
        foreach ($m in 'cero-http', 'cero-core', 'cero-view', 'cero-data', 'cero-adapter-servlet') {
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
        # Aplica el lote entero sobre una base vacía y desechable y dice qué pasó: nada toca
        # la base de verdad, así que vale en integración continua.
        Requisitos
        if ($Resto.Count -eq 0 -or $Resto[0] -ne '--verificar') {
            Malo 'uso:  cero migraciones --verificar [directorio] [url-jdbc]'
        }
        $dir = if ($Resto.Count -gt 1) { $Resto[1] } else { 'db\migraciones' }
        if (-not (Test-Path -PathType Container $dir)) { Malo "no existe el directorio de migraciones $dir" }
        Mvn @('-B', '-q', '-f', (Join-Path $JavaDir 'pom.xml'), '-DskipTests', '-pl', 'cero-launcher', '-am', 'install')
        # El driver lo pone quien llama; si no dice nada, se usa el H2 de ~\.m2.
        $driver = if ($env:CERO_JDBC_JAR) { $env:CERO_JDBC_JAR } else {
            (Get-ChildItem (Join-Path $HOME '.m2\repository\com\h2database\h2\*\h2-*.jar') -ErrorAction SilentlyContinue |
             Select-Object -First 1).FullName
        }
        if (-not $driver) { Malo 'hace falta un driver JDBC: $env:CERO_JDBC_JAR = C:\ruta\driver.jar' }
        # Las clases recién compiladas, no los jar de target\: ahí conviven versiones viejas.
        $cp = @('cero-launcher', 'cero-data', 'cero-core' |
                ForEach-Object { Join-Path $JavaDir "$_\target\classes" }) + $driver -join ';'
        $argumentos = @($dir); if ($Resto.Count -gt 2) { $argumentos += $Resto[2] }
        & java -cp $cp 'cero.launcher.Migraciones' @argumentos
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }

    '^(migrar|migrate)$' {
        # Migra una aplicación de una versión anterior del framework a Cero 0.6.0.
        #
        # Exige que el árbol de git de la aplicación esté limpio: así el propio git es la copia
        # de seguridad —`git diff` enseña todo y `git checkout .` lo deshace— y no hay que dejar
        # carpetas .bak por ahí. Mismo trato que en el guion de bash.
        if ($Resto.Count -eq 0) { Malo 'uso:  cero migrar <ruta-de-la-app> [--probar]' }
        $destino = $Resto[0]
        if (-not (Test-Path -PathType Container $destino)) { Malo "no existe la carpeta $destino" }
        $ensayo = (($Resto -contains '--probar') -or ($Resto -contains '--dry-run'))

        # El try envuelve a git a propósito: una carpeta que no es un repositorio hace que
        # `rev-parse` salga con 128, y desde PowerShell 7.4 un código distinto de cero en una
        # orden nativa aborta el guion entero por el $ErrorActionPreference de arriba. Que la
        # aplicación no esté en git no es un error: solo significa que no hay red de seguridad.
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
        Write-Host ''
        Gris '  `cero dist` arma los paquetes de la descarga y solo está en macOS y Linux:'
        Gris '  lo corre quien publica una versión, y comprueba el paquete desempaquetándolo.'
    }
}
