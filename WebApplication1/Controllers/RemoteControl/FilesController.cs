using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using ChuckieHelper.WebApi.Services.RemoteControl;
using ChuckieHelper.WebApi.Models.RemoteControl;

namespace ChuckieHelper.WebApi.Controllers.RemoteControl;



[ApiController]
[Route("api/[controller]")]
[Authorize]
public class FilesController : ControllerBase
{
    private readonly FileService _fileService;

    public FilesController(FileService fileService)
    {
        _fileService = fileService;
    }

    private IActionResult ApiResult(object data = null, string message = null, bool success = true)
        => Ok(new { success, message, data });

    private IActionResult ApiError(string message)
        => BadRequest(new { message });


    /// <summary>
    /// 批量删除文件/文件夹
    /// </summary>
    [HttpPost("delete-batch")]
    public IActionResult DeleteBatch([FromBody] BatchDeleteRequest request)
    {
        if (request?.Items == null || request.Items.Count == 0)
            return ApiResult(null, "No items to delete", false);
        int success = 0, fail = 0;
        var results = new List<object>();
        foreach (var item in request.Items)
        {
            bool ok = item.IsDirectory
                ? _fileService.DeleteDirectory(item.Path, true)
                : _fileService.DeleteFile(item.Path);
            if (ok) success++; else fail++;
            results.Add(new { item.Path, item.IsDirectory, ok });
        }
        return ApiResult(new { success, fail, results }, $"已删除 {success} 项，失败 {fail} 项");
    }

    /// <summary>
    /// 批量复制文件/文件夹
    /// </summary>
    [HttpPost("copy-batch")]
    public IActionResult CopyBatch([FromBody] BatchCopyRequest request)
    {
        if (request?.Items == null || request.Items.Count == 0 || string.IsNullOrEmpty(request.DestPath))
            return ApiResult(null, "参数不完整", false);
        int success = 0, fail = 0;
        var results = new List<object>();
        foreach (var item in request.Items)
        {
            var name = System.IO.Path.GetFileName(item.Path);
            var dest = System.IO.Path.Combine(request.DestPath, name);
            bool ok = _fileService.CopyFile(item.Path, dest, request.Overwrite);
            if (ok) success++; else fail++;
            results.Add(new { item.Path, dest, item.IsDirectory, ok });
        }
        return ApiResult(new { success, fail, results }, $"已复制 {success} 项，失败 {fail} 项");
    }

    /// <summary>
    /// 批量移动文件/文件夹
    /// </summary>
    [HttpPost("move-batch")]
    public IActionResult MoveBatch([FromBody] BatchMoveRequest request)
    {
        if (request?.Items == null || request.Items.Count == 0 || string.IsNullOrEmpty(request.DestPath))
            return ApiResult(null, "参数不完整", false);
        int success = 0, fail = 0;
        var results = new List<object>();
        foreach (var item in request.Items)
        {
            var name = System.IO.Path.GetFileName(item.Path);
            var dest = System.IO.Path.Combine(request.DestPath, name);
            bool ok = _fileService.MoveFile(item.Path, dest);
            if (ok) success++; else fail++;
            results.Add(new { item.Path, dest, item.IsDirectory, ok });
        }
        return ApiResult(new { success, fail, results }, $"已移动 {success} 项，失败 {fail} 项");
    }

    /// <summary>
    /// 获取文件列表
    /// </summary>
    [HttpGet("list")]
    public IActionResult GetFiles([FromQuery] string path = null)
    {
        try
        {
            var files = _fileService.GetFiles(path);
            return ApiResult(files, "File list fetched");
        }
        catch (Exception ex)
        {
            return ApiError($"Get files failed: {ex.Message}");
        }
    }

    /// <summary>
    /// 读取文件内容（binary=true 时直接返回二进制流）
    /// </summary>
    [HttpGet("read")]
    public async Task<IActionResult> ReadFile([FromQuery] string path, [FromQuery] bool binary = false)
    {
        try
        {
            if (string.IsNullOrEmpty(path))
                return ApiResult(null, "Path is required", false);

            if (binary)
            {
                var stream = _fileService.OpenFileReadStream(path);
                if (stream == null)
                    return ApiResult(null, "文件不存在或超过 2MB 限制", false);
                return File(stream, "application/octet-stream");
            }

            var content = await _fileService.ReadFileAsync(path);
            return ApiResult(content, "File read");
        }
        catch (Exception ex)
        {
            return ApiError($"Read file failed: {ex.Message}");
        }
    }

    /// <summary>
    /// 写入文件内容（文本）
    /// </summary>
    [HttpPost("write")]
    public async Task<IActionResult> WriteFile([FromBody] WriteFileRequest request)
    {
        try
        {
            if (string.IsNullOrEmpty(request.Path))
                return ApiResult(null, "Path is required", false);

            var result = await _fileService.WriteFileAsync(request.Path, request.Content ?? "");
            if (result)
                return ApiResult(null, "File written successfully");
            return ApiResult(null, "Failed to write file", false);
        }
        catch (Exception ex)
        {
            return ApiError($"Write file failed: {ex.Message}");
        }
    }

