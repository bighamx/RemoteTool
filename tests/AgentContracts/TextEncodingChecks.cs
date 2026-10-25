using System.Text;
using RemoteTool.WebApi.Services;

internal static class TextEncodingChecks {
    public static void Run() {
        Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);
        const string chinese = "编码修复验证：中文正常";

        // 1) 标准 UTF-8
        if (TextEncodingRepair.Decode(Encoding.UTF8.GetBytes(chinese), null) != chinese)
            throw new Exception("UTF-8 bodies must decode unchanged");

        // 2) GBK 字节、无 charset 头（中文 Windows 客户端）
        if (TextEncodingRepair.Decode(Encoding.GetEncoding(936).GetBytes(chinese), null) != chinese)
            throw new Exception("GBK bytes must fall back to GB18030");

        // 3) 显式 charset 优先
        if (TextEncodingRepair.Decode(Encoding.GetEncoding(936).GetBytes(chinese), "gbk") != chinese)
            throw new Exception("Explicit charset must win");

        // 4) 双重编码（UTF-8 字节被当作 Latin-1 再编码）
        var mojibake = Encoding.Latin1.GetString(Encoding.UTF8.GetBytes(chinese));
        if (TextEncodingRepair.RepairText(mojibake) != chinese)
            throw new Exception("Double-encoded text must be repaired");

        // 5) 混合文本只修乱码片段，正常中文与西文保持原样
        if (TextEncodingRepair.RepairText("正常" + mojibake + "café") != "正常" + chinese + "café")
            throw new Exception("Mixed text must repair only the mojibake run");

        // 6) 纯西文/正常文本不得被误改
        foreach (var safe in new[] { "plain ascii", "Größe", "naïve café", chinese })
            if (TextEncodingRepair.RepairText(safe) != safe)
                throw new Exception($"Plain text must stay untouched: {safe}");

        Console.WriteLine("Text encoding tolerance checks passed: utf8, gbk fallback, latin-1 repair, mixed runs, no false positives");
    }
}
