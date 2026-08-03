<#
    lux — órdenes de LuxCore en Windows. La misma lista que el guion `lux` de macOS y Linux.
    Sin dependencias más allá de Maven y un JDK 21.
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
if ($Vivo) { $Laton = "$e[38;5;179m"; $Tenue = "$e[38;5;245m"; $Rojo = "$e[38;5;167m"; $Fin = "$e[0m" }
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
    if ($v -lt 21) { Malo "LuxCore necesita Java 21 o superior (hilos virtuales); tienes $v" }
}

# El classpath de un módulo ya compilado: sus clases más las de los lux-*.
function Classpath([string] $modulo) {
    $jars = Get-ChildItem (Join-Path $JavaDir 'lux-*\target\lux-*.jar') -ErrorAction SilentlyContinue
    $partes = @((Join-Path $JavaDir "$modulo\target\classes")) + ($jars | ForEach-Object { $_.FullName })
    return ($partes -join ';')
}

switch -Regex ($Orden) {

    '^(correr|run)$' {
        Requisitos
        $puerto = if ($Resto.Count -gt 0) { $Resto[0] } else { '8080' }
        Azul 'compilando la aplicación de ejemplo…'
        Mvn @('-B', '-q', '-f', (Join-Path $JavaDir 'pom.xml'), '-DskipTests', 'install')
        Azul "arrancando en http://localhost:$puerto"
        & java -cp (Classpath 'ejemplo') 'ejemplo.App' $puerto
    }

    '^(probar|test)$' {
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

    '^(instalar|install)$' {
        Requisitos
        Azul 'compilando, probando e instalando en ~\.m2'
        Mvn @('-B', '-f', (Join-Path $JavaDir 'pom.xml'), 'install')
    }

    '^(empaquetar|package)$' {
        Requisitos
        Azul 'empaquetando…'
        Mvn @('-B', '-q', '-f', (Join-Path $JavaDir 'pom.xml'), '-DskipTests', 'package')
        Write-Host ''
        Write-Host ('  {0,-22} {1,10}' -f 'MÓDULO', 'TAMAÑO')
        $total = 0
        foreach ($m in 'lux-http', 'lux-core', 'lux-view', 'lux-data', 'lux-adapter-servlet') {
            $jar = Get-ChildItem (Join-Path $JavaDir "$m\target\$m-*.jar") -ErrorAction SilentlyContinue |
                   Select-Object -First 1
            if (-not $jar) { continue }
            $total += $jar.Length
            Write-Host ('  {0,-22} {1,7} KB' -f $m, [int]($jar.Length / 1KB))
        }
        Write-Host ('  {0,-22} {1,7} KB' -f 'total', [int]($total / 1KB))
    }

    '^(nuevo|new)$' {
        Requisitos
        if ($Resto.Count -eq 0) { Malo 'dime el nombre:  lux nuevo mi-app [grupo] [motor]' }
        Mvn @('-B', '-q', '-f', (Join-Path $JavaDir 'pom.xml'), '-DskipTests', 'install')
        Azul "creando $($Resto[0])…"
        & java -cp (Classpath 'lux-web') 'lux.web.Nuevo' @Resto
    }

    '^fatjar$' {
        Requisitos
        $modulo = if ($Resto.Count -gt 0) { $Resto[0] } else { 'ejemplo' }
        $main = if ($Resto.Count -gt 1) { $Resto[1] } else {
            switch ($modulo) {
                'ejemplo' { 'ejemplo.App' }
                'lux-web' { 'lux.web.App' }
                default   { Malo 'dime la clase principal:  lux fatjar <modulo> <clase>' }
            }
        }
        Mvn @('-B', '-q', '-f', (Join-Path $JavaDir 'pom.xml'), '-DskipTests', 'install')
        Mvn @('-B', '-q', '-f', (Join-Path $JavaDir "$modulo\pom.xml"),
              'dependency:build-classpath', '-Dmdep.outputFile=target/classpath.txt')
        $destino = Join-Path $Aqui "$modulo.jar"
        $partes = (Get-Content (Join-Path $JavaDir "$modulo\target\classpath.txt")) -split ';'
        & java -cp (Join-Path $JavaDir 'lux-launcher\target\classes') 'lux.launcher.Packager' `
              '--main' $main '--out' $destino (Join-Path $JavaDir "$modulo\target\classes") @partes
        Azul "arráncalo con:  java -jar $modulo.jar"
    }

    '^portal$' {
        Requisitos
        $puerto = if ($Resto.Count -gt 0) { $Resto[0] } else { '8080' }
        Azul 'compilando el sitio…'
        Mvn @('-B', '-q', '-f', (Join-Path $JavaDir 'pom.xml'), '-DskipTests', 'install')
        Azul "arrancando el sitio en http://localhost:$puerto"
        & java -cp (Classpath 'lux-web') 'lux.web.App' $puerto
    }

    '^(limpiar|clean)$' {
        Azul 'borrando lo generado…'
        Mvn @('-B', '-q', '-f', (Join-Path $JavaDir 'pom.xml'), 'clean')
        Gris 'listo'
    }

    '^(estado|status)$' {
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

    default {
        Azul 'lux — órdenes de LuxCore'
        Write-Host ''
        Write-Host ('  {0,-24} {1}' -f 'lux nuevo <nombre>',   'crea un proyecto nuevo y listo para arrancar')
        Write-Host ('  {0,-24} {1}' -f 'lux correr [puerto]',  'arranca la aplicación de ejemplo')
        Write-Host ('  {0,-24} {1}' -f 'lux probar [módulo]',  'corre las pruebas')
        Write-Host ('  {0,-24} {1}' -f 'lux empaquetar',       'genera los JAR y muestra sus tamaños')
        Write-Host ('  {0,-24} {1}' -f 'lux fatjar [módulo]',  'un solo jar ejecutable con java -jar')
        Write-Host ('  {0,-24} {1}' -f 'lux instalar',         'compila, prueba e instala en ~\.m2')
        Write-Host ('  {0,-24} {1}' -f 'lux portal [puerto]',  'arranca lux-web: acceso, demos y generador')
        Write-Host ('  {0,-24} {1}' -f 'lux limpiar',          'borra lo generado')
        Write-Host ('  {0,-24} {1}' -f 'lux estado',           'resumen del proyecto')
    }
}
