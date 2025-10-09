const img = document.getElementById('edgeImage') as HTMLImageElement;
const fpsEl = document.getElementById('fps') as HTMLElement;
const modeEl = document.getElementById('mode') as HTMLElement;
const resEl = document.getElementById('res') as HTMLElement;
const reloadBtn = document.getElementById('reloadBtn') as HTMLButtonElement;
const serverUrlInput = document.getElementById('serverUrl') as HTMLInputElement;
const connectBtn = document.getElementById('connectBtn') as HTMLButtonElement;
const connectionStatus = document.getElementById('connectionStatus') as HTMLElement;
const toggleAutoRefreshBtn = document.getElementById('toggleAutoRefresh') as HTMLButtonElement;

let serverUrl: string | null = null;
let autoRefreshEnabled = true;
let refreshInterval: number | null = null;

// Try to load server URL from localStorage
const savedUrl = localStorage.getItem('androidServerUrl');
if (savedUrl) {
  serverUrlInput.value = savedUrl;
}

function updateResolution() {
  if (img.naturalWidth && img.naturalHeight) {
    resEl.textContent = `Resolution: ${img.naturalWidth}x${img.naturalHeight}`;
  }
}

function updateConnectionStatus(connected: boolean) {
  if (connected) {
    connectionStatus.textContent = 'Connected';
    connectionStatus.classList.remove('disconnected');
  } else {
    connectionStatus.textContent = 'Disconnected';
    connectionStatus.classList.add('disconnected');
  }
}

async function fetchStats() {
  if (!serverUrl) return;
  
  try {
    const response = await fetch(`${serverUrl}/api/stats`);
    if (!response.ok) throw new Error('Failed to fetch stats');
    
    const data = await response.json();
    fpsEl.textContent = `FPS: ${data.fps}`;
    modeEl.textContent = `Mode: ${data.mode}`;
    updateConnectionStatus(true);
  } catch (error) {
    console.error('Failed to fetch stats:', error);
    updateConnectionStatus(false);
  }
}

function refreshImage() {
  const timestamp = Date.now();
  if (serverUrl) {
    img.src = `${serverUrl}/api/latest-image?t=${timestamp}`;
  } else {
    const u = new URL(img.src, window.location.href);
    u.searchParams.set('t', timestamp.toString());
    img.src = u.toString();
  }
}

function startAutoRefresh() {
  if (refreshInterval) return;
  
  refreshInterval = window.setInterval(() => {
    if (autoRefreshEnabled) {
      refreshImage();
      fetchStats();
    }
  }, 200); // Refresh every 200ms for ~5 FPS display
}

function stopAutoRefresh() {
  if (refreshInterval) {
    clearInterval(refreshInterval);
    refreshInterval = null;
  }
}

connectBtn.addEventListener('click', () => {
  const url = serverUrlInput.value.trim();
  if (url) {
    serverUrl = url;
    localStorage.setItem('androidServerUrl', url);
    updateConnectionStatus(true);
    refreshImage();
    fetchStats();
    if (autoRefreshEnabled) {
      startAutoRefresh();
    }
  }
});

reloadBtn.addEventListener('click', () => {
  refreshImage();
  fetchStats();
});

toggleAutoRefreshBtn.addEventListener('click', () => {
  autoRefreshEnabled = !autoRefreshEnabled;
  toggleAutoRefreshBtn.textContent = autoRefreshEnabled ? '⏸ Pause Auto-refresh' : '▶️ Resume Auto-refresh';
  
  if (autoRefreshEnabled && serverUrl) {
    startAutoRefresh();
  } else {
    stopAutoRefresh();
  }
});

img.addEventListener('load', updateResolution);

// Initial resolution report when image is cached
if (img.complete) updateResolution();

// Auto-connect if URL is saved
if (savedUrl) {
  serverUrl = savedUrl;
  updateConnectionStatus(true);
  refreshImage();
  fetchStats();
  startAutoRefresh();
}
