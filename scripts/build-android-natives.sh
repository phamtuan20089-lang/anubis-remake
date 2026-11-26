#!/usr/bin/env bash
# Builds libimgui-java64.so (Android arm64 / bionic) from the imgui-java sources with the Android NDK.
# Output: out/libimgui-java64.so  (+ out/diagnostics.txt, always written)
set -u
IMGUI_TAG="${IMGUI_TAG:-v1.90.0}"
WORK="$PWD/natives-work"
OUT="$PWD/out"
API=24
mkdir -p "$WORK" "$OUT"
DIAG="$OUT/diagnostics.txt"
: > "$DIAG"
log() { echo "[natives] $*" | tee -a "$DIAG"; }

cd "$WORK"
log "cloning imgui-java $IMGUI_TAG"
git clone --depth 1 --branch "$IMGUI_TAG" --recurse-submodules --shallow-submodules https://github.com/SpaiR/imgui-java.git src >>"$DIAG" 2>&1 || { log "clone failed"; tail -20 "$DIAG"; exit 1; }
cd src
log "submodules:"; (cat .gitmodules 2>/dev/null; git submodule status 2>&1) | tee -a "$DIAG"
if [ -f .gitmodules ]; then git submodule update --init --recursive --depth 1 >>"$DIAG" 2>&1 || log "submodule update failed"; fi
log "imgui_widgets.cpp present: $(find . -name imgui_widgets.cpp | head -3 | tr '\n' ' ')"
log "tong so .cpp: $(find . -name '*.cpp' | wc -l)"
log "native dir:"; (ls -R imgui-binding/src/main/native 2>/dev/null | head -40) | tee -a "$DIAG"

log "gradle tasks that look relevant:"
chmod +x gradlew
./gradlew --no-daemon tasks --all 2>&1 | grep -i -E "generat|native|lib|jni" | tee -a "$DIAG" | head -40

# 1) Let the project's own tooling generate the JNI C++ glue (linux build may fail at compile step; that's fine).
sudo apt-get install -y g++ >/dev/null 2>&1 || true
for cmd in ":imgui-binding:generateApi" ":imgui-binding:generateLibs"; do
  for extra in "-Denvs=linux -Dlocal=true" "-Denvs=linux"; do
    log "running ./gradlew $cmd $extra"
    ./gradlew --no-daemon --stacktrace "$cmd" $extra >"$WORK/gradle.out" 2>&1; rc=$?
    cat "$WORK/gradle.out" >>"$DIAG"
    log "$cmd $extra -> exit $rc"
    if [ "$rc" -ne 0 ]; then
      log "---- gradle loi (60 dong cuoi) ----"; tail -60 "$WORK/gradle.out" | tee -a "$DIAG"
    fi
    n=$(grep -rl "JNIEXPORT" --include=*.cpp --include=*.cc . 2>/dev/null | wc -l)
    log "JNI cpp sau lan nay: $n"
    [ "$n" -ge 5 ] && break 2
  done
done

# 2) Locate sources.
log "locating native sources"
IMGUI_CORE_DIR="$(dirname "$(find . -name imgui_widgets.cpp -not -path '*/node_modules/*' | head -1)")"
mapfile -t JNI_FILES < <(grep -rl "JNIEXPORT" --include=*.cpp --include=*.cc . 2>/dev/null)
log "imgui core dir: $IMGUI_CORE_DIR"
log "JNI cpp files: ${#JNI_FILES[@]}"
if [ -z "$IMGUI_CORE_DIR" ] || [ "${#JNI_FILES[@]}" -lt 5 ]; then
  log "could not find imgui core or JNI glue; repo layout follows"
  find . -maxdepth 4 -type d | grep -v "\.git" | head -80 | tee -a "$DIAG"
  exit 2
fi

declare -A ROOTS
for f in "${JNI_FILES[@]}"; do ROOTS["$(dirname "$f")"]=1; done
ROOTS["$IMGUI_CORE_DIR"]=1
# also pick up sibling extension libs (implot, imnodes, imguizmo, node-editor, text editor ...) under the same native tree
NATIVE_TREE="$(dirname "$IMGUI_CORE_DIR")"
for d in $(find "$NATIVE_TREE" -type d 2>/dev/null | grep -v -E "/(\.git|build|test|tests|example|examples|backends|misc)(/|$)"); do
  if ls "$d"/*.cpp >/dev/null 2>&1; then ROOTS["$d"]=1; fi
done

SRCS=()
for d in "${!ROOTS[@]}"; do
  while IFS= read -r f; do
    case "$f" in
      *imgui_impl_*|*example*|*/test*|*main.cpp|*_win32*|*_dx*|*_vulkan*|*_metal*|*_glfw*|*_sdl*|*_android*|*_opengl*) continue;;
    esac
    SRCS+=("$f")
  done < <(find "$d" -maxdepth 1 -name '*.cpp' -o -maxdepth 1 -name '*.cc')
