#!/usr/bin/env bash
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
root=$(dirname "$here")

version=1.28.6
revision=${MUPDF_REVISION:-reepdf}
artifact=mupdf-fitz
api=26
abis="armeabi-v7a arm64-v8a x86 x86_64"

work=${MUPDF_WORK:-$root/work/mupdf_build}
out=${MUPDF_OUT:-$here/dist}
patches=${MUPDF_PATCHES:-$here/patches}
ndk=${ANDROID_NDK:-$HOME/Android/Sdk/ndk/30.0.16248370}
android_jar=${ANDROID_JAR:-$HOME/Android/Sdk/platforms/android-37.0/android.jar}
gnumake=${GNUMAKE:-/usr/bin/make}

github=https://github.com/ArtifexSoftware
mupdf_commit=da5f5eb890d7b3fb76903160b5f7f48ba1301492
submodules=(
    "brotli thirdparty-brotli 05d2263409056fde56805e9cdabd2d31a1f1d427"
    "cmark-gfm thirdparty-cmark-gfm 74e0f5b6b62bbdd1e262c92f4bb2c6c871c6307e"
    "extract extract 8750ac39c30a0d65119b426b5a491c5b8e8bf674"
    "freetype thirdparty-freetype2 0a0221a1347e2f1e07c395263540026e9a0aa7c7"
    "gumbo-parser thirdparty-gumbo-parser 61bd4e736c3b342e592f65be0d65ec6a0acd8dcb"
    "harfbuzz thirdparty-harfbuzz c28ba35a1e6dc4b6b32e2b1d11fd2a408f6c86bb"
    "jbig2dec jbig2dec dc15c39bbbddc90f79c14563d2eb5a794106be8f"
    "lcms2 thirdparty-lcms2 d69c64417c4a33a4629957fc0e08f4f4c5abcc3d"
    "libjpeg thirdparty-libjpeg 627faae398468d5d11e3d694dc325432f734b5c4"
    "mujs mujs 2c61a7bed7e7625c03c6e82e644a711ac4471c1e"
    "openjpeg thirdparty-openjpeg 957029eb875eee1118743f200cb86da9d8289de2"
    "zlib thirdparty-zlib da607da739fa6047df13e66a2af6b8bec7c2a498"
)

src=$work/src
tree=$work/tree
obj=$work/obj
libs=$work/libs
stage=$work/stage

usage() {
    echo "usage: $0 [all|fetch|build|clean]" >&2
    exit 2
}

fetch_repo() {
    local dir=$1 url=$2 commit=$3
    if [ -d "$dir/.git" ] && [ "$(git -C "$dir" rev-parse HEAD 2>/dev/null)" = "$commit" ]; then
        return
    fi
    rm -rf "$dir"
    mkdir -p "$dir"
    git -C "$dir" init -q
    git -C "$dir" fetch -q --depth 1 "$url" "$commit"
    git -C "$dir" checkout -q FETCH_HEAD
    if [ "$(git -C "$dir" rev-parse HEAD)" != "$commit" ]; then
        echo "commit mismatch in $dir" >&2
        exit 1
    fi
}

fetch() {
    echo "fetch: mupdf $mupdf_commit"
    fetch_repo "$src/mupdf" "$github/mupdf.git" "$mupdf_commit"
    local entry path repo commit
    for entry in "${submodules[@]}"; do
        read -r path repo commit <<< "$entry"
        echo "fetch: $path $commit"
        fetch_repo "$src/mupdf/thirdparty/$path" "$github/$repo.git" "$commit"
    done
}

zip_dir() {
    python3 - "$1" "$2" "$3" "$4" <<'EOF'
import os, sys, zipfile
target, base, dirs = sys.argv[1], sys.argv[2], sys.argv[4]
stamp = tuple(int(x) for x in sys.argv[3].split(","))
entries = []
for dirpath, dirnames, filenames in os.walk(base):
    dirnames.sort()
    rel = os.path.relpath(dirpath, base).replace(os.sep, "/")
    if rel != "." and (dirs == "all" or rel.split("/")[0] == dirs):
        entries.append((rel + "/", None))
    for name in sorted(filenames):
        path = os.path.join(dirpath, name)
        entries.append((os.path.relpath(path, base).replace(os.sep, "/"), path))
entries.sort(key=lambda e: e[0])
with zipfile.ZipFile(target, "w") as zf:
    for name, path in entries:
        info = zipfile.ZipInfo(name, stamp)
        info.create_system = 0
        if path is None:
            info.external_attr = 0o40755 << 16 | 0x10
            info.compress_type = zipfile.ZIP_STORED
            zf.writestr(info, b"")
        else:
            info.external_attr = 0o100644 << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            with open(path, "rb") as f:
                zf.writestr(info, f.read(), compresslevel=9)
EOF
}

