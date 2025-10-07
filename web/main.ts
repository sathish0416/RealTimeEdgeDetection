const img = document.getElementById('edgeImage') as HTMLImageElement;
const fpsEl = document.getElementById('fps') as HTMLElement;
const resEl = document.getElementById('res') as HTMLElement;
const reloadBtn = document.getElementById('reloadBtn') as HTMLButtonElement;

let last = performance.now();
let frameCount = 0; // avoid collision with window.frames
let lastReport = last;

function tick() {
  const now = performance.now();
  frameCount++;
  if (now - lastReport > 1000) {
    const fps = Math.round((frameCount * 1000) / (now - lastReport));
    fpsEl.textContent = `FPS: ${fps}`;
    lastReport = now;
    frameCount = 0;
  }
  requestAnimationFrame(tick);
}
requestAnimationFrame(tick);

function updateResolution() {
  if (img.naturalWidth && img.naturalHeight) {
    resEl.textContent = `Resolution: ${img.naturalWidth}x${img.naturalHeight}`;
  }
}

img.addEventListener('load', updateResolution);
reloadBtn.addEventListener('click', () => {
  const u = new URL(img.src, window.location.href);
  u.searchParams.set('t', Date.now().toString());
  img.src = u.toString();
});

// Initial resolution report when image is cached
if (img.complete) updateResolution();
