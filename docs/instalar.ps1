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
    if (-not $Vivo) { Escribe "Cero - instalador`n"; return }
    Escribe ''
    Escribe ("        {0}.{1}   {0}|{1}   {0}.{1}" -f $Acento, $Fin)
    Escribe ("   {0}\{1}    {0}.{1}     {0}.{1}    {0}/{1}" -f $Acento, $Fin)
    Escribe (" {0}-{1}   {0}.{1}   {0}{2}###{1}   {0}.{1}   {0}-{1}      {2}Cero{1}" -f $Acento, $Fin, $Fuerte)
    Escribe ("   {0}/{1}    {0}.{1}     {0}.{1}    {0}\{1}      {2}framework web para Java{1}" -f $Acento, $Fin, $Tenue)
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
    Escribe ("  {0} {1}" -f 'sistema '.PadRight(14), $DetalleSo)
    Escribe ("  {0} {1}" -f 'terminal'.PadRight(14), $Interprete)
    Escribe ("  {0} {1}" -f 'gestor  '.PadRight(14), $(if ($Gestor) { $Gestor } else { 'ninguno conocido' }))
    Escribe ("  {0} {1}" -f 'java    '.PadRight(14), $(if ($jv) { $jv } else { 'no encontrado' }))
    Escribe ("  {0} {1}" -f 'maven   '.PadRight(14), $(if (Donde 'mvn') { 'presente' } else { 'no encontrado' }))
    Escribe ("  {0} {1}" -f 'salida  '.PadRight(14), $(if ($Vivo) { 'terminal con color' } else { 'plana (sin escapes)' }))
    if ($jv -lt $JavaMinimo) {
        Escribe ''
        Escribe "  Para tener Java $JavaMinimo en tu sistema:"
        Escribe ''
        Escribe ("      {0}" -f (OrdenJava))
        Escribe ''
    }
    exit 0
}

# ─── 1 · lo que hace falta ──────────────────────────────────────────────────────────────
Marca
Paso 'comprobando el entorno'

if (-not $EnWindows) {
    Aviso 'no estás en Windows' "usa el instalador de shell:  curl -fsSL $Base/instalar | sh"
}

$falta = @('java', 'mvn') | Where-Object { -not (Donde $_) }
if ($falta) {
    Borra
    Write-Host ("  {0}{1}  falta: {2}{3}" -f $Rojo, $No, ($falta -join ' '), $Fin)
    Escribe ''
    Escribe "  Cero necesita un ${Fuerte}JDK $JavaMinimo${Fin} o superior y ${Fuerte}Maven${Fin}."
    Escribe "  ${Tenue}Detectado: $DetalleSo - gestor $(if ($Gestor) { $Gestor } else { 'ninguno' })${Fin}"
    Escribe ''
    Escribe ("      {0}{1}{2}" -f $Fuerte, (OrdenJava), $Fin)
    Escribe ''
    Escribe "  ${Tenue}Cierra y abre PowerShell despues de instalarlos, para que entren en el PATH.${Fin}"
    exit 1
}

$javaV = VersionJava
if ($javaV -lt $JavaMinimo) {
    Borra
    Write-Host ("  {0}{1}  Cero necesita Java $JavaMinimo o superior - hilos virtuales. Tienes $javaV.{2}" -f $Rojo, $No, $Fin)
    Escribe ''
    Escribe ("      {0}{1}{2}" -f $Fuerte, (OrdenJava), $Fin)
    Escribe ''
    exit 1
}
$mavenV = ((& cmd.exe /c 'mvn -v' 2>$null) | Select-Object -First 1)
Bien 'entorno' "$DetalleSo - Java $javaV"

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
Paso $(if ($ConPruebas) { 'compilando los ocho modulos y corriendo las pruebas' } else { 'compilando los ocho modulos' })
$pom = Join-Path $destino 'java\pom.xml'
$mvnArgs = @('-B', '-q', '-f', $pom, 'install')
if (-not $ConPruebas) { $mvnArgs += '-DskipTests' }
$registro = Join-Path $tmp 'mvn.log'
# mvn en Windows es un .cmd, asi que va por cmd.exe
$codigo = Girando $registro 'cmd.exe' (@('/c', 'mvn') + $mvnArgs)
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

# ─── resumen ────────────────────────────────────────────────────────────────────────────
Escribe ''
Escribe "  ${Verde}${Fuerte}Cero $version instalado${Fin}"
Escribe ''
Escribe ("  {0} {1}" -f 'sistema '.PadRight(12), $DetalleSo)
Escribe ("  {0} {1}" -f 'java    '.PadRight(12), "$javaV - $mavenV")
Escribe ("  {0} {1}" -f 'carpeta '.PadRight(12), $destino)
Escribe ("  {0} {1}" -f 'orden   '.PadRight(12), (Join-Path $Bin 'cero.cmd'))
Escribe ''
if ($script:PathTocado) {
    Escribe "  ${Acento}Abre una terminal nueva${Fin} para que el PATH se entere de la orden ${Fuerte}cero${Fin}."
    Escribe ''
}
Escribe "  ${Tenue}Crear un proyecto y arrancarlo:${Fin}"
Escribe ''
Escribe "      ${Fuerte}cero new mi-app${Fin}"
Escribe "      ${Fuerte}cd mi-app && mvn -q package && java -Xmx64m -jar target\mi-app.jar${Fin}"
Escribe ''
Escribe "  ${Tenue}Guia completa:${Fin}  $Base/empezar"
Escribe ''

Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
