#!/usr/bin/env bash
# Instala la app en el emulador, la abre y toma capturas de sus tres pantallas.
# La app usa por defecto http://10.0.2.2:8765, que en el emulador es el servidor de MIRS del equipo anfitrión.
# Cada captura se imprime en base64 entre marcas para recuperarla desde el registro de la ejecución.
set -euo pipefail
APK=android/app/build/outputs/apk/debug/app-debug.apk
mkdir -p capturas
adb install -r "$APK"
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell am start -W -n com.umb.mirs/.MainActivity
sleep 25

tocar() {  # toca el elemento de la interfaz con el id indicado
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null
  local b; b=$(adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep "com.umb.mirs:id/$1\"" | grep -o 'bounds="[^"]*"' | head -1 | tr -dc '0-9,[]' | tr '][' ' ,')
  IFS=', ' read -r x1 y1 x2 y2 <<<"$(echo "$b" | tr -s ' ,' ' ')"
  adb shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))
}
captura() {
  adb exec-out screencap -p > "capturas/$1.png"
  echo "===CAPTURA $1 INICIO==="; base64 -w 0 "capturas/$1.png"; echo; echo "===CAPTURA $1 FIN==="
}

captura 01_panel
adb shell input swipe 540 1700 540 700 400; sleep 2
captura 02_panel_historial
tocar nav_procesos; sleep 6
captura 03_procesos
tocar nav_alertas; sleep 6
captura 04_alertas
adb shell uiautomator dump /sdcard/ui.xml >/dev/null
echo "Textos visibles en Alertas:"; adb shell cat /sdcard/ui.xml | grep -o 'text="[^"]\+"' | head -40
