const { app, BrowserWindow, dialog, ipcMain } = require("electron");
const fs = require("fs");
const path = require("path");

const DEV_SERVER_URL = process.env.VITE_DEV_SERVER_URL;
const WORKSPACE_WATCH_DEBOUNCE_MS = 180;

let workspaceWatcherState = {
  rootPath: null,
  watchers: [],
  pendingChanges: new Set(),
  flushTimer: null
};

function broadcastWorkspaceChanged(rootPath, changedPaths) {
  const payload = {
    rootPath,
    changedPaths,
    changedAt: Date.now()
  };
  for (const window of BrowserWindow.getAllWindows()) {
    window.webContents.send("talking-weapon:workspace-changed", payload);
  }
}

function flushWorkspaceChanges() {
  if (!workspaceWatcherState.rootPath || workspaceWatcherState.pendingChanges.size === 0) {
    return;
  }
  const changedPaths = Array.from(workspaceWatcherState.pendingChanges).sort();
  workspaceWatcherState.pendingChanges.clear();
  broadcastWorkspaceChanged(workspaceWatcherState.rootPath, changedPaths);
}

function queueWorkspaceChange(relativePath) {
  if (relativePath) {
    workspaceWatcherState.pendingChanges.add(relativePath);
  }
  if (workspaceWatcherState.flushTimer) {
    clearTimeout(workspaceWatcherState.flushTimer);
  }
  workspaceWatcherState.flushTimer = setTimeout(() => {
    workspaceWatcherState.flushTimer = null;
    flushWorkspaceChanges();
  }, WORKSPACE_WATCH_DEBOUNCE_MS);
}

function closeWorkspaceWatchers() {
  for (const watcher of workspaceWatcherState.watchers) {
    try {
      watcher.close();
    } catch (_error) {
    }
  }
  workspaceWatcherState.watchers = [];
  if (workspaceWatcherState.flushTimer) {
    clearTimeout(workspaceWatcherState.flushTimer);
  }
  workspaceWatcherState.flushTimer = null;
  workspaceWatcherState.pendingChanges.clear();
  workspaceWatcherState.rootPath = null;
}

function attachWatcher(targetPath, mapFilename) {
  const watcher = fs.watch(targetPath, { persistent: false }, (_eventType, filename) => {
    const normalizedFilename = typeof filename === "string" ? filename.replace(/\\/g, "/") : "";
    queueWorkspaceChange(mapFilename(normalizedFilename));
  });
  workspaceWatcherState.watchers.push(watcher);
}

function startWorkspaceWatcher(rootPath) {
  const resolvedRoot = path.resolve(rootPath);
  if (workspaceWatcherState.rootPath === resolvedRoot && workspaceWatcherState.watchers.length > 0) {
    return;
  }

  closeWorkspaceWatchers();
  workspaceWatcherState.rootPath = resolvedRoot;

  attachWatcher(resolvedRoot, (filename) => filename);

  const iconsPath = path.join(resolvedRoot, "native_catalog_icons");
  if (fs.existsSync(iconsPath) && fs.statSync(iconsPath).isDirectory()) {
    attachWatcher(iconsPath, (filename) => filename ? `native_catalog_icons/${filename}` : "native_catalog_icons");
  }

  console.log("[TW Studio] workspace watcher started", {
    rootPath: resolvedRoot
  });
}

function createWindow() {
  const preloadPath = path.join(__dirname, "preload.cjs");
  console.log("[TW Studio] createWindow", {
    devServerUrl: DEV_SERVER_URL ?? null,
    preloadPath
  });

  const window = new BrowserWindow({
    width: 1480,
    height: 960,
    minWidth: 1180,
    minHeight: 760,
    backgroundColor: "#f4f4f1",
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
      preload: preloadPath
    }
  });

  window.webContents.on("did-finish-load", () => {
    console.log("[TW Studio] window loaded", {
      url: window.webContents.getURL()
    });
  });

  window.webContents.on("did-fail-load", (_event, errorCode, errorDescription, validatedURL) => {
    console.error("[TW Studio] window failed to load", {
      errorCode,
      errorDescription,
      validatedURL
    });
  });

  window.webContents.on("console-message", (_event, level, message, line, sourceId) => {
    console.log("[TW Studio][renderer console]", { level, message, line, sourceId });
  });

  if (DEV_SERVER_URL) {
    window.loadURL(DEV_SERVER_URL);
    return;
  }

  window.loadFile(path.join(__dirname, "..", "dist", "index.html"));
}

ipcMain.handle("talking-weapon:pick-workspace-directory", async (_event, mode) => {
  const result = await dialog.showOpenDialog({
    properties: ["openDirectory", "createDirectory"],
    title: mode === "readwrite" ? "选择 Studio 工作区目录（可读写）" : "选择 Studio 工作区目录"
  });

  if (result.canceled || !result.filePaths.length) {
    return null;
  }

  const dirPath = result.filePaths[0];
  return {
    kind: "directory",
    bridge: "desktop",
    path: dirPath,
    name: path.basename(dirPath)
  };
});

ipcMain.handle("talking-weapon:start-workspace-watcher", async (_event, rootPath) => {
  startWorkspaceWatcher(rootPath);
  return { ok: true };
});

ipcMain.handle("talking-weapon:stop-workspace-watcher", async () => {
  closeWorkspaceWatchers();
  return { ok: true };
});

app.whenReady().then(() => {
  createWindow();

  app.on("activate", () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow();
    }
  });
});

app.on("window-all-closed", () => {
  closeWorkspaceWatchers();
  if (process.platform !== "darwin") {
    app.quit();
  }
});
