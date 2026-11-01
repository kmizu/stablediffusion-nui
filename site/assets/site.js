// SDNUI manual: the hero resolves a real SDNUI output from noise, as the app's live preview does.
(function () {
  const nav = document.querySelector(".nav-toggle");
  if (nav && window.matchMedia("(max-width: 820px)").matches) nav.removeAttribute("open");

  const canvas = document.querySelector("canvas.denoise");
  if (!canvas) return;
  const ctx = canvas.getContext("2d");
  const label = document.querySelector(".hero-plate .step");
  const replay = document.querySelector(".hero-plate .replay");
  const steps = 24;
  const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  const w = canvas.width, h = canvas.height;
  const image = new Image();
  image.decoding = "async";
  image.src = canvas.dataset.src;

  let target, noise, timer;
  function prepare() {
    ctx.drawImage(image, 0, 0, w, h);
    target = ctx.getImageData(0, 0, w, h);
    noise = new Float32Array(w * h * 3);
    let seed = 20261101;
    const rand = () => ((seed = (seed * 1664525 + 1013904223) >>> 0) / 4294967296);
    for (let i = 0; i < noise.length; i++) {
      // Sum of uniforms approximates the Gaussian noise a sampler starts from.
      noise[i] = (rand() + rand() + rand() - 1.5) * 2;
    }
  }
  function frame(step) {
    // Cosine schedule: signal share grows slowly at first, then quickly.
    const t = step / steps;
    const signal = Math.sin(t * Math.PI / 2) ** 2;
    const sNoise = Math.sqrt(1 - signal) * 110;
    const out = ctx.createImageData(w, h);
    const src = target.data, dst = out.data;
    for (let p = 0, n = 0; p < src.length; p += 4, n += 3) {
      const grey = (src[p] + src[p + 1] + src[p + 2]) / 3;
      for (let c = 0; c < 3; c++) {
        // Early steps keep only coarse brightness, like a latent preview.
        const base = grey + (src[p + c] - grey) * signal;
        dst[p + c] = 128 + (base - 128) * (0.35 + 0.65 * signal) + noise[n + c] * sNoise;
      }
      dst[p + 3] = 255;
    }
    ctx.putImageData(out, 0, 0);
    if (label) label.textContent = `Step ${step}/${steps}`;
  }
  function run() {
    clearInterval(timer);
    if (reduce) {
      ctx.drawImage(image, 0, 0, w, h);
      if (label) label.textContent = `Step ${steps}/${steps}`;
      return;
    }
    let step = 0;
    frame(step);
    timer = setInterval(() => {
      step += 1;
      if (step >= steps) {
        clearInterval(timer);
        ctx.drawImage(image, 0, 0, w, h);
        if (label) label.textContent = `Step ${steps}/${steps}`;
      } else {
        frame(step);
      }
    }, 70);
  }
  image.addEventListener("load", () => { prepare(); run(); });
  if (replay) {
    if (reduce) replay.hidden = true;
    replay.addEventListener("click", () => { if (target) run(); });
  }
})();
