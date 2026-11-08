using System.Security.Claims;
using RemoteTool.WebApi.Services.RemoteControl;

var root = Path.Combine(Path.GetTempPath(), "remotetool-bookmarks-" + Guid.NewGuid().ToString("N"));
var count = 0;
void Check(bool valid, string name) { if (!valid) throw new Exception(name); count++; }
ClaimsPrincipal User(string source, string provider, string name) => new(new ClaimsIdentity([
    new Claim("remotetool:auth-source", source), new Claim("remotetool:auth-provider", provider),
    new Claim(ClaimTypes.Name, name)
], "test"));
try {
    var store = new FileBookmarkStore(root);
    var alice = User("oidc", "main", "alice@example.com");
    var aliceOtherProvider = User("oidc", "other", "alice@example.com");
    var password = User("password", "local", "admin");
    var first = store.Add(alice, Path.Combine(root, "projects", "one"));
    Check(first.Items.Count == 1 && first.Revision == 1, "first bookmark persisted");
    var same = store.Add(alice, Path.Combine(root, "projects", "one") + Path.DirectorySeparatorChar);
    Check(same.Items.Count == 1 && same.Revision == 1, "duplicate does not change revision");
    Check(store.Read(aliceOtherProvider).Items.Count == 0, "OIDC providers are isolated");
    Check(store.Read(password).Items.Count == 0, "password identity is isolated");
    store.Add(password, "/srv/releases");
    Check(store.Read(alice).Items.Count == 1 && store.Read(password).Items.Count == 1, "users do not overwrite each other");
    var removed = store.Remove(alice, Path.Combine(root, "projects", "one"));
    Check(removed.Items.Count == 0 && removed.Revision == 2 && store.Read(alice).Revision == 2, "removal is persisted atomically");
    Check(Directory.EnumerateFiles(root, "*.json").Count() == 2, "one protected file per stable identity");
} finally { if (Directory.Exists(root)) Directory.Delete(root, true); }
Console.WriteLine($"File bookmark checks: {count} passed");
