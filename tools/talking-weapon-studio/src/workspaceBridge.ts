export type WorkspaceAccessMode = "read" | "readwrite";

type DesktopDirectoryHandle = {
  kind: "directory";
  bridge: "desktop";
  path: string;
  name: string;
};

type DesktopBinaryFile = {
  name: string;
  data: ArrayBuffer;
  lastModified: number;
};

type DesktopWorkspaceBridge = {
  pickWorkspaceDirectory: (mode: WorkspaceAccessMode) => Promise<DesktopDirectoryHandle | null>;
  readTextFile: (rootPath: string, relativePath: string) => Promise<string | null>;
  writeTextFile: (rootPath: string, relativePath: string, contents: string) => Promise<void>;
  readBinaryFile: (rootPath: string, relativePath: string) => Promise<DesktopBinaryFile | null>;
};

type DesktopWorkspaceMeta = {
  bridgeReady?: boolean;
  source?: string;
};

type DirectoryPickerWindow = Window &
  typeof globalThis & {
    showDirectoryPicker?: (options?: { mode?: WorkspaceAccessMode }) => Promise<FileSystemDirectoryHandle>;
    talkingWeaponDesktop?: DesktopWorkspaceBridge;
    talkingWeaponDesktopMeta?: DesktopWorkspaceMeta;
  };

type DirectoryPermissionHandle = FileSystemDirectoryHandle & {
  queryPermission?: (options?: { mode?: WorkspaceAccessMode }) => Promise<PermissionState>;
  requestPermission?: (options?: { mode?: WorkspaceAccessMode }) => Promise<PermissionState>;
};

export type WorkspaceDirectoryHandle = FileSystemDirectoryHandle;

function getDesktopBridge(): DesktopWorkspaceBridge | undefined {
  return (window as DirectoryPickerWindow).talkingWeaponDesktop;
}

function getDesktopMeta(): DesktopWorkspaceMeta | undefined {
  return (window as DirectoryPickerWindow).talkingWeaponDesktopMeta;
}

function isDesktopDirectoryHandle(handle: WorkspaceDirectoryHandle): handle is WorkspaceDirectoryHandle & DesktopDirectoryHandle {
  const candidate = handle as unknown as Partial<DesktopDirectoryHandle> | null;
  return !!candidate && candidate.bridge === "desktop" && typeof candidate.path === "string";
}

export function supportsDirectoryAccessApi(): boolean {
  return typeof getDesktopBridge()?.pickWorkspaceDirectory === "function"
    || typeof (window as DirectoryPickerWindow).showDirectoryPicker === "function";
}

export function describeWorkspaceAccessMode(): string {
  if (typeof getDesktopBridge()?.pickWorkspaceDirectory === "function") {
    return "desktop-bridge";
  }
  if (typeof (window as DirectoryPickerWindow).showDirectoryPicker === "function") {
    return "browser-directory-picker";
  }
  return "folder-upload-fallback";
}

export function isDesktopBridgeReady(): boolean {
  return getDesktopMeta()?.bridgeReady === true;
}

export function getDesktopBridgeSource(): string {
  return getDesktopMeta()?.source ?? "missing";
}

export function isBrowserDirectoryPickerAvailable(): boolean {
  return typeof (window as DirectoryPickerWindow).showDirectoryPicker === "function";
}

export async function pickDirectoryHandle(mode: WorkspaceAccessMode): Promise<WorkspaceDirectoryHandle | null> {
  const desktopBridge = getDesktopBridge();
  if (desktopBridge) {
    const picked = await desktopBridge.pickWorkspaceDirectory(mode);
    return picked ? (picked as unknown as WorkspaceDirectoryHandle) : null;
  }

  const picker = (window as DirectoryPickerWindow).showDirectoryPicker;
  if (!picker) {
    return null;
  }
  return picker({ mode });
}

export async function ensureDirectoryPermission(
  handle: WorkspaceDirectoryHandle,
  mode: WorkspaceAccessMode
): Promise<boolean> {
  if (isDesktopDirectoryHandle(handle)) {
    return true;
  }

  const permissionHandle = handle as DirectoryPermissionHandle;
  if (!permissionHandle.queryPermission || !permissionHandle.requestPermission) {
    return true;
  }

  const current = await permissionHandle.queryPermission({ mode });
  if (current === "granted") {
    return true;
  }
  return (await permissionHandle.requestPermission({ mode })) === "granted";
}

export async function readJsonFileFromDirectory(
  handle: WorkspaceDirectoryHandle,
  filename: string
): Promise<unknown | null> {
  try {
    if (isDesktopDirectoryHandle(handle)) {
      const desktopBridge = getDesktopBridge();
      if (!desktopBridge) {
        throw new Error("Desktop workspace bridge is unavailable.");
      }
      const text = await desktopBridge.readTextFile(handle.path, filename);
      return text !== null ? JSON.parse(text) as unknown : null;
    }

    const fileHandle = await handle.getFileHandle(filename);
    const file = await fileHandle.getFile();
    return JSON.parse(await file.text()) as unknown;
  } catch (error) {
    if (error instanceof DOMException && error.name === "NotFoundError") {
      return null;
    }
    throw error;
  }
}

export async function readFileFromDirectoryPath(
  handle: WorkspaceDirectoryHandle,
  relativePath: string
): Promise<File | null> {
  try {
    if (isDesktopDirectoryHandle(handle)) {
      const desktopBridge = getDesktopBridge();
      if (!desktopBridge) {
        throw new Error("Desktop workspace bridge is unavailable.");
      }
      const file = await desktopBridge.readBinaryFile(handle.path, relativePath);
      if (!file) {
        return null;
      }
      return new File([file.data], file.name, { lastModified: file.lastModified });
    }

    const segments = relativePath.replace(/\\/g, "/").split("/").filter(Boolean);
    if (!segments.length) {
      return null;
    }

    let currentDirectory = handle;
    for (const segment of segments.slice(0, -1)) {
      currentDirectory = await currentDirectory.getDirectoryHandle(segment);
    }

    const fileHandle = await currentDirectory.getFileHandle(segments[segments.length - 1]);
    return await fileHandle.getFile();
  } catch (error) {
    if (error instanceof DOMException && (error.name === "NotFoundError" || error.name === "TypeMismatchError")) {
      return null;
    }
    throw error;
  }
}

export async function writeJsonFileToDirectory(
  handle: WorkspaceDirectoryHandle,
  filename: string,
  payload: unknown
): Promise<void> {
  const contents = JSON.stringify(payload, null, 2);

  if (isDesktopDirectoryHandle(handle)) {
    const desktopBridge = getDesktopBridge();
    if (!desktopBridge) {
      throw new Error("Desktop workspace bridge is unavailable.");
    }
    await desktopBridge.writeTextFile(handle.path, filename, contents);
    return;
  }

  const fileHandle = await handle.getFileHandle(filename, { create: true });
  const writable = await fileHandle.createWritable();
  await writable.write(contents);
  await writable.close();
}

export function describeWorkspaceDirectoryHandle(handle: WorkspaceDirectoryHandle | null): string {
  if (!handle) {
    return "未选择";
  }

  if (isDesktopDirectoryHandle(handle)) {
    return handle.path;
  }

  return `${handle.name} (web sandbox)`;
}
