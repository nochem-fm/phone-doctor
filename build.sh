#!/usr/bin/env bash
# Same pipeline you already use: aapt2 -> ecj -> d8 -> zip -> zipalign -> uber-apk-signer
# Set these env vars (or edit the defaults) to point at your tools.
set -e
ANDROID_JAR=${ANDROID_JAR:-$HOME/android.jar}
ECJ=${ECJ:-$HOME/ecj.jar}
D8=${D8:-d8}
SIGNER=${SIGNER:-$HOME/uber-apk-signer.jar}

rm -rf build out && mkdir -p build/classes build/dex out

aapt2 compile --dir res -o build/res.zip
aapt2 link -o build/base.apk -I "$ANDROID_JAR" --manifest AndroidManifest.xml build/res.zip \
  --min-sdk-version 19 --target-sdk-version 33

java -jar "$ECJ" -encoding UTF-8 -source 1.8 -target 1.8 -proc:none -nowarn \
  -cp "$ANDROID_JAR" -d build/classes $(find src -name '*.java')

$D8 --release --min-api 19 --lib "$ANDROID_JAR" --output build/dex \
  $(find build/classes -name '*.class')

( cd build/dex && zip -j ../base.apk classes.dex )
zipalign -f -p 4 build/base.apk build/aligned.apk
java -jar "$SIGNER" -a build/aligned.apk --allowResign -o out
ls -l out
