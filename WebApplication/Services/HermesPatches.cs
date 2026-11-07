#nullable enable
using System.Collections.Concurrent;
using System.Diagnostics;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace RemoteTool.WebApi.Services;

/// <summary>Hermes 补丁管理。
///
/// 补丁清单放在 <c>&lt;hermes home&gt;/patches/manifest.json</c>，每个条目描述一个逻辑补丁及其 .patch 文件；
/// 应用走标准 <c>git apply</c>（先 --check 探测，失败即不动工作树），成功后对受影响的 .py 做 py_compile。
/// 标准应用失败时，用 <c>v1/runs</c> 起一个 Hermes 会话，把补丁意图和失败输出交给模型自行实现（agent-fix）。
/// </summary>
public sealed class HermesPatches(HermesProfiles profiles, IConfiguration configuration, HermesBridge bridge, IWebHostEnvironment environment)
{
    public sealed record Outcome(JsonObject Body, int Status);

    private string Home => profiles.Home;
    private string Repo => configuration["Hermes:SourceDirectory"] is { Length: > 0 } dir ? dir : Path.Combine(Home, "hermes-agent");
    // 补丁包随 RemoteTool 一起发布(仓库 patches/ 目录), 不是用户本机 patches 目录里的东西。
    private string CatalogDir => Path.Combine(environment.ContentRootPath, "patches");
    private string ManifestPath => Path.Combine(CatalogDir, "manifest.json");
    private string PatchesDir => Path.Combine(CatalogDir, "files");
    private string Python => configuration["Hermes:PythonExecutable"] is { Length: > 0 } py
        ? py
        : Path.Combine(Repo, "venv", "Scripts", "python.exe");

    /// <summary>本机 Hermes 版本, 取自网关状态文件; 取不到就返回空串。</summary>
    private string HermesVersion()
    {
        try {
            var state = JsonNode.Parse(File.ReadAllText(Path.Combine(Home, "gateway_state.json")));
            return state?["code_version"]?.ToString() ?? "";
        } catch (Exception error) when (error is IOException or JsonException) { return ""; }
    }

    // 版本拿不到时必须失败关闭。把所有补丁当作可用会在未知版本上直接修改源码。
    private static bool VersionMatches(string wanted, string actual) =>
        wanted.Length == 0 || actual.Length > 0 &&
        actual.StartsWith(wanted, StringComparison.OrdinalIgnoreCase);

    private static bool IsInside(string root, string relative, out string full)
    {
        full = "";
        if (string.IsNullOrWhiteSpace(relative) || Path.IsPathRooted(relative)) return false;
        var rootFull = Path.GetFullPath(root);
        full = Path.GetFullPath(Path.Combine(rootFull, relative));
        var rel = Path.GetRelativePath(rootFull, full);
        return rel != ".." && !rel.StartsWith(".." + Path.DirectorySeparatorChar, StringComparison.Ordinal) &&
            !rel.StartsWith(".." + Path.AltDirectorySeparatorChar, StringComparison.Ordinal);
    }

    // ---------- 清单 ----------

    private JsonArray Entries()
    {
        if (!File.Exists(ManifestPath)) return new JsonArray();
        try {
            var root = JsonNode.Parse(File.ReadAllText(ManifestPath));
            return root?["patches"] as JsonArray ?? new JsonArray();
        } catch (Exception error) when (error is IOException or JsonException) {
            return new JsonArray();
        }
    }

    private JsonObject? Entry(string id) => Entries().OfType<JsonObject>()
        .FirstOrDefault(item => string.Equals(item["id"]?.ToString(), id, StringComparison.Ordinal));

    private static string[] Files(JsonObject entry) =>
        (entry["patch_files"] as JsonArray)?.Select(node => node?.ToString() ?? "").Where(name => name.Length > 0).ToArray() ?? [];

    private static string[] Targets(JsonObject entry) =>
        (entry["files"] as JsonArray)?.Select(node => node?.ToString() ?? "").Where(name => name.Length > 0).ToArray() ?? [];

    // ---------- 状态 ----------

    private sealed record PatchState(bool Applied, bool Applicable, string? Missing, string? Detail, bool Incompatible = false);

