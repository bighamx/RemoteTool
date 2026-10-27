namespace RemoteTool.WebApi.Services.PhoneControl;

public static class PhoneMcpProtocol {
    public const string Instructions = """
        Declare your real clientInfo.name during MCP initialize, or client_name in phone_begin_ui on the existing owner; names are display metadata, never authorization.
        Reading: backend=auto/accessibility/uiautomator selects collection; include_unimportant=true retains potentially omitted chat text; mode=compact/text/raw only selects output. UiAutomation is slower and its nodes are read-only; reobserve through accessibility for node actions.
        Control the bound Android phone with configured phone_* tools or the persistent MCP adapter. A missing chat binding is a setup error: use the explicitly configured endpoint, not plugin source or another chat's credentials.
        HTTP: initialize once; retain Mcp-Session-Id and negotiated MCP-Protocol-Version on subsequent requests. Reuse across batches and short-lived processes; isolate caches by endpoint, credentials and logical agent. The shipped CLI accepts --agent <stable-chat-id>. Never share identities between independent agents.
        When initialize returns _meta.remotetool.resume_token, keep it private. After explicit HTTP 404 session_not_found, initialize with params._meta["remotetool/resume_token"] to recover the same owner; the old transport is fenced. device_busy means an old call is still running. On connection_closed_by_user or failed recovery stop, never silently create another identity or bypass ownership.
        Check phone_get_capabilities, then phone_begin_ui before UI reads/actions. Prefer compact observe or find_nodes. If exposed, use selectors, guard, wait_for, return_state and phone_execute for bounded serial actions with fresh targets and one final observation. Prefer condition waits to fixed sleeps and accessibility to shell dumps. Invalid/ambiguous/obscured targets must be resolved, not guessed. Snapshot/node IDs are owner-scoped and expire in 30 seconds. UI shell commands require ui_operation=true.
        accepted is not business success; verify the postcondition. matched=false is a timeout. On delivery_unknown/lost responses never replay writes with a new request_id; query phone_get_operation or observe first. A request_id identifies one logical operation, not a transport or JSON-RPC message. Partial batches return per-step receipts and must not be blindly replayed.
        Use optional explanation for a short user-facing purpose, without secrets or hidden reasoning. For help use phone_notify_user(kind=help), preserve notice_id, release UI and check phone_get_user_reply; pending/dismissed/expired is not completion. Reacquire UI before continuing. phone_end_ui releases only your UI batch; ui_busy/device_busy never permits taking another owner's lock.
        After all work and verification, including failure cleanup, release UI and call phone_disconnect last. HTTP clients may then DELETE their own MCP session. Do not call more phone tools just to verify disconnection, revoke the device binding, or disconnect other agents.
        """;
    private static readonly string[] Versions = ["2025-11-25", "2025-06-18", "2025-03-26"];
    public static string SelectVersion(string? requested) =>
        Array.IndexOf(Versions, requested) >= 0 ? requested! : Versions[0];
}
