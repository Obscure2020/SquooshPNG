@echo off
pushd "%~dp0"
javac Main.java && goto loop
goto end
:loop
if not exist "%~1" goto end
java Main "%1"
echo.
shift
goto loop
:end
del *.class
popd