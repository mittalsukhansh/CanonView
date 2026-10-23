#!/usr/bin/env python3
"""
CanonView laptop tool: live view, shutter, flash pop-up, tap-to-focus, ISO/aperture/shutter/WB
for a Canon EOS 1200D over USB. Same PTP logic as the Android app (cross-checked with libgphoto2).

  python canon_tool.py --probe     step-by-step connection diagnostics (run this first)
  python canon_tool.py             GUI

Setup: see README.md in this folder (driver notes for Windows/Linux/macOS).
"""
import io
import math
import queue
import struct
import sys
import threading
import time

import usb.core
import usb.util

CANON = 0x04A9

OC_GET_DEVICE_INFO = 0x1001
OC_OPEN_SESSION = 0x1002
OC_CLOSE_SESSION = 0x1003
OC_EOS_SET_PROP = 0x9110
OC_EOS_REMOTE_RELEASE_ON = 0x9128
OC_EOS_REMOTE_RELEASE_OFF = 0x9129
OC_EOS_POPUP_FLASH = 0x9131
OC_EOS_SET_REMOTE_MODE = 0x9114
OC_EOS_SET_EVENT_MODE = 0x9115
OC_EOS_GET_EVENT = 0x9116
OC_EOS_KEEP_DEVICE_ON = 0x911D
OC_EOS_GET_VIEWFINDER = 0x9153
OC_EOS_DO_AF = 0x9154
OC_EOS_ZOOM_POSITION = 0x9159

PROP_EVF_OUTPUT_DEVICE = 0xD1B0
PROP_EVF_MODE = 0xD1B1

EC_PROP_VALUE_CHANGED = 0xC189
EC_AVAIL_LIST_CHANGED = 0xC18A

RC_OK = 0x2001
RC_BUSY = 0x2019
RC_SESSION_OPEN = 0x201E
RC_NOT_READY = 0xA102

# name, prop id, value mask, labels
ISO = {0: 'Auto', 64: '50', 72: '100', 75: '125', 77: '160', 80: '200', 83: '250', 85: '320', 88: '400', 91: '500', 93: '640', 96: '800', 99: '1000', 101: '1250', 104: '1600', 107: '2000', 109: '2500', 112: '3200', 115: '4000', 117: '5000', 120: '6400', 123: '8000', 125: '10000', 128: '12800'}
APERTURE = {8: 'f/1', 11: 'f/1.1', 12: 'f/1.2', 16: 'f/1.4', 19: 'f/1.6', 20: 'f/1.8', 24: 'f/2', 27: 'f/2.2', 28: 'f/2.5', 32: 'f/2.8', 35: 'f/3.2', 36: 'f/3.5', 40: 'f/4', 43: 'f/4.5', 45: 'f/5', 48: 'f/5.6', 51: 'f/6.3', 52: 'f/6.7', 53: 'f/7.1', 56: 'f/8', 59: 'f/9', 60: 'f/9.5', 61: 'f/10', 64: 'f/11', 68: 'f/13', 69: 'f/14', 72: 'f/16', 75: 'f/18', 76: 'f/19', 77: 'f/20', 80: 'f/22', 83: 'f/25', 84: 'f/27', 85: 'f/29', 88: 'f/32'}
SHUTTER = {4: 'Bulb', 16: '30"', 19: '25"', 21: '20"', 24: '15"', 27: '13"', 28: '10"', 32: '8"', 35: '6"', 36: '6"', 37: '5"', 40: '4"', 43: '3.2"', 44: '3"', 45: '2.5"', 48: '2"', 51: '1.6"', 52: '1.5"', 53: '1.3"', 56: '1"', 59: '0.8"', 60: '0.7"', 61: '0.6"', 64: '0.5"', 67: '0.4"', 68: '0.3"', 72: '1/4', 75: '1/5', 76: '1/6', 80: '1/8', 84: '1/10', 85: '1/13', 88: '1/15', 92: '1/20', 93: '1/25', 96: '1/30', 99: '1/40', 100: '1/45', 101: '1/50', 104: '1/60', 107: '1/80', 108: '1/90', 109: '1/100', 112: '1/125', 115: '1/160', 116: '1/180', 117: '1/200', 120: '1/250', 123: '1/320', 124: '1/350', 125: '1/400', 128: '1/500', 131: '1/640', 132: '1/750', 133: '1/800', 136: '1/1000', 139: '1/1250', 140: '1/1500', 141: '1/1600', 144: '1/2000', 147: '1/2500', 148: '1/3000', 149: '1/3200', 152: '1/4000'}
WB = {0: 'Auto', 1: 'Daylight', 2: 'Cloudy', 3: 'Tungsten', 4: 'Fluorescent', 5: 'Flash', 6: 'Manual', 8: 'Shadow', 9: 'Color temp'}
PROPS = [
    ("ISO", 0xD103, 0xFFFF, ISO),
    ("Aperture", 0xD101, 0xFFFF, APERTURE),
    ("Shutter", 0xD102, 0xFFFF, SHUTTER),
    ("WB", 0xD109, 0xFF, WB),
]
AE_MODE = {0: "P", 1: "Tv", 2: "Av", 3: "M", 4: "Bulb", 5: "A-DEP", 6: "DEP", 7: "C", 9: "Auto",
           10: "Night Portrait", 11: "Sports", 12: "Portrait", 13: "Landscape", 14: "Close-up",
           15: "Flash Off", 19: "CA", 20: "Movie"}
