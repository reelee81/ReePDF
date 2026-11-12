#!/usr/bin/env bash
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
patches=${MUPDF_PATCHES:-$here/patches}

mupdf_repo=https://github.com/ArtifexSoftware/mupdf.git
mupdf_raw=https://raw.githubusercontent.com/ArtifexSoftware/mupdf
github_raw=https://raw.githubusercontent.com/ArtifexSoftware
github_api=https://api.github.com/repos/ArtifexSoftware/mupdf/contents
fitz_repo=https://github.com/ArtifexSoftware/mupdf-android-fitz.git
maven_metadata=https://maven.ghostscript.com/com/artifex/mupdf/fitz/maven-metadata.xml

upstream_fixes=(
    "0001 platform/java/jni/pdfdocument.c FUN(PDFDocument_setPageTreeCache)"
    "0002 platform/java/jni/context.c FUN(Context_releaseFreeMemory)"
    "0003 source/fitz/load-jpx.c opj_set_decoded_resolution_factor"
    "0004 platform/java/jni/pdfdocument.c FUN(PDFDocument_clearXref)"
    "0005 include/mupdf/fitz/context.h fz_check_abort"
    "0006 thirdparty/openjpeg/src/lib/openjp2/openjpeg.h opj_set_abort_handler"
    "0007 thirdparty/jbig2dec/jbig2.h jbig2_ctx_set_abort_callback"
    "0008 thirdparty/lcms2/include/lcms2mt.h cmsSetAbortCheck"
    "0009 source/fitz/load-jpx.c opj_set_decode_area"
    "0010 thirdparty/openjpeg/src/lib/openjp2/tcd.c qmfbid == 1) ? 2 : 4;"
    "0011 source/fitz/draw-scale-simple.c y = (y - h) - dst_y_int;"
    "0012 source/pdf/pdf-function.c if (efrac[dim] == 0)"
    "0013 source/pdf/pdf-function.c clamp_input("
    "0014 source/fitz/draw-rasterize.c else if (fz_is_empty_irect(clip))"
    "0015 source/fitz/draw-imp.h fz_saturate_float_to_int"
    "0016 source/fitz/draw-device.c fz_saturate_float_to_int(floorf((area.x0"
    "0017 source/pdf/pdf-stream.c pdf_remember_inline_image_length"
    "0018 source/pdf/pdf-op-run.c pattern_cell_too_large"
)

current=$(sed -n 's/^version=//p' "$here/build.sh")
current_commit=$(sed -n 's/^mupdf_commit=//p' "$here/build.sh")
if [ -z "$current" ] || [ -z "$current_commit" ]; then
    echo "cannot read version and mupdf_commit from $here/build.sh" >&2
    exit 1
fi

release_tags() {
    awk '{ sub("^refs/tags/", "", $2); sub("\\^\\{\\}$", "", $2); print $2 }' \
        | { grep -E '^[0-9]+\.[0-9]+\.[0-9]+$' || true; } \
        | sort -u -V
}

tag_commit() {
    local refs=$1 tag=$2 peeled direct
    peeled=$(awk -v r="refs/tags/$tag^{}" '$2 == r { print $1 }' <<< "$refs")
    direct=$(awk -v r="refs/tags/$tag" '$2 == r { print $1 }' <<< "$refs")
    echo "${peeled:-$direct}"
}

mupdf_refs=$(git ls-remote --tags "$mupdf_repo")
mupdf_tags=$(release_tags <<< "$mupdf_refs")
latest=$(tail -n 1 <<< "$mupdf_tags")
if [ -z "$latest" ]; then
    echo "no release tags found in $mupdf_repo" >&2
    exit 1
fi
latest_commit=$(tag_commit "$mupdf_refs" "$latest")

fitz_latest=$(git ls-remote --tags "$fitz_repo" | release_tags | tail -n 1)
maven_latest=$(curl -fsS -m 30 "$maven_metadata" 2>/dev/null \
    | { grep -o '<version>[^<]*</version>' || true; } \
    | sed 's/<[^>]*>//g' | sort -V | tail -n 1)