done
mapfile -t SRCS < <(printf '%s\n' "${SRCS[@]}" | sort -u)
log "compiling ${#SRCS[@]} files"

INCS=("-I./imgui-binding/build/jni" "-I./imgui-binding/src/main/native")
while IFS= read -r d; do INCS+=("-I$d"); done < <(find . -name '*.h' -o -name '*.hpp' | grep -v "/\.git/" | xargs -n1 dirname | sort -u)
if [ -n "${JAVA_HOME:-}" ] && [ -d "$JAVA_HOME/include" ]; then INCS+=("-I$JAVA_HOME/include" "-I$JAVA_HOME/include/linux"); fi
log "imconfig.h candidates: $(find . -name imconfig.h | tr '\n' ' ')"

# 3) Compile definitions: ONLY from the xml files jnigen generated for this project.
DEFS=()
JNI_DIR="imgui-binding/build/jni"
log "jnigen build files: $(ls "$JNI_DIR"/build*.xml 2>/dev/null | tr '\n' ' ')"
if ls "$JNI_DIR"/build*.xml >/dev/null 2>&1; then
  while IFS= read -r d; do DEFS+=("$d"); done < <(python3 - "$JNI_DIR"/build*.xml <<'PY'
import re, sys, html
seen = set()
for path in sys.argv[1:]:
    t = html.unescape(open(path, errors="ignore").read())
    for m in re.findall(r'-D[A-Za-z_][A-Za-z0-9_]*(?:=[^\s"<>)(]*)?', t):
        name = m[2:].split("=")[0]
        if re.search(r'USER_CONFIG|IMPL|FREETYPE|DAWN|EXTERNAL|SKIP_HEADERS|CUSTOM_NUMERIC', name):
            continue
        if not re.match(r'(IMGUI|IM_|IMPLOT|IMNODES|IMGUIZMO|NODE_EDITOR)', name):
            continue
        if m.endswith("=") or m.endswith("\\"):
            continue
        if m not in seen:
            seen.add(m); print(m)
PY
)
fi
log "extra defines: ${DEFS[*]:-none}"

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_ROOT:-}}}"
[ -d "$NDK" ] || { log "NDK not found"; exit 3; }
CXX="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android${API}-clang++"
[ -x "$CXX" ] || { log "compiler missing: $CXX"; exit 3; }
log "using $CXX"

OBJ="$WORK/obj"; rm -rf "$OBJ"; mkdir -p "$OBJ"
FAIL=0
i=0
for f in "${SRCS[@]}"; do
  i=$((i+1))
  o="$OBJ/$i.o"
  if ! "$CXX" -c -fPIC -O2 -std=c++17 -fexceptions -frtti -fvisibility=default -Wno-everything \
        "${INCS[@]}" "${DEFS[@]}" "$f" -o "$o" >>"$DIAG" 2>"$OBJ/$i.err"; then
    log "FAILED: $f"; head -5 "$OBJ/$i.err" | tee -a "$DIAG"; FAIL=$((FAIL+1))
  fi
done
log "compile failures: $FAIL"

# 4) Link (static libc++ so Pojav's JRE needs nothing extra).
log "linking"
"$CXX" -shared -fPIC -static-libstdc++ -Wl,-z,max-page-size=16384 -Wl,--no-undefined-version \
  "$OBJ"/*.o -llog -ldl -o "$OUT/libimgui-java64.so" >>"$DIAG" 2>&1 || { log "link failed"; exit 4; }

NM="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm"
COUNT=$("$NM" -D --defined-only "$OUT/libimgui-java64.so" | grep -c "Java_imgui_")
log "exported Java_imgui_* symbols: $COUNT"
[ "$COUNT" -ge 100 ] || { log "too few JNI symbols; build is not usable"; exit 5; }
ls -la "$OUT/libimgui-java64.so" | tee -a "$DIAG"
log "OK"