DRIVE = {0: "Single", 1: "Continuous", 2: "Video", 4: "Cont. high", 5: "Cont. low", 6: "Single silent",
         7: "Cont. timer", 0x10: "Timer 10s", 0x11: "Timer 2s"}
METERING = {0: "Center", 1: "Spot", 2: "Average", 3: "Evaluative", 4: "Partial", 5: "Center avg",
            6: "Spot (AF)", 7: "Multi spot"}
FOCUS = {0: "One-Shot", 1: "AI Servo", 2: "AI Focus", 3: "MF"}
STYLE = {0x81: "Standard", 0x82: "Portrait", 0x83: "Landscape", 0x84: "Neutral", 0x85: "Faithful",
         0x86: "Monochrome", 0x87: "Auto", 0x88: "Fine detail", 0x21: "User 1", 0x22: "User 2", 0x23: "User 3"}
BATTERY = {0: "Low", 1: "50%", 2: "100%", 4: "75%", 5: "25%"}
INFO = [
    ("Mode", 0xD105, 0xFFFF, AE_MODE),
    ("Exp comp", 0xD104, 0xFF, {}),
    ("Drive", 0xD106, 0xFFFF, DRIVE),
    ("Metering", 0xD107, 0xFF, METERING),
    ("Focus", 0xD108, 0xFFFFFFFF, FOCUS),
    ("Style", 0xD110, 0xFF, STYLE),
    ("Battery", 0xD111, 0xFFFF, BATTERY),
    ("Shots", 0xD11B, 0xFFFFFFFF, {}),
]
TRACKED = {pid: (name, mask, labels) for name, pid, mask, labels in PROPS + INFO}


def label(pid, v):
    if pid == 0xD104:  # signed, 1/8 EV units, shown to nearest 1/3 stop
        ev = struct.unpack("b", bytes([v & 0xFF]))[0] / 8.0
        t = round(ev * 3) / 3
        return "0" if abs(t) < 0.05 else "%+.1f" % t
    if pid == 0xD11B:
        return str(v)
    return TRACKED[pid][2].get(v, "0x%X" % v)


class PtpError(Exception):
    pass


class Ptp:
    def __init__(self, ep_in, ep_out):
        self.i, self.o = ep_in, ep_out
        self.tx = 0
        self.rx = b""

    def _container(self, typ, code, payload):
        return struct.pack("<IHHI", 12 + len(payload), typ, code, self.tx) + payload

    def _fill(self, need, timeout):
        while len(self.rx) < need:
            try:
                d = self.i.read(16384, timeout)
            except usb.core.USBError as e:
                raise PtpError("USB read failed: %s" % e)
            self.rx += bytes(d)  # an empty read is a zero-length packet; keep going

    def _read_container(self, timeout):
        self._fill(12, timeout)
        ln, typ, code, _ = struct.unpack_from("<IHHI", self.rx)
        if ln < 12 or ln > 64 * 1024 * 1024:
            raise PtpError("bad container length %d" % ln)
        self._fill(ln, timeout)
        payload, self.rx = self.rx[12:ln], self.rx[ln:]
        return typ, code, payload

    def transact(self, op, params=(), data_out=None, timeout=3000):
        self.tx += 1
        try:
            self.o.write(self._container(1, op, b"".join(struct.pack("<I", p) for p in params)), timeout)
            if data_out is not None:
                self.o.write(self._container(2, op, data_out), timeout)
        except usb.core.USBError as e:
            raise PtpError("USB write failed: %s" % e)
        data = None
        while True:
            typ, code, payload = self._read_container(timeout)
            if typ == 2:
                data = payload
            elif typ == 3:
                return code, data


