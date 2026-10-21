# S-Pen Bluetooth Digitizer Tablet (Android APK)

An open-source Android application that turns your **Samsung Galaxy Tab S6 Lite** (or any S-Pen equipped Galaxy Tab) into a **serverless, screenless Bluetooth graphics tablet**.

---

## 🌟 Key Features

- **100% Serverless (Zero PC Software):**
  - Emulates a native **Bluetooth HID Digitizer** (`Usage Page 0x0D, Usage 0x02 Pen`).
  - Windows, macOS, Linux, and Android automatically detect the tablet as a plug-and-play precision graphics tablet / pen digitizer.
  - Full native **Windows Ink** compatibility in apps like Photoshop, Krita, OneNote, Clip Studio Paint, Blender, etc.

- **Full S-Pen Hardware Support:**
  - **4,096 Levels of Pressure:** Smooth dynamic inking and brush opacity.
  - **Hover Tracking (`In Range`):** Move the pen ~1.5 cm above the glass to position your cursor on your monitor before touching down.
  - **Tilt & Orientation:** Real-time X/Y tilt angles calculated from S-Pen spherical orientation.
  - **S-Pen Barrel Button:** Mapped to Stylus Primary / Right-Click.
  - **Hardware Palm Rejection:** Rejects touch gestures from fingers/palms while drawing.

- **Screenless Tablet Optimizations:**
  - **Multi-Monitor Display Switching:** Instantly switch coordinate mapping between **Monitor 1 (Left Half)**, **Monitor 2 (Right Half)**, or **Entire Virtual Desktop** directly from the tablet.
  - **1:1 Aspect Ratio Mapping:** Letterboxing with visual guide frames for **16:9** (standard monitors) and **16:10** (full tablet) so circles drawn on tablet remain circles on your display.
  - **Tactile Edge Haptics:** Gentle haptic vibration pulse whenever the S-Pen tip crosses outside the active monitor drawing boundary.
  - **True Blackout Battery Saver Mode:** Turns the entire display pitch black, dims screen brightness to near-zero, and displays only 4 subtle phosphor-cyan corner guides for 12+ hours of battery life and zero screen glare.
  - **Turbo Bluetooth Polling (⚡):** Configurable low-latency QoS requesting a 7.5ms connection interval for ultra-smooth ~133Hz inking.
  - **Keep-Screen-On:** Prevents the tablet from locking or dimming during painting sessions.
  - **Live Telemetry HUD:** Displays real-time cursor coordinate percentage, pressure gauge, tilt degrees, and hover state.

---

## 📱 How to Pair & Use with Windows

1. **Enable Bluetooth** on both your Samsung Galaxy Tab S6 Lite and your Windows PC.
2. Launch the **S-Pen Digitizer** app on your tablet.
3. Grant the requested Bluetooth permissions when prompted.
4. On your **Windows PC**:
   - Go to **Settings > Bluetooth & devices > Add device > Bluetooth**.
   - Select **Tab S6 Lite Digitizer** (or the paired tablet name).
   - Windows will pair with the tablet and configure the **HID Pen / Digitizer** driver automatically.
5. In the app on your tablet:
   - Select your PC from the paired devices dropdown and tap **Connect** (if not auto-connected).
   - The status badge will change to **Connected**.
6. Hover your S-Pen over the tablet screen — your Windows cursor will follow the pen! Touch down to draw with pressure sensitivity.

---

## 🏗️ Project Architecture

```
Drawing Tablet App/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml              # Bluetooth HID & wake permissions
│   │   ├── java/com/tablet/penemulator/
│   │   │   ├── HidDescriptor.kt             # HID Report Descriptor (Digitizer Pen)
│   │   │   ├── BluetoothHidManager.kt       # BluetoothHidDevice lifecycle & report sender
│   │   │   ├── TabletCanvasView.kt          # Touch/hover processor, aspect ratio, HUD
│   │   │   └── MainActivity.kt              # App controller, pairing UI & device selector
│   │   └── res/                             # Themes, layouts, colors, vector icons
│   └── build.gradle.kts                     # Android app build configuration
└── settings.gradle.kts                      # Project settings & dependencies
```
