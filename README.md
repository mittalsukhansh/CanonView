# CanonView 📷📱

An Android application and Python desktop companion tool for Canon EOS DSLR cameras (EOS 1200D and similar) via USB PTP protocol.

## Features
- **Live View Stream**: Real-time camera viewfinder streaming over USB-OTG or USB.
- **Camera HUD Overlay**: Mode, Shutter Speed, Aperture, ISO, Battery level, Remaining shots, EV scale, and 64-bin real-time histogram.
- **Full Camera Control**: ISO, Aperture, Shutter Speed, White Balance, Drive Mode (Single, Burst, Self-Timer 2s/10s), Metering Mode.
- **Shooting & Controls**: Remote Shutter Release, Built-in Pop-up Flash release.
- **Touch & Manual Focus**: Tap-to-focus on screen + Manual Lens Stepping (`N<<`, `N<`, `F>`, `F>>`).
- **Focus Peaking**: Real-time green edge highlighting for in-focus areas.
- **Live Zoom**: 1x, 5x, 10x digital viewfinder zoom.
- **Aspect Ratio Crop Guides**: 9:16 (Reels/Shorts/TikTok) and 1:1 Square composition masks.
- **Photo Preview Card**: Post-capture image preview overlay.

## Desktop Companion Tool (`canon_tool.py`)
Run on laptop/desktop:
```bash
python canon_tool.py          # Launch GUI
python canon_tool.py --probe  # Connection diagnostics
```
