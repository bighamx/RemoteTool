
using System;
using System.Collections.Generic;

namespace ChuckieHelper.WebApi.Models.RemoteControl;

/// <summary>
/// 文件信息
/// </summary>
public class RemoteFileInfo
{
    public string Name { get; set; } = "";
    public string Path { get; set; } = "";
    public bool IsDirectory { get; set; }
    public long Size { get; set; }
    public DateTime Modified { get; set; }
    public bool CanRead { get; set; }
    public bool CanWrite { get; set; }
    /// <summary>磁盘总字节数（仅根路径驱动器列表时有值）</summary>
    public long? TotalBytes { get; set; }
    /// <summary>磁盘可用字节数（仅根路径驱动器列表时有值）</summary>
    public long? FreeBytes { get; set; }
}

public class WriteFileRequest
{
    public string Path { get; set; } = "";
    public string Content { get; set; }
}

public class CreateDirectoryRequest
{
    public string Path { get; set; } = "";
}

public class CopyFileRequest
{
    public string SourcePath { get; set; } = "";
    public string DestPath { get; set; } = "";
    public bool Overwrite { get; set; }
}

public class MoveFileRequest
{
    public string SourcePath { get; set; } = "";
    public string DestPath { get; set; } = "";
}

public class RenameRequest
{
    public string OldPath { get; set; } = "";
    public string NewPath { get; set; } = "";
}

public class SetDriveLabelRequest
{
    public string Path { get; set; } = "";
    public string Label { get; set; }
}


public class BatchDeleteRequest
{
    public List<BatchItem> Items { get; set; } = new();
}
public class BatchCopyRequest
{
    public List<BatchItem> Items { get; set; } = new();
    public string DestPath { get; set; } = "";
    public bool Overwrite { get; set; } = false;
}
public class BatchMoveRequest
{
    public List<BatchItem> Items { get; set; } = new();
    public string DestPath { get; set; } = "";
}
public class BatchItem
{
    public string Path { get; set; } = "";
    public bool IsDirectory { get; set; }
}

public class CompressRequest
{
    public List<string> Items { get; set; } = new();
    public string DestZipPath { get; set; } = "";
}

public class DecompressRequest
{
    public string ArchivePath { get; set; } = "";
    public string DestPath { get; set; } = "";
}
