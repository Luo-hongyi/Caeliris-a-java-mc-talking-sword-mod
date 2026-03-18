const { app, BrowserWindow, dialog, ipcMain } = require("electron");
const path = require("path");

const DEV_SERVER_URL = process.env.VITE_DEV_SERVER_URL;

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

app.whenReady().then(() => {
  createWindow();

  app.on("activate", () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow();
    }
  });
});

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") {
    app.quit();
  }
});
