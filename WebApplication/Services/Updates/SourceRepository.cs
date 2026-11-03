using System.Diagnostics;
using System.Text.Json.Nodes;
using RemoteTool.Updates;

namespace RemoteTool.WebApi.Services.Updates;

public sealed record SourceCheckout(string Branch, string Revision, bool Dirty);

/// <summary>Read-only checks and an explicitly requested fast-forward of this checkout.</summary>
public sealed class SourceRepository(string project, Func<string[],Task<string>>? run = null)
{
    private async Task<string> Git(params string[] arguments) {
        if(run != null)return await run(arguments);
        var info=new ProcessStartInfo("git") {WorkingDirectory=project,UseShellExecute=false,CreateNoWindow=true,RedirectStandardOutput=true,RedirectStandardError=true};
        info.ArgumentList.Add("-c");info.ArgumentList.Add("safe.directory="+Directory.GetParent(project)!.FullName);
        foreach(var argument in arguments)info.ArgumentList.Add(argument);
        info.Environment["GIT_TERMINAL_PROMPT"]="0";info.Environment["GCM_INTERACTIVE"]="Never";
        using var process=Process.Start(info) ?? throw new IOException("无法启动 Git");
        var output=process.StandardOutput.ReadToEndAsync();var errors=process.StandardError.ReadToEndAsync();
        using var deadline=new CancellationTokenSource(TimeSpan.FromSeconds(arguments[0]=="fetch"?120:30));
        try {await process.WaitForExitAsync(deadline.Token);}
        catch {if(!process.HasExited)process.Kill(true);throw new IOException("Git 操作超时，网站未停止");}
        var text=await output;await errors;
        if(process.ExitCode!=0)throw new IOException(arguments[0] switch {
            "fetch"=>"拉取源码失败，请检查当前服务身份的网络和 Git 访问权限",
            "merge"=>"无法快进更新，分支可能已分歧；请先手动同步源码",
            "ls-remote"=>"无法检查远端源码版本，请检查网络、当前分支及 Git 访问权限",
            _=>"无法读取源码仓库状态"
        });
        return text.Trim();
    }
    public async Task<SourceCheckout> Read() {
        var origin=await Git("remote","get-url","origin");
        var owned=origin.TrimEnd('/').Equals("https://github.com/"+UpdateContract.Repository+".git",StringComparison.OrdinalIgnoreCase) ||
            origin.TrimEnd('/').Equals("https://github.com/"+UpdateContract.Repository,StringComparison.OrdinalIgnoreCase) ||
            origin.Equals("git@github.com:"+UpdateContract.Repository+".git",StringComparison.OrdinalIgnoreCase) ||
            origin.Equals("git@github.com:"+UpdateContract.Repository,StringComparison.OrdinalIgnoreCase);
        if(!owned)throw new IOException("当前源码的 origin 不是 "+UpdateContract.Repository+"，请先核对仓库");
        var branch=await Git("branch","--show-current");
        if(string.IsNullOrWhiteSpace(branch))throw new IOException("源码处于分离 HEAD 状态，请先切换到要更新的分支");
        return new(branch,await Git("rev-parse","HEAD"),(await Git("status","--porcelain","--untracked-files=no")).Length>0);
    }
    private async Task<bool> IsAncestor(string ancestor, string descendant) {
        try { await Git("merge-base","--is-ancestor",ancestor,descendant); return true; }
        catch { return false; }
    }
    public async Task<JsonObject> Check(string runningVersion) {
        var local=await Read();
        var remote=(await Git("ls-remote","--exit-code","origin","refs/heads/"+local.Branch)).Split((char[]?)null,StringSplitOptions.RemoveEmptyEntries).FirstOrDefault() ?? "";
        if(remote.Length!=40 || !remote.All(Uri.IsHexDigit))throw new IOException("远端源码版本无效");
        var current=await Read();
        if(current.Branch!=local.Branch)throw new IOException("检查期间分支已变化，请重新检查");
        local=current;
        // 本地比远端新（有未推送提交）时不该再提示“可更新”：拉取改变不了任何东西
        var runningMatches = runningVersion.Length>=7 && !runningVersion.EndsWith("-dirty",StringComparison.Ordinal) &&
            local.Revision.StartsWith(runningVersion,StringComparison.OrdinalIgnoreCase);
        var relation = local.Revision==remote ? "same"
            : await IsAncestor(remote,local.Revision) ? "ahead"
            : await IsAncestor(local.Revision,remote) ? "behind" : "diverged";
        var state=local.Dirty?"source_changed":relation switch {
            "diverged"=>"source_diverged",
            "behind"=>"source_out_of_sync",
            "ahead" or "same"=>runningMatches?"up_to_date":"rebuild_required",
            _=>"source_out_of_sync"
        };
        var message=state switch {
            "up_to_date"=>"已是最新版本",
            "source_changed"=>"本地源码有未提交改动，可编译当前源码",
            "source_out_of_sync"=>"远端有新的提交，可拉取并更新",
            "source_diverged"=>"本地与远端已分叉，需先手动合并再更新",
            _=>"源码已更新，需编译并重启"
        };
        return new() { ["kind"]="source",["state"]=state,["message"]=message,["checkedAt"]=DateTimeOffset.UtcNow,
            ["available"]=state is "source_out_of_sync" or "rebuild_required",["runningVersion"]=runningVersion,["branch"]=local.Branch,["localRevision"]=local.Revision,["latestRevision"]=remote };
    }
    public async Task<SourceCheckout> Pull(string expectedBranch) {
        var before=await Read();
        if(before.Branch!=expectedBranch)throw new IOException("当前分支已变化，请重新检查后再更新");
        if(before.Dirty)throw new IOException("本地有未提交或未跟踪的改动，请先处理后再拉取；可以选择编译当前源码");
        await Git("fetch","--no-tags","origin",expectedBranch);
        var current=await Read();
        if(current!=before)throw new IOException("拉取期间本地源码发生变化，已取消更新");
        await Git("merge","--ff-only","FETCH_HEAD");
        var after=await Read();
        if(after.Dirty || after.Branch!=expectedBranch)throw new IOException("更新后本地源码发生变化，已取消编译；本地改动保留");
        return after;
    }
}
