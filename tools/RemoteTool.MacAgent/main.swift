import AppKit
import ScreenCaptureKit
import CoreImage
import ApplicationServices
import ImageIO
import CoreMedia
import CoreGraphics

// stdout is exclusively the JSONL protocol. No input or clipboard content is logged.
private func screenLocked() -> Bool {
    let session = CGSessionCopyCurrentDictionary() as? [String: Any]
    return (session?["CGSSessionScreenIsLocked"] as? NSNumber)?.boolValue == true
}

final class Capture: NSObject, SCStreamOutput {
    private let lock = NSLock()
    private var latest: Data?
    private let context = CIContext()
    private var stream: SCStream?
    private var currentDisplayID: CGDirectDisplayID?

    func start(displayID: CGDirectDisplayID) async throws {
        if stream != nil && currentDisplayID != displayID { await stop() }
        guard stream == nil else { return }
        guard !screenLocked() else { throw AgentError.message("Mac 当前已锁屏，请先解锁后再连接桌面") }
        guard CGPreflightScreenCaptureAccess() else { throw AgentError.message("请在系统设置中授予 RemoteTool Mac Agent 屏幕录制权限") }
        let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
        guard let display = content.displays.first(where: { $0.displayID == displayID }) else { throw AgentError.message("找不到所选显示器，请确认 Mac 已解锁且屏幕录制权限已开启") }
        let config = SCStreamConfiguration()
        config.width = min(1280, display.width)
        config.height = max(2, config.width * display.height / display.width)
        config.minimumFrameInterval = CMTime(value: 1, timescale: 5)
        config.queueDepth = 2
        config.showsCursor = true
        let candidate = SCStream(filter: SCContentFilter(display: display, excludingWindows: []), configuration: config, delegate: nil)
        try candidate.addStreamOutput(self, type: .screen, sampleHandlerQueue: DispatchQueue(label: "remote.capture"))
        try await candidate.startCapture()
        stream = candidate
        currentDisplayID = displayID
    }
    func stream(_ stream: SCStream, didOutputSampleBuffer sampleBuffer: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .screen, sampleBuffer.isValid, let buffer = sampleBuffer.imageBuffer else { return }
        let image = CIImage(cvPixelBuffer: buffer)
        guard let jpeg = context.jpegRepresentation(of: image, colorSpace: CGColorSpaceCreateDeviceRGB(), options: [CIImageRepresentationOption(rawValue: kCGImageDestinationLossyCompressionQuality as String): 0.6]) else { return }
        lock.lock(); latest = jpeg; lock.unlock()
    }
    private func frame() -> Data? { lock.lock(); defer { lock.unlock() }; return latest }
    private func clear() { lock.lock(); latest=nil; lock.unlock() }
    func stop() async {
        if let current = stream { try? await current.stopCapture(); stream=nil }
        currentDisplayID = nil
        clear()
    }
    func jpeg(displayID: CGDirectDisplayID) async throws -> Data {
        if screenLocked() {
            await stop()
            throw AgentError.message("Mac 当前已锁屏，请先解锁后再连接桌面")
        }
        try await start(displayID: displayID)
        for _ in 0..<30 {
            if let data = frame() { return data }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        throw AgentError.message("尚未收到桌面画面，请检查授权和登录状态")
    }
}
enum AgentError: Error { case message(String) }

@MainActor final class Agent {
    private var captures: [CGDirectDisplayID: Capture] = [:]
    private var heldKeys = Set<CGKeyCode>()
    private var heldMouse: CGMouseButton?
    private var flags: CGEventFlags {
        var result: CGEventFlags = []
        if heldKeys.contains(55) || heldKeys.contains(54) { result.insert(.maskCommand) }
        if heldKeys.contains(56) || heldKeys.contains(60) { result.insert(.maskShift) }
        if heldKeys.contains(58) || heldKeys.contains(61) { result.insert(.maskAlternate) }
        if heldKeys.contains(59) || heldKeys.contains(62) { result.insert(.maskControl) }
        return result
    }
    private let keys: [String: CGKeyCode] = [
        "KeyA":0,"KeyS":1,"KeyD":2,"KeyF":3,"KeyH":4,"KeyG":5,"KeyZ":6,"KeyX":7,"KeyC":8,"KeyV":9,
        "KeyB":11,"KeyQ":12,"KeyW":13,"KeyE":14,"KeyR":15,"KeyY":16,"KeyT":17,"Digit1":18,"Digit2":19,
        "Digit3":20,"Digit4":21,"Digit6":22,"Digit5":23,"Equal":24,"Digit9":25,"Digit7":26,"Minus":27,"Digit8":28,
        "Digit0":29,"BracketRight":30,"KeyO":31,"KeyU":32,"BracketLeft":33,"KeyI":34,"KeyP":35,"Enter":36,
        "KeyL":37,"KeyJ":38,"Quote":39,"KeyK":40,"Semicolon":41,"Backslash":42,"Comma":43,"Slash":44,
        "KeyN":45,"KeyM":46,"Period":47,"Tab":48,"Space":49,"Backquote":50,"Backspace":51,"Escape":53,
        "MetaRight":54,"MetaLeft":55,"ShiftLeft":56,"CapsLock":57,"AltLeft":58,"ControlLeft":59,
        "ShiftRight":60,"AltRight":61,"ControlRight":62,"F1":122,"F2":120,"F3":99,"F4":118,"F5":96,
        "F6":97,"F7":98,"F8":100,"F9":101,"F10":109,"F11":103,"F12":111,"Home":115,"PageUp":116,
        "Delete":117,"End":119,"PageDown":121,"ArrowLeft":123,"ArrowRight":124,"ArrowDown":125,"ArrowUp":126
    ]
    private func number(_ request: [String:Any], _ name: String) -> Double { (request[name] as? NSNumber)?.doubleValue ?? 0 }
    private func post(_ event: CGEvent?) { event?.flags = flags; event?.post(tap: .cghidEventTap) }
    private func displayID(_ request: [String:Any]) throws -> CGDirectDisplayID {
        let id = (request["displayId"] as? NSNumber)?.uint32Value ?? CGMainDisplayID()
        var displays = [CGDirectDisplayID](repeating: 0, count: 16)
        var count: UInt32 = 0
        guard CGGetActiveDisplayList(16, &displays, &count) == .success,
              displays.prefix(Int(count)).contains(id) else { throw AgentError.message("所选显示器已断开") }
        return id
    }
    private func key(_ code: CGKeyCode, _ down: Bool) {
        if down { heldKeys.insert(code) } else { heldKeys.remove(code) }
        post(CGEvent(keyboardEventSource: nil, virtualKey: code, keyDown: down))
    }
    func handle(_ r: [String:Any]) async throws -> [String:Any] {
        let type = r["type"] as? String ?? ""
        if type == "status" {
            let display = CGMainDisplayID()
            var active = [CGDirectDisplayID](repeating: 0, count: 16)
            var count: UInt32 = 0
            _ = CGGetActiveDisplayList(16, &active, &count)
            let displays: [[String:Any]] = active.prefix(Int(count)).enumerated().map { index, id in
                ["id":id, "name":id == display ? "主屏幕" : "显示器 \(index + 1)",
                 "width":CGDisplayPixelsWide(id), "height":CGDisplayPixelsHigh(id), "main":id == display]
            }
            return ["screenRecording":CGPreflightScreenCaptureAccess(), "accessibility":AXIsProcessTrusted(),
                    "displayId":display, "sessionLocked":screenLocked(),
                    "width":CGDisplayPixelsWide(display), "height":CGDisplayPixelsHigh(display), "displays":displays]
        }
        if type == "capture" {
            let id = try displayID(r)
            let capture = captures[id] ?? Capture()
            captures[id] = capture
            return ["jpeg":try await capture.jpeg(displayID: id).base64EncodedString()]
        }
        if type == "stop-capture" {
            for capture in captures.values { await capture.stop() }
            captures.removeAll()
            return ["ok":true]
        }
        if type == "clipboard-get" { return ["text":NSPasteboard.general.string(forType:.string) ?? ""] }
        if type == "clipboard-set" {
            NSPasteboard.general.clearContents(); NSPasteboard.general.setString(r["text"] as? String ?? "", forType:.string); return ["ok":true]
        }
        guard AXIsProcessTrusted() else { throw AgentError.message("请在系统设置中授予 RemoteTool Mac Agent 辅助功能权限") }
        if type == "release" {
            for code in Array(heldKeys) { key(code,false) }
            if let mouse = heldMouse {
                let up: CGEventType = mouse == .right ? .rightMouseUp : mouse == .center ? .otherMouseUp : .leftMouseUp
                post(CGEvent(mouseEventSource:nil,mouseType:up,mouseCursorPosition:CGEvent(source:nil)?.location ?? .zero,mouseButton:mouse));heldMouse=nil
            }
            return ["ok":true]
        }
        guard !screenLocked() else { throw AgentError.message("Mac 当前已锁屏，请先解锁后再控制桌面") }
        if type == "key" {
            guard let code = keys[r["code"] as? String ?? ""] else { throw AgentError.message("不支持的物理按键") }
            key(code, r["isKeyDown"] as? Bool ?? false); return ["ok":true]
        }
        if type == "keyboard" || type == "keyboard-multi" {
            func physical(_ vk:Int) -> String? {
                if vk >= 65 && vk <= 90 { return "Key" + String(UnicodeScalar(vk)!) }
                if vk >= 48 && vk <= 57 { return "Digit" + String(UnicodeScalar(vk)!) }
                return [8:"Backspace",9:"Tab",13:"Enter",16:"ShiftLeft",17:"ControlLeft",18:"AltLeft",27:"Escape",32:"Space",
                    33:"PageUp",34:"PageDown",35:"End",36:"Home",37:"ArrowLeft",38:"ArrowUp",39:"ArrowRight",40:"ArrowDown",46:"Delete",91:"MetaLeft"][vk]
            }
            let codes = type == "keyboard" ? [Int(number(r,"vkCode"))] : (r["vkCodes"] as? [Int] ?? [])
            for vk in codes { guard let name = physical(vk), let code = keys[name] else { throw AgentError.message("此按键请使用 H5 物理键协议") }; key(code,r["isKeyDown"] as? Bool ?? false) }
            return ["ok":true]
        }
        if type == "text" {
            let text = r["text"] as? String ?? ""
            guard text.utf16.count <= 2048 else { throw AgentError.message("文本最多 2048 个字符") }
            let chars = Array(text.utf16)
            if !chars.isEmpty {
                for down in [true,false] {
                    let event = CGEvent(keyboardEventSource:nil,virtualKey:0,keyDown:down)
                    chars.withUnsafeBufferPointer { event?.keyboardSetUnicodeString(stringLength:chars.count,unicodeString:$0.baseAddress!) }
                    post(event)
                }
            }
            return ["ok":true]
        }
        let relative = ["mouse-relative","pointer-click","pointer-wheel"].contains(type)
        let bounds = CGDisplayBounds(try displayID(r))
        let x = number(r,"x"), y = number(r,"y")
        if !relative && (!x.isFinite || !y.isFinite || x < 0 || x > 1 || y < 0 || y > 1) { throw AgentError.message("无效鼠标坐标") }
        var point = relative ? (CGEvent(source:nil)?.location ?? .zero) : CGPoint(x:bounds.minX+x*bounds.width,y:bounds.minY+y*bounds.height)
        if type == "mouse-relative" {
            point.x += max(-4096,min(4096,number(r,"dx"))); point.y += max(-4096,min(4096,number(r,"dy")))
            post(CGEvent(mouseEventSource:nil,mouseType:.mouseMoved,mouseCursorPosition:point,mouseButton:.left)); return ["ok":true]
        }
        if type == "mouse-wheel" || type == "pointer-wheel" {
            if !relative { post(CGEvent(mouseEventSource:nil,mouseType:.mouseMoved,mouseCursorPosition:point,mouseButton:.left)) }
            let delta = max(-12000,min(12000,number(r,type == "pointer-wheel" ? "dy" : "delta")))
            post(CGEvent(scrollWheelEvent2Source:nil,units:.pixel,wheelCount:1,wheel1:Int32(delta),wheel2:0,wheel3:0)); return ["ok":true]
        }
        let button = type == "right-click" ? 2 : type == "middle-click" ? 1 : Int(number(r,"button"))
        let mouse:CGMouseButton = button == 2 ? .right : button == 1 ? .center : .left
        let down:CGEventType = button == 2 ? .rightMouseDown : button == 1 ? .otherMouseDown : .leftMouseDown
        let up:CGEventType = button == 2 ? .rightMouseUp : button == 1 ? .otherMouseUp : .leftMouseUp
        switch type {
        case "click","right-click","middle-click","pointer-click":
            post(CGEvent(mouseEventSource:nil,mouseType:down,mouseCursorPosition:point,mouseButton:mouse)); post(CGEvent(mouseEventSource:nil,mouseType:up,mouseCursorPosition:point,mouseButton:mouse))
        case "mouse-down":heldMouse=mouse;post(CGEvent(mouseEventSource:nil,mouseType:down,mouseCursorPosition:point,mouseButton:mouse))
        case "mouse-up":heldMouse=nil;post(CGEvent(mouseEventSource:nil,mouseType:up,mouseCursorPosition:point,mouseButton:mouse))
        case "mouse-move":
            let movement: CGEventType = heldMouse == .right ? .rightMouseDragged : heldMouse == .center ? .otherMouseDragged : heldMouse == .left ? .leftMouseDragged : .mouseMoved
            post(CGEvent(mouseEventSource:nil,mouseType:movement,mouseCursorPosition:point,mouseButton:heldMouse ?? .left))
        default:throw AgentError.message("未知桌面操作")
        }
        return ["ok":true]
    }
}

@main struct RemoteToolMacAgent {
    static func main() async {
        if CommandLine.arguments.contains("--permissions") {
            CGRequestScreenCaptureAccess()
            _ = AXIsProcessTrustedWithOptions([kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String:true] as CFDictionary)
            return
        }
        let agent = Agent()
        // Blocking reads happen on a dedicated thread so capture callbacks can run.
        let queue = AsyncStream<String> { continuation in
            DispatchQueue.global().async {
                while let line = readLine() { continuation.yield(line) }
                continuation.finish()
            }
        }
        for await line in queue {
            let response:[String:Any]
            do {
                guard let request = try JSONSerialization.jsonObject(with:Data(line.utf8)) as? [String:Any] else { throw AgentError.message("无效 JSON") }
                response = try await agent.handle(request)
            } catch AgentError.message(let text) { response = ["error":text] }
            catch { response = ["error":error.localizedDescription] }
            if let data = try? JSONSerialization.data(withJSONObject:response) {
                FileHandle.standardOutput.write(data); FileHandle.standardOutput.write(Data([10]))
            }
        }
        _ = try? await agent.handle(["type":"release"])
    }
}
