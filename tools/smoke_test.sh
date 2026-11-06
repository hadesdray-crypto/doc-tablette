#!/usr/bin/env bash
# Test sur téléphone virtuel : installation, ouverture de chaque écran, retour, aucun plantage.
set -u
APK="$1"
PKG=fr.doctablette.app
fail() { echo "ÉCHEC : $1"; adb logcat -d -t 300 | grep -E "FATAL|AndroidRuntime" | head -20; exit 1; }

adb install -r "$APK" | grep -q Success || fail "installation"
adb logcat -c
adb shell am start -n $PKG/fr.doctablette.MainActivity >/dev/null
sleep 4
adb shell pidof $PKG >/dev/null || fail "l'appli ne démarre pas"

dump() {
  local o=""
  for i in 1 2 3 4 5; do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    o=$(adb shell cat /sdcard/ui.xml 2>/dev/null)
    case "$o" in *"<hierarchy"*) break;; esac
    sleep 2
  done
  printf '%s' "$o"
}
texts() { dump | grep -o 'text="[^"]*"' | sed 's/text="//;s/"$//' ; }
find_xy() { # position du bouton portant ce texte exact
  dump | python3 -c "
import re,sys
x=sys.stdin.read(); t=sys.argv[1]
for m in re.finditer(r'text=\"([^\"]*)\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"',x):
    if m.group(1)==t:
        print((int(m.group(2))+int(m.group(4)))//2,(int(m.group(3))+int(m.group(5)))//2); break
" "$1"
}
tap_text() { # touche le bouton ; fait défiler l'écran s'il n'est pas encore visible
  local xy=""
  for i in 1 2 3 4; do
    xy=$(find_xy "$1"); [ -n "$xy" ] && break
    adb shell input swipe 540 1800 540 600 300; sleep 0.8
  done
  [ -n "$xy" ] || return 1
  adb shell input tap $xy; sleep 1.2
}
home() { texts | grep -qx "Quitter"; }

# Premier lancement : écran « Comment ça marche »
texts | grep -q "Compris" || fail "écran « Comment ça marche » absent au premier lancement"
tap_text "Compris" || fail "bouton Compris"
home || fail "retour à l'accueil après « Compris »"

# Chaque écran s'ouvre, a son texte, et la flèche retour d'Android revient à l'accueil
for ecran in "Aide : brancher la tablette" "Informations légales" "Licence : activer" "Mettre à jour Doc Tablette" "Tests : écran, tactile, son" "Mises à jour" "Applis de la tablette" "Tout réparer" "Connecter la tablette"; do
  tap_text "$ecran" || fail "bouton « $ecran » introuvable sur l'accueil"
  texts | grep -q "Retour" || fail "« $ecran » : pas de bouton Retour visible"
  texts | grep -q "^?$" || fail "« $ecran » : bouton ? absent"
  adb shell input keyevent KEYCODE_BACK; sleep 1
  home || fail "« $ecran » : la flèche retour n'a pas ramené à l'accueil"
  echo "OK : $ecran"
done

# Bouton Retour de l'appli
tap_text "Aide : brancher la tablette" || fail "aide"
tap_text "Retour" || fail "bouton Retour"
home || fail "bouton Retour de l'aide"

# Rotation en paysage puis retour portrait : l'écran reste utilisable
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1; sleep 2
home || fail "paysage : accueil illisible"
adb shell settings put system user_rotation 0; sleep 2

# Texte à 200 %
adb shell settings put system font_scale 2.0; sleep 2
tap_text "Aide : brancher la tablette" || fail "police 200 % : bouton hors écran"
texts | grep -q "Retour" || fail "police 200 % : Retour absent"
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell settings put system font_scale 1.0

# Quitter demandé une seule fois depuis l'accueil
adb shell input keyevent KEYCODE_BACK; sleep 1
texts | grep -q "Voulez-vous quitter" || fail "confirmation « Quitter ? » absente"
tap_text "Annuler" || true

# Rien n'a planté
adb shell pidof $PKG >/dev/null || fail "l'appli s'est arrêtée"
if adb logcat -d | grep -E "FATAL EXCEPTION" | grep -q .; then fail "plantage détecté"; fi
echo "TOUT PASSE SUR TÉLÉPHONE VIRTUEL"