def open_usb(log=print):
    backend = None
    try:
        import libusb_package
        backend = libusb_package.get_libusb1_backend()
    except Exception:
        pass
    dev = usb.core.find(idVendor=CANON, backend=backend)
    if dev is None:
        raise PtpError("No Canon USB device found (camera on? data cable? driver installed? see README)")
    log("found Canon device %04x:%04x" % (dev.idVendor, dev.idProduct))
    try:
        for cfg in dev:
            for intf in cfg:
                if dev.is_kernel_driver_active(intf.bInterfaceNumber):
                    dev.detach_kernel_driver(intf.bInterfaceNumber)
                    log("detached kernel driver")
    except (NotImplementedError, usb.core.USBError, ValueError):
        pass
    try:
        dev.set_configuration()
    except usb.core.USBError as e:
        log("set_configuration: %s (continuing)" % e)
    cfg = dev.get_active_configuration()
    intf = next((i for i in cfg if i.bInterfaceClass == 6), None)
    if intf is None:
        raise PtpError("no Still Image (PTP) interface on this device")
    eps = list(intf)
    ep_in = next(e for e in eps if e.bmAttributes & 3 == 2 and e.bEndpointAddress & 0x80)
    ep_out = next(e for e in eps if e.bmAttributes & 3 == 2 and not e.bEndpointAddress & 0x80)
    try:
        usb.util.claim_interface(dev, intf.bInterfaceNumber)
    except usb.core.USBError as e:
        pass  # On Windows WinUSB, interface 0 is claimed automatically
    log("claimed PTP interface, bulk in=0x%02x out=0x%02x" % (ep_in.bEndpointAddress, ep_out.bEndpointAddress))
    return dev, Ptp(ep_in, ep_out)


def ptp_string(b, pos):
    n = b[pos]
    s = b[pos + 1: pos + 1 + n * 2].decode("utf-16le", "ignore").rstrip("\x00")
    return s, pos + 1 + n * 2


def parse_device_info(d):
    pos = 8
    desc, pos = ptp_string(d, pos)
    pos += 2
    ops = []
    n = struct.unpack_from("<I", d, pos)[0]; pos += 4
    for _ in range(n):
        ops.append(struct.unpack_from("<H", d, pos)[0]); pos += 2
    for _ in range(4):  # events, props, capture formats, image formats
        n = struct.unpack_from("<I", d, pos)[0]; pos += 4 + 2 * n
    maker, pos = ptp_string(d, pos)
    model, pos = ptp_string(d, pos)
    return maker, model, ops


