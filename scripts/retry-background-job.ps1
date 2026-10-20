[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)]
    [ValidatePattern('^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')]
    [string]$JobId,
    [Parameter(Mandatory=$true)]
    [ValidatePattern('^[A-Z][A-Z0-9_-]{2,63}$')]
    [string]$Reason,
    [Parameter(Mandatory=$true)]
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$')]
    [string]$Operator,
    [Parameter(Mandatory=$true)]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Leaf })]
    [string]$ArtifactPath,
    [Parameter(Mandatory=$true)]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Leaf })]
    [string]$JavaPath
)
$ErrorActionPreference = 'Stop'
# Run in the operator's deployment environment. No development target or credentials are inferred.
# Required environment: SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME, SPRING_DATASOURCE_PASSWORD.
# Media additionally requires APP_CLOUDINARY_CLOUD_NAME (the configured public cloud name).
foreach ($name in @('SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME','SPRING_DATASOURCE_PASSWORD')) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
        throw "Missing deployment setting: $name"
    }
}
& $JavaPath '-Dloader.main=com.learne.backend.jobs.maintenance.JobMaintenanceApplication' '-cp' $ArtifactPath `
    'org.springframework.boot.loader.launch.PropertiesLauncher' `
    "--app.jobs.maintenance.retry-job=$JobId" "--app.jobs.maintenance.reason=$Reason" `
    "--app.jobs.maintenance.operator=$Operator"
exit $LASTEXITCODE
