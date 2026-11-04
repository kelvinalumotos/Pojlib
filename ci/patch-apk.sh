#!/bin/bash
# Replaces the Pojlib inside an official QuestCraft APK with the Pojlib of an AAR built from this repo.
# Usage: patch-apk.sh <Pojlib-release.aar> <QCXR.apk> <out-unsigned.apk>
# Needs: apktool.jar in the working directory, $BT (Android build-tools dir), $ANDROID_HOME.
set -euo pipefail
AAR=$(realpath "$1"); APK=$(realpath "$2"); OUT=$(realpath -m "$3")
W=$(mktemp -d)
APKTOOL="java -jar $(realpath apktool.jar)"

# -r keeps resources as they are, we only touch code, native libs and assets
$APKTOOL d -r -f -o "$W/apk" "$APK"
mkdir -p "$W/aar" && unzip -q "$AAR" -d "$W/aar"

# --- Java: dex the AAR classes and turn them into smali (apktool needs a manifest next to the dex)
mkdir -p "$W/dex"
"$BT/d8" --release --min-api 29 --lib "$ANDROID_HOME/platforms/android-34/android.jar" \
    --output "$W/dex" "$W/aar/classes.jar"
mkdir -p "$W/pojlib-apk"
cp "$W/dex/classes.dex" "$W/pojlib-apk/"
unzip -q -o "$APK" AndroidManifest.xml -d "$W/pojlib-apk"
(cd "$W/pojlib-apk" && zip -q -r ../pojlib.apk .)
$APKTOOL d -r -f -o "$W/pojlib" "$W/pojlib.apk"

# Remove every old copy of a class we ship, plus the whole pojlib package (old synthetic classes)
(cd "$W/pojlib/smali" && find . -name '*.smali' | sed 's#^\./##') > "$W/new-classes.txt"
echo "new classes: $(wc -l < "$W/new-classes.txt")"
removed=0
for d in "$W/apk"/smali*; do
    if [ -d "$d/pojlib" ]; then
        removed=$((removed + $(find "$d/pojlib" -name '*.smali' | wc -l)))
        rm -rf "$d/pojlib"
    fi
    while read -r f; do
        if [ -f "$d/$f" ]; then rm -f "$d/$f"; removed=$((removed + 1)); fi
    done < "$W/new-classes.txt"
done
echo "old classes removed: $removed"
if [ "$removed" -eq 0 ]; then
    echo "no Pojlib classes found in the APK, refusing to continue" >&2
    exit 1
fi

# Add ours as an extra dex: smali = classes.dex, smali_classesN = classesN.dex
n=$(find "$W/apk" -maxdepth 1 -type d -name 'smali_classes*' | wc -l)
cp -r "$W/pojlib/smali" "$W/apk/smali_classes$((n + 2))"
echo "added as smali_classes$((n + 2))"

# --- Native libs: everything Pojlib ships except libc++_shared, which Unity's libraries use too
for so in "$W/aar/jni/arm64-v8a/"*.so; do
    name=$(basename "$so")
    [ "$name" = "libc++_shared.so" ] && continue
    cp "$so" "$W/apk/lib/arm64-v8a/$name"
done

# --- Assets (lwjgl jars and natives, default configs)
cp -r "$W/aar/assets/." "$W/apk/assets/"

# --- Version shown at the top of the launcher (Unity's Application.version = the manifest's versionName).
# Resources are not decoded, so the manifest is still binary: swap the string in place. Only same-length
# versions work this way, and exactly one occurrence must be found.
if [ -n "${VERSION_NAME:-}" ]; then
    FROM="${VERSION_FROM:-6.0.0}"
    if [ "${#FROM}" -ne "${#VERSION_NAME}" ]; then
        echo "VERSION_NAME must have the same length as $FROM" >&2
        exit 1
    fi
    count=$(FROM="$FROM" TO="$VERSION_NAME" perl -0777 -i -pe '
        my ($f, $t) = ($ENV{FROM}, $ENV{TO});
        my ($f16, $t16) = (join("\0", split //, $f) . "\0", join("\0", split //, $t) . "\0");
        # the string pool is UTF-16LE or UTF-8
        my $n = s/\Q$f16\E/$t16/g;
        $n ||= s/\Q$f\E/$t/g;
        print STDERR "$n\n";
    ' "$W/apk/AndroidManifest.xml" 2>&1 >/dev/null | tail -1)
    if [ "$count" != "1" ]; then
        echo "expected one versionName $FROM in the manifest, found ${count:-0}" >&2
        exit 1
    fi
    echo "versionName $FROM -> $VERSION_NAME"
fi

$APKTOOL b -o "$OUT" "$W/apk"
echo "built $OUT"
