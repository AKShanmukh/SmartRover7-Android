# SmartRover7 - Full-Stack BLE Autonomous & RC Robotic Rover

An end-to-end full-stack IoT and robotics project featuring a physical robotic rover driven by an ESP32 microcontroller, paired with a custom Android client controller app built using Jetpack Compose and Kotlin.

The rover supports **three active operating modes**: BLE Smartphone Control, Autonomous BLE Beacon-Following, and remote RC Pilot Control via a FlySky RC Transmitter, complete with ultrasonic obstacle avoidance, differential mixing, and real-time telemetry.

---

## 🏗️ System Architecture

```mermaid
graph TD
    subgraph Android App (Kotlin & Compose)
        UI[User Dashboard UI] -->|Commands via NUS BLE| BLE_Mgr[BleConnectionManager]
    end
    
    subgraph ESP32 Rover Firmware
        BLE_Server[BLE NUS Server] -->|Parse Writes| Mode_Ctrl[Mode Controller]
        Ultrasonic[Ultrasonic Sensor] -->|Distance Check| Avoid[Avoidance Maneuver]
        RC_Recv[FlySky RC Receiver] -->|PWM Pulse Capture| Tank_Mix[Differential Motor Mixer]
        
        Mode_Ctrl -->|Set Speed/Dir| Motor_Drv[L298N Motor Driver]
        Avoid -->|Override Stop/Reverse| Motor_Drv
        Tank_Mix -->|Apply PWM Outputs| Motor_Drv
        
        Telemetry[Serial Telemetry Engine] -->|Real-time Diagnostics| PC[USB Serial Debugger]
    end
```

---

## 🔌 Hardware Configuration & Pinout

| Component | Device Pin | ESP32 Pin | Function |
|---|---|---|---|
| **L298N Motor Driver** | IN1 | **GPIO 26** | Left Motor Forward |
| | IN2 | **GPIO 25** | Left Motor Reverse |
| | IN3 | **GPIO 33** | Right Motor Forward |
| | IN4 | **GPIO 32** | Right Motor Reverse |
| | ENA | **GPIO 27** | Left Motor Speed (PWM) |
| | ENB | **GPIO 14** | Right Motor Speed (PWM) |
| **HC-SR04 Ultrasonic** | TRIG | **GPIO 5** | Trigger Echo Pulse |
| | ECHO | **GPIO 23** | Read Pulse Echo Time |
| **FlySky RC Receiver** | CH1 | **GPIO 18** | PWM input (Steering) |
| | CH2 | **GPIO 19** | PWM input (Throttle) |
| **Feedback System** | LED_STATUS| **GPIO 2** | Connection/Mode status LED |
| | LED_MODE | **GPIO 4** | Operation Mode LED |
| | BUZZER | **GPIO 15** | Alert tone generator |

---

## ⚙️ Operating Modes

### 1. BLE Manual Mode (Smartphone)
The rover advertises as `SMARTROVER` and exposes the standard **Nordic UART Service (NUS)**. Mobile app directives are processed as byte packets sent to the RX Characteristic to command movements (`DRV:FWD`, `DRV:LEFT`, etc.) or dynamically scale speed (`SPD:VALUE`).

### 2. BLE Follow Mode (Autonomous Tracking)
In this mode, the rover acts as a BLE Scanner. It searches for active advertisement packages matching the target UUID `12345678-1234-5678-1234-567812345678` (a tracking beacon or phone).
- **RSSI Filtering**: An Exponential Moving Average (EMA) filter is applied to the raw signal strength (RSSI) to smooth noise and prevent jitter:
  $$\text{RSSI}_{\text{filtered}} = \alpha \cdot \text{RSSI}_{\text{raw}} + (1 - \alpha) \cdot \text{RSSI}_{\text{previous}}$$
  *(where $\alpha = 0.3$)*
- **Proximity Control**:
  - **RSSI < -85 dBm (Far)**: Forward speed maps to high PWM (180–255) to catch up.
  - **-85 to -60 dBm (Medium)**: Forward speed drops (80–180) for a steady approach.
  - **> -60 dBm (Close)**: Motors stop.
  - **Sudden RSSI Spike (> 8dB Increase)**: Triggers an immediate brief reverse maneuver to avoid collision if the beacon approaches too quickly.

### 3. RC Transmitter Mode (FlySky Remote Control)
Reads PWM signals from the RC receiver. Pulse duration is captured via `pulseIn` with a timeout of $25\text{ ms}$.
- **Differential/Tank Mixing**: Standard mixing is applied to transform steer/throttle axes into independent left and right wheel speeds:
  $$\text{LeftMotor} = \text{Constrain}(\text{Throttle} + \text{Steering}, -255, 255)$$
  $$\text{RightMotor} = \text{Constrain}(\text{Throttle} - \text{Steering}, -255, 255)$$
  This differential mixing allows the rover to pivot in place when steering is applied at neutral throttle.
- **Hysteresis Auto-Switching**: When the phone is connected in manual mode, turning on the FlySky transmitter (detected by stable PWM pulses on RC pins for $> 300\text{ ms}$) automatically triggers the firmware to yield control to the RC Transmitter. Returning control to Manual Mode occurs if pulses are absent for $> 500\text{ ms}$.

### 4. Safety Layer (Ultrasonic Obstacle Avoidance)
Regardless of the active control mode, the ultrasonic distance sensor checks for barriers at regular intervals.
- If distance drops below **18 cm**, a safety override is triggered:
  1. The rover stops immediately and buzzes an alarm.
  2. Backs up at speed 150 for $300\text{ ms}$.
  3. Executes a right pivot for $350\text{ ms}$ to clear the path.
  4. Returns control to the active mode engine.

---

## 📱 Mobile App Features
- **Kotlin & Coroutines**: Handles async BLE callbacks on background threads to keep the UI smooth and responsive.
- **StateFlow UI Binding**: Reacts instantly to connection events, scans, and system status (such as notifying when Bluetooth is disabled).
- **Automated Connection Recovery**: Auto-reconnects up to 3 times on unexpected packet loss.

---

## 📂 Project Structure
- **`app/`**: Gradle Kotlin Android Studio project source files.
- **`firmware/smart_rover_esp32/`**: ESP32 C++ Arduino sketch:
  - [`smart_rover_esp32.ino`](firmware/smart_rover_esp32/smart_rover_esp32.ino): The complete firmware implementation.

---

## 🚀 Setup & Execution

### 1. Uploading ESP32 Firmware
1. Open [`smart_rover_esp32.ino`](firmware/smart_rover_esp32/smart_rover_esp32.ino) in the Arduino IDE.
2. Install ESP32 board support via the Boards Manager.
3. Select your ESP32 model (e.g. ESP32 Dev Module) and port.
4. Upload the code to your hardware.

### 2. Building the Android App
1. Open the [`app/`](.) folder in Android Studio.
2. Sync the project with Gradle.
3. Build and install the APK on a physical Android device.
4. Open the app, grant Bluetooth permissions, and tap **Connect** to link with the powered-on rover.

## 📄 License
This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