build() {
    [ -d "$src/mupdf/.git" ] || fetch
    [ -x "$ndk/ndk-build" ] || { echo "missing ndk-build: $ndk" >&2; exit 1; }
    [ -f "$android_jar" ] || { echo "missing android.jar: $android_jar" >&2; exit 1; }
    [ -x "$gnumake" ] || { echo "missing GNU make: $gnumake" >&2; exit 1; }

    rm -rf "$tree" "$obj" "$libs" "$stage"
    mkdir -p "$tree" "$stage/aar/jni" "$stage/classes" "$out"

    echo "build: copy sources"
    tar -C "$src" --exclude=.git -cf - mupdf | tar -C "$tree" -xf -

    local patch count=0
    for patch in "$patches"/*.patch; do
        [ -e "$patch" ] || continue
        echo "build: apply $(basename "$patch")"
        patch -p1 -s -d "$tree/mupdf" --no-backup-if-mismatch < "$patch"
        count=$((count + 1))
    done
    echo "build: $count patch(es) applied"

    echo "build: make generate"
    make -s -C "$tree/mupdf" -o source/html/css-properties.h generate

    echo "build: ndk-build ($abis, android-$api, $("$gnumake" --version | head -n 1))"
    GNUMAKE=$gnumake "$ndk/ndk-build" -j"$(nproc)" \
        NDK_PROJECT_PATH=null \
        APP_BUILD_SCRIPT="$tree/mupdf/platform/java/Android.mk" \
        APP_ABI="$abis" \
        APP_PLATFORM="android-$api" \
        APP_OPTIM=release \
        NDK_DEBUG=0 \
        NDK_OUT="$obj" \
        NDK_LIBS_OUT="$libs" \
        APP_SHORT_COMMANDS=false \
        LOCAL_SHORT_COMMANDS=false

    echo "build: javac"
    find "$tree/mupdf/platform/java/src" -name '*.java' | sort > "$stage/sources.txt"
    javac --release 8 -Xlint:-options -nowarn -encoding UTF-8 \
        -cp "$android_jar" -d "$stage/classes" @"$stage/sources.txt"
    zip_dir "$stage/aar/classes.jar" "$stage/classes" 1981,1,1,1,1,0 all

    local abi
    for abi in $abis; do
        mkdir -p "$stage/aar/jni/$abi"
        cp "$libs/$abi/libmupdf_java.so" "$stage/aar/jni/$abi/"
    done
    : > "$stage/aar/R.txt"
    printf '%s\n%s\n%s\n\n%s\n\n%s' \
        '<?xml version="1.0" encoding="utf-8"?>' \
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android"' \
        '    package="com.artifex.mupdf.fitz" >' \
        "    <uses-sdk android:minSdkVersion=\"$api\" />" \
        '</manifest>' > "$stage/aar/AndroidManifest.xml"
    mkdir -p "$stage/aar/META-INF/com/android/build/gradle"
    cat > "$stage/aar/META-INF/com/android/build/gradle/aar-metadata.properties" <<EOF
aarFormatVersion=1.0
aarMetadataVersion=1.0
minCompileSdk=1
minCompileSdkExtension=0
minAndroidGradlePluginVersion=1.0.0
coreLibraryDesugaringEnabled=false
EOF

    local aar=$out/$artifact-$version-$revision.aar
    zip_dir "$aar" "$stage/aar" 1980,2,1,0,0,0 jni
    echo "build: $aar"
    sha256sum "$aar"

    rm -rf "$tree" "$obj" "$libs" "$stage"
}

clean() {
    rm -rf "$work"
}

case "${1:-all}" in
    all) fetch; build ;;
    fetch) fetch ;;
    build) build ;;
    clean) clean ;;
    *) usage ;;
esac
