@echo off
echo Voltando ao IP automatico (DHCP)...
netsh interface ip set address "Wi-Fi 2" dhcp
netsh interface ip set dns "Wi-Fi 2" dhcp
echo.
echo Pronto! O IP voltara a ser automatico.
echo (pode demorar alguns segundos a obter novo IP)
echo.
pause
