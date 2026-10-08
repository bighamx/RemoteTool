using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using SharpCompress.Archives;
using SharpCompress.Common;
using System.Linq;

namespace ChuckieHelper.Lib.Tool
{
    public static class Util
    {
        /// <summary>
        /// 归档结构类型
        /// </summary>
        private enum ArchiveStructureType
        {
            /// <summary>
            /// 单文件
            /// </summary>
            SingleFile,
            /// <summary>
            /// 单根文件夹，且包含多个文件
            /// </summary>
            SingleRootFolderWithMultipleFiles,
            /// <summary>
            /// 单根文件夹，但只包含一个文件或子文件夹
            /// </summary>
            SingleRootFolderWithSingleItem,
            /// <summary>
            /// 多文件夹结构
            /// </summary>
            MultipleFolders
        }

        /// <summary>
        /// 归档条目信息
        /// </summary>
        private record ArchiveEntryInfo(string NormalizedPath, string FileName, bool IsDirectory, int Index);

        /// <summary>
        /// 归档结构分析结果
        /// </summary>
        private record ArchiveStructure(ArchiveStructureType Type, string RootFolder, List<ArchiveEntryInfo> FileEntries);

        /// <summary>
        /// 智能解压单个压缩文件（支持zip/rar）
        /// </summary>
        /// <param name="filePath">压缩文件路径</param>
        /// <returns>解压的文件列表</returns>
        public static List<string> SmartExtract(string filePath)
        {
            var result = new List<string>();
            if (!File.Exists(filePath)) return result;
            
            var ext = Path.GetExtension(filePath).ToLowerInvariant();
            var directoryName = Path.GetDirectoryName(filePath);
            if (string.IsNullOrEmpty(directoryName))
            {
                Console.WriteLine($"[SmartExtract] Invalid file path: {filePath}");
                return result;
            }
            
            var extractDir = Path.Combine(directoryName, Path.GetFileNameWithoutExtension(filePath));
            
            try
            {
                if (ext == ".zip")
                {
                    result.AddRange(ExtractZipArchive(filePath, extractDir));
                }
                else if (ext == ".rar")
                {
                    result.AddRange(ExtractRarArchive(filePath, extractDir));
                }
                else
                {
                    Console.WriteLine($"[SmartExtract] Unsupported file type: {filePath}");
                }
            }
            catch (SharpCompress.Common.InvalidFormatException ex)
            {
                Console.WriteLine($"[SmartExtract] Invalid RAR format: {filePath}, {ex.Message}");
            }
            catch (InvalidDataException ex)
            {
                Console.WriteLine($"[SmartExtract] Invalid ZIP format: {filePath}, {ex.Message}");
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[SmartExtract] Exception: {filePath}, {ex.Message}");
            }
            
            return result;
        }

        /// <summary>
        /// 解压 ZIP 归档
        /// </summary>
        private static List<string> ExtractZipArchive(string filePath, string extractDir)
        {
            var result = new List<string>();
            using var archive = ZipFile.OpenRead(filePath);
            
            // 提取条目信息
            var entryInfos = archive.Entries
                .Select((entry, index) => new ArchiveEntryInfo(
                    NormalizedPath: entry.FullName.Replace('\\', '/'),
                    FileName: entry.Name,
                    IsDirectory: string.IsNullOrEmpty(entry.Name),
                    Index: index
                ))
                .ToList();

            // 分析结构
            var structure = AnalyzeArchiveStructure(entryInfos);
            
            // 执行解压
            ExtractByStructure(archive.Entries, structure, extractDir, result, (entry, destPath) =>
            {
                entry.ExtractToFile(destPath, true);
            });
            
            return result;
        }

        /// <summary>
        /// 解压 RAR 归档
        /// </summary>
        private static List<string> ExtractRarArchive(string filePath, string extractDir)
        {
            var result = new List<string>();
            using var archive = ArchiveFactory.OpenArchive(filePath);
            
            // 提取条目信息
            var entryInfos = archive.Entries
                .Select((entry, index) => new ArchiveEntryInfo(
                    NormalizedPath: entry.Key.Replace('\\', '/'),
                    FileName: Path.GetFileName(entry.Key),
                    IsDirectory: entry.IsDirectory,
                    Index: index
                ))
                .ToList();

            // 分析结构
            var structure = AnalyzeArchiveStructure(entryInfos);
            
            // 执行解压
            ExtractByStructure(archive.Entries, structure, extractDir, result, (entry, destPath) =>
            {
                entry.WriteToFile(destPath, new ExtractionOptions { Overwrite = true });
            });
            
            return result;
        }