    /// <summary>
    /// 以二进制流写入文件（请求体为原始字节，用于十六进制编辑保存）
    /// </summary>
    [HttpPost("write-binary")]
    public async Task<IActionResult> WriteFileBinary([FromQuery] string path)
    {
        try
        {
            if (string.IsNullOrEmpty(path))
                return ApiResult(null, "Path is required", false);

            using var ms = new MemoryStream();
            await Request.Body.CopyToAsync(ms);
            var bytes = ms.ToArray();

            var result = await _fileService.WriteFileFromBytesAsync(path, bytes);
            if (result)
                return ApiResult(null, "File written successfully");
            return ApiResult(null, "Failed to write file", false);
        }
        catch (Exception ex)
        {
            return ApiError($"Write file (binary) failed: {ex.Message}");
        }
    }

    /// <summary>
    /// 删除文件
    /// </summary>
    /// <summary>
    /// 删除目录
    /// </summary>
    [HttpPost("delete-directory")]
    public IActionResult DeleteDirectory([FromQuery] string path, [FromQuery] bool recursive = true)
    {
        try
        {
            if (string.IsNullOrEmpty(path))
                return ApiResult(null, "Path is required", false);

            var result = _fileService.DeleteDirectory(path, recursive);
            if (result)
                return ApiResult(null, "Directory deleted successfully");
            return ApiResult(null, "Failed to delete directory", false);
        }
        catch (Exception ex)
        {
            return ApiError($"Delete directory failed: {ex.Message}");
        }
    }

    /// <summary>
    /// 创建目录
    /// </summary>
    [HttpPost("create-directory")]
    public IActionResult CreateDirectory([FromBody] CreateDirectoryRequest request)
    {
        try
        {
            if (string.IsNullOrEmpty(request.Path))
                return ApiResult(null, "Path is required", false);

            var result = _fileService.CreateDirectory(request.Path);
            if (result)
                return ApiResult(null, "Directory created successfully");
            return ApiResult(null, "Failed to create directory", false);
        }
        catch (Exception ex)
        {
            return ApiError($"Create directory failed: {ex.Message}");
        }
    }

    /// <summary>
    /// 下载文件
    /// </summary>
    [HttpGet("download")]
    public IActionResult DownloadFile([FromQuery] string path)
    {
        try
        {
            if (string.IsNullOrEmpty(path))
                return ApiResult(null, "Path is required", false);

            var (stream, contentType, _) = _fileService.GetFileStream(path);
            if (stream == null)
                return ApiResult(null, "File not found", false);

            var fileName = Path.GetFileName(path);
            return File(stream, "application/octet-stream", fileName);
        }
        catch (Exception ex)
        {
            return ApiError($"Download file failed: {ex.Message}");
        }
    }

    /// <summary>
    /// 流式传输文件（支持 Range 请求，用于视频流式播放）
    /// </summary>
    [HttpGet("stream")]
    public IActionResult StreamFile([FromQuery] string path)
    {
        try
        {
            if (string.IsNullOrEmpty(path))
                return ApiResult(null, "Path is required", false);

            var (stream, contentType, _) = _fileService.GetFileStream(path);
            if (stream == null)
                return ApiResult(null, "File not found", false);

            // 启用 Range 请求支持，浏览器自动处理 206 Partial Content
            Response.Headers["Accept-Ranges"] = "bytes";
            return File(stream, contentType, enableRangeProcessing: true);
        }
        catch (Exception ex)
        {
            return ApiError($"Stream file failed: {ex.Message}");
        }
    }

    /// <summary>
    /// 图片压缩预览（限制最大分辨率和体积）
    /// </summary>
    [HttpGet("preview-image")]
    public IActionResult PreviewImage(
        [FromQuery] string path,
        [FromQuery] int maxWidth = 1920,
        [FromQuery] int maxHeight = 1080,
        [FromQuery] int quality = 80)
    {
        try
        {
            if (string.IsNullOrEmpty(path))
                return ApiResult(null, "Path is required", false);

            // 限制参数范围
            maxWidth = Math.Clamp(maxWidth, 100, 3840);
            maxHeight = Math.Clamp(maxHeight, 100, 2160);
            quality = Math.Clamp(quality, 10, 100);

            var imageBytes = _fileService.GetImagePreview(path, maxWidth, maxHeight, quality);
            if (imageBytes.Length == 0)
                return ApiResult(null, "Image not found or not supported", false);

            return File(imageBytes, "image/jpeg");
        }
        catch (Exception ex)
        {
            return ApiError($"Preview image failed: {ex.Message}");
        }
    }

