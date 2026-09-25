<#
    Instalador de Cero para Windows.

        irm https://cero.ginit.dev/instalar.ps1 | iex

    Detecta el sistema, la arquitectura y el gestor de paquetes; baja el paquete, comprueba su
    huella, lo compila, deja los artefactos en ~\.m2 y la orden `cero` en el PATH del usuario.
    No necesita administrador y no escribe fuera de tu perfil.

    Con pruebas:  & ([scriptblock]::Create((irm https://cero.ginit.dev/instalar.ps1))) -ConPruebas
    Solo detectar: ... -Detectar
#>
[CmdletBinding()]
param(
    [switch] $ConPruebas,
    [switch] $SinColor,
    [switch] $Detectar,
    [string] $Base = $(if ($env:CERO_BASE) { $env:CERO_BASE } else { 'https://cero.ginit.dev' })
)

$ErrorActionPreference = 'Stop'
$ProgressPreference    = 'SilentlyContinue'   # la barra nativa de Invoke-WebRequest la frena mucho

$JavaMinimo = 25
$Total      = 8
$Raiz = if ($env:CERO_HOME) { $env:CERO_HOME }
        elseif ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA 'Cero' }
        else { Join-Path $HOME '.cero' }
$Bin  = Join-Path $Raiz 'bin'

# ─── detección ──────────────────────────────────────────────────────────────────────────
$Arq = switch -Regex ("$([Runtime.InteropServices.RuntimeInformation]::OSArchitecture)") {
    'Arm64' { 'arm64' }; 'X64' { 'x86_64' }; 'X86' { 'x86' }; default { "$_" }
}
$EnWindows = [Runtime.InteropServices.RuntimeInformation]::IsOSPlatform([Runtime.InteropServices.OSPlatform]::Windows)
$DetalleSo = if ($EnWindows) {
    "Windows $([Environment]::OSVersion.Version.Major) - $Arq"
} else {
    "$([Runtime.InteropServices.RuntimeInformation]::OSDescription) - $Arq"
}
$Interprete = if ($PSVersionTable.PSEdition -eq 'Core') { "PowerShell $($PSVersionTable.PSVersion)" }
              else { "Windows PowerShell $($PSVersionTable.PSVersion)" }

function Donde([string] $orden) { (Get-Command $orden -ErrorAction SilentlyContinue).Source }

$Gestor = @('winget', 'scoop', 'choco', 'brew', 'apt-get') | Where-Object { Donde $_ } | Select-Object -First 1

function OrdenJava {
    switch ($Gestor) {
        'winget'  { "winget install EclipseAdoptium.Temurin.$JavaMinimo.JDK Apache.Maven" }
        'scoop'   { "scoop install temurin$JavaMinimo-jdk maven" }
        'choco'   { "choco install -y temurin$JavaMinimo maven" }
        'brew'    { "brew install openjdk@$JavaMinimo maven" }
        'apt-get' { "sudo apt-get install -y openjdk-$JavaMinimo-jdk maven" }
        default   { "baja un JDK $JavaMinimo de https://adoptium.net/temurin/releases/?os=windows&arch=$Arq y Maven de https://maven.apache.org/download.cgi" }
    }
}

function VersionJava {
    if (-not (Donde 'java')) { return 0 }
    $linea = (& java -version 2>&1 | Select-Object -First 1)
    if ("$linea" -match '"(\d+)') { return [int]$Matches[1] }
    return 0
}

# ─── pintura ────────────────────────────────────────────────────────────────────────────
# Sin terminal, con NO_COLOR, con TERM=dumb o dentro de integración continua: líneas planas,
# ni un escape ni un retorno de carro.
$EnCi = @('CI','GITHUB_ACTIONS','GITLAB_CI','JENKINS_URL','BUILDKITE','TEAMCITY_VERSION','TF_BUILD') |
        Where-Object { [Environment]::GetEnvironmentVariable($_) }
$Vivo = -not $SinColor -and -not $EnCi -and -not $env:NO_COLOR -and $env:TERM -ne 'dumb' `
        -and $Host.UI.RawUI -and -not [Console]::IsOutputRedirected
$e = [char]27
if ($Vivo) {
    # El azul de la marca (#38bdf8). Windows Terminal habla truecolor; la consola vieja no.
    $Acento = if ($env:WT_SESSION -or $env:COLORTERM -in 'truecolor','24bit') {
        '{0}[38;2;56;189;248m' -f $e } else { '{0}[38;5;75m' -f $e }
    $Tenue='{0}[38;5;245m' -f $e; $Verde='{0}[38;5;71m'  -f $e
    $Rojo ='{0}[38;5;167m' -f $e; $Fuerte='{0}[1m'       -f $e; $Fin  ='{0}[0m'        -f $e
} else {
    $Acento=''; $Tenue=''; $Verde=''; $Rojo=''; $Fuerte=''; $Fin=''
}

# Glifos UTF-8 solo si la consola los sabe pintar; si no, ASCII.
$Utf = [Console]::OutputEncoding.WebName -match 'utf'
if ($Utf) { $Giros = '⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏'.ToCharArray(); $Lleno='━'; $Vacio='─'; $Ok='✓'; $No='✗' }
else      { $Giros = '|/-\'.ToCharArray();           $Lleno='#'; $Vacio='-'; $Ok='+'; $No='x' }

# ─── idioma ─────────────────────────────────────────────────────────────────────────────
# El instalador es lo primero que alguien ve de Cero, y hasta ahora solo hablaba castellano.
# `CERO_LANG` manda sobre la cultura de la consola, para forzarlo en un guion o en CI.
$Idioma = if ($env:CERO_LANG) { "$env:CERO_LANG" } else { "$PSUICulture" }
$Idioma = if ($Idioma -match '^(?i)es') { 'es' } else { 'en' }

$TextosEs = @{
    'p-entorno'     = 'comprobando el entorno'
    'p-version'     = 'consultando la version'
    'p-bajando'     = 'bajando el paquete'
    'p-huella'      = 'comprobando la huella'
    'p-extrayendo'  = 'extrayendo'
    'p-compilando'  = 'compilando los ocho modulos'
    'p-compilando-t'= 'compilando los ocho modulos y corriendo las pruebas'
    'p-instalando'  = 'instalando la orden cero'
    'p-comprobando' = 'comprobando la instalacion'
    'b-entorno'     = 'entorno'
    'b-version'     = 'version'
    'b-descargado'  = 'descargado'
    'b-huella'      = 'huella'
    'b-extraido'    = 'extraido'
    'b-compilado'   = 'compilado'
    'b-orden'       = 'orden cero'
    'b-comprobado'  = 'comprobado'
    'd-modulos'     = 'ocho modulos en ~\.m2 - {0} s'
    'd-responde'    = 'cero status responde'
    'e-sin-red'     = 'no se pudo hablar con {0} - hay conexion?'
    'e-version'     = 'el servidor devolvio una version rara'
    'e-descarga'    = 'no se pudo bajar {0}'
    'e-sin-huella'  = 'no se pudo bajar la huella'
    'e-contenido'   = 'el paquete no traia {0} dentro'
    'e-compilar'    = 'la compilacion fallo'
    'e-no-responde' = "quedo instalado pero 'cero status' no responde"
    'e-huella-mal'  = 'la huella no coincide - el paquete llego cambiado, no lo instalo.'
    'e-esperada'    = 'esperada'
    'e-recibida'    = 'recibida'
    'e-falta'       = 'falta: {0}'
    'e-java-viejo'  = 'Cero necesita Java {0} o superior - hilos virtuales. Tienes {1}.'
    'a-no-windows'  = 'no estas en Windows'
    'a-usa-shell'   = 'usa el instalador de shell:  curl -fsSL {0}/instalar | sh'
    't-necesita'    = 'Cero necesita un JDK {0} o superior y Maven.'
    't-detectado'   = 'Detectado: {0} - gestor {1}'
    't-ninguno'     = 'ninguno'
    't-reabre'      = 'Cierra y abre PowerShell despues de instalarlos, para que entren en el PATH.'
    'd-lema'        = 'framework web para Java'
    'f-instalador'  = 'Cero - instalador'
    'f-instalado'   = 'Cero {0} instalado'
    'f-sistema'     = 'sistema'
    'f-terminal'    = 'terminal'
    'f-gestor'      = 'gestor'
    'f-java'        = 'java'
    'f-maven'       = 'maven'
    'f-salida'      = 'salida'
    'f-carpeta'     = 'carpeta'
    'f-orden'       = 'orden'
    'f-ninguno'     = 'ninguno conocido'
    'f-no-hay'      = 'no encontrado'
    'f-presente'    = 'presente'
    'f-color'       = 'terminal con color'
    'f-plana'       = 'plana (sin escapes)'
    'f-para-java'   = 'Para tener Java {0} en tu sistema:'
    'f-abre'        = 'Abre una terminal nueva'
    'f-abre-resto'  = 'para que el PATH se entere de la orden'
    'f-crear'       = 'Crear un proyecto y arrancarlo:'
    'f-guia'        = 'Guia completa:'
}

$TextosEn = @{
    'p-entorno'     = 'checking the environment'
    'p-version'     = 'asking for the version'
    'p-bajando'     = 'downloading the package'
    'p-huella'      = 'verifying the checksum'
    'p-extrayendo'  = 'extracting'
    'p-compilando'  = 'building the eight modules'
    'p-compilando-t'= 'building the eight modules and running the tests'
    'p-instalando'  = 'installing the cero command'
    'p-comprobando' = 'checking the installation'
    'b-entorno'     = 'environment'
    'b-version'     = 'version'
    'b-descargado'  = 'downloaded'
    'b-huella'      = 'checksum'
    'b-extraido'    = 'extracted'
    'b-compilado'   = 'built'
    'b-orden'       = 'cero command'
    'b-comprobado'  = 'checked'
    'd-modulos'     = 'eight modules in ~\.m2 - {0} s'
    'd-responde'    = 'cero status answers'
    'e-sin-red'     = 'could not reach {0} - is there a connection?'
    'e-version'     = 'the server returned an odd version'
    'e-descarga'    = 'could not download {0}'
    'e-sin-huella'  = 'could not download the checksum'
    'e-contenido'   = 'the package did not contain {0}'
    'e-compilar'    = 'the build failed'
    'e-no-responde' = "it installed but 'cero status' does not answer"
    'e-huella-mal'  = 'the checksum does not match - the package arrived altered, not installing it.'
    'e-esperada'    = 'expected'
    'e-recibida'    = 'received'
    'e-falta'       = 'missing: {0}'
    'e-java-viejo'  = 'Cero needs Java {0} or newer - virtual threads. You have {1}.'
    'a-no-windows'  = 'you are not on Windows'
    'a-usa-shell'   = 'use the shell installer:  curl -fsSL {0}/instalar | sh'
    't-necesita'    = 'Cero needs a JDK {0} or newer and Maven.'
    't-detectado'   = 'Detected: {0} - package manager {1}'
    't-ninguno'     = 'none'
    't-reabre'      = 'Close and reopen PowerShell after installing them, so they enter the PATH.'
    'd-lema'        = 'web framework for Java'
    'f-instalador'  = 'Cero - installer'
    'f-instalado'   = 'Cero {0} installed'
    'f-sistema'     = 'system'
    'f-terminal'    = 'shell'
    'f-gestor'      = 'manager'
    'f-java'        = 'java'
    'f-maven'       = 'maven'
    'f-salida'      = 'output'
    'f-carpeta'     = 'directory'
    'f-orden'       = 'command'
    'f-ninguno'     = 'none known'
    'f-no-hay'      = 'not found'
    'f-presente'    = 'present'
    'f-color'       = 'terminal with colour'
    'f-plana'       = 'plain (no escapes)'
    'f-para-java'   = 'To get Java {0} on your system:'
    'f-abre'        = 'Open a new terminal'
    'f-abre-resto'  = 'so the PATH learns about the command'
    'f-crear'       = 'Create a project and start it:'
    'f-guia'        = 'Full guide:'
}

# Sin traduccion cae al castellano: un hueco tiene que verse raro, no quedarse en blanco.
function T([string] $clave) {
    $v = if ($Idioma -eq 'en') { $TextosEn[$clave] } else { $null }
    if (-not $v) { $v = $TextosEs[$clave] }
    if (-not $v) { $v = $clave }
    return $v
}

function Escribe([string] $t) { Write-Host $t }
function Borra { if ($Vivo) { Write-Host ("`r{0}[K" -f $e) -NoNewline } }

$script:Paso = 0
$script:Etiqueta = ''
function Barra {
    $ancho = 16
    $hechos = [int]($script:Paso * $ancho / $Total)
    ($Lleno * $hechos) + ($Vacio * ($ancho - $hechos))
}
function Paso([string] $t) {
    $script:Paso++
    $script:Etiqueta = $t
    if ($Vivo) {
        Borra
        Write-Host ("  {0}{1}{2} {3}{4}/{5}{2}  {6}" -f $Acento, (Barra), $Fin, $Tenue, $script:Paso, $Total, $t) -NoNewline
    } else {
        Write-Host ("[{0}/{1}] {2}" -f $script:Paso, $Total, $t)
    }
}
function Bien([string] $t, [string] $nota) {
    if ($Vivo) {
        Borra
        Write-Host ("  {0}{1}{2}  {3}{4}" -f $Verde, $Ok, $Fin, $t.PadRight(26), $(if ($nota) { "$Tenue$nota$Fin" }))
    } else {
        Write-Host ("        {0} {1}" -f $t, $nota)
    }
}
function Aviso([string] $t, [string] $nota) {
    Borra
    Write-Host ("  {0}!{1}  {2}{3}" -f $Acento, $Fin, $t.PadRight(26), $(if ($nota) { "$Tenue$nota$Fin" }))
}
function Muere([string] $t, [string] $registro) {
    Borra
    Write-Host ("  {0}{1}  {2}{3}" -f $Rojo, $No, $t, $Fin)
    if ($registro -and (Test-Path $registro)) {
        Write-Host ''
        Get-Content $registro -Tail 25 | ForEach-Object { Write-Host "$Tenue$_$Fin" }
    }
    exit 1
}

function Marca {
    if (-not $Vivo) { Escribe ("{0}`n" -f (T 'f-instalador')); return }
    Escribe ''
    Escribe ("        {0}.{1}   {0}|{1}   {0}.{1}" -f $Acento, $Fin)
    Escribe ("   {0}\{1}    {0}.{1}     {0}.{1}    {0}/{1}" -f $Acento, $Fin)
    Escribe (" {0}-{1}   {0}.{1}   {0}{2}###{1}   {0}.{1}   {0}-{1}      {2}Cero{1}" -f $Acento, $Fin, $Fuerte)
    Escribe ("   {0}/{1}    {0}.{1}     {0}.{1}    {0}\{1}      {2}{3}{1}" -f $Acento, $Fin, $Tenue, (T 'd-lema'))
    Escribe ("        {0}.{1}   {0}|{1}   {0}.{1}" -f $Acento, $Fin)
    Escribe ''
}

# Corre algo largo enseñando el paso y un giro. La salida va a un fichero: solo se enseña si
# falla. El finally devuelve el cursor aunque corten con Ctrl-C.
function Girando([string] $registro, [string] $orden, [string[]] $argumentos) {
    $inicio = Get-Date
    $proc = Start-Process -FilePath $orden -ArgumentList $argumentos -NoNewWindow -PassThru `
                          -RedirectStandardOutput $registro -RedirectStandardError "$registro.err"
    try {
        if (-not $Vivo) {
            $proc.WaitForExit()
        } else {
            Write-Host ("{0}[?25l" -f $e) -NoNewline
            $i = 0
            while (-not $proc.HasExited) {
                $s = [int]((Get-Date) - $inicio).TotalSeconds
                Write-Host ("`r{0}[K  {1}{2}{3} {4}{5}/{6}{3}  {7} {1}{8}{3} {4}{9}s{3}" -f `
                            $e, $Acento, (Barra), $Fin, $Tenue, $script:Paso, $Total, `
                            $script:Etiqueta, $Giros[$i % $Giros.Length], $s) -NoNewline
                $i++
                Start-Sleep -Milliseconds 90
            }
        }
    } finally {
        if ($Vivo) { Write-Host ("{0}[?25h" -f $e) -NoNewline }
        if (-not $proc.HasExited) { $proc.Kill() }
    }
    $script:Segundos = [int]((Get-Date) - $inicio).TotalSeconds
    if (Test-Path "$registro.err") { Get-Content "$registro.err" | Add-Content $registro }
    return $proc.ExitCode
}

# ─── solo detectar ──────────────────────────────────────────────────────────────────────
if ($Detectar) {
    Marca
    $jv = VersionJava
    Escribe ("  {0} {1}" -f (T 'f-sistema').PadRight(14), $DetalleSo)
    Escribe ("  {0} {1}" -f (T 'f-terminal').PadRight(14), $Interprete)
    Escribe ("  {0} {1}" -f (T 'f-gestor').PadRight(14), $(if ($Gestor) { $Gestor } else { T 'f-ninguno' }))
    Escribe ("  {0} {1}" -f (T 'f-java').PadRight(14), $(if ($jv) { $jv } else { T 'f-no-hay' }))
    Escribe ("  {0} {1}" -f (T 'f-maven').PadRight(14), $(if (Donde 'mvn') { T 'f-presente' } else { T 'f-no-hay' }))
    Escribe ("  {0} {1}" -f (T 'f-salida').PadRight(14), $(if ($Vivo) { T 'f-color' } else { T 'f-plana' }))
    if ($jv -lt $JavaMinimo) {
        Escribe ''
        Escribe ("  " + ((T 'f-para-java') -f $JavaMinimo))
        Escribe ''
        Escribe ("      {0}" -f (OrdenJava))
        Escribe ''
    }
    exit 0
}

# ─── 1 · lo que hace falta ──────────────────────────────────────────────────────────────
Marca
Paso (T 'p-entorno')

if (-not $EnWindows) {
    Aviso (T 'a-no-windows') ((T 'a-usa-shell') -f $Base)
}

$falta = @('java', 'mvn') | Where-Object { -not (Donde $_) }
if ($falta) {
    Borra
    Write-Host ("  {0}{1}  {2}{3}" -f $Rojo, $No, ((T 'e-falta') -f ($falta -join ' ')), $Fin)
    Escribe ''
    Escribe ("  {0}{1}{2}" -f $Fuerte, ((T 't-necesita') -f $JavaMinimo), $Fin)
    Escribe ("  {0}{1}{2}" -f $Tenue, ((T 't-detectado') -f $DetalleSo, $(if ($Gestor) { $Gestor } else { T 't-ninguno' })), $Fin)
    Escribe ''
    Escribe ("      {0}{1}{2}" -f $Fuerte, (OrdenJava), $Fin)
    Escribe ''
    Escribe ("  {0}{1}{2}" -f $Tenue, (T 't-reabre'), $Fin)
    exit 1
}

$javaV = VersionJava
if ($javaV -lt $JavaMinimo) {
    Borra
    Write-Host ("  {0}{1}  {2}{3}" -f $Rojo, $No, ((T 'e-java-viejo') -f $JavaMinimo, $javaV), $Fin)
    Escribe ''
    Escribe ("      {0}{1}{2}" -f $Fuerte, (OrdenJava), $Fin)
    Escribe ''
    exit 1
}
$mavenV = ((& cmd.exe /c 'mvn -v' 2>$null) | Select-Object -First 1)
Bien (T 'b-entorno') "$DetalleSo - Java $javaV"

# ─── 2 · qué versión ────────────────────────────────────────────────────────────────────
Paso (T 'p-version')
try { $version = (Invoke-RestMethod -Uri "$Base/version" -TimeoutSec 20).ToString().Trim() }
catch { Muere ((T 'e-sin-red') -f $Base) }
if ($version -notmatch '^[0-9][0-9.]*$') { Muere ("{0}: '{1}'" -f (T 'e-version'), $version) }
Bien (T 'b-version') "Cero $version"

# ─── 3 · bajarlo ────────────────────────────────────────────────────────────────────────
$paquete = "cero-$version.zip"
$tmp = Join-Path ([IO.Path]::GetTempPath()) ("cero-" + [Guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $tmp -Force | Out-Null
$zip = Join-Path $tmp $paquete

Paso (T 'p-bajando')
try { Invoke-WebRequest -Uri "$Base/estaticos/$paquete" -OutFile $zip -TimeoutSec 300 }
catch { Muere ((T 'e-descarga') -f "$Base/estaticos/$paquete") }
Bien (T 'b-descargado') ("{0} - {1} KB" -f $paquete, [int]((Get-Item $zip).Length / 1KB))

# ─── 4 · comprobar la huella ────────────────────────────────────────────────────────────
Paso (T 'p-huella')
try { $esperada = ((Invoke-RestMethod -Uri "$Base/estaticos/$paquete.sha256" -TimeoutSec 20) -split '\s+')[0] }
catch { Muere (T 'e-sin-huella') }
$real = (Get-FileHash -Path $zip -Algorithm SHA256).Hash.ToLower()
if ($real -ne $esperada.ToLower()) {
    Muere ("{0}`n      {1}  {2}`n      {3}  {4}" -f (T 'e-huella-mal'), (T 'e-esperada'), $esperada, (T 'e-recibida'), $real)
}
Bien (T 'b-huella') "sha256 $($real.Substring(0,16))..."

# ─── 5 · extraer ────────────────────────────────────────────────────────────────────────
Paso (T 'p-extrayendo')
$destino = Join-Path $Raiz "cero-$version"
if (Test-Path $destino) { Remove-Item $destino -Recurse -Force }
New-Item -ItemType Directory -Path $Raiz -Force | Out-Null
Expand-Archive -Path $zip -DestinationPath $Raiz -Force
if (-not (Test-Path $destino)) { Muere ((T 'e-contenido') -f "cero-$version") }
Bien (T 'b-extraido') $destino

# ─── 6 · compilar ───────────────────────────────────────────────────────────────────────
Paso $(if ($ConPruebas) { T 'p-compilando-t' } else { T 'p-compilando' })
$pom = Join-Path $destino 'java\pom.xml'
$mvnArgs = @('-B', '-q', '-f', $pom, 'install')
if (-not $ConPruebas) { $mvnArgs += '-DskipTests' }
$registro = Join-Path $tmp 'mvn.log'
# mvn en Windows es un .cmd, asi que va por cmd.exe
$codigo = Girando $registro 'cmd.exe' (@('/c', 'mvn') + $mvnArgs)
if ($codigo -ne 0) { Muere (T 'e-compilar') $registro }
Bien (T 'b-compilado') ((T 'd-modulos') -f $script:Segundos)

# ─── 7 · dejar la orden a mano ──────────────────────────────────────────────────────────
Paso (T 'p-instalando')
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
Bien (T 'b-orden') (Join-Path $Bin 'cero.cmd')

# ─── 8 · comprobar que sirve ────────────────────────────────────────────────────────────
Paso (T 'p-comprobando')
& cmd.exe /c "`"$Bin\cero.cmd`" estado" *> $null
if ($LASTEXITCODE -ne 0) { Muere (T 'e-no-responde') }
Bien (T 'b-comprobado') (T 'd-responde')

# ─── resumen ────────────────────────────────────────────────────────────────────────────
Escribe ''
Escribe ("  {0}{1}{2}{3}" -f $Verde, $Fuerte, ((T 'f-instalado') -f $version), $Fin)
Escribe ''
Escribe ("  {0} {1}" -f (T 'f-sistema').PadRight(12), $DetalleSo)
Escribe ("  {0} {1}" -f (T 'f-java').PadRight(12), "$javaV - $mavenV")
Escribe ("  {0} {1}" -f (T 'f-carpeta').PadRight(12), $destino)
Escribe ("  {0} {1}" -f (T 'f-orden').PadRight(12), (Join-Path $Bin 'cero.cmd'))
Escribe ''
if ($script:PathTocado) {
    Escribe ("  {0}{1}{2} {3} {4}cero{2}." -f $Acento, (T 'f-abre'), $Fin, (T 'f-abre-resto'), $Fuerte)
    Escribe ''
}
Escribe ("  {0}{1}{2}" -f $Tenue, (T 'f-crear'), $Fin)
Escribe ''
Escribe "      ${Fuerte}cero new mi-app${Fin}"
Escribe "      ${Fuerte}cd mi-app && mvn -q package && java -Xmx64m -jar target\mi-app.jar${Fin}"
Escribe ''
Escribe ("  {0}{1}{2}  {3}/empezar" -f $Tenue, (T 'f-guia'), $Fin, $Base)
Escribe ''

Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
