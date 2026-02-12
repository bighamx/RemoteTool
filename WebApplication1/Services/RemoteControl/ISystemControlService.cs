using System.Collections.Generic;
using System.Threading.Tasks;

namespace ChuckieHelper.WebApi.Services.RemoteControl;

public interface ISystemControlService
{
    // Power
    void Shutdown();
    void CancelShutdown();
    void Reboot();
    void Sleep();
    void Hibernate();
    void LockWorkstation();

    // Process
    Task<List<ProcessInfo>> GetProcessListAsync();
    bool KillProcess(int processId);

    // System Info
    SystemInfo GetSystemInfo();

    // Screen Capture
    byte[] CaptureScreen();
    Task<byte[]> CaptureScreenAsync();

    // Input - Mouse
    void SendMouseClick(double normalizedX, double normalizedY);
    void SendMouseRightClick(double normalizedX, double normalizedY);
    void SendMouseMiddleClick(double normalizedX, double normalizedY);
    void SendMouseDown(double normalizedX, double normalizedY, int button = 0);
    void SendMouseUp(double normalizedX, double normalizedY, int button = 0);
    void SendMouseMove(double normalizedX, double normalizedY);
    void SendMouseWheel(double normalizedX, double normalizedY, int delta);

    // Input - Keyboard
    void SendKeyboardEvent(byte vkCode, bool isKeyDown);
    void SendKeyboardEvents(byte[] vkCodes, bool isKeyDown);

    // Clipboard
    string GetClipboardText();
    void SetClipboardText(string text);
}
