@echo off
echo Configurando IP fixo para a escola...
netsh interface ip set address "Wi-Fi 2" static 10.213.68.200 255.255.255.0 10.213.68.160
netsh interface ip set dns "Wi-Fi 2" static 10.213.68.160
echo.
echo IP fixo definido: 10.213.68.200
echo Os colegas acedem em: http://10.213.68.200:8080
echo.
pause
