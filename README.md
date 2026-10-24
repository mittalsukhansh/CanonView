# CanonView

A powerful, full-featured Android application and Python desktop companion tool for Canon EOS DSLR cameras (EOS 1200D and compatible models) operating over USB PTP protocol.

![Version](https://img.shields.io/badge/version-1.0.1-blue.svg)
![Platform](https://img.shields.io/badge/platform-Android%20%7C%20Windows%20%7C%20macOS%20%7C%20Linux-brightgreen.svg)

---

## Android App Features & Guide

### Features:
- **Real-Time Live View**: Viewfinder streaming via USB-OTG.
- **Real-Time Camera HUD**: On-screen replica displaying Mode, Shutter Speed, Aperture, ISO, White Balance, Drive Mode, Metering, Focus Mode, Picture Style, Battery Level, Shots Remaining, EV Scale, and a live 64-bin Histogram.
- **Full Settings Control**: On-screen controls to adjust **ISO**, **Aperture**, **Shutter Speed**, **White Balance**, **Drive Mode** (Single, Continuous, Self-Timer 2s / 10s), and **Metering Mode**.
- **Tap-To-Focus**: Tap anywhere on the Live View image to move the camera's AF point and trigger autofocus.
- **1x / 5x / 10x Live View Zoom**: Toggle digital viewfinder zoom for critical focus checks.
- **Focus Peaking**: High-contrast green edge highlighting mapped precisely to image bounds for manual focus.
- **Composition Grid**: Rule of Thirds (3x3) and Rule of Thirds + Center Cross grid overlays.
- **Intervalometer (Timelapse)**: Automated sequence shooting with custom interval spacing (seconds) and shot count (0 for continuous).
- **Exposure Bracketing (AEB)**: Automated exposure bracketing presets (`3x1/3 EV` up to `5x2 EV`) adjusting shutter speed in M mode or exposure compensation in P/Tv/Av modes.
- **9:16 & 1:1 Aspect Ratio Guides**: Composition crop guides with dimmed side masks for vertical 9:16 (Instagram Reels / YouTube Shorts / TikTok) and 1:1 Square content framing.
- **Manual Lens Stepping (`N<<`, `N<`, `F>`, `F>>`)**: Micro-adjust lens focus motor near or far in fine and coarse steps directly from the screen.
- **Remote Controls**: Shutter release, On-Screen Countdown Timer (2s, 5s, 10s), and Built-In Pop-Up Flash release button.
- **Post-Shot Photo Preview**: Instant thumbnail overlay card of single-shot captures.

### How to Use on Android Phone:
1. Connect your Canon camera to your phone using a **USB-OTG adapter cable**.
2. Turn your camera power switch **ON**.
3. Accept the USB permission prompt on your Android phone.
4. Enjoy real-time Live View streaming and full camera control.

---

## Desktop Companion Tool (`canon_tool.py`)

Run the desktop companion tool on Windows, macOS, or Linux:

```bash
# Install dependencies
pip install pyusb pillow libusb-package

# Run connection diagnostic probe
python canon_tool.py --probe

# Launch Desktop GUI
python canon_tool.py
```

### Windows USB Driver Setup (Zadig):
If running on Windows, Windows binds cameras to its default WPD driver. To enable Python direct USB access:
1. Download [Zadig](https://zadig.akeo.ie/).
2. Click **Options** -> check **List All Devices**.
3. Select **Canon EOS 1200D** (or your Canon model).
4. Select **WinUSB** (or **libusbK**) on the right and click **Replace Driver**.
5. Re-plug the camera USB cable.

---

## Releases

Download the ready-to-install Android APK:
- **`CanonView-v1.0.1.apk`**: Version 1.0.1 Release build.

---

## Built With
- **Android**: Kotlin, Jetpack Core, AppCompat, Custom Canvas Views, Android USB Host API.
- **Desktop**: Python 3, PyUSB, LibUSB, Tkinter, Pillow.
