const GAME_BRIDGE_BASE_URL = "http://127.0.0.1:47852/api/studio";

type GameBridgeSuccess<T> = T & {
  ok: true;
};

type GameBridgeFailure = {
  ok: false;
  error?: string;
  errorType?: string;
  errorLogPath?: string;
  latestErrorLogPath?: string;
};

export type GameBridgeHealth = GameBridgeSuccess<{
  serverTime: string;
  workspaceRoot: string;
  manifestPath: string;
  latestErrorLogPath: string;
  manifest?: unknown;
}>;

export type GameBridgePublishResult = GameBridgeSuccess<{
  workspaceRoot: string;
  publishedFiles: string[];
  customTagCount: number;
  descriptionCount: number;
  relationCount: number;
  personaCount: number;
  memoryCount: number;
}>;

export type GameBridgeExportResult = GameBridgeSuccess<{
  workspaceRoot: string;
  fileCount: number;
  snapshotEntryCount: number;
  runtimeTagCount: number;
  renderedIcons: number;
  placeholderIcons: number;
}>;

type GameBridgeResponse<T> = T | GameBridgeFailure;

async function requestGameBridge<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`${GAME_BRIDGE_BASE_URL}${path}`, {
      cache: "no-store",
      ...init
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    throw new Error(`无法连接到游戏内 bridge：${message}`);
  }

  let payload: GameBridgeResponse<T>;
  try {
    payload = (await response.json()) as GameBridgeResponse<T>;
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    throw new Error(`游戏内 bridge 返回了无法解析的响应：${message}`);
  }

  if (!response.ok || !payload || (payload as GameBridgeFailure).ok === false) {
    const failure = payload as GameBridgeFailure | undefined;
    const reason = failure?.error ?? `HTTP ${response.status}`;
    const logSuffix = failure?.errorLogPath || failure?.latestErrorLogPath
      ? `（日志：${failure.errorLogPath ?? failure.latestErrorLogPath}）`
      : "";
    throw new Error(`${reason}${logSuffix}`);
  }

  return payload as T;
}

export async function pingGameBridge(): Promise<GameBridgeHealth> {
  return await requestGameBridge<GameBridgeHealth>("/health", { method: "GET" });
}

export async function publishWorkspaceToGame(): Promise<GameBridgePublishResult> {
  return await requestGameBridge<GameBridgePublishResult>("/publish", { method: "POST" });
}

export async function exportWorkspaceFromGame(): Promise<GameBridgeExportResult> {
  return await requestGameBridge<GameBridgeExportResult>("/export", { method: "POST" });
}
