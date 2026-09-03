<#
    Instalador de Cero para Windows.

        irm https://cero.ginit.dev/instalar.ps1 | iex

    Baja el paquete, comprueba su huella, lo compila, deja los artefactos en ~\.m2 y la orden
    `cero` en el PATH del usuario. No necesita administrador y no escribe fuera de tu perfil.

    Con pruebas:  & ([scriptblock]::Create((irm https://cero.ginit.dev/instalar.ps1))) -ConPruebas
#>
[CmdletBinding()]
param(
    [switch] $ConPruebas,
    [switch] $SinColor,
    [string] $Base = $(if ($env:CERO_BASE) { $env:CERO_BASE } else { 'https://cero.ginit.dev' })
)

$ErrorActionPreference = 'Stop'
$ProgressPreference    = 'SilentlyContinue'   # la barra nativa de Invoke-WebRequest la frena mucho

$Raiz = if ($env:CERO_HOME) { $env:CERO_HOME } else { Join-Path $env:LOCALAPPDATA 'Cero' }
$Bin  = Join-Path $Raiz 'bin'

# ─── pintura ────────────────────────────────────────────────────────────────────────────
$Vivo = -not $SinColor -and $Host.UI.RawUI -and -not [Console]::IsOutputRedirected
$e = [char]27
if ($Vivo) {
    $Acento='{0}[38;5;205m' -f $e; $Tenue='{0}[38;5;245m' -f $e; $Verde='{0}[38;5;71m'  -f $e
    $Rojo ='{0}[38;5;167m' -f $e; $Fuerte='{0}[1m'       -f $e; $Fin  ='{0}[0m'        -f $e
} else {
    $Acento=''; $Tenue=''; $Verde=''; $Rojo=''; $Fuerte=''; $Fin=''
}

function Escribe([string] $t) { Write-Host $t }
function Borra { if ($Vivo) { Write-Host ("`r{0}[K" -f $e) -NoNewline } }

$script:Paso = 0
function Paso([string] $t) {
    $script:Paso++
    Write-Host ("  {0}{1:D2}{2}  {3}" -f $Acento, $script:Paso, $Fin, $t) -NoNewline
}
function Bien([string] $t, [string] $nota) {
    Borra
    Write-Host ("  {0}OK{1}  {2}{3}" -f $Verde, $Fin, $t, $(if ($nota) { "  $Tenue$nota$Fin" }))
}
function Muere([string] $t, [string] $registro) {
    Borra
    Write-Host ("  {0}X   {1}{2}" -f $Rojo, $t, $Fin) -ForegroundColor Red
    if ($registro -and (Test-Path $registro)) {
        Write-Host ''
        Get-Content $registro -Tail 25 | ForEach-Object { Write-Host "$Tenue$_$Fin" }
    }
    exit 1
}

function Marca {
    if (-not $Vivo) { Escribe "Cero - instalador`n"; return }
    Escribe ''
    Escribe ("        {0}.{1}   {0}|{1}   {0}.{1}" -f $Acento, $Fin)
    Escribe ("   {0}\{1}    {0}.{1}     {0}.{1}    {0}/{1}" -f $Acento, $Fin)
    Escribe (" {0}-{1}   {0}.{1}   {0}{2}###{1}   {0}.{1}   {0}-{1}      {2}Cero{1}" -f $Acento, $Fin, $Fuerte)
    Escribe ("   {0}/{1}    {0}.{1}     {0}.{1}    {0}\{1}      {2}framework web para Java{1}" -f $Acento, $Fin, $Tenue)
    Escribe ("        {0}.{1}   {0}|{1}   {0}.{1}" -f $Acento, $Fin)
    Escribe ''
}

# Corre algo largo enseñando un giro. La salida va a un fichero: solo se enseña si falla.
function Girando([string] $etiqueta, [string] $registro, [string] $orden, [string[]] $argumentos) {
    $inicio = Get-Date
    $proc = Start-Process -FilePath $orden -ArgumentList $argumentos -NoNewWindow -PassThru `
                          -RedirectStandardOutput $registro -RedirectStandardError "$registro.err"
    if (-not $Vivo) {
        Escribe "  ... $etiqueta"
        $proc.WaitForExit()
    } else {
        $giros = '|/-\'.ToCharArray()
        $i = 0
        while (-not $proc.HasExited) {
            $s = [int]((Get-Date) - $inicio).TotalSeconds
            Write-Host ("`r{0}[K  {1}{2}{3}   {4} {5}{6}s{3}" -f `
                        $e, $Acento, $giros[$i % 4], $Fin, $etiqueta, $Tenue, $s) -NoNewline
            $i++
            Start-Sleep -Milliseconds 90
        }
    }
    $script:Segundos = [int]((Get-Date) - $inicio).TotalSeconds
    if (Test-Path "$registro.err") { Get-Content "$registro.err" | Add-Content $registro }
    return $proc.ExitCode
}

function Donde([string] $orden) { (Get-Command $orden -ErrorAction SilentlyContinue).Source }

# ─── 1 · lo que hace falta ──────────────────────────────────────────────────────────────
Marca
Paso 'comprobando el entorno'

$falta = @('java', 'mvn') | Where-Object { -not (Donde $_) }
if ($falta) {
    Borra
    Write-Host ("  {0}X   falta: {1}{2}" -f $Rojo, ($falta -join ' '), $Fin)
    Escribe ''
    Escribe "  Cero necesita un ${Fuerte}JDK 25${Fin} o superior y ${Fuerte}Maven${Fin}."
    Escribe "  ${Tenue}winget install EclipseAdoptium.Temurin.25.JDK${Fin}"
    Escribe "  ${Tenue}winget install Apache.Maven${Fin}"
    Escribe ''
    Escribe "  ${Tenue}Cierra y abre PowerShell despues de instalarlos, para que entren en el PATH.${Fin}"
    exit 1
}

$javaV = 0
$linea = (& java -version 2>&1 | Select-Object -First 1)
if ("$linea" -match '"(\d+)') { $javaV = [int]$Matches[1] }
if ($javaV -lt 25) { Muere "Cero necesita Java 25 o superior - hilos virtuales. Tienes $javaV." }
Bien 'entorno' "Java $javaV - Windows $([Environment]::OSVersion.Version.Major) - $env:PROCESSOR_ARCHITECTURE"

# ─── 2 · qué versión ────────────────────────────────────────────────────────────────────
Paso 'consultando la version'
try { $version = (Invoke-RestMethod -Uri "$Base/version" -TimeoutSec 20).ToString().Trim() }
catch { Muere "no se pudo hablar con $Base - hay conexion?" }
if ($version -notmatch '^[0-9][0-9.]*$') { Muere "el servidor devolvio una version rara: '$version'" }
Bien 'version' "Cero $version"

# ─── 3 · bajarlo ────────────────────────────────────────────────────────────────────────
$paquete = "cero-$version.zip"
$tmp = Join-Path ([IO.Path]::GetTempPath()) ("cero-" + [Guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $tmp -Force | Out-Null
$zip = Join-Path $tmp $paquete

Paso 'bajando el paquete'
try { Invoke-WebRequest -Uri "$Base/estaticos/$paquete" -OutFile $zip -TimeoutSec 300 }
catch { Muere "no se pudo bajar $Base/estaticos/$paquete" }
Bien 'descargado' ("{0} - {1} KB" -f $paquete, [int]((Get-Item $zip).Length / 1KB))

# ─── 4 · comprobar la huella ────────────────────────────────────────────────────────────
Paso 'comprobando la huella'
try { $esperada = ((Invoke-RestMethod -Uri "$Base/estaticos/$paquete.sha256" -TimeoutSec 20) -split '\s+')[0] }
catch { Muere 'no se pudo bajar la huella' }
$real = (Get-FileHash -Path $zip -Algorithm SHA256).Hash.ToLower()
if ($real -ne $esperada.ToLower()) {
    Muere "la huella no coincide - el paquete llego cambiado, no lo instalo.`n      esperada  $esperada`n      recibida  $real"
}
Bien 'huella' "sha256 $($real.Substring(0,16))..."

# ─── 5 · extraer ────────────────────────────────────────────────────────────────────────
Paso 'extrayendo'
$destino = Join-Path $Raiz "cero-$version"
if (Test-Path $destino) { Remove-Item $destino -Recurse -Force }
New-Item -ItemType Directory -Path $Raiz -Force | Out-Null
Expand-Archive -Path $zip -DestinationPath $Raiz -Force
if (-not (Test-Path $destino)) { Muere "el paquete no traia cero-$version dentro" }
Bien 'extraido' $destino

# ─── 6 · compilar ───────────────────────────────────────────────────────────────────────
Paso 'compilando'
$pom = Join-Path $destino 'java\pom.xml'
$mvnArgs = @('-B', '-q', '-f', $pom, 'install')
if (-not $ConPruebas) { $mvnArgs += '-DskipTests' }
$registro = Join-Path $tmp 'mvn.log'
# mvn en Windows es un .cmd, asi que va por cmd.exe
$codigo = Girando 'compilando los ocho modulos' $registro 'cmd.exe' (@('/c', 'mvn') + $mvnArgs)
if ($codigo -ne 0) { Muere 'la compilacion fallo' $registro }
Bien 'compilado' "ocho modulos en ~\.m2 - $script:Segundos s"

# ─── 7 · dejar la orden a mano ──────────────────────────────────────────────────────────
Paso 'instalando la orden cero'
$actual = Join-Path $Raiz 'actual'
if (Test-Path $actual) { Remove-Item $actual -Recurse -Force }
Copy-Item -Path $destino -Destination $actual -Recurse
New-Item -ItemType Directory -Path $Bin -Force | Out-Null
@"
@echo off
rem Generado por el instalador de Cero. Apunta siempre a la version en uso.
call "$actual\cero.cmd" %*
"@ | Set-Content -Path (Join-Path $Bin 'cero.cmd') -Encoding ASCII

$pathUsuario = [Environment]::GetEnvironmentVariable('Path', 'User')
if ($pathUsuario -notlike "*$Bin*") {
    [Environment]::SetEnvironmentVariable('Path', "$pathUsuario;$Bin", 'User')
    $script:PathTocado = $true
}
$env:Path = "$env:Path;$Bin"
Bien 'orden cero' (Join-Path $Bin 'cero.cmd')

# ─── 8 · comprobar que sirve ────────────────────────────────────────────────────────────
Paso 'comprobando la instalacion'
& cmd.exe /c "`"$Bin\cero.cmd`" estado" *> $null
if ($LASTEXITCODE -ne 0) { Muere "quedo instalado pero 'cero status' no responde" }
Bien 'comprobado' 'cero status responde'

# ─── final ──────────────────────────────────────────────────────────────────────────────
Escribe ''
Escribe "  ${Verde}${Fuerte}Cero $version instalado${Fin}"
Escribe ''
if ($script:PathTocado) {
    Escribe "  ${Acento}Abre una terminal nueva${Fin} para que el PATH se entere de la orden ${Fuerte}lux${Fin}."
    Escribe ''
}
Escribe "  ${Tenue}Crear un proyecto y arrancarlo:${Fin}"
Escribe ''
Escribe "      ${Fuerte}cero new mi-app${Fin}"
Escribe "      ${Fuerte}cd mi-app && mvn -q package && java -jar target\mi-app.jar${Fin}"
Escribe ''
Escribe "  ${Tenue}Guia completa:${Fin}  $Base/empezar"
Escribe ''

Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
