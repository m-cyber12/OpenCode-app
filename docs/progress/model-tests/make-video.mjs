// Makes out.mp4 from frames drawn by this script. Run it in the project folder:
//   node make-video.mjs
// Needs @ffmpeg/core 0.12.10 installed in this project (npm install @ffmpeg/core@0.12.10).
import fs from "fs";

// Settings: change these, and drawFrame, for your video.
const W = 320, H = 240, FPS = 10, N = 30;

// The ffmpeg WebAssembly build is written for a web worker. It needs the two
// globals a worker has, and nothing else from a browser.
const coreDir = "./node_modules/@ffmpeg/core/dist/esm";
globalThis.self = { location: { href: "file://" + process.cwd() + "/" + coreDir.slice(2) + "/" } };
const createFFmpegCore = (await import(coreDir + "/ffmpeg-core.js")).default;
const logs = [];
const core = await createFFmpegCore({
  wasmBinary: fs.readFileSync(coreDir + "/ffmpeg-core.wasm"),
  print: (m) => logs.push(m),
  printErr: (m) => logs.push(m),
});

// Draw frame f into px: raw RGBA, 4 bytes per pixel, row by row.
function drawFrame(f, px) {
  for (let y = 0; y < H; y++) {
    for (let x = 0; x < W; x++) {
      const o = (y * W + x) * 4;
      const inSquare = x > f * 8 && x < f * 8 + 60 && y > 90 && y < 150;
      px[o] = inSquare ? 255 : 20;
      px[o + 1] = 60;
      px[o + 2] = inSquare ? 40 : 200;
      px[o + 3] = 255;
    }
  }
}

const frames = new Uint8Array(W * H * 4 * N);
for (let f = 0; f < N; f++) {
  drawFrame(f, frames.subarray(f * W * H * 4, (f + 1) * W * H * 4));
}
core.FS.writeFile("frames.raw", frames);

const rc = core.exec("-f", "rawvideo", "-pix_fmt", "rgba", "-s", W + "x" + H, "-r", String(FPS),
  "-i", "frames.raw", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-preset", "ultrafast",
  "-movflags", "+faststart", "out.mp4");
if (rc !== 0) {
  console.error("encode failed, rc=" + rc + " last log: " + logs.slice(-3).join(" | "));
  process.exit(1);
}
// Check the file decodes end to end before you report success.
const check = core.exec("-v", "error", "-i", "out.mp4", "-f", "null", "-");
fs.writeFileSync("out.mp4", core.FS.readFile("out.mp4"));
console.log("wrote out.mp4 bytes=" + fs.statSync("out.mp4").size + " decode_rc=" + check);
if (check !== 0) process.exit(1);
