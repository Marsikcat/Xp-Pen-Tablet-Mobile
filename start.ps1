# Xp Pen Tablet Mobile (неофициальный проект, не связан с XP-Pen). Запуск драйвера на телефоне через ADB (по Wi-Fi или USB).
#   start.bat                 — найти телефон и запустить драйвер
#   start.bat 192.168.1.50:37123 — подключиться по адресу из «Отладка по Wi-Fi»
#   stop.bat                  — остановить драйвер
param(
    [string]$Address,
    [switch]$Stop,
    [switch]$Install
)

[Console]::OutputEncoding = [Text.Encoding]::UTF8
$pkg = 'com.neon.xppen'

function Find-Adb {
    $candidates = @(
        (Join-Path $PSScriptRoot 'platform-tools\adb.exe'),
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe')
    )
    foreach ($c in $candidates) { if (Test-Path $c) { return $c } }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}

$adb = Find-Adb
if (-not $adb) {
    Write-Host 'Не найден adb. Положи папку platform-tools рядом с этим скриптом или установи Android SDK Platform-Tools.' -ForegroundColor Red
    exit 1
}

& $adb start-server 2>$null | Out-Null

function Get-Devices {
    (& $adb devices) | Select-Object -Skip 1 | Where-Object { $_ -match "`tdevice$" } | ForEach-Object { ($_ -split "`t")[0] }
}

if ($Address) { & $adb connect $Address | Out-Host }

$devices = @(Get-Devices)
if ($devices.Count -eq 0) {
    Write-Host 'Ищу телефон по Wi-Fi...'
    for ($i = 0; $i -lt 10 -and $devices.Count -eq 0; $i++) {
        foreach ($line in (& $adb mdns services)) {
            if ($line -match '_adb-tls-connect' -and $line -match '(\d+\.\d+\.\d+\.\d+:\d+)') {
                & $adb connect $Matches[1] | Out-Null
            }
        }
        Start-Sleep -Seconds 1
        $devices = @(Get-Devices)
    }
}
if ($devices.Count -eq 0) {
    Write-Host ''
    Write-Host 'Телефон не найден.' -ForegroundColor Yellow
    Write-Host '1. Телефон и ПК должны быть в одной сети Wi-Fi.'
    Write-Host '2. На телефоне: Настройки -> Параметры разработчика -> Отладка по Wi-Fi -> включить.'
    Write-Host '3. Если телефон ещё ни разу не подключался к этому ПК: там же «Подключить устройство с помощью кода»,'
    Write-Host "   затем выполни: `"$adb`" pair IP:ПОРТ  и введи код."
    $addr = Read-Host 'Или введи «IP-адрес и порт» с экрана «Отладка по Wi-Fi» (Enter — выход)'
    if ($addr) {
        & $adb connect $addr | Out-Host
        $devices = @(Get-Devices)
    }
    if ($devices.Count -eq 0) { exit 1 }
}

$serial = $devices[0]
$model = (& $adb -s $serial shell getprop ro.product.model) -join ''
Write-Host "Телефон: $model ($serial)"

if ($Stop) {
    & $adb -s $serial shell 'kill $(pidof g640_helper) 2>/dev/null; sleep 0.5; if pidof g640_helper >/dev/null; then echo FAIL; else echo OK; fi' | Out-Null
    Write-Host 'Драйвер остановлен. Планшет снова работает как без драйвера.' -ForegroundColor Green
    exit 0
}

$apkOnPhone = (& $adb -s $serial shell pm path $pkg) -join ''
if ($Install -or -not $apkOnPhone) {
    $apk = Join-Path $PSScriptRoot 'XpPenTabletMobile.apk'
    if (-not (Test-Path $apk)) {
        Write-Host "Приложение не установлено, и рядом нет XpPenTabletMobile.apk." -ForegroundColor Red
        exit 1
    }
    Write-Host 'Устанавливаю приложение...'
    & $adb -s $serial install -r $apk | Out-Host
}

Write-Host 'Запускаю драйвер...'
$cmd = 'APK=$(pm path com.neon.xppen | head -n 1 | cut -d: -f2); ' +
    'kill $(pidof g640_helper) 2>/dev/null; sleep 0.5; ' +
    'CLASSPATH=$APK setsid nohup app_process /system/bin --nice-name=g640_helper com.neon.xppen.helper.HelperMain >/dev/null 2>&1 </dev/null & ' +
    'sleep 1.5; if pidof g640_helper >/dev/null; then echo OK; else echo FAIL; tail -n 20 /data/local/tmp/g640_helper.log; fi'
$result = & $adb -s $serial shell $cmd
if (($result -join "`n") -match '^OK') {
    Write-Host 'Готово: драйвер запущен. Компьютер больше не нужен — драйвер работает до перезагрузки телефона.' -ForegroundColor Green
} else {
    Write-Host 'Драйвер не запустился:' -ForegroundColor Red
    $result | Out-Host
    exit 1
}