    private PatchState Inspect(JsonObject entry)
    {
        var names = Files(entry);
        if (names.Length == 0) return new PatchState(false, false, null, "清单条目缺少 patch_files");
        foreach (var name in names) {
            if (!IsInside(PatchesDir, name, out _))
                return new PatchState(false, false, null, $"补丁文件路径越界: {name}");
        }
        var targets = Targets(entry);
        foreach (var target in targets) {
            if (!IsInside(Repo, target, out _))
                return new PatchState(false, false, null, $"目标文件路径越界: {target}");
        }
        var wants = entry["hermes_version"]?.ToString() ?? "";
        if (!VersionMatches(wants, HermesVersion()))
            return new PatchState(false, false, null, $"只适用于 Hermes {wants}（本机 {HermesVersion()}）", Incompatible: true);
        var appliedCount = 0;
        var applicableCount = 0;
        string? detail = null;
        foreach (var name in names) {
            if (!IsInside(PatchesDir, name, out var path) || !File.Exists(path))
                return new PatchState(false, false, name, "补丁文件不存在");
            var reverse = Git(["apply", "--reverse", "--check", path], Repo);
            if (reverse.Exit == 0) { appliedCount++; continue; }
            var forward = Git(["apply", "--check", path], Repo);
            if (forward.Exit == 0) applicableCount++;
            else detail ??= FirstLine(forward.Error) ?? FirstLine(forward.Output);
        }
        if (appliedCount == names.Length) return new PatchState(true, false, null, null);
        if (appliedCount == 0 && applicableCount == names.Length) return new PatchState(false, true, null, null);
        return new PatchState(false, false, null, detail ?? "部分 patch 已应用或上下文不匹配");
    }

    public JsonObject List()
    {
        var rows = new JsonArray();
        var version = HermesVersion();
        foreach (var entry in Entries().OfType<JsonObject>()) {
            var state = Inspect(entry);
            rows.Add(new JsonObject {
                ["id"] = entry["id"]?.ToString(),
                ["title"] = entry["title"]?.ToString(),
                ["summary"] = entry["summary"]?.ToString(),
                ["detail"] = entry["detail"]?.ToString(),
                ["requires"] = entry["hermes_version"]?.ToString(),
                ["state"] = state.Applied ? "applied" : state.Incompatible ? "incompatible" : state.Applicable ? "ready" : "conflict",
                ["restart"] = entry["restart"]?.GetValue<bool>() == true,
                ["files"] = new JsonArray(Targets(entry).Select(name => (JsonNode?)name).ToArray()),
                ["patch_files"] = new JsonArray(Files(entry).Select(name => (JsonNode?)name).ToArray()),
                ["detail_text"] = state.Detail ?? state.Missing,
            });
        }
        return new JsonObject {
            ["repo"] = Repo,
            ["manifest"] = ManifestPath,
            ["manifest_present"] = File.Exists(ManifestPath),
            ["hermes_version"] = version,
            ["patches"] = rows,
            ["pending"] = rows.Count(row => row?["state"]?.ToString() == "ready"),
            ["available"] = rows.Count(row => row?["state"]?.ToString() is "ready" or "conflict"),
        };
    }

    // ---------- 标准应用 ----------

    private static readonly ConcurrentDictionary<string, SemaphoreSlim> ApplyGates =
        new(StringComparer.OrdinalIgnoreCase);

    public async Task<Outcome> ApplyAsync(string id, CancellationToken ct = default)
    {
        var gate = ApplyGates.GetOrAdd(Path.GetFullPath(Repo), _ => new SemaphoreSlim(1, 1));
        await gate.WaitAsync(ct);
        try { return ApplyLocked(id); }
        finally { gate.Release(); }
    }