    /// <summary>
    /// 重命名文件或文件夹
    /// </summary>
    [HttpPost("rename")]
    public IActionResult Rename([FromBody] RenameRequest request)
    {
        try
        {
            if (string.IsNullOrEmpty(request.OldPath) || string.IsNullOrEmpty(request.NewPath))
                return ApiResult(null, "OldPath and NewPath are required", false);
            var result = _fileService.Rename(request.OldPath, request.NewPath);
            if (result)
                return ApiResult(null, "重命名成功");
            return ApiResult(null, "重命名失败", false);
        }
        catch (Exception ex)
        {
            return ApiError($"Rename failed: {ex.Message}");
        }
    }

    /// <summary>
    /// 设置磁盘卷标（仅 Windows，用于修改磁盘显示名称）
    /// </summary>
    [HttpPost("set-drive-label")]
    public IActionResult SetDriveLabel([FromBody] SetDriveLabelRequest request)
    {
        try
        {
            if (string.IsNullOrEmpty(request.Path))
                return ApiResult(null, "Path is required", false);
            var result = _fileService.SetDriveLabel(request.Path, request.Label ?? "");
            if (result)
                return ApiResult(null, "磁盘名称已修改");
            return ApiResult(null, "修改失败或当前系统不支持", false);
        }
        catch (Exception ex)
        {
            return ApiError($"Set drive label failed: {ex.Message}");
        }
    }

    /// <summary>
    /// 上传文件（支持二进制、相对路径、子目录自动创建）
    /// </summary>
    [HttpPost("upload")]
    public async Task<IActionResult> UploadFile([FromQuery] string path)
    {
        try
        {
            if (string.IsNullOrEmpty(path))
                return ApiResult(null, "Path is required", false);

            var files = Request.Form.Files;
            if (files.Count == 0)
                return ApiResult(null, "No file uploaded", false);

            var file = files[0];
            var relativePath = Request.Form["relativePath"].FirstOrDefault();
            if (string.IsNullOrEmpty(relativePath))
                relativePath = file.FileName;
            if (string.IsNullOrWhiteSpace(relativePath))
                return ApiResult(null, "Invalid file name", false);

            var basePath = path.Replace('/', Path.DirectorySeparatorChar).TrimEnd(Path.DirectorySeparatorChar);
            // Windows 盘符根路径需保留尾部反斜杠，否则 Path.Combine("D:", "x") 会得到 "D:x"
            if (basePath.Length == 2 && basePath[1] == ':' && char.IsLetter(basePath[0]))
                basePath += Path.DirectorySeparatorChar;
            var fullPath = Path.Combine(basePath, relativePath.Replace('/', Path.DirectorySeparatorChar));
            fullPath = Path.GetFullPath(fullPath);

            // 防止路径穿越：确保最终路径在目标目录之下
            var resolvedBase = Path.GetFullPath(basePath);
            if (!fullPath.StartsWith(resolvedBase, StringComparison.OrdinalIgnoreCase))
                return ApiResult(null, "Invalid file path", false);

            var dirPath = Path.GetDirectoryName(fullPath);
            if (!string.IsNullOrEmpty(dirPath) && !Directory.Exists(dirPath))
            {
                _fileService.CreateDirectory(dirPath);
            }

            using var fileStream = file.OpenReadStream();
            var result = await _fileService.WriteFileFromStreamAsync(fullPath, fileStream);

            if (result)
                return ApiResult(new { fileName = Path.GetFileName(fullPath) }, "File uploaded successfully");

            return ApiResult(null, "Failed to upload file", false);
        }
        catch (Exception ex)
        {
            return ApiError($"Upload file failed: {ex.Message}");
        }
    }

    /// <summary>
    /// 压缩文件/文件夹
    /// </summary>
    [HttpPost("compress")]
    public IActionResult Compress([FromBody] CompressRequest request)
    {
        try
        {
            if (request?.Items == null || request.Items.Count == 0 || string.IsNullOrEmpty(request.DestZipPath))
                return ApiResult(null, "参数不完整", false);

            var result = _fileService.Compress(request.Items, request.DestZipPath);
            if (result)
                return ApiResult(null, "压缩成功");
            return ApiResult(null, "压缩失败", false);
        }
        catch (Exception ex)
        {
            return ApiError($"Compress failed: {ex.Message}");
        }
    }

    /// <summary>
    /// 解压文件
    /// </summary>
    [HttpPost("decompress")]
    public IActionResult Decompress([FromBody] DecompressRequest request)
    {
        try
        {
            if (string.IsNullOrEmpty(request.ArchivePath) || string.IsNullOrEmpty(request.DestPath))
                return ApiResult(null, "参数不完整", false);

            var result = _fileService.Decompress(request.ArchivePath, request.DestPath);
            if (result)
                return ApiResult(null, "解压成功");
            return ApiResult(null, "解压失败", false);
        }
        catch (Exception ex)
        {
            return ApiError($"Decompress failed: {ex.Message}");
        }
    }
}

