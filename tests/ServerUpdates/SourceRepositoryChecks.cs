using System.Diagnostics;
using System.Text.Json.Nodes;
using RemoteTool.Updates;
using RemoteTool.WebApi.Services.Updates;

internal static class SourceRepositoryChecks
{
    public static async Task Run() {
        var root=Path.Combine(Path.GetTempPath(),"remotetool-source-check-"+Guid.NewGuid().ToString("N"));Directory.CreateDirectory(root);
        var checks=0;
        void Assert(bool value,string reason){if(!value)throw new Exception(reason);checks++;}
        async Task Reject(Func<Task> work){try{await work();}catch(IOException){checks++;return;}throw new Exception("Unsafe source operation accepted");}
        async Task<string> Git(string directory,params string[] args) {
            var info=new ProcessStartInfo("git") {WorkingDirectory=directory,UseShellExecute=false,CreateNoWindow=true,RedirectStandardOutput=true,RedirectStandardError=true};
            foreach(var argument in args)info.ArgumentList.Add(argument);
            using var process=Process.Start(info)!;var output=process.StandardOutput.ReadToEndAsync();var errors=process.StandardError.ReadToEndAsync();
            await process.WaitForExitAsync();var text=await output;await errors;if(process.ExitCode!=0)throw new IOException("Fixture Git command rejected: "+args[0]);return text.Trim();
        }
        async Task Commit(string directory,string message){await Git(directory,"add",".");await Git(directory,"-c","user.name=fixture","-c","user.email=fixture@example.invalid","commit","-m",message);}
        try {
            var bare=Path.Combine(root,"origin.git");var seed=Path.Combine(root,"seed");Directory.CreateDirectory(seed);
            await Git(root,"init","--bare","-b","master",bare);await Git(seed,"init","-b","master");
            var source=Path.Combine(seed,"WebApplication");Directory.CreateDirectory(source);
            File.WriteAllText(Path.Combine(seed,".gitignore"),"**/bin/\n**/obj/\n");
            File.WriteAllText(Path.Combine(source,"WebApplication.csproj"),"fixture");File.WriteAllText(Path.Combine(source,"marker.txt"),"one");
            await Commit(seed,"initial");await Git(seed,"remote","add","origin",bare);await Git(seed,"push","origin","master");
            var clone=Path.Combine(root,"checkout");await Git(root,"clone",bare,clone);var project=Path.Combine(clone,"WebApplication");
            Task<string> Run(string[] args)=>args is ["remote","get-url","origin"]?Task.FromResult("https://github.com/"+UpdateContract.Repository+".git"):Git(project,args);
            var repository=new SourceRepository(project,Run);var first=await Git(clone,"rev-parse","HEAD");
            Assert((await repository.Check(first[..12]))["state"]!.ToString()=="up_to_date","Clean matching source must be latest");
            File.WriteAllText(Path.Combine(project,"marker.txt"),"developer edit");
            Assert((await repository.Check(first[..12]))["state"]!.ToString()=="source_changed","Local edits must not be latest");
            await Reject(async()=>{await repository.Pull("master");});
            Assert(File.ReadAllText(Path.Combine(project,"marker.txt"))=="developer edit","Pull overwrote a local edit");
            await Git(clone,"checkout","--","WebApplication/marker.txt");
            File.WriteAllText(Path.Combine(project,"untracked.txt"),"keep");await Reject(async()=>{await repository.Pull("master");});File.Delete(Path.Combine(project,"untracked.txt"));
            File.WriteAllText(Path.Combine(source,"marker.txt"),"two");await Commit(seed,"remote update");await Git(seed,"push","origin","master");
            var second=await Git(seed,"rev-parse","HEAD");
            Assert((await repository.Check(first[..12]))["state"]!.ToString()=="source_out_of_sync","Remote source update was not found");
            Assert(await Git(clone,"rev-parse","refs/remotes/origin/master")==first && File.ReadAllText(Path.Combine(project,"marker.txt"))=="one","Read-only check fetched or changed source");
            await Reject(async()=>{await repository.Pull("another-branch");});
            await repository.Pull("master");
            Assert(await Git(clone,"rev-parse","HEAD")==second && File.ReadAllText(Path.Combine(project,"marker.txt"))=="two","Fast-forward did not update checkout");
            Assert((await repository.Check(first[..12]))["state"]!.ToString()=="rebuild_required","Uncompiled new source was called latest");
            Assert((await repository.Check(second[..12]))["state"]!.ToString()=="up_to_date","New matching build was not latest");
            File.WriteAllText(Path.Combine(project,"marker.txt"),"local commit");await Commit(clone,"local-only");var local=await Git(clone,"rev-parse","HEAD");
            File.WriteAllText(Path.Combine(source,"marker.txt"),"remote commit");await Commit(seed,"remote divergence");await Git(seed,"push","origin","master");
            await Reject(async()=>{await repository.Pull("master");});
            Assert(await Git(clone,"rev-parse","HEAD")==local && File.ReadAllText(Path.Combine(project,"marker.txt"))=="local commit","Divergence lost local commits or files");
            await Git(clone,"checkout","--detach","HEAD");await Reject(async()=>{await repository.Read();});
            var foreign=new SourceRepository(project,args=>args[0]=="remote"?Task.FromResult("https://github.com/other/repository.git"):Run(args));await Reject(async()=>{await foreign.Read();});
            foreach(var scenario in new[]{"success","compile-failure","dirty","network-failure"}) {
                var checkout=Path.Combine(root,"worker-"+scenario);await Git(root,"clone",bare,checkout);var workerProject=Path.Combine(checkout,"WebApplication");
                File.WriteAllText(Path.Combine(source,"marker.txt"),scenario);await Commit(seed,scenario);await Git(seed,"push","origin","master");
                var target=await Git(seed,"rev-parse","HEAD");
                if(scenario=="dirty")File.WriteAllText(Path.Combine(workerProject,"marker.txt"),"developer work");
                var output=Path.Combine(workerProject,"bin","Debug","net8.0");Directory.CreateDirectory(output);
                File.Copy(typeof(Worker).Assembly.Location,Path.Combine(output,"WebApplication.dll"));File.WriteAllText(Path.Combine(output,"appsettings.json"),"keep-config");File.WriteAllText(Path.Combine(output,"hangfire.db"),"keep-data");
                var state=Path.Combine(root,"state-"+scenario);Directory.CreateDirectory(state);
                var config=new Installation("site","pool",state,state,"http://127.0.0.1:1","device","fixture","source",workerProject,output);
                var job=new UpdateJob(Guid.NewGuid().ToString("N"),"requested","",1,"source",SourceBuild:true,PullSource:true,SourceBranch:"master");
                UpdateContract.Write(Path.Combine(state,"installation.json"),config);UpdateContract.WriteJob(state,job);
                FakeSourceRuntime? runtime=null;
                async Task<string> Runner(string[] args){
                    if(args[0] is "fetch" or "merge")Assert(!runtime!.BridgeStopped && runtime.Commands.Count==0,"Website stopped before network/Git checks completed");
                    if(args[0]=="fetch" && scenario=="network-failure")throw new IOException("network unavailable");
                    return args is ["remote","get-url","origin"]?"https://github.com/"+UpdateContract.Repository+".git":await Git(workerProject,args);
                }
                runtime=new FakeSourceRuntime(output,scenario=="compile-failure",false,git:Runner);
                await new Worker(Path.Combine(state,"installation.json"),runtime).Run();var outcome=UpdateContract.Read<UpdateJob>(Path.Combine(state,"job.json"));
                Assert(outcome.Phase==(scenario switch {"success"=>"completed","compile-failure"=>"rolled_back",_=>"failed"}),"Wrong pull/update outcome: "+scenario+":"+outcome.Message);
                if(scenario is "dirty" or "network-failure")Assert(!runtime.Compiled && !runtime.BridgeStopped && runtime.Commands.Count==0,"Rejected pull stopped tasks/site");
                else Assert(await Git(checkout,"rev-parse","HEAD")==target,"Pulled source was reset or not installed");
                if(scenario=="compile-failure")Assert(outcome.Message.Contains("已拉取源码保留"),"Rollback claims source was untouched");
                Assert(File.ReadAllText(Path.Combine(output,"appsettings.json"))=="keep-config" && File.ReadAllText(Path.Combine(output,"hangfire.db"))=="keep-data","Pull/update overwrote deployment data");
            }
            Console.WriteLine($"Source updates: {checks} real-Git/read-only-status/fast-forward/worker/rollback checks passed");
        } finally {
            if(!Path.GetFullPath(root).StartsWith(Path.GetFullPath(Path.GetTempPath()),StringComparison.OrdinalIgnoreCase))throw new Exception("Unsafe fixture cleanup path");
            foreach(var file in Directory.EnumerateFiles(root,"*",SearchOption.AllDirectories))File.SetAttributes(file,File.GetAttributes(file)&~FileAttributes.ReadOnly);
            Directory.Delete(root,true);
        }
    }
}
