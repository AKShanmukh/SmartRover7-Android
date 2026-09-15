# SmartRover7 - Autonomous Multi-Modal Human-Following Robotic Rover

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Platform: ESP32](https://img.shields.io/badge/Platform-ESP32-blue.svg)](https://www.espressif.com/)
[![Android: Jetpack Compose](https://img.shields.io/badge/Android-Jetpack%20Compose-green.svg)](https://developer.android.com/jetpack/compose)
[![Language: C++ / Kotlin](https://img.shields.io/badge/Languages-C%2B%2B%20%7C%20Kotlin-orange.svg)]()

An end-to-end autonomous embedded robotics platform featuring a differential-drive mobile rover governed by an Espressif ESP32 dual-core microcontroller, paired with a custom native Android teleoperation and telemetry dashboard built using Jetpack Compose and Kotlin.

The rover integrates **three operating modes**:
1. **Autonomous BLE Beacon-Following**: Closed-loop proximity regulation using filtered signal attenuation.
2. **FlySky RC Remote Pilot**: Direct PWM pulse-width capture with differential tank mixing.
3. **BLE Smartphone Teleoperation**: Manual control over the Nordic UART Service (NUS).

All modes are bound by an active real-time safety layer featuring ultrasonic emergency braking and asymmetric debounce state switching.

---

## 🏗️ System Architecture

```mermaid
graph TD
    subgraph Android App [Android Companion App (Kotlin & Jetpack Compose)]
        UI[User Dashboard UI] -->|Command Packets via NUS BLE| BLE_Mgr[BleConnectionManager]
        BeaconSvc[BeaconService / BLE Advertiser] -.->|iBeacon Frames| BLE_Scan
    end
    
    subgraph ESP32 [ESP32 Rover Firmware]
        BLE_Server[GATT NUS Server] -->|Parse ASCII OpCodes| Mode_Ctrl[Mode Controller]
        BLE_Scan[BLE Scan Engine] -->|Raw RSSI Stream| RSSI_Filter[IIR Exponential Filter]
        RSSI_Filter -->|Smoothed RSSI| Prox_Reg[Proximity Regulator]
        
        RC_Recv[FlySky RC Receiver] -->|PWM Pulse Capture| Hyst_Debounce[Asymmetric Debounce Logic]
        Hyst_Debounce -->|RC Mode Transition| Tank_Mix[Differential Motor Mixer]
        
        Ultrasonic[HC-SR04 Transducer] -->|Range <= 18 cm| Safety_Override[Emergency Safety Layer]
        
        Prox_Reg --> Motor_Drv[L298N H-Bridge Driver]
        Mode_Ctrl --> Motor_Drv
        Tank_Mix --> Motor_Drv
        Safety_Override -->|Pre-emptive Brake / Pivot| Motor_Drv
    end
```

---

## 🎛️ Control Algorithms & Mathematical Formulation

### 1. First-Order IIR Exponential Smoothing on RSSI
Raw Received Signal Strength Indication (RSSI) in interior environments experiences heavy multipath reflections and fading noise. To prevent actuator thrashing and distance estimation oscillation, raw signal readings are filtered through an exponential moving average (EMA / single-pole IIR filter):

$$\text{RSSI}_{\text{filtered}} = \alpha \cdot \text{RSSI}_{\text{raw}} + (1 - \alpha) \cdot \text{RSSI}_{\text{prev}}$$

where the filter smoothing factor is calibrated to:
$$\alpha = 0.3$$

This yields a smooth signal profile while maintaining sufficient phase responsiveness during user movement.

### 2. Proximity Regulation State Machine
The smoothed RSSI signal maps into non-linear PWM regimes ($60 \le \text{PWM} \le 255$) across three distinct operational zones:

| Zone | RSSI Boundary | Commanded Behavior | PWM Mapping |
| :--- | :--- | :--- | :--- |
| **FAR** | $\text{RSSI} < -85\text{ dBm}$ | Rapid Catch-up Pursuit | $180 \le \text{PWM} \le 255$ |
| **MEDIUM** | $-85\text{ dBm} \le \text{RSSI} \le -60\text{ dBm}$ | Proportional Deceleration Approach | $80 \le \text{PWM} \le 180$ |
| **CLOSE** | $\text{RSSI} > -60\text{ dBm}$ | Equilibrium Safe Halt | $\text{PWM} = 0$ |

*Collision Delta Guard:* A sudden RSSI surge ($> +8\text{ dBm}$ in a single sampling window) indicates the target is rapidly encroaching; the controller triggers an automatic reverse burst ($150\text{ PWM}$ for $250\text{ ms}$) to preserve target distance.

### 3. Differential Tank Mixing (RC Mode)
Dual-channel RC inputs capture steering ($\text{CH}_1$) and throttle ($\text{CH}_2$) PWM pulses ($1000\text{–}2000\,\mu\text{s}$, center neutral $1500\,\mu\text{s}$, deadband $\pm 100\,\mu\text{s}$). The signals are normalized and mapped through a differential drive kinematics mixer:

$$\text{PWM}_{\text{Left}} = \text{clamp}\left(\text{Throttle} + \text{Steering}, -255, 255\right)$$
$$\text{PWM}_{\text{Right}} = \text{clamp}\left(\text{Throttle} - \text{Steering}, -255, 255\right)$$

This allows zero-radius turning when steering is commanded at zero net throttle.

### 4. Asymmetric Temporal Debounce Hysteresis
To prevent erratic oscillation between autonomous BLE and manual RC modes due to intermittent RF reception, an asymmetric temporal state machine regulates transitions:
- **Autonomous $\to$ RC Override:** Requires stable, valid RC pulses for a continuous window of $\tau_{\text{engage}} \ge 300\text{ ms}$.
- **RC $\to$ Autonomous Recovery:** Requires complete absence of RC carrier signal for $\tau_{\text{release}} \ge 500\text{ ms}$.
- **Beacon Loss Timeout:** If the target BLE beacon is unobserved for $t > 3000\text{ ms}$, the rover immediately halts all motors and sounds an intermittent buzzer alert.

### 5. Ultrasonic Safety Override
Operating concurrently across all modes, an HC-SR04 sensor triggers an interrupt-like safety sequence when detecting any obstacle at $d \le 18\text{ cm}$:
1. **Immediate Brake:** Actuator PWM driven to zero, buzzer sounded.
2. **Reverse Extraction:** Both tracks driven backwards at $\text{PWM} = 150$ for $300\text{ ms}$.
3. **Escape Pivot:** Executes a clockwise turn for $350\text{ ms}$ before yielding control back to the primary state machine.

---

## 🔌 Hardware Configuration & Pinout

| Subsystem | Device Pin | ESP32 GPIO | Description / Specifications |
| :--- | :--- | :--- | :--- |
| **L298N Dual H-Bridge** | `IN1` | **GPIO 26** | Left Motor Direction A |
| | `IN2` | **GPIO 25** | Left Motor Direction B |
| | `IN3` | **GPIO 33** | Right Motor Direction A |
| | `IN4` | **GPIO 32** | Right Motor Direction B |
| | `ENA` | **GPIO 27** | Left Motor Speed (LEDC PWM) |
| | `ENB` | **GPIO 14** | Right Motor Speed (LEDC PWM) |
| **HC-SR04 Rangefinder** | `TRIG` | **GPIO 5** | $10\,\mu\text{s}$ Ultrasound Trigger Pulse |
| | `ECHO` | **GPIO 23** | Pulse Width Echo Capture |
| **FlySky FS-iA6B Receiver** | `CH1` | **GPIO 18** | Steering Pulse ($1000\text{–}2000\,\mu\text{s}$) |
| | `CH2` | **GPIO 19** | Throttle Pulse ($1000\text{–}2000\,\mu\text{s}$) |
| **Telemetry & Feedback** | `LED_STATUS` | **GPIO 2** | Connection & Heartbeat Indicator |
| | `LED_MODE` | **GPIO 4** | Active State Mode Indicator |
| | `BUZZER` | **GPIO 15** | Audio Alerts (Collision / Beacon Loss) |

---

## 📱 Android Client Architecture

The companion Android application (`app/`) is architected with modern Android development standards:
- **Jetpack Compose UI:** Reactive, single-activity layout displaying live RSSI, active mode indicators, speed sliders, and directional touch pads.
- **BleConnectionManager:** Encapsulates GATT connection lifecycles, service discovery, MTU negotiation, and automated 3-stage reconnect routines.
- **Nordic UART Protocol:**
  - **Service UUID:** `6e400001-b5a3-f393-e0a9-e50e24dcca9e`
  - **RX Characteristic:** `6e400002-b5a3-f393-e0a9-e50e24dcca9e` (Commands: `DRV:FWD`, `DRV:REV`, `DRV:STOP`, `SPD:<val>`)
  - **TX Characteristic:** `6e400003-b5a3-f393-e0a9-e50e24dcca9e` (Telemetry Stream: RSSI, Mode, Obstacle Alert)

---

## 🚀 Setup & Flashing

### 1. ESP32 Firmware
1. Open [`firmware/smart_rover_esp32/smart_rover_esp32.ino`](firmware/smart_rover_esp32/smart_rover_esp32.ino) in Arduino IDE.
2. Under **Tools > Board**, select **ESP32 Dev Module**.
3. Ensure the ESP32 BLE library is included in the board package.
4. Compile and flash over USB at 115200 baud.

### 2. Android Dashboard APK
1. Open the project root in **Android Studio**.
2. Sync Gradle dependencies.
3. Build and deploy to an Android device (Android 8.0+ / API 26+).
4. Enable Bluetooth and Location permissions, then connect to `SMARTROVER`.

---

## 📄 License
This project is open-source under the [MIT License](LICENSE).
