#!/usr/bin/env bash
set -euo pipefail
runtime=$(realpath "$1")
dotnet_host=${DOTNET_HOST_PATH:-$(command -v dotnet)}
root=$(mktemp -d /tmp/remotetool-updater-entry.XXXXXX)
cleanup(){ [[ "$root" == /tmp/remotetool-updater-entry.* ]] && rm -rf -- "$root"; }
trap cleanup EXIT
mkdir "$root/engine" "$root/updates"
cp "$runtime"/*.dll "$runtime/WebApplication.deps.json" "$runtime/WebApplication.runtimeconfig.json" "$root/engine/"
if [[ -d "$runtime/runtimes" ]];then cp -R "$runtime/runtimes" "$root/engine/";fi
python3 - "$root" <<'PY'
import json,pathlib,sys
r=pathlib.Path(sys.argv[1]);(r/'updates/installation.json').write_text(json.dumps(dict(site='fixture',pool='fixture.service',releases=str(r),shared=str(r),healthUrl='http://127.0.0.1:1',deviceId='fixture',workerToken='fixture',host='systemd',runtimeDirectory=str(r))))
PY
REMOTETOOL_DATA_DIR="$root/must-not-start-web" "$dotnet_host" "$root/engine/WebApplication.dll" --server-updater "$root/updates/installation.json"
test -f "$root/updates/worker.lock"
test ! -e "$root/must-not-start-web"
echo 'Linux full WebApplication DLL: updater entry starts outside website without Web initialization'
