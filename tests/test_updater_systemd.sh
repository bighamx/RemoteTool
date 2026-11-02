#!/usr/bin/env bash
# Disposable user service only. Exercises production launcher/bootstrap/worker code.
set -euo pipefail
repo=$(cd "$(dirname "$0")/.." && pwd)
dotnet_host=${DOTNET_HOST_PATH:-$(command -v dotnet)}
root=$(mktemp -d "$HOME/.cache/remotetool-systemd-test.XXXXXX")
unit="remotetool-updater-test-$(basename "$root" | tr -cd 'a-zA-Z0-9').service"
unitfile="$HOME/.config/systemd/user/$unit"
project="$root/project"; runtime="$project/bin/Debug/net8.0"; data="$root/data"
port=$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()')
mkdir -p "$project" "$data" "$(dirname "$unitfile")"
cleanup() {
    systemctl --user stop "$unit" >/dev/null 2>&1 || true
    rm -f -- "$unitfile"
    systemctl --user daemon-reload
    # Keep the isolated fixture and journal for diagnosis. No user data is involved.
    echo "systemd test fixture: $root"
}
trap cleanup EXIT
cat > "$project/WebApplication.csproj" <<XML
<Project Sdk="Microsoft.NET.Sdk.Web"><PropertyGroup><TargetFramework>net8.0</TargetFramework><ImplicitUsings>enable</ImplicitUsings><Nullable>enable</Nullable><UseAppHost>false</UseAppHost></PropertyGroup><ItemGroup>
$(for file in UpdateContract UpdaterProcess UpdaterBootstrap SystemdControl ServerUpdateWorker SourceUpdateWorker LinuxUpdateWorker SourceRepository; do echo "<Compile Include=\"$repo/WebApplication/Services/Updates/$file.cs\" Link=\"$file.cs\"/>"; done)
<AssemblyMetadata Include="ReleaseSequence" Value="\$(ServerReleaseSequence)"/>
</ItemGroup></Project>
XML
cat > "$project/Stubs.cs" <<'CS'
namespace RemoteTool.WebApi.Services {
public sealed class DeviceIdentity { public string Id=>"systemd-test-device"; }
public static class RemoteToolPaths { public static string ProgramData=>System.Environment.GetEnvironmentVariable("REMOTETOOL_DATA_DIR")!; }
}
namespace RemoteTool.WebApi.Services.RemoteControl {
public static class InteractiveProcessLauncher { public static string GetDotnetPath()=>System.Environment.GetEnvironmentVariable("DOTNET_HOST_PATH")!; }
}
CS
cat > "$project/Program.cs" <<'CS'
using RemoteTool.WebApi.Services;
using RemoteTool.WebApi.Services.Updates;
using RemoteTool.Updates;
using System.Reflection;
if(args is ["--server-updater",var config]) {await new Worker(config).Run();return;}
var builder=WebApplication.CreateBuilder(args);
builder.Configuration.AddInMemoryCollection(new Dictionary<string,string?> { ["ServerUpdate:ServiceName"]=Environment.GetEnvironmentVariable("FIXTURE_UNIT"),["ServerUpdate:UserService"]="true" });
builder.Services.AddSingleton<DeviceIdentity>();builder.Services.AddSingleton<UpdaterBootstrap>();
var app=builder.Build();
app.MapGet("/api/device/update-health",()=>new {id="systemd-test-device",sequence=long.TryParse(typeof(Program).Assembly.GetCustomAttributes<AssemblyMetadataAttribute>().FirstOrDefault(a=>a.Key=="ReleaseSequence")?.Value,out var seq)?seq:0,buildId=typeof(Program).Module.ModuleVersionId.ToString("N"),version="before"});
app.MapPost("/api/server-update/readiness",()=>new {ready=true});
app.MapPost("/api/server-update/release-drain",()=>Results.Ok());
app.MapPost("/api/server-update/warm-bridge",()=>new {ready=true});
app.MapPost("/start",async(UpdaterBootstrap bootstrap)=>{
 await bootstrap.Ensure(default);
 var root=Path.Combine(RemoteToolPaths.ProgramData,"updates");
 if(!File.Exists(Path.Combine(root,"installation.json")))return Results.Problem(bootstrap.Message);
 var job=new UpdateJob(Guid.NewGuid().ToString("N"),"requested","fixture",0,"fixture",SourceBuild:true);
 UpdateContract.WriteJob(root,job);await bootstrap.Start(job.Id,default);return Results.Accepted();
});
app.MapPost("/start-release",async(UpdaterBootstrap bootstrap)=>{
 var root=Path.Combine(RemoteToolPaths.ProgramData,"updates");
 var path=Path.Combine(root,"installation.json");var settings=UpdateContract.Read<Installation>(path);
 UpdateContract.Write(path,settings with {Mode="release",ProjectDirectory=null});
 var job=new UpdateJob(Guid.NewGuid().ToString("N"),"requested","fixture",1,"9.9.9");
 var snapshot=Path.Combine(root,"jobs",job.Id);Directory.CreateDirectory(snapshot);
 File.Copy(Path.Combine(RemoteToolPaths.ProgramData,"release-template.json"),Path.Combine(snapshot,"manifest.json"));
 UpdateContract.Write(Path.Combine(root,"request.json"),new {downloadOnly=false});
 UpdateContract.WriteJob(root,job);await bootstrap.Start(job.Id,default);return Results.Accepted();
});
await app.RunAsync();
CS
"$dotnet_host" build "$project/WebApplication.csproj" -c Debug -v:q > "$root/initial-build.log" 2>&1 || { tail -30 "$root/initial-build.log"; exit 1; }
printf 'unchanged fixture database' > "$runtime/hangfire.db"
cat > "$unitfile" <<UNIT
[Unit]
Description=RemoteTool isolated updater integration fixture
[Service]
Type=simple
WorkingDirectory=$runtime
ExecStart=$dotnet_host $runtime/WebApplication.dll
KillMode=process
Environment=ASPNETCORE_URLS=http://127.0.0.1:$port
Environment=REMOTETOOL_DATA_DIR=$data
Environment=FIXTURE_UNIT=$unit
Environment=DOTNET_HOST_PATH=$dotnet_host
Environment=PATH=$(dirname "$dotnet_host"):/usr/bin:/bin
Environment=XDG_RUNTIME_DIR=/run/user/$(id -u)
Environment=DOTNET_SYSTEM_GLOBALIZATION_INVARIANT=${DOTNET_SYSTEM_GLOBALIZATION_INVARIANT:-0}
UNIT
systemctl --user daemon-reload; systemctl --user start "$unit"
for attempt in $(seq 1 100);do if curl -fsS "http://127.0.0.1:$port/api/device/update-health" > "$root/before.json" 2>/dev/null;then break;fi;sleep .1;done
sed -i 's/version="before"/version="after"/' "$project/Program.cs"
cp "$project/Program.cs" "$root/good-source.cs"
run_update() {
    curl -fsS -X POST "http://127.0.0.1:$port/${1:-start}" >/dev/null || true # a disconnect never replays the request
    for attempt in $(seq 1 600);do
        phase=$(python3 -c 'import json,sys,pathlib;p=pathlib.Path(sys.argv[1]);print(json.loads(p.read_text())["phase"] if p.exists() else "")' "$data/updates/job.json")
        case "$phase" in completed|rolled_back|failed|recovery_required) echo "update phase: $phase";return;;esac
        sleep .2
    done
    echo 'update timeout';exit 1
}
run_update
curl -fsS "http://127.0.0.1:$port/api/device/update-health" > "$root/after.json"
python3 - "$root" <<'PY'
import json,pathlib,sys
r=pathlib.Path(sys.argv[1]);j=json.loads((r/'data/updates/job.json').read_text())
assert j['phase']=='completed',j
before=json.loads((r/'before.json').read_text());after=json.loads((r/'after.json').read_text())
assert before['buildId']!=after['buildId'] and after['version']=='after',(before,after)
assert (r/'project/bin/Debug/net8.0/hangfire.db').read_text()=='unchanged fixture database'
jobdir=r/'data/updates/jobs'/j['id'];launch=json.loads((jobdir/'launch.json').read_text());worker=json.loads((jobdir/'worker.json').read_text())
assert launch['pid']==worker['pid'] and launch['startCookie']==worker['startCookie']
print('Linux systemd: direct updater survived service stop; real compile/restart, MVID and data preservation passed')
PY
printf '\nthis deliberately does not compile;\n' >> "$project/Program.cs"
run_update
curl -fsS "http://127.0.0.1:$port/api/device/update-health" > "$root/restored.json"
python3 - "$root" <<'PY'
import json,pathlib,sys
r=pathlib.Path(sys.argv[1]);j=json.loads((r/'data/updates/job.json').read_text());assert j['phase']=='rolled_back',j
assert json.loads((r/'restored.json').read_text())==json.loads((r/'after.json').read_text())
assert 'deliberately' in (r/'project/Program.cs').read_text()
assert (r/'project/bin/Debug/net8.0/hangfire.db').read_text()=='unchanged fixture database'
print('Linux systemd: real compiler failure rolled back; original version, source edits and database preserved')
PY
cp "$root/good-source.cs" "$project/Program.cs"
sed -i 's/version="after"/version="release"/' "$project/Program.cs"
"$dotnet_host" publish "$project/WebApplication.csproj" -c Release -p:UseAppHost=false -p:ServerReleaseSequence=1 -o "$root/release" -v:q > "$root/release-build.log" 2>&1 || { tail -30 "$root/release-build.log";exit 1; }
python3 - "$root" <<'PY'
import json,pathlib,sys,zipfile,hashlib,platform
r=pathlib.Path(sys.argv[1]);archive=r/'data/updates/package-1.zip'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
 for file in (r/'release').rglob('*'):
  if file.is_file() and not file.name.startswith('appsettings') and file.name!='web.config':z.write(file,file.relative_to(r/'release'))