    private Outcome ApplyLocked(string id)
    {
        if (Entry(id) is not { } entry) return new Outcome(new JsonObject { ["message"] = "未知补丁: " + id }, StatusCodes.Status404NotFound);
        var names = Files(entry);
        if (names.Length == 0) return new Outcome(new JsonObject {
            ["ok"] = false, ["state"] = "conflict", ["needs_agent"] = false,
            ["message"] = "清单条目缺少 patch_files",
        }, StatusCodes.Status409Conflict);
        var state = Inspect(entry);
        if (state.Applied) return new Outcome(new JsonObject { ["ok"] = true, ["state"] = "applied", ["message"] = "补丁已是应用状态" }, StatusCodes.Status200OK);
        if (state.Incompatible) return new Outcome(new JsonObject {
            ["ok"] = false, ["state"] = "incompatible", ["needs_agent"] = true,
            ["message"] = state.Detail ?? "此补丁不适用于当前 Hermes 版本",
        }, StatusCodes.Status409Conflict);
        if (!state.Applicable) return new Outcome(new JsonObject {
            ["ok"] = false, ["state"] = "conflict", ["needs_agent"] = true,
            ["message"] = state.Detail ?? "补丁上下文不匹配，可让 Hermes 自行实现",
        }, StatusCodes.Status409Conflict);

        var log = new StringBuilder();
        var patchPaths = new List<(string Name, string Path)>();
        // Check the complete set before writing anything. A later patch failing after an early
        // patch was applied used to trigger a checkout that also wiped pre-existing user edits.
        foreach (var name in names) {
            if (!IsInside(PatchesDir, name, out var path)) return new Outcome(new JsonObject {
                ["ok"] = false, ["state"] = "conflict", ["needs_agent"] = false,
                ["message"] = "补丁文件路径越界: " + name,
            }, StatusCodes.Status409Conflict);
            var check = Git(["apply", "--check", path], Repo);
            if (check.Exit != 0) {
                log.AppendLine($"[check 失败] {name}: {FirstLine(check.Error) ?? FirstLine(check.Output)}");
                return new Outcome(new JsonObject {
                    ["ok"] = false, ["state"] = "conflict", ["needs_agent"] = true,
                    ["message"] = "补丁无法直接应用，可让 Hermes 自行实现", ["log"] = log.ToString(),
                }, StatusCodes.Status409Conflict);
            }
            patchPaths.Add((name, path));
        }

        Directory.CreateDirectory(Path.Combine(Home, "backups"));
        var stamp = DateTime.Now.ToString("yyyyMMdd-HHmmss-fff");
        var backup = Path.Combine(Home, "backups", $"patches-apply-{stamp}.patch");
        var diff = Git(["diff", "--binary"], Repo);
        if (diff.Exit != 0) return new Outcome(new JsonObject {
            ["ok"] = false, ["state"] = "failed",
            ["message"] = "无法备份现有工作树，未修改任何文件", ["log"] = FirstLine(diff.Error) ?? "",
        }, StatusCodes.Status500InternalServerError);
        var hasBackup = diff.Output.Length > 0;
        if (hasBackup) File.WriteAllText(backup, diff.Output);
        log.AppendLine($"备份工作树差异: {(hasBackup ? backup : "(无改动)")}");

        // Exact bytes are the last-resort rollback if the patch cannot be reversed cleanly.
        var snapshots = new Dictionary<string, byte[]?>(StringComparer.OrdinalIgnoreCase);
        foreach (var target in Targets(entry)) {
            if (!IsInside(Repo, target, out var full)) continue;
            snapshots[full] = File.Exists(full) ? File.ReadAllBytes(full) : null;
        }

        var applied = new List<string>();
        foreach (var patch in patchPaths) {
            var apply = Git(["apply", patch.Path], Repo);
            log.AppendLine($"[apply rc={apply.Exit}] {patch.Name}");
            if (apply.Exit != 0) {
                log.AppendLine(FirstLine(apply.Error) ?? "");
                RollbackApplied(applied, snapshots, log);
                return new Outcome(new JsonObject {
                    ["ok"] = false, ["state"] = "conflict", ["needs_agent"] = true,
                    ["message"] = "补丁应用失败，本次改动已撤销，可让 Hermes 自行实现", ["log"] = log.ToString(),
                }, StatusCodes.Status409Conflict);
            }
            applied.Add(patch.Path);
        }

        var compileFailed = new List<string>();
        foreach (var file in Targets(entry).Where(name => name.EndsWith(".py", StringComparison.OrdinalIgnoreCase))) {
            if (!IsInside(Repo, file, out var full) || !File.Exists(full)) continue;
            var compile = Git(["-m", "py_compile", file], Repo, Python);
            log.AppendLine($"[py_compile rc={compile.Exit}] {file}");
            if (compile.Exit != 0) { compileFailed.Add(file); log.AppendLine(FirstLine(compile.Error) ?? ""); }
        }
        if (compileFailed.Count > 0) {
            RollbackApplied(applied, snapshots, log);
            return new Outcome(new JsonObject {
                ["ok"] = false, ["state"] = "rolled_back", ["needs_agent"] = true,
                ["message"] = "语法检查未通过，本次改动已撤销: " + string.Join(", ", compileFailed), ["log"] = log.ToString(),
            }, StatusCodes.Status409Conflict);
        }

        var after = Inspect(entry);
        return new Outcome(new JsonObject {
            ["ok"] = after.Applied, ["state"] = after.Applied ? "applied" : "unknown",
            ["restart"] = entry["restart"]?.GetValue<bool>() == true,
            ["message"] = after.Applied ? "补丁已应用" + (entry["restart"]?.GetValue<bool>() == true ? "，重启网关后生效" : "") : "补丁状态未变",
            ["log"] = log.ToString(),
        }, StatusCodes.Status200OK);
    }

