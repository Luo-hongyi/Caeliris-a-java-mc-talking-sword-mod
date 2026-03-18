const { contextBridge, ipcRenderer } = require("electron");
const fs = require("fs").promises;
const path = require("path");

console.log("[TW Studio] preload start", {
  pid: process.pid,
  cwd: process.cwd(),
  file: __filename
});

function resolveWorkspacePath(rootPath, relativePath) {
  const resolvedRoot = path.resolve(rootPath);
  const resolvedTarget = path.resolve(rootPath, relativePath);
  if (resolvedTarget !== resolvedRoot && !resolvedTarget.startsWith(`${resolvedRoot}${path.sep}`)) {
    throw new Error(`Workspace path escapes root: ${relativePath}`);
  }
  return resolvedTarget;
}

function isMissingFileError(error) {
  return error && typeof error === "object" && ["ENOENT", "ENOTDIR", "EISDIR"].includes(error.code);
}

contextBridge.exposeInMainWorld("talkingWeaponDesktopMeta", {
  bridgeReady: true,
  source: "electron-preload"
});

contextBridge.exposeInMainWorld("talkingWeaponDesktop", {
  pickWorkspaceDirectory(mode) {
    return ipcRenderer.invoke("talking-weapon:pick-workspace-directory", mode);
  },

  async readTextFile(rootPath, relativePath) {
    try {
      const targetPath = resolveWorkspacePath(rootPath, relativePath);
      return await fs.readFile(targetPath, "utf8");
    } catch (error) {
      if (isMissingFileError(error)) {
        return null;
      }
      throw error;
    }
  },

  async writeTextFile(rootPath, relativePath, contents) {
    const targetPath = resolveWorkspacePath(rootPath, relativePath);
    await fs.mkdir(path.dirname(targetPath), { recursive: true });
    await fs.writeFile(targetPath, contents, "utf8");
  },

  async readBinaryFile(rootPath, relativePath) {
    try {
      const targetPath = resolveWorkspacePath(rootPath, relativePath);
      const [buffer, stat] = await Promise.all([fs.readFile(targetPath), fs.stat(targetPath)]);
      return {
        name: path.basename(targetPath),
        data: buffer.buffer.slice(buffer.byteOffset, buffer.byteOffset + buffer.byteLength),
        lastModified: stat.mtimeMs
      };
    } catch (error) {
      if (isMissingFileError(error)) {
        return null;
      }
      throw error;
    }
  }
});

console.log("[TW Studio] preload bridge exposed");
