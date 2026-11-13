$ErrorActionPreference = 'Stop'

$containerName = 'users-migration-test-' + [guid]::NewGuid().ToString('N')
$oldJdbcUrl = $env:MIGRATION_TEST_JDBC_URL
$oldDbUser = $env:MIGRATION_TEST_DB_USER
$oldDbPassword = $env:MIGRATION_TEST_DB_PASSWORD
$oldInstanceMarker = $env:MIGRATION_TEST_INSTANCE_MARKER
$oldMigrationGatewaySecret = $env:MIGRATION_TEST_GATEWAY_SECRET
$oldMigrationInternalToken = $env:MIGRATION_TEST_INTERNAL_TOKEN
$oldDbUrl = $env:DB_URL
$oldDbUsername = $env:DB_USERNAME
$oldDbPasswordApp = $env:DB_PASSWORD
$oldGatewaySecret = $env:GATEWAY_SECRET
$oldInternalToken = $env:INTERNAL_API_TOKEN
$oldEurekaEnabled = $env:EUREKA_CLIENT_ENABLED
$oldEurekaRegister = $env:EUREKA_CLIENT_REGISTER_WITH_EUREKA
$oldEurekaFetch = $env:EUREKA_CLIENT_FETCH_REGISTRY
$oldServerPort = $env:SERVER_PORT
$oldJavaHome = $env:JAVA_HOME
$oldPath = $env:PATH
$dockerAvailable = [bool](Get-Command docker -ErrorAction SilentlyContinue)
$containerCreated = $false
$mavenCommand = Get-Command mvn -ErrorAction SilentlyContinue
$exitCode = 1

try {
    if (-not $dockerAvailable) {
        throw 'Docker CLI is required to run isolated PostgreSQL migration tests.'
    }
    if (-not $env:JAVA_HOME) {
        $javaHome = Get-ChildItem (Join-Path $env:ProgramFiles 'Java') -Directory -Filter 'jdk-21*' `
            -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1
        if ($javaHome) {
            $env:JAVA_HOME = $javaHome.FullName
            $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
        }
    }
    if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        throw 'Java 21 is required to run migration tests.'
    }
    if (-not $mavenCommand -and -not (Test-Path (Join-Path (Split-Path -Parent $PSScriptRoot) 'mvnw.cmd'))) {
        throw 'Maven or the repository Maven wrapper is required to run migration tests.'
    }

    & docker run --detach --name $containerName --publish '127.0.0.1::5432' `
        --mount 'type=tmpfs,destination=/var/lib/postgresql/data,tmpfs-size=1073741824' `
        --env POSTGRES_DB=migration_test `
        --env POSTGRES_USER=postgres `
        --env POSTGRES_HOST_AUTH_METHOD=trust `
        postgres:16.15-alpine
    $containerCreated = $LASTEXITCODE -eq 0
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not start the disposable PostgreSQL 16.15 container.'
    }

    $containerPort = $null
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        $portMapping = & docker port $containerName 5432/tcp 2>$null
        if ($LASTEXITCODE -eq 0 -and $portMapping) {
            $containerPort = [int](($portMapping | Select-Object -First 1) -split ':')[-1]
        }
        $ready = & docker exec $containerName pg_isready -U postgres -d migration_test 2>$null
        if ($LASTEXITCODE -eq 0 -and $containerPort) {
            break
        }
        Start-Sleep -Seconds 1
    }
    if (-not $containerPort -or $LASTEXITCODE -ne 0) {
        throw 'PostgreSQL did not become ready within 60 seconds.'
    }

    $instanceMarker = [guid]::NewGuid().ToString('N')
    & docker exec $containerName psql -U postgres -d migration_test -c `
        "COMMENT ON DATABASE migration_test IS '$instanceMarker'" | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not mark the disposable PostgreSQL database for test isolation.'
    }

    $env:MIGRATION_TEST_JDBC_URL = "jdbc:postgresql://127.0.0.1:$containerPort/migration_test"
    $env:MIGRATION_TEST_DB_USER = 'postgres'
    $env:MIGRATION_TEST_DB_PASSWORD = ''
    $env:MIGRATION_TEST_INSTANCE_MARKER = $instanceMarker
    $env:MIGRATION_TEST_GATEWAY_SECRET = [guid]::NewGuid().ToString('N')
    $env:MIGRATION_TEST_INTERNAL_TOKEN = [guid]::NewGuid().ToString('N')
    $env:DB_URL = $env:MIGRATION_TEST_JDBC_URL
    $env:DB_USERNAME = 'postgres'
    $env:DB_PASSWORD = ''
    $env:GATEWAY_SECRET = $env:MIGRATION_TEST_GATEWAY_SECRET
    $env:INTERNAL_API_TOKEN = $env:MIGRATION_TEST_INTERNAL_TOKEN
    $env:EUREKA_CLIENT_ENABLED = 'false'
    $env:EUREKA_CLIENT_REGISTER_WITH_EUREKA = 'false'
    $env:EUREKA_CLIENT_FETCH_REGISTRY = 'false'
    $env:SERVER_PORT = '0'

    Push-Location (Split-Path -Parent $PSScriptRoot)
    try {
        if ($mavenCommand) {
            & $mavenCommand.Source test
        } else {
            & .\mvnw.cmd test
        }
        $exitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
} catch {
    Write-Error $_
    $exitCode = 1
} finally {
    $env:MIGRATION_TEST_JDBC_URL = $oldJdbcUrl
    $env:MIGRATION_TEST_DB_USER = $oldDbUser
    $env:MIGRATION_TEST_DB_PASSWORD = $oldDbPassword
    $env:MIGRATION_TEST_INSTANCE_MARKER = $oldInstanceMarker
    $env:MIGRATION_TEST_GATEWAY_SECRET = $oldMigrationGatewaySecret
    $env:MIGRATION_TEST_INTERNAL_TOKEN = $oldMigrationInternalToken
    $env:DB_URL = $oldDbUrl
    $env:DB_USERNAME = $oldDbUsername
    $env:DB_PASSWORD = $oldDbPasswordApp
    $env:GATEWAY_SECRET = $oldGatewaySecret
    $env:INTERNAL_API_TOKEN = $oldInternalToken
    $env:EUREKA_CLIENT_ENABLED = $oldEurekaEnabled
    $env:EUREKA_CLIENT_REGISTER_WITH_EUREKA = $oldEurekaRegister
    $env:EUREKA_CLIENT_FETCH_REGISTRY = $oldEurekaFetch
    $env:SERVER_PORT = $oldServerPort
    $env:JAVA_HOME = $oldJavaHome
    $env:PATH = $oldPath
    if ($dockerAvailable -and $containerCreated) {
        try {
            & docker rm --force $containerName 2>$null | Out-Null
        } catch {
            Write-Warning 'Could not remove the disposable test container; remove it manually by its generated name.'
        }
    }
}

exit $exitCode