        /// <summary>
        /// 分析归档结构
        /// </summary>
        private static ArchiveStructure AnalyzeArchiveStructure(List<ArchiveEntryInfo> entryInfos)
        {
            // 过滤出文件条目
            var fileEntries = entryInfos.Where(e => !e.IsDirectory && !string.IsNullOrEmpty(e.FileName)).ToList();
            
            // 单文件情况
            if (fileEntries.Count == 1)
            {
                return new ArchiveStructure(ArchiveStructureType.SingleFile, string.Empty, fileEntries);
            }

            // 提取根文件夹
            var rootFolders = entryInfos
                .Select(e => e.NormalizedPath.Split('/')[0])
                .Where(f => !string.IsNullOrWhiteSpace(f))
                .Distinct()
                .ToList();

            // 判断是否只有一个根文件夹
            bool isSingleRoot = rootFolders.Count == 1 && 
                               entryInfos.All(e => e.NormalizedPath == rootFolders[0] || e.NormalizedPath.StartsWith(rootFolders[0] + "/"));

            if (isSingleRoot)
            {
                // 获取根文件夹下的文件（排除根文件夹本身）
                var folderFiles = fileEntries
                    .Where(e => e.NormalizedPath.StartsWith(rootFolders[0] + "/"))
                    .ToList();

                // 如果根文件夹下有多个文件，则跳过根文件夹层级
                if (folderFiles.Count > 1)
                {
                    return new ArchiveStructure(
                        ArchiveStructureType.SingleRootFolderWithMultipleFiles,
                        rootFolders[0],
                        folderFiles
                    );
                }
                else
                {
                    // 单根文件夹但只有一个文件或子文件夹
                    return new ArchiveStructure(
                        ArchiveStructureType.SingleRootFolderWithSingleItem,
                        rootFolders[0],
                        fileEntries
                    );
                }
            }

            // 多文件夹结构
            return new ArchiveStructure(ArchiveStructureType.MultipleFolders, string.Empty, fileEntries);
        }

        /// <summary>
        /// 根据结构类型执行解压
        /// </summary>
        private static void ExtractByStructure<TEntry>(
            IEnumerable<TEntry> allEntries,
            ArchiveStructure structure,
            string extractDir,
            List<string> result,
            Action<TEntry, string> extractAction)
        {
            Directory.CreateDirectory(extractDir);

            switch (structure.Type)
            {
                case ArchiveStructureType.SingleFile:
                    ExtractSingleFile(allEntries, structure, extractDir, result, extractAction);
                    break;

                case ArchiveStructureType.SingleRootFolderWithMultipleFiles:
                    ExtractSingleRootFolderWithMultipleFiles(allEntries, structure, extractDir, result, extractAction);
                    break;

                case ArchiveStructureType.SingleRootFolderWithSingleItem:
                    ExtractSingleRootFolderWithSingleItem(allEntries, structure, extractDir, result, extractAction);
                    break;

                case ArchiveStructureType.MultipleFolders:
                    ExtractMultipleFolders(allEntries, structure, extractDir, result, extractAction);
                    break;
            }
        }

        /// <summary>
        /// 解压单文件
        /// </summary>
        private static void ExtractSingleFile<TEntry>(
            IEnumerable<TEntry> allEntries,
            ArchiveStructure structure,
            string extractDir,
            List<string> result,
            Action<TEntry, string> extractAction)
        {
            var entriesList = allEntries.ToList();
            var entryInfo = structure.FileEntries[0];
            var entry = entriesList[entryInfo.Index];
            // 使用 FileName（对于 ZIP 是 entry.Name，对于 RAR 是 Path.GetFileName(entry.Key)）
            var destPath = Path.Combine(extractDir, entryInfo.FileName.Replace('/', Path.DirectorySeparatorChar));
            
            extractAction(entry, destPath);
            result.Add(destPath);
        }