class Canon:
    def __init__(self, ptp):
        self.ptp = ptp
        self.values = {}
        self.options = {}
        self.changed = False
        self.n = 0
        self.keepalive = time.time()

    def set_prop(self, prop, value):
        return self.ptp.transact(OC_EOS_SET_PROP, data_out=struct.pack("<III", 12, prop, value))[0]

    def start(self, log=print):
        code, _ = self.ptp.transact(OC_OPEN_SESSION, (1,))
        log("OpenSession -> 0x%x" % code)
        if code not in (RC_OK, RC_SESSION_OPEN):
            raise PtpError("OpenSession failed 0x%x" % code)
        for name, op in (("SetRemoteMode", OC_EOS_SET_REMOTE_MODE), ("SetEventMode", OC_EOS_SET_EVENT_MODE)):
            code, _ = self.ptp.transact(op, (1,))
            log("%s -> 0x%x" % (name, code))
            if code != RC_OK:
                raise PtpError("%s failed 0x%x" % (name, code))
        for _ in range(4):
            self.poll()
            time.sleep(0.05)
        code = self.set_prop(PROP_EVF_MODE, 1)
        log("EvfMode=1 -> 0x%x" % code)
        if code not in (RC_OK, RC_BUSY):
            raise PtpError("EvfMode failed 0x%x" % code)
        code = self.set_prop(PROP_EVF_OUTPUT_DEVICE, 2)
        log("EvfOutputDevice=PC -> 0x%x" % code)
        if code != RC_OK:
            raise PtpError("EvfOutputDevice failed 0x%x" % code)

    def poll(self):
        _, d = self.ptp.transact(OC_EOS_GET_EVENT)
        if not d:
            return
        pos = 0
        while pos + 8 <= len(d):
            size, code = struct.unpack_from("<II", d, pos)
            if size < 8 or pos + size > len(d):
                break
            if code == EC_PROP_VALUE_CHANGED and size >= 16:
                prop, val = struct.unpack_from("<II", d, pos + 8)
                if prop in TRACKED:
                    self.values[prop] = val & TRACKED[prop][1]
                    self.changed = True
            elif code == EC_AVAIL_LIST_CHANGED and size >= 20:
                prop, typ, count = struct.unpack_from("<III", d, pos + 8)
                if prop in TRACKED and typ == 3 and 1 <= count <= 512 and 20 + count * 4 <= size:
                    mask = TRACKED[prop][1]
                    self.options[prop] = [struct.unpack_from("<I", d, pos + 20 + 4 * k)[0] & mask
                                          for k in range(count)]
                    self.changed = True
            pos += size

    def frame(self):
        self.n += 1
        if self.n % 5 == 0:
            self.poll()
        if time.time() - self.keepalive > 10:
            self.ptp.transact(OC_EOS_KEEP_DEVICE_ON)
            self.keepalive = time.time()
        code, d = self.ptp.transact(OC_EOS_GET_VIEWFINDER, (0x00200000, 0, 0), timeout=2000)
        if code in (RC_BUSY, RC_NOT_READY):
            return None
        if code != RC_OK:
            raise PtpError("GetViewFinder failed 0x%x" % code)
        return extract_jpeg(d) if d else None

    def focus(self, x, y):
        z, _ = self.ptp.transact(OC_EOS_ZOOM_POSITION, (x, y))
        f, _ = self.ptp.transact(OC_EOS_DO_AF, timeout=8000)
        print("AF x=%d y=%d zoomPos=0x%x doAf=0x%x" % (x, y, z, f))
        return None if f == RC_OK else "AF failed 0x%x" % f

    def flash(self):
        r, _ = self.ptp.transact(OC_EOS_POPUP_FLASH)
        print("popup flash 0x%x" % r)
        return None if r == RC_OK else "Flash failed 0x%x (use P/Av/Tv/M)" % r

    def shoot(self):
        self.poll()
        half, _ = self.ptp.transact(OC_EOS_REMOTE_RELEASE_ON, (1, 0), timeout=10000)
        self.poll()
        full, _ = self.ptp.transact(OC_EOS_REMOTE_RELEASE_ON, (2, 0), timeout=10000)
        print("shutter half=0x%x full=0x%x" % (half, full))
        err = None if full == RC_OK else "Shutter failed 0x%x (half 0x%x)" % (full, half)
        self.ptp.transact(OC_EOS_REMOTE_RELEASE_OFF, (2,))
        self.poll()
        self.ptp.transact(OC_EOS_REMOTE_RELEASE_OFF, (1,))
        time.sleep(0.3)
        self.set_prop(PROP_EVF_MODE, 1)
        self.set_prop(PROP_EVF_OUTPUT_DEVICE, 2)
        return err

    def stop(self):
        for fn in (lambda: self.set_prop(PROP_EVF_OUTPUT_DEVICE, 0),
                   lambda: self.ptp.transact(OC_EOS_GET_EVENT),
                   lambda: self.ptp.transact(OC_EOS_SET_REMOTE_MODE, (0,)),
                   lambda: self.ptp.transact(OC_CLOSE_SESSION)):
            try:
                fn()
            except Exception:
                pass


def extract_jpeg(d):
    pos = 0
    while pos + 8 <= len(d):
        ln, typ = struct.unpack_from("<II", d, pos)
        if ln < 8 or pos + ln > len(d):
            return None
        if typ in (1, 11):
            return d[pos + 8: pos + ln]
        pos += ln
    return None


