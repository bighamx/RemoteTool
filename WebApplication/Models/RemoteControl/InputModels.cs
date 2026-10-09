
namespace ChuckieHelper.WebApi.Models.RemoteControl;

public class ClipboardRequest
{
    public string? Text { get; set; }
}

public class ClickRequest
{
    public double X { get; set; }
    public double Y { get; set; }
}

public class MouseEventRequest
{
    public double X { get; set; }
    public double Y { get; set; }
    public int Button { get; set; } = 0; // 0=左键, 1=中键, 2=右键
}

/// <summary>滚轮请求：归一化坐标 + 滚动量（正=上滚，负=下滚）</summary>
public class MouseWheelRequest
{
    public double X { get; set; }
    public double Y { get; set; }
    public int Delta { get; set; } // 如 ±120 表示一档
}

public class KeyboardRequest
{
    public int VkCode { get; set; }
    public bool IsKeyDown { get; set; }
}

public class KeyboardMultiRequest
{
    public int[] VkCodes { get; set; } = Array.Empty<int>();
    public bool IsKeyDown { get; set; }
}