rid='linux-arm64-systemd' if platform.machine()=='aarch64' else 'linux-x64-systemd'
manifest=dict(schemaVersion=1,version='9.9.9',releaseSequence=1,commit='a'*40,channel='stable',minimumUpdaterVersion='1.2.0',protocolMin=1,protocolMax=1,rollbackSupported=True,releaseNotes='fixture',packages={rid:dict(url='https://github.com/bighamx/RemoteTool/releases/download/server-v9.9.9/server.zip',size=archive.stat().st_size,sha256=hashlib.sha256(archive.read_bytes()).hexdigest())})
(r/'data/release-template.json').write_text(json.dumps(manifest))
PY
run_update start-release
curl -fsS "http://127.0.0.1:$port/api/device/update-health" > "$root/released.json"
python3 - "$root" <<'PY'
import json,pathlib,sys
r=pathlib.Path(sys.argv[1]);j=json.loads((r/'data/updates/job.json').read_text());assert j['phase']=='completed',j
health=json.loads((r/'released.json').read_text());assert health['sequence']==1 and health['version']=='release',health
assert (r/'project/bin/Debug/net8.0/hangfire.db').read_text()=='unchanged fixture database'
print('Linux systemd: SHA-256 verified release ZIP installed; service/version recovered and database preserved')
PY
