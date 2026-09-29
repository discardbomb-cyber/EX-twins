# Procedural combat assets

The sound directory is populated by `tools/build_combat_sounds.mjs`, which
synthesizes every effect (FM voices, filtered noise, chirps, short reverb) and
encodes it with a WASM Ogg Vorbis encoder. The files are original generated
assets, not downloaded or third-party audio.

```
cd tools
npm install
npm run sounds         # regenerate src/main/resources/assets/relics_addon/sounds/combat
npm run sounds:check   # decode every referenced file: mono 48 kHz, peak, DC, subtitles
node build_combat_sounds.mjs --wav-only   # WAV previews in work/sound-preview
```
