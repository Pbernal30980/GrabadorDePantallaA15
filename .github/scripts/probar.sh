#!/bin/bash
# Prueba automática en emulador: abrir la app, grabar ~8 s, pausar, detener y revisar el video.
set -x
OUT=/tmp/out; mkdir -p $OUT
PKG=com.pedro.grabador
BT=$(ls -d $ANDROID_HOME/build-tools/* | sort -V | tail -1)
keytool -genkeypair -keystore /tmp/t.jks -alias t -keyalg RSA -keysize 2048 -validity 100 \
  -storepass 123456 -keypass 123456 -dname CN=prueba >/dev/null 2>&1
$BT/zipalign -f -p 4 app/build/outputs/apk/release/app-release-unsigned.apk /tmp/al.apk
$BT/apksigner sign --ks /tmp/t.jks --ks-pass pass:123456 --out /tmp/test.apk /tmp/al.apk
adb install -r -g /tmp/test.apk 2>&1 | tee $OUT/install.txt
adb shell pm grant $PKG android.permission.RECORD_AUDIO
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
adb logcat -c

shot() { adb exec-out screencap -p > $OUT/$1.png; }

# 1) Abrir
adb shell am start -W -n $PKG/.MainActivity
sleep 5
shot 1_inicio
adb shell pidof $PKG > $OUT/pid_inicio.txt || echo "PROCESO MUERTO AL INICIAR" > $OUT/pid_inicio.txt

# 2) Iniciar grabación y aceptar el diálogo de captura
python3 .github/scripts/tap.py "Iniciar grabaci"
sleep 3
shot 2_dialogo
python3 .github/scripts/tap.py "^(Single app|Una sola app|A single app)$" >/dev/null 2>&1 || true
python3 .github/scripts/tap.py "entire screen|pantalla completa" || true
sleep 1
python3 .github/scripts/tap.py "^(Start|Start recording|Iniciar|Comenzar|Share screen|Next)$"
sleep 8
shot 3_grabando
adb shell dumpsys notification --noredact > $OUT/notificaciones.txt

# 3) Pausar / reanudar / detener desde la app
adb shell am start -n $PKG/.MainActivity
sleep 2
python3 .github/scripts/tap.py "Pausar"
sleep 2
shot 4_pausa
python3 .github/scripts/tap.py "Reanudar"
sleep 3
python3 .github/scripts/tap.py "Detener y guardar"
sleep 6
shot 5_final

# 4) Revisar el video guardado
adb shell content query --uri content://media/external/video/media \
  --projection _display_name:_size:duration:relative_path:is_pending > $OUT/videos.txt 2>&1
adb shell pidof $PKG > $OUT/pid_final.txt || echo "PROCESO MUERTO" > $OUT/pid_final.txt

adb logcat -d -b crash > $OUT/crash.txt
adb logcat -d > /tmp/full.txt
grep -E "AndroidRuntime|FATAL|$PKG|MediaProjection|grabador|MediaCodec|Muxer" /tmp/full.txt | tail -400 > $OUT/logcat.txt
cp /tmp/full.txt $OUT/logcat_completo.txt
exit 0
