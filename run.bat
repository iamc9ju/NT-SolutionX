@echo off
setlocal enabledelayedexpansion

if exist .env (
    echo Loading environment variables from .env file...
    for /f "usebackq delims=" %%x in (".env") do (
        set "line=%%x"
        if not "!line!"=="" (
            if not "!line:~0,1!"=="#" (
                set "var=!line!"
                for /f "tokens=1* delims==" %%a in ("!var!") do (
                    set "key=%%a"
                    set "val=%%b"
                    for /f "tokens=*" %%g in ("!key!") do set "key=%%g"
                    for /f "tokens=*" %%g in ("!val!") do set "val=%%g"
                    set "!key!=!val!"
                    echo Set environment variable: !key!=!val!
                )
            )
        )
    )
) else (
    echo WARNING: .env file not found. Running with default configurations.
)

echo Starting WildFly server...
mvn wildfly:run