    // Reverse only the patches this request successfully applied. checkout would restore HEAD
    // and erase unrelated edits that existed before the request.
    private void RollbackApplied(IReadOnlyList<string> applied, IReadOnlyDictionary<string, byte[]?> snapshots, StringBuilder log)
    {
        var clean = true;
        for (var i = applied.Count - 1; i >= 0; i--) {
            var restore = Git(["apply", "--reverse", applied[i]], Repo);
            log.AppendLine($"[rollback rc={restore.Exit}] {Path.GetFileName(applied[i])}");
            if (restore.Exit != 0) { clean = false; log.AppendLine(FirstLine(restore.Error) ?? ""); }
        }
        if (clean) return;
        foreach (var (path, bytes) in snapshots) {
            try {
                if (bytes == null) { if (File.Exists(path)) File.Delete(path); }
                else {
                    Directory.CreateDirectory(Path.GetDirectoryName(path)!);
                    File.WriteAllBytes(path, bytes);
                }
                log.AppendLine($"[rollback snapshot] {Path.GetRelativePath(Repo, path)}");
            } catch (Exception error) when (error is IOException or UnauthorizedAccessException) {
                log.AppendLine($"[rollback snapshot 失败] {Path.GetRelativePath(Repo, path)}: {error.Message}");
            }
        }
    }

    // ---------- 交给 agent 自行实现 ----------

    public async Task<Outcome> AgentFix(string id, string? failureLog, string idempotencyKey, CancellationToken ct)
    {
        if (Entry(id) is not { } entry) return new Outcome(new JsonObject { ["message"] = "未知补丁: " + id }, StatusCodes.Status404NotFound);
        var goal = entry["goal"]?.ToString();
        if (string.IsNullOrWhiteSpace(goal)) goal = entry["summary"]?.ToString();
        var files = Targets(entry);
        var prompt = new StringBuilder();
        prompt.AppendLine($"仓库：{Repo}");
        prompt.AppendLine($"任务：按下述意图直接修改代码实现一个补丁（这是一次补丁失败后的兜底实现，不要试图复用 .patch 文件）。");
        prompt.AppendLine($"补丁名：{entry["title"]?.ToString()}");
        prompt.AppendLine($"意图：{goal}");
        if (files.Length > 0) prompt.AppendLine($"目标文件：{string.Join(", ", files)}");
        if (Inspect(entry).Incompatible)
            prompt.AppendLine($"注意：这是跨版本适配（补丁针对 Hermes {entry["hermes_version"]}，本机 {HermesVersion()}）。按本机实际代码结构实现同等效果，不要照抄旧版本的行号或字段名；如果该行为在本机版本里已经存在，直接说明并不要改动。");
        prompt.AppendLine("要求：只改实现该意图所必需的代码；改完对每个改动的 .py 执行 py_compile 验证；不要改动其他本地补丁的区域；最后列出改了哪些位置以及为什么。");
        if (!string.IsNullOrWhiteSpace(failureLog)) {
            prompt.AppendLine("标准 patch 应用失败的输出如下，供定位上下文差异：");
            prompt.AppendLine(failureLog!.Length > 4000 ? failureLog[..4000] : failureLog);
        }

        var body = JsonSerializer.SerializeToElement(new Dictionary<string, object> {
            ["input"] = prompt.ToString(),
            ["title"] = "补丁兜底实现: " + (entry["title"]?.ToString() ?? id),
        });
        using var response = await bridge.SendAsync(HttpMethod.Post, "v1/runs", body, idempotencyKey, ct);
        var text = await response.Content.ReadAsStringAsync(ct);
        if (!response.IsSuccessStatusCode)
            return new Outcome(new JsonObject {
                ["ok"] = false, ["message"] = "无法启动 Hermes 会话", ["detail"] = text.Length > 600 ? text[..600] : text,
            }, (int)response.StatusCode);
        JsonNode? parsed = null;
        try { parsed = JsonNode.Parse(text); } catch (JsonException) { }
        return new Outcome(new JsonObject {
            ["ok"] = true,
            ["run_id"] = parsed?["run_id"]?.ToString() ?? parsed?["id"]?.ToString(),
            ["session_id"] = parsed?["session_id"]?.ToString(),
            ["message"] = "已交给 Hermes 自行实现，可在会话里跟踪进度",
        }, StatusCodes.Status200OK);
    }

