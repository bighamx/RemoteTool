using ChuckieHelper.WebApi.Services.RemoteControl;

var root = Path.Combine(Path.GetTempPath(), "chuckie-files-check-" + Guid.NewGuid().ToString("N"));
Directory.CreateDirectory(root); var count = 0;
void Check(bool valid, string name) { if (!valid) throw new Exception(name); count++; }
void Reject(Action action, string name) { try { action(); throw new Exception(name); } catch (ArgumentException) { count++; } }
try {
    Check(FilePathPolicy.IsWithin(root, Path.Combine(root, "child")), "child allowed");
    Check(!FilePathPolicy.IsWithin(root, root + "-sibling/file"), "prefix sibling refused");
    Reject(() => FilePathPolicy.Resolve(root, ""), "blank path must not map to root");
    Reject(() => FilePathPolicy.Resolve(root, "../escape"), "relative escape");
    var service = new FileService();
    var source = Path.Combine(root, "source"); Directory.CreateDirectory(source);
    var nested = Path.Combine(source, "child"); Directory.CreateDirectory(nested); File.WriteAllText(Path.Combine(nested, "value.txt"), "source");
    Check(!service.CopyDirectory(source, Path.Combine(source, "copy")), "self-recursive copy rejected");
    Check(!Directory.Exists(Path.Combine(source, "copy")), "no recursive destination created");
    var dest = Path.Combine(root, "dest"); Check(service.CopyDirectory(source, dest), "normal recursive copy");
    Check(File.ReadAllText(Path.Combine(dest, "child", "value.txt")) == "source", "copied nested content");
    File.WriteAllText(Path.Combine(dest, "child", "value.txt"), "keep");
    Check(!service.CopyDirectory(source, dest, false), "nested failure propagates");
    Check(File.ReadAllText(Path.Combine(dest, "child", "value.txt")) == "keep", "copy preserves conflict");
    var a = Path.Combine(root, "a.txt"); var b = Path.Combine(root, "b.txt"); File.WriteAllText(a, "a"); File.WriteAllText(b, "b");
    Check(!service.MoveFile(a, b) && File.ReadAllText(a) == "a" && File.ReadAllText(b) == "b", "move preserves both conflicts");
    Check(!service.Rename(a, b) && File.ReadAllText(b) == "b", "rename preserves destination");
    var large = Path.Combine(root, "large.txt"); using (var f = File.Create(large)) f.SetLength(10 * 1024 * 1024 + 1);
    try { await service.ReadFileAsync(large); throw new Exception("large file editable placeholder returned"); } catch (IOException) { count++; }
    try { await service.ReadFileAsync(Path.Combine(root, "missing")); throw new Exception("missing file editable blank returned"); } catch (FileNotFoundException) { count++; }
    try { service.GetFiles(Path.Combine(root, "missing")); throw new Exception("missing directory returned empty success"); } catch (DirectoryNotFoundException) { count++; }
    Check(!service.DeleteDirectory(""), "blank delete refused");
    var zip = Path.Combine(root, "nested.zip");
    using (var archive = System.IO.Compression.ZipFile.Open(zip, System.IO.Compression.ZipArchiveMode.Create)) {
        using var writer = new StreamWriter(archive.CreateEntry("folder/test.txt").Open()); writer.Write("archive content");
    }
    var extract = Path.Combine(root, "extract");
    Check(service.Decompress(zip, extract) && File.ReadAllText(Path.Combine(extract, "folder", "test.txt")) == "archive content", "nested archive paths retained");
    File.WriteAllText(Path.Combine(extract, "folder", "test.txt"), "preserve");
    Check(!service.Decompress(zip, extract) && File.ReadAllText(Path.Combine(extract, "folder", "test.txt")) == "preserve", "extraction never silently overwrites");
    var malicious = Path.Combine(root, "bad.zip");
    using (var archive = System.IO.Compression.ZipFile.Open(malicious, System.IO.Compression.ZipArchiveMode.Create)) {
        archive.CreateEntry("safe.txt"); archive.CreateEntry("../escaped/");
    }
    var reject = Path.Combine(root, "reject");
    Check(!service.Decompress(malicious, reject), "archive traversal refused");
    Check(!File.Exists(Path.Combine(reject, "safe.txt")) && !Directory.Exists(Path.Combine(root, "escaped")), "invalid archive preflight writes no entries");
} finally { Directory.Delete(root, true); }
Console.WriteLine($"Remote file checks: {count} passed");
