using System.Buffers.Binary;
using System.Net;
using System.Text.Json;

namespace RemoteTool.WebApi.Services.Tunnels;

public sealed record TunnelRule(int Id, string Name, string Protocol, string Direction,
    string ListenHost, int ListenPort, string TargetHost, int TargetPort)
{
    public bool PhoneListens => Direction == "phone_to_pc";
    public bool Udp => Protocol == "udp";
    public void Validate() {
        if (Id is < 1 or > 32 || Name == null || Name.Length > 80 || Protocol is not ("tcp" or "udp") ||
            Direction is not ("phone_to_pc" or "pc_to_phone") ||
            !IPAddress.TryParse(ListenHost, out _) || ListenPort is < 1024 or > 65535 ||
            TargetPort is < 1 or > 65535 || string.IsNullOrWhiteSpace(TargetHost) ||
            TargetHost.Length > 253 || TargetHost.Any(char.IsWhiteSpace) || TargetHost.Contains('/') || TargetHost.Contains('\\'))
            throw new InvalidDataException("无效的端口转发规则");
    }
}

internal readonly record struct TunnelFrame(byte Type, int Id, int Aux, byte[] Payload)
{
    public const byte Open = 1, Opened = 2, Data = 3, Fin = 4, Close = 5, Window = 6, Datagram = 7, Error = 8;
    public const int Header = 12, Chunk = 16384, Credit = 65536, MaxDatagram = 65507, MaxFrame = MaxDatagram + Header;
    public byte[] Encode() {
        var result = new byte[Header + Payload.Length]; result[0] = 1; result[1] = Type;
        BinaryPrimitives.WriteInt32BigEndian(result.AsSpan(4), Id);
        BinaryPrimitives.WriteInt32BigEndian(result.AsSpan(8), Aux); Payload.CopyTo(result, Header); return result;
    }
    public static TunnelFrame Decode(byte[] raw) {
        if (raw.Length < Header || raw.Length > MaxFrame || raw[0] != 1 || raw[2] != 0 || raw[3] != 0)
            throw new InvalidDataException("无效的隧道数据帧");
        var type = raw[1]; var id = BinaryPrimitives.ReadInt32BigEndian(raw.AsSpan(4));
        var aux = BinaryPrimitives.ReadInt32BigEndian(raw.AsSpan(8)); var length = raw.Length - Header;
        if (id <= 0 || type is < Open or > Error || (type == Data && (length == 0 || length > Chunk)) ||
            (type is Open or Opened or Fin or Close or Window && length != 0) || (type == Error && length > 1024))
            throw new InvalidDataException("无效的隧道帧长度或类型");
        return new(type, id, aux, raw[Header..]);
    }
}