        /// <summary>
        /// 解压单根文件夹（多个文件）- 跳过根文件夹层级
        /// </summary>
        private static void ExtractSingleRootFolderWithMultipleFiles<TEntry>(
            IEnumerable<TEntry> allEntries,
            ArchiveStructure structure,
            string extractDir,
            List<string> result,
            Action<TEntry, string> extractAction)
        {
            var entriesList = allEntries.ToList();
            foreach (var entryInfo in structure.FileEntries)
            {
                var entry = entriesList[entryInfo.Index];
                var relativePath = string.Join(Path.DirectorySeparatorChar.ToString(), 
                    entryInfo.NormalizedPath.Split('/').Skip(1));
                var destPath = Path.Combine(extractDir, relativePath);
                
                var dir = Path.GetDirectoryName(destPath);
                if (!string.IsNullOrEmpty(dir))
                {
                    Directory.CreateDirectory(dir);
                }
                
                extractAction(entry, destPath);
                result.Add(destPath);
            }
        }

        /// <summary>
        /// 解压单根文件夹（单个文件或子文件夹）
        /// </summary>
        private static void ExtractSingleRootFolderWithSingleItem<TEntry>(
            IEnumerable<TEntry> allEntries,
            ArchiveStructure structure,
            string extractDir,
            List<string> result,
            Action<TEntry, string> extractAction)
        {
            var entriesList = allEntries.ToList();
            foreach (var entryInfo in structure.FileEntries)
            {
                var entry = entriesList[entryInfo.Index];
                var relativePath = string.Join(Path.DirectorySeparatorChar.ToString(), 
                    entryInfo.NormalizedPath.Split('/').Skip(1));
                var destPath = Path.Combine(extractDir, relativePath);
                
                var dir = Path.GetDirectoryName(destPath);
                if (!string.IsNullOrEmpty(dir))
                {
                    Directory.CreateDirectory(dir);
                }
                
                extractAction(entry, destPath);
                result.Add(destPath);
            }
        }

        /// <summary>
        /// 解压多文件夹结构
        /// </summary>
        private static void ExtractMultipleFolders<TEntry>(
            IEnumerable<TEntry> allEntries,
            ArchiveStructure structure,
            string extractDir,
            List<string> result,
            Action<TEntry, string> extractAction)
        {
            var entriesList = allEntries.ToList();
            foreach (var entryInfo in structure.FileEntries)
            {
                var entry = entriesList[entryInfo.Index];
                var destPath = Path.Combine(extractDir, 
                    entryInfo.NormalizedPath.Replace('/', Path.DirectorySeparatorChar));
                
                var dir = Path.GetDirectoryName(destPath);
                if (!string.IsNullOrEmpty(dir))
                {
                    Directory.CreateDirectory(dir);
                }
                
                extractAction(entry, destPath);
                result.Add(destPath);
            }
        }


        /// <summary>
        /// 递归清理指定目录下的所有空文件夹
        /// </summary>
        /// <param name="rootPath">根目录</param>
        /// <returns>被删除的空文件夹路径列表</returns>
        public static List<string> CleanEmptyDirectories(string rootPath)
        {
            var deleted = new List<string>();
            if (!Directory.Exists(rootPath)) return deleted;
            foreach (var dir in Directory.GetDirectories(rootPath))
            {
                deleted.AddRange(CleanEmptyDirectories(dir));
            }
            // 如果当前目录为空，则删除
            if (!Directory.EnumerateFileSystemEntries(rootPath).Any())
            {
                Directory.Delete(rootPath);
                deleted.Add(rootPath);
            }
            return deleted;
        }

        /// <summary>
        /// 智能递归解压指定目录及其子目录下的所有压缩文件（支持zip/rar）
        /// </summary>
        /// <param name="rootPath">根目录</param>
        /// <returns>解压的文件列表</returns>
        public static List<string> SmartUnzipAll(string rootPath)
        {
            var extractedFiles = new List<string>();
            if (!Directory.Exists(rootPath)) return extractedFiles;
            var files = Directory.GetFiles(rootPath, "*.*", SearchOption.AllDirectories)
                .Where(f => f.EndsWith(".zip", StringComparison.OrdinalIgnoreCase) || f.EndsWith(".rar", StringComparison.OrdinalIgnoreCase));
            foreach (var file in files)
            {
                extractedFiles.AddRange(SmartExtract(file));
            }
            return extractedFiles;
        }
    }
}
