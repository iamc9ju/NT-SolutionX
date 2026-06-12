# PowerShell script to load environment variables from .env and run wildfly
if (Test-Path ".env") {
    Write-Host "Loading environment variables from .env file..." -ForegroundColor Cyan
    Get-Content .env | ForEach-Object {
        $line = $_.Trim()
        # Skip empty lines and comments
        if ($line -and -not $line.StartsWith("#")) {
            $key, $value = $line.Split("=", 2)
            if ($key -and $value) {
                $key = $key.Trim()
                $value = $value.Trim()
                [System.Environment]::SetEnvironmentVariable($key, $value, "Process")
                Write-Host "Set environment variable: $key = $value" -ForegroundColor DarkGray
            }
        }
    }
} else {
    Write-Warning ".env file not found. Running with default configurations."
}

# Execute mvn wildfly:run
Write-Host "Starting WildFly server via mvn wildfly:run..." -ForegroundColor Green
mvn wildfly:run
