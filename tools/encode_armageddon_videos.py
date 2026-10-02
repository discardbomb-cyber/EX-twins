"""Encode completed native captures using their recorded game-tick timestamps."""
import pathlib, subprocess, time, argparse
parser = argparse.ArgumentParser()
parser.add_argument('--frames', default='D:/ex-twins-captures/miners-dream-run/screenshots')
parser.add_argument('--output', default='D:/ex-twins-captures/armageddon-top-20261002')
parser.add_argument('--log', default='artifacts/miners-dream-top-20261002-attempt-2.log')
args = parser.parse_args()
root = pathlib.Path(args.frames)
output = pathlib.Path(args.output)
output.mkdir(parents=True, exist_ok=True)
log = pathlib.Path(args.log)
ffmpeg = 'D:/ex-twins-captures/ship-models-2026-10-02/video-tools/imageio_ffmpeg/binaries/ffmpeg-win-x86_64-v7.1.exe'
for clip in ['armageddon','mana-armageddon','rf-armageddon']:
    deadline = time.monotonic() + 900
    while True:
        messages = log.read_text(encoding='utf-8', errors='replace') if log.exists() else ''
        if any('World scenario '+clip+':' in line and ' frames' in line for line in messages.splitlines()): break
        if time.monotonic() > deadline: raise TimeoutError(clip)
        time.sleep(2)
    rows = [line.split(',') for line in (root / ('scenario-'+clip+'.ticks')).read_text().splitlines()]
    manifest = output / (clip+'.concat')
    with manifest.open('w', encoding='utf-8') as file:
        for index, (frame, tick) in enumerate(rows):
            path = root / ('scenario-'+clip+'-'+f'{int(frame):03d}'+'.png')
            if not path.exists(): raise FileNotFoundError(path)
            duration = max(.001, (float(rows[index+1][1])-float(tick))/20) if index+1<len(rows) else .05
            file.write("file '"+path.as_posix()+"'\nduration "+str(duration)+'\n')
        file.write("file '"+path.as_posix()+"'\n")
    video = output / (clip+'.mp4')
    with (output/(clip+'-encode.log')).open('w') as capture_log:
        subprocess.run([ffmpeg,'-y','-f','concat','-safe','0','-i',str(manifest),'-vf',
            'scale=trunc(iw/2)*2:trunc(ih/2)*2','-r','30','-c:v','libx264','-preset','fast',
            '-crf','20','-pix_fmt','yuv420p','-movflags','+faststart',str(video)],
            stdout=capture_log, stderr=subprocess.STDOUT, check=True)
    assert video.stat().st_size > 10000
    print('PASS', clip, len(rows), 'frames', video, flush=True)