    // ---------- 进程 ----------

    private readonly record struct GitResult(int Exit, string Output, string Error);

    private static GitResult Git(string[] args, string workdir, string? executable = null)
    {
        try {
            var info = new ProcessStartInfo(executable ?? "git") {
                WorkingDirectory = workdir,
                RedirectStandardInput = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                CreateNoWindow = true,
                StandardOutputEncoding = Encoding.UTF8,
                StandardErrorEncoding = Encoding.UTF8,
            };
            if (executable == null) {
                // 服务进程(IIS 应用池)常以其它账户运行: 该 repo 属于 chuckie, git 会以
                // 「dubious ownership」拒绝, 表现为 `git diff` 报 Not a git repository。
                // 单次调用带上 safe.directory, 不去改全局配置。
                info.ArgumentList.Add("-c");
                info.ArgumentList.Add("safe.directory=" + workdir.Replace('\\', '/'));
                info.ArgumentList.Add("-c");
                info.ArgumentList.Add("core.pager=cat");
                info.ArgumentList.Add("--no-pager");
                info.Environment["GIT_PAGER"] = "cat";
            }
            foreach (var arg in args) info.ArgumentList.Add(arg);
            using var process = Process.Start(info);
            if (process == null) return new GitResult(-1, "", "无法启动进程");
            try { process.StandardInput.Close(); } catch (IOException) { }
            var stdout = new StringBuilder();
            var stderr = new StringBuilder();
            var done = new System.Threading.ManualResetEventSlim(false);
            process.OutputDataReceived += (_, e) => { if (e.Data != null) stdout.AppendLine(e.Data); };
            process.ErrorDataReceived += (_, e) => { if (e.Data != null) stderr.AppendLine(e.Data); };
            process.BeginOutputReadLine();
            process.BeginErrorReadLine();
            process.Exited += (_, _) => done.Set();
            process.EnableRaisingEvents = true;
            if (!process.WaitForExit(90_000)) {
                try { process.Kill(entireProcessTree: true); } catch (Exception killError) when (killError is InvalidOperationException or System.ComponentModel.Win32Exception) { }
                return new GitResult(-1, stdout.ToString(), "命令超时: " + string.Join(" ", args));
            }
            done.Wait(2_000);
            process.WaitForExit();
            return new GitResult(process.ExitCode, stdout.ToString(), stderr.ToString());
        } catch (Exception error) when (error is System.ComponentModel.Win32Exception or IOException or InvalidOperationException) {
            return new GitResult(-1, "", error.Message);
        }
    }

    private static string? FirstLine(string? text)
    {
        if (string.IsNullOrWhiteSpace(text)) return null;
        foreach (var line in text.Split('\n')) if (line.Trim().Length > 0) return line.Trim();
        return null;
    }
}
