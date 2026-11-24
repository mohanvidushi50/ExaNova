$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($env:MYSQL_USER)) {
    $mysqlUser = Read-Host 'MySQL username [root]'
    $env:MYSQL_USER = if ([string]::IsNullOrWhiteSpace($mysqlUser)) { 'root' } else { $mysqlUser.Trim() }
}

$mysqlSecure = Read-Host 'MySQL password (input is hidden)' -AsSecureString
$env:MYSQL_PASSWORD = [System.Net.NetworkCredential]::new('', $mysqlSecure).Password

if ([string]::IsNullOrWhiteSpace($env:EVALIO_ADMIN_USERNAME)) {
    $adminName = Read-Host 'Optional first admin username [Enter to skip]'
    if (-not [string]::IsNullOrWhiteSpace($adminName)) {
        $adminSecure = Read-Host 'First admin password (input is hidden)' -AsSecureString
        $adminPassword = [System.Net.NetworkCredential]::new('', $adminSecure).Password
        if ($adminPassword.Length -lt 8) { throw 'The initial admin password must have at least 8 characters.' }
        $env:EVALIO_ADMIN_USERNAME = $adminName.Trim()
        $env:EVALIO_ADMIN_PASSWORD = $adminPassword
    }
}

try {
    & (Join-Path $PSScriptRoot 'run.bat')
    exit $LASTEXITCODE
}
finally {
    $env:MYSQL_PASSWORD = $null
    $env:EVALIO_ADMIN_PASSWORD = $null
}
