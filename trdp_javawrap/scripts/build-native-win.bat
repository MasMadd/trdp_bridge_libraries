@echo off
:: ---------------------------------------------------------------------------
:: build-native-win.bat
::
:: Builds the TRDP DLL using the Visual Studio 2019 solution and copies it
:: into the JNA auto-extraction resource path:
::
::   src\main\resources\com\sun\jna\win32-x86-64\trdp.dll
::
:: JNA picks the right file at runtime based on Platform.RESOURCE_PREFIX
:: (win32-x86-64 for 64-bit Windows), extracts it and loads it automatically.
::
:: Usage (from the trdp_javawrap directory, or via Maven):
::   scripts\build-native-win.bat            (Release build)
::   scripts\build-native-win.bat --debug    (Debug build)
::
:: Prerequisites:
::   Visual Studio 2019 or 2022 with "Desktop development with C++" workload.
::   MSBuild must be on PATH, or the script will search common install paths.
:: ---------------------------------------------------------------------------

setlocal enabledelayedexpansion

set SCRIPT_DIR=%~dp0
:: Strip trailing backslash
if "%SCRIPT_DIR:~-1%"=="\" set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"

set JAVA_PROJECT=%SCRIPT_DIR%\..
set TRDP_ROOT=%JAVA_PROJECT%\..\TRDP\3.0.0.0
set SLN=%TRDP_ROOT%\VSExpress2019\Win_TRDP_VS2019.sln
set TRDP_DLL_PROJECT=TRDP_DLL
set RESOURCES_DIR=%JAVA_PROJECT%\src\main\resources\com\sun\jna\win32-x86-64

:: Build configuration
set CONFIGURATION=Release
if /I "%~1"=="--debug" set CONFIGURATION=Debug

echo =^>Build configuration: %CONFIGURATION%

:: --------------------------------------------------------------------------
:: Locate MSBuild
:: --------------------------------------------------------------------------

set MSBUILD=

:: Try PATH first
for /f "tokens=*" %%i in ('where msbuild 2^>nul') do (
    if not defined MSBUILD set "MSBUILD=%%i"
)

:: Search known VS2022 paths (64-bit and 32-bit Program Files)
if not defined MSBUILD (
    for %%p in (
        "C:\Program Files\Microsoft Visual Studio\2022\Enterprise\MSBuild\Current\Bin\MSBuild.exe"
        "C:\Program Files\Microsoft Visual Studio\2022\Professional\MSBuild\Current\Bin\MSBuild.exe"
        "C:\Program Files\Microsoft Visual Studio\2022\Community\MSBuild\Current\Bin\MSBuild.exe"
        "C:\Program Files\Microsoft Visual Studio\2022\BuildTools\MSBuild\Current\Bin\MSBuild.exe"
        "C:\Program Files (x86)\Microsoft Visual Studio\2022\Enterprise\MSBuild\Current\Bin\MSBuild.exe"
        "C:\Program Files (x86)\Microsoft Visual Studio\2022\Professional\MSBuild\Current\Bin\MSBuild.exe"
        "C:\Program Files (x86)\Microsoft Visual Studio\2022\Community\MSBuild\Current\Bin\MSBuild.exe"
        "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\MSBuild\Current\Bin\MSBuild.exe"
    ) do (
        if not defined MSBUILD if exist %%p set "MSBUILD=%%~p"
    )
)

:: Search known VS2019 paths
if not defined MSBUILD (
    for %%p in (
        "C:\Program Files (x86)\Microsoft Visual Studio\2019\Enterprise\MSBuild\Current\Bin\MSBuild.exe"
        "C:\Program Files (x86)\Microsoft Visual Studio\2019\Professional\MSBuild\Current\Bin\MSBuild.exe"
        "C:\Program Files (x86)\Microsoft Visual Studio\2019\Community\MSBuild\Current\Bin\MSBuild.exe"
        "C:\Program Files (x86)\Microsoft Visual Studio\2019\BuildTools\MSBuild\Current\Bin\MSBuild.exe"
    ) do (
        if not defined MSBUILD if exist %%p set "MSBUILD=%%~p"
    )
)

if not defined MSBUILD (
    echo ERROR: MSBuild not found.
    echo Install Visual Studio 2019/2022 with "Desktop development with C++" workload,
    echo or install "Build Tools for Visual Studio" from:
    echo https://visualstudio.microsoft.com/downloads/
    exit /b 1
)

echo =^>MSBuild : %MSBUILD%
echo =^>Solution: %SLN%

:: --------------------------------------------------------------------------
:: Build the DLL
:: --------------------------------------------------------------------------

"%MSBUILD%" "%SLN%" ^
    /t:%TRDP_DLL_PROJECT% ^
    /p:Configuration=%CONFIGURATION% ^
    /p:Platform=x64 ^
    /m ^
    /nologo ^
    /verbosity:minimal

if errorlevel 1 (
    echo ERROR: MSBuild failed.
    exit /b 1
)

:: --------------------------------------------------------------------------
:: Locate the produced DLL
:: --------------------------------------------------------------------------
:: The VS project may name the output SimTRDP_DLL.dll, TRDP_DLLSim.dll, or
:: TRDP_DLL.dll depending on the active configuration's TargetName property.
:: We find the most recently written .dll in the output directory.

set DLL_SEARCH=%TRDP_ROOT%\VSExpress2019\%TRDP_DLL_PROJECT%\x64\%CONFIGURATION%

set FOUND_DLL=
for /f "delims=" %%f in ('dir /b /o-d "%DLL_SEARCH%\*.dll" 2^>nul') do (
    if not defined FOUND_DLL set "FOUND_DLL=%DLL_SEARCH%\%%f"
)

if not defined FOUND_DLL (
    echo ERROR: No DLL found in %DLL_SEARCH%
    echo Check that the TRDP_DLL project built successfully.
    exit /b 1
)

echo =^>Built DLL : %FOUND_DLL%

:: --------------------------------------------------------------------------
:: Copy to JNA resource path
:: --------------------------------------------------------------------------
:: JNA looks for  com/sun/jna/win32-x86-64/trdp.dll  inside the JAR.
:: The DLL is renamed to "trdp.dll" to match the libName used in Native.load().

if not exist "%RESOURCES_DIR%" mkdir "%RESOURCES_DIR%"

copy /Y "%FOUND_DLL%" "%RESOURCES_DIR%\trdp.dll"
if errorlevel 1 (
    echo ERROR: Failed to copy DLL.
    exit /b 1
)

echo.
echo =^>Done. Library installed at:
echo     %RESOURCES_DIR%\trdp.dll
echo.
echo     JNA will auto-extract and load it on win32-x86-64 at runtime.

endlocal
