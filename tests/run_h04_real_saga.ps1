$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$composeFile = Join-Path $PSScriptRoot 'integration\h04-isolated.compose.yml'
$projectName = 'h04-saga-' + [guid]::NewGuid().ToString('N').Substring(0, 12)
$databaseName = 'h04_users_saga'
$oldEnvironment = @{}
$environmentNames = @(
    'KAFKA_HOST_PORT', 'H04_POSTGRES_PASSWORD', 'H04_COMPOSE_PROJECT', 'H04_COMPOSE_FILE',
    'H04_DATABASE_NAME', 'H04_POSTGRES_PORT', 'H04_REDIS_PORT', 'H04_KAFKA_BOOTSTRAP',
    'H04_USERS_JAR', 'H04_PARTIDAS_ROOT', 'H04_GROUP_SUFFIX', 'PYTHONPATH',
    'PYTHONDONTWRITEBYTECODE', 'DB_URL', 'DB_USERNAME', 'DB_PASSWORD',
    'KAFKA_BOOTSTRAP_SERVERS', 'KAFKA_GROUP_ID_USUARIOS', 'KAFKA_GROUP_ID_USUARIOS_DLQ',
    'SPRING_PROFILES_ACTIVE', 'USERS_FLYWAY_FRESH_DATABASE_NAME',
    'USERS_FLYWAY_FRESH_CONFIRMATION', 'METRICS_SCRAPE_USERNAME',
    'METRICS_SCRAPE_PASSWORD', 'GATEWAY_SECRET', 'INTERNAL_API_TOKEN',
    'EUREKA_CLIENT_ENABLED', 'EUREKA_CLIENT_REGISTER_WITH_EUREKA',
    'EUREKA_CLIENT_FETCH_REGISTRY', 'SERVER_ADDRESS', 'SERVER_PORT',
    'MATCH_SAGA_OUTBOX_INITIAL_DELAY_MS', 'MATCH_SAGA_OUTBOX_POLL_INTERVAL_MS',
    'MATCH_SAGA_OUTBOX_BATCH_SIZE', 'MATCH_SAGA_OUTBOX_LEASE_SECONDS',
    'MATCH_SAGA_OUTBOX_ACK_TIMEOUT_MS', 'KAFKA_POC_PING_ON_START',
    'REDIS_HOST', 'REDIS_PORT', 'KAFKA_GROUP_ID_PARTIDAS_SAGA', 'H04_SCENARIOS'
)
$composeStarted = $false
$sharedBefore = @()
$resultCode = 1

foreach ($name in $environmentNames) {
    $oldEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

function Get-FreeLoopbackPort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try {
        return ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
    } finally {
        $listener.Stop()
    }
}

