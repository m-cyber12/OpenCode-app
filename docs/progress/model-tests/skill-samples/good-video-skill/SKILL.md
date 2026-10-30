---
name: make-mp4-video
description: Use when the user asks for a video, MP4, animation or clip made from code, or for an ffmpeg-like encode in this app.
---

# Make an MP4 from code

1. Read `briefs/video.md` once.
2. In the project folder run `npm install @ffmpeg/core@0.12.10`.
3. Write `make-video.mjs` from the recipe in that brief and change only the settings and `drawFrame`.
4. Run `node make-video.mjs`. Done when the output line ends with `decode_rc=0`.
