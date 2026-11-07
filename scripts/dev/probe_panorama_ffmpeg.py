"""Throwaway local decoder feasibility check; this is not the product playback implementation.

Input: the generated 320x160 red-then-blue H.264 test video. ffmpeg is supplied by the user,
not downloaded or distributed by this script. No shell is used and no output files are written.
"""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

WIDTH, HEIGHT, CHANNELS = 320, 160, 3
FRAME_BYTES = WIDTH * HEIGHT * CHANNELS


def start(executable: str, video: Path, seconds: float) -> subprocess.Popen:
    args = [executable, '-nostdin', '-v', 'error', '-ss', str(seconds),
            '-protocol_whitelist', 'file,pipe', '-i', str(video), '-an',
            '-vf', f'fps=10,scale={WIDTH}:{HEIGHT}', '-pix_fmt', 'rgb24',
            '-threads', '1', '-f', 'rawvideo', 'pipe:1']
    return subprocess.Popen(args, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                            stderr=subprocess.DEVNULL,
                            creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))


def frame(process: subprocess.Popen) -> bytes:
    data = bytearray()
    while len(data) < FRAME_BYTES:
        chunk = process.stdout.read(FRAME_BYTES - len(data))
        if not chunk:
            raise RuntimeError(f'ffmpeg ended before a full frame (exit={process.poll()})')
        data.extend(chunk)
    return bytes(data)


def stop(process: subprocess.Popen) -> int:
    if process.poll() is None:
        process.terminate()
    try:
        code = process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        code = process.wait(timeout=5)
    process.stdout.close()
    if process.poll() is None:
        raise RuntimeError('ffmpeg child did not exit')
    return code


def centre(frame_bytes: bytes) -> tuple[int, int, int]:
    pos = ((HEIGHT // 2) * WIDTH + WIDTH // 2) * CHANNELS
    return tuple(frame_bytes[pos:pos + 3])


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('--ffmpeg', default=shutil.which('ffmpeg'))
    parser.add_argument('--video', required=True, type=Path)
    args = parser.parse_args()
    if not args.ffmpeg or not Path(args.ffmpeg).is_file():
        parser.error('ffmpeg executable not found; choose an existing local executable')
    if not args.video.is_file():
        parser.error('video file not found')
    video = args.video.resolve()
    first = start(str(Path(args.ffmpeg).resolve()), video, 0.0)
    try:
        red = centre(frame(first))
        assert red[0] > 170 and red[2] < 80, f'expected red, got {red}'
        # Pausing consumption must not accumulate an unbounded application-side frame queue.
        time.sleep(0.5)
        assert first.poll() is None, 'decoder exited while consumer was paused'
    finally:
        first_exit = stop(first)
    second = start(str(Path(args.ffmpeg).resolve()), video, 1.4)
    try:
        blue = centre(frame(second))
        assert blue[2] > 170 and blue[0] < 80, f'expected blue after seek, got {blue}'
    finally:
        second_exit = stop(second)
    print(f'PANORAMA_FFMPEG_SPIKE_OK red={red} blue={blue} child_exit={first_exit},{second_exit}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