def probe():
    print("== CanonView probe ==")
    dev, ptp = open_usb()
    code, d = ptp.transact(OC_GET_DEVICE_INFO)
    print("GetDeviceInfo -> 0x%x" % code)
    if d:
        maker, model, ops = parse_device_info(d)
        print("camera: %s %s" % (maker, model))
        want = {0x910F: "RemoteRelease", 0x9128: "RemoteReleaseOn", 0x9131: "PopupFlash",
                0x9153: "GetViewFinder", 0x9154: "DoAf", 0x9159: "ZoomPosition"}
        for op, name in want.items():
            print("  %-16s 0x%04x %s" % (name, op, "supported" if op in ops else "NOT listed"))
    cam = Canon(ptp)
    cam.start()
    for i in range(30):
        f = cam.frame()
        if f:
            print("live view OK: got a %d byte JPEG on try %d" % (len(f), i + 1))
            break
        time.sleep(0.1)
    else:
        print("no live view frame after 30 tries (mode dial on a photo mode? lens attached?)")
    cam.poll()
    for pid, v in cam.values.items():
        print("  %s = %s" % (TRACKED[pid][0], label(pid, v)))
    cam.stop()
    print("probe done")


class App:
    def __init__(self):
        import tkinter as tk
        from tkinter import ttk
        from PIL import Image, ImageTk
        self.tk, self.ttk, self.Image, self.ImageTk = tk, ttk, Image, ImageTk
        self.root = tk.Tk()
        self.root.title("CanonView")
        self.canvas = tk.Canvas(self.root, width=960, height=640, bg="black", highlightthickness=0)
        self.canvas.pack()
        self.canvas.bind("<Button-1>", self.on_click)
        self.status = tk.StringVar(value="connecting...")
        bar = tk.Frame(self.root)
        bar.pack(fill="x")
        self.combos = {}
        self.codes = {}
        for name, pid, _, _ in PROPS:
            tk.Label(bar, text=name).pack(side="left", padx=(8, 2))
            cb = ttk.Combobox(bar, width=9, state="readonly")
            cb.pack(side="left")
            cb.bind("<<ComboboxSelected>>", lambda e, p=pid, c=cb: self.on_select(p, c))
            self.combos[pid] = cb
        tk.Button(bar, text="SHOOT", command=lambda: self.actions.put(("shoot",))).pack(side="right", padx=4)
        tk.Button(bar, text="FLASH", command=lambda: self.actions.put(("flash",))).pack(side="right", padx=4)
        tk.Label(self.root, textvariable=self.status, anchor="w").pack(fill="x")
        self.actions = queue.Queue()
        self.latest = None
        self.fw = self.fh = 1
        self.scale = 1.0
        self.msg = None
        self.est = None
        self.hist = None
        self.cam = None
        self.running = True
        self.photo = None
        threading.Thread(target=self.worker, daemon=True).start()
        self.root.protocol("WM_DELETE_WINDOW", self.close)
        self.tick()

    def close(self):
        self.running = False
        self.root.after(400, self.root.destroy)

    def on_select(self, pid, cb):
        i = cb.current()
        if i >= 0:
            self.actions.put(("set", pid, self.codes[pid][i]))

    def on_click(self, ev):
        if self.cam and self.fw > 1:
            self.actions.put(("focus", int(ev.x / self.scale), int(ev.y / self.scale)))

    def worker(self):
        cam = None
        try:
            dev, ptp = open_usb(print)
            cam = Canon(ptp)
            cam.start(print)
            self.cam = cam
            t0, n = time.time(), 0
            while self.running:
                while not self.actions.empty():
                    a = self.actions.get()
                    if a[0] == "set":
                        r = cam.set_prop(a[1], a[2])
                        self.msg = None if r == RC_OK else "Set failed 0x%x (check mode dial)" % r
                    elif a[0] == "focus":
                        self.msg = cam.focus(a[1], a[2])
                    elif a[0] == "shoot":
                        self.msg = cam.shoot()
                    elif a[0] == "flash":
                        self.msg = cam.flash()
                    cam.poll()
                f = cam.frame()
                if f is None:
                    time.sleep(0.05)
                    continue
                self.latest = f
                n += 1
                if time.time() - t0 >= 1:
                    self.status.set("Live %d fps%s" % (n, ("  |  " + self.msg) if self.msg else ""))
                    t0, n = time.time(), 0
        except Exception as e:
            self.status.set("Error: %s" % e)
            print("error:", e)
        finally:
            if cam:
                cam.stop()
            self.cam = None

    def draw_hud(self):
        cam = self.cam
        if not cam:
            return
        cv, v = self.canvas, cam.values

        def t(pid):
            return label(pid, v[pid]) if pid in v else "-"

        W, H = 960, 640
        cv.create_rectangle(0, 0, W, 62, fill="black", stipple="gray50", outline="")
        cv.create_rectangle(12, 8, 12 + 14 + 14 * len(t(0xD105)), 38, outline="white", width=2)
        cv.create_text(19, 10, anchor="nw", fill="white", font=("Arial", 16, "bold"), text=t(0xD105))
        x = 12 + 14 + 14 * len(t(0xD105)) + 16
        main = "%s    %s    ISO %s" % (t(0xD102), t(0xD101), t(0xD103))
        cv.create_text(x, 10, anchor="nw", fill="white", font=("Arial", 16, "bold"), text=main)
        cv.create_text(W - 12, 12, anchor="ne", fill="#cccccc", font=("Arial", 11),
                       text="%s  [%s]" % (t(0xD111), t(0xD11B)))
        cv.create_text(12, 40, anchor="nw", fill="#cccccc", font=("Arial", 10),
                       text="WB %s  |  %s  |  %s  |  %s  |  %s" % (t(0xD109), t(0xD106), t(0xD107), t(0xD108), t(0xD110)))

        if self.hist:  # histogram
            hx, hy, hw, hh = W - 140, 68, 128, 48
            cv.create_rectangle(hx, hy, hx + hw, hy + hh, fill="black", stipple="gray50", outline="")
            mx = max(self.hist) or 1
            for i, n in enumerate(self.hist):
                bx = hx + i * hw / 64
                cv.create_line(bx, hy + hh, bx, hy + hh - hh * n / mx, fill="white", width=2)

        # exposure scale
        cx, half, sy = W / 2, 240, H - 80
        cv.create_rectangle(cx - half - 24, sy - 34, cx + half + 24, sy + 62, fill="black", stipple="gray50", outline="")
        cv.create_line(cx - half, sy, cx + half, sy, fill="white", width=2)
        for i in range(-9, 10):
            px = cx + (i / 3.0) / 3.0 * half
            cv.create_line(px, sy, px, sy + (12 if i % 3 == 0 else 6), fill="white")
            if i % 3 == 0:
                cv.create_text(px, sy + 22, fill="#cccccc", font=("Arial", 10), text="0" if i == 0 else "%+d" % (i // 3))

        def posof(ev):
            return cx + max(-3.0, min(3.0, ev)) / 3.0 * half

        manual = v.get(0xD105) == 3
        if manual and self.est is not None:
            p = posof(self.est)
            cv.create_rectangle(p - 3, sy - 20, p + 3, sy - 3, fill="#40e0ff", outline="")
            tag = "exposure (estimated from picture)"
        else:
            tag = "exposure compensation"
            if not manual and 0xD104 in v:
                ev = struct.unpack("b", bytes([v[0xD104] & 0xFF]))[0] / 8.0
                p = posof(ev)
                cv.create_polygon(p - 7, sy - 20, p + 7, sy - 20, p, sy - 4, fill="#ffd60a", outline="")
        cv.create_text(cx, sy + 46, fill="#cccccc", font=("Arial", 10), text=tag)

    def tick(self):
        if self.latest is not None:
            try:
                im = self.Image.open(io.BytesIO(self.latest))
                self.fw, self.fh = im.size
                self.scale = min(960 / self.fw, 640 / self.fh)
                # brightness stats for the exposure estimate + histogram
                g = im.convert("L").resize((96, 64))
                h = g.histogram()
                mean = max(sum(h[i] * (i / 255.0) ** 2.2 for i in range(256)) / (96 * 64), 1e-6)
                self.est = max(-3.0, min(3.0, math.log2(mean / 0.18)))
                self.hist = [sum(h[b * 4:b * 4 + 4]) for b in range(64)]
                im = im.resize((int(self.fw * self.scale), int(self.fh * self.scale)))
                self.photo = self.ImageTk.PhotoImage(im)
                self.canvas.delete("all")
                self.canvas.create_image(0, 0, anchor="nw", image=self.photo)
                self.draw_hud()
            except Exception:
                pass
            self.latest = None
        cam = self.cam
        if cam and cam.changed:
            cam.changed = False
            for name, pid, _, _ in PROPS:
                opts = cam.options.get(pid)
                if opts:
                    self.codes[pid] = opts
                    self.combos[pid]["values"] = [label(pid, c) for c in opts]
                if pid in cam.values:
                    self.combos[pid].set(label(pid, cam.values[pid]))
        self.root.after(30, self.tick)


if __name__ == "__main__":
    if "--probe" in sys.argv:
        try:
            probe()
        except Exception as e:
            print("FAILED:", e)
            sys.exit(1)
    else:
        App().root.mainloop()