try {
    $usersBranch = (& git -c safe.directory=C:/dev/TheDeathDoku/users_service -C C:/dev/TheDeathDoku/users_service branch --show-current).Trim()
    if ($usersBranch -ne 'fix/h04-users-transactional-outbox') {
        throw 'users_service is not on the approved H04 branch.'
    }
    $partidasBranch = (& git -c safe.directory=C:/dev/TheDeathDoku/partidas-service -C C:/dev/TheDeathDoku/partidas-service branch --show-current).Trim()
    if ($partidasBranch -ne 'main') {
        throw 'partidas-service is not on the expected main branch.'
    }

    $env:KAFKA_HOST_PORT = [string](Get-FreeLoopbackPort)
    $env:H04_POSTGRES_PASSWORD = [guid]::NewGuid().ToString('N')
    $env:H04_COMPOSE_PROJECT = $projectName
    $env:H04_COMPOSE_FILE = $composeFile
    $env:H04_DATABASE_NAME = $databaseName
    $env:H04_GROUP_SUFFIX = [guid]::NewGuid().ToString('N').Substring(0, 10)
    $env:H04_PARTIDAS_ROOT = (Resolve-Path (Join-Path $repoRoot '..\partidas-service')).Path
    $env:PYTHONPATH = $env:H04_PARTIDAS_ROOT
    $env:PYTHONDONTWRITEBYTECODE = '1'

    $sharedBefore = @(& docker ps -q)
    if ($LASTEXITCODE -ne 0) { throw 'Could not inventory Docker before the isolated test.' }

    $composeStarted = $true
    & docker compose -p $projectName -f $composeFile up -d --wait
    if ($LASTEXITCODE -ne 0) { throw 'Disposable Kafka/PostgreSQL/Redis did not become healthy.' }

    $postgresMapping = (& docker compose -p $projectName -f $composeFile port postgres 5432).Trim()
    $redisMapping = (& docker compose -p $projectName -f $composeFile port redis 6379).Trim()
    if ($LASTEXITCODE -ne 0 -or $postgresMapping -notmatch '^127\.0\.0\.1:' -or $redisMapping -notmatch '^127\.0\.0\.1:') {
        throw 'Disposable data services are not bound exclusively to loopback.'
    }
    $env:H04_POSTGRES_PORT = [string](($postgresMapping -split ':')[-1])
    $env:H04_REDIS_PORT = [string](($redisMapping -split ':')[-1])
    $env:H04_KAFKA_BOOTSTRAP = "127.0.0.1:$env:KAFKA_HOST_PORT"

    foreach ($topic in @('matches.finished.v1', 'matches.finished.dlq', 'matches.saga.confirmed.v1')) {
        & docker compose -p $projectName -f $composeFile exec -T kafka `
            /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --create --if-not-exists `
            --topic $topic --partitions 1 --replication-factor 1
        if ($LASTEXITCODE -ne 0) { throw 'Could not create an isolated saga topic.' }
    }

    Push-Location $repoRoot
    try {
        & mvn -q -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw 'Could not package users_service for the isolated run.' }
    } finally {
        Pop-Location
    }

    $env:H04_USERS_JAR = Join-Path $repoRoot 'target\users_service-0.0.1-SNAPSHOT.jar'
    $env:DB_URL = "jdbc:postgresql://127.0.0.1:$env:H04_POSTGRES_PORT/$databaseName"
    $env:DB_USERNAME = 'postgres'
    $env:DB_PASSWORD = $env:H04_POSTGRES_PASSWORD
    $env:KAFKA_BOOTSTRAP_SERVERS = $env:H04_KAFKA_BOOTSTRAP
    $env:KAFKA_GROUP_ID_USUARIOS = "h04-users-$env:H04_GROUP_SUFFIX"
    $env:KAFKA_GROUP_ID_USUARIOS_DLQ = "h04-users-dlq-$env:H04_GROUP_SUFFIX"
    $env:SPRING_PROFILES_ACTIVE = 'flyway-fresh'
    $env:USERS_FLYWAY_FRESH_DATABASE_NAME = $databaseName
    $env:USERS_FLYWAY_FRESH_CONFIRMATION = "INITIALIZE_FRESH_DATABASE:$databaseName"
    $env:METRICS_SCRAPE_USERNAME = 'h04-test'
    $env:METRICS_SCRAPE_PASSWORD = [guid]::NewGuid().ToString('N')
    $env:GATEWAY_SECRET = [guid]::NewGuid().ToString('N')
    $env:INTERNAL_API_TOKEN = [guid]::NewGuid().ToString('N')
    $env:EUREKA_CLIENT_ENABLED = 'false'
    $env:EUREKA_CLIENT_REGISTER_WITH_EUREKA = 'false'
    $env:EUREKA_CLIENT_FETCH_REGISTRY = 'false'
    $env:SERVER_ADDRESS = '127.0.0.1'
    $env:SERVER_PORT = '0'
    $env:MATCH_SAGA_OUTBOX_INITIAL_DELAY_MS = '300000'
    $env:MATCH_SAGA_OUTBOX_POLL_INTERVAL_MS = '1000'
    $env:MATCH_SAGA_OUTBOX_BATCH_SIZE = '1'
    $env:MATCH_SAGA_OUTBOX_LEASE_SECONDS = '3'
    $env:MATCH_SAGA_OUTBOX_ACK_TIMEOUT_MS = '1000'
    $env:KAFKA_POC_PING_ON_START = 'false'
    $env:REDIS_HOST = '127.0.0.1'
    $env:REDIS_PORT = $env:H04_REDIS_PORT
    $env:KAFKA_GROUP_ID_PARTIDAS_SAGA = "h04-partidas-$env:H04_GROUP_SUFFIX"

    $python = Join-Path $env:H04_PARTIDAS_ROOT '.venv\Scripts\python.exe'
    if (-not (Test-Path -LiteralPath $python)) {
        throw 'The partidas-service Python virtual environment is required for the real consumer test.'
    }
    & $python (Join-Path $PSScriptRoot 'integration\h04_real_saga_harness.py')
    $resultCode = $LASTEXITCODE
} catch {
    Write-Error $_
    $resultCode = 1
} finally {
    if ($composeStarted) {
        & docker compose -p $projectName -f $composeFile down --remove-orphans
        if ($LASTEXITCODE -ne 0) {
            Write-Warning "Cleanup failed for isolated project $projectName; only inspect that named project."
            $resultCode = 1
        }
        $remaining = @(& docker ps -aq --filter "label=com.docker.compose.project=$projectName")
        if ($remaining.Count -gt 0) {
            Write-Warning "Disposable containers remain for isolated project $projectName."
            $resultCode = 1
        }
        $sharedAfter = @(& docker ps -q)
        $beforeSet = @($sharedBefore | Sort-Object)
        $afterSet = @($sharedAfter | Sort-Object)
        if (Compare-Object $beforeSet $afterSet) {
            Write-Warning 'Docker container inventory differs from before the run; review only the isolated project and pre-run snapshot.'
            $resultCode = 1
        }
    }
    foreach ($name in $environmentNames) {
        [Environment]::SetEnvironmentVariable($name, $oldEnvironment[$name], 'Process')
    }
}

exit $resultCode