echo "In use:  MuPDF $current ($current_commit)"
echo "Latest:  MuPDF $latest ($latest_commit)"
echo "Artifex: mupdf-android-fitz ${fitz_latest:-?}, Maven fitz ${maven_latest:-?}"

if [ "$(tag_commit "$mupdf_refs" "$current")" != "$current_commit" ]; then
    echo "WARNING: tag $current no longer points to $current_commit"
fi

newer=$(awk -v cur="$current" 'found { print } $0 == cur { found = 1 }' <<< "$mupdf_tags")
if [ -n "${MUPDF_CHECK_TAG:-}" ]; then
    latest=$MUPDF_CHECK_TAG
    echo "Checking the patches against $latest (MUPDF_CHECK_TAG)."
elif [ -z "$newer" ]; then
    echo "Up to date."
    exit 0
fi

echo
[ -z "$newer" ] || echo "Newer MuPDF release(s): $(tr '\n' ' ' <<< "$newer")"
echo "Release notes: https://github.com/ArtifexSoftware/mupdf/blob/$latest/CHANGES"

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

declare -A submodule_commits=()

submodule_repo() {
    sed -n "s/^    \"$1 \([^ ]*\) [0-9a-f]\{40\}\"$/\1/p" "$here/build.sh"
}

submodule_commit() {
    local dir=$1 commit
    if [ -z "${submodule_commits[$dir]:-}" ]; then
        commit=$(curl -fsS -m 30 "$github_api/thirdparty/$dir?ref=$latest" \
            | python3 -c 'import json, sys; print(json.load(sys.stdin)["sha"])') || return 1
        submodule_commits[$dir]=$commit
    fi
}

fetch_file() {
    local file=$1 dir rest repo commit
    mkdir -p "$tmp/$latest/$(dirname "$file")"
    if [[ $file == thirdparty/*/* ]]; then
        dir=${file#thirdparty/}
        rest=${dir#*/}
        dir=${dir%%/*}
        repo=$(submodule_repo "$dir")
        [ -n "$repo" ] || return 1
        submodule_commit "$dir" || return 1
        commit=${submodule_commits[$dir]}
        curl -fsS -m 60 -o "$tmp/$latest/$file" "$github_raw/$repo/$commit/$rest"
    else
        curl -fsS -m 60 -o "$tmp/$latest/$file" "$mupdf_raw/$latest/$file"
    fi
}

echo
echo "Fixed upstream in $latest (the patch can then be dropped):"
for entry in "${upstream_fixes[@]}"; do
    read -r number file marker <<< "$entry"
    if ! fetch_file "$file" 2>/dev/null; then
        echo "  $number: $file not found in $latest"
    elif grep -qF "$marker" "$tmp/$latest/$file"; then
        echo "  $number: yes ($marker found in $file)"
    else
        echo "  $number: no"
    fi
done

echo
echo "Patches against $latest:"
missing=0
for file in $(grep -h '^diff --git a/' "$patches"/*.patch | awk '{ print substr($3, 3) }' | sort -u); do
    [ -f "$tmp/$latest/$file" ] && continue
    if ! fetch_file "$file" 2>/dev/null; then
        echo "  missing upstream: $file"
        missing=1
    fi
done
for patch in "$patches"/*.patch; do
    [ -e "$patch" ] || continue
    name=$(basename "$patch")
    if output=$(patch -p1 --no-backup-if-mismatch -r - -d "$tmp/$latest" < "$patch" 2>&1); then
        if grep -qE 'offset|fuzz' <<< "$output"; then
            echo "  ${name:0:4}: applies with offset/fuzz, review it"
        else
            echo "  ${name:0:4}: applies cleanly"
        fi
    else
        echo "  ${name:0:4}: DOES NOT APPLY"
        sed 's/^/        /' <<< "$output"
    fi
done
[ "$missing" -eq 0 ] || echo "  (some patched files are missing upstream)"

echo
echo "To update: set version and the commits in build.sh (submodule commits:"
echo "https://github.com/ArtifexSoftware/mupdf/tree/$latest/thirdparty), rebase the"
echo "patches, then follow mupdf/README.md."
exit 3
