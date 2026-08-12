/*
  SMART ROVER - ALL FEATURES - FIXED VERSION
  - BLE Manual (NORDIC UART)
  - BLE Follow mode (scan for beacon/service UUID)
  - FlySky Transmitter Mode (CH1 steering, CH2 throttle)
  - Auto switch MANUAL -> TX when transmitter is turned ON (stable for 300 ms)
  - Proper differential/tank mixing allowing pivoting with steering even at neutral throttle
  - Ultrasonic obstacle avoidance
  - LED + Buzzer indicators
  - Telemetry, smoothing, hysteresis, anti-bounce protections
  - Detailed Serial logs for everything
*/

// =========================
//  INCLUDE LIBRARIES
// =========================

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <BLEScan.h>

// =========================
//  USER CONFIGURATION
// =========================

// Nordic UART-like service UUIDs (used by many mobile apps)
#define SERVICE_UUID           "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
#define CHARACTERISTIC_UUID_RX "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
#define CHARACTERISTIC_UUID_TX "6e400003-b5a3-f393-e0a9-e50e24dcca9e"

// Beacon target UUID to follow (example)
String targetUUID = "12345678-1234-5678-1234-567812345678";

// =========================
//  PIN ASSIGNMENTS
// =========================

// Motor driver pins (L298-like)
#define IN1 26
#define IN2 25
#define IN3 33
#define IN4 32

// Motor PWM enable pins
#define ENA 27
#define ENB 14

// Ultrasonic sensor pins
#define TRIG 5
#define ECHO 23

// FlySky RC receiver input pins (PWM)
#define RC_CH1 18   // Steering channel
#define RC_CH2 19   // Throttle channel

// LED / Buzzer for user feedback
#define LED_STATUS 2      // Onboard LED (or any other pin)
#define LED_MODE 4        // Mode indicator LED
#define BUZZER_PIN 15     // Buzzer for alerts

// =========================
//  CONSTANTS & TUNING
// =========================

const int rcNeutral = 1500;   // microseconds center for RC channels
const int rcDead = 100;       // deadzone around neutral
const unsigned long TX_PULSE_TIMEOUT = 25000; // microseconds to wait for pulseIn

// Smooth/anti-bounce settings
const unsigned long TX_STABLE_MS = 300;   // transmitter must be stable for this period before switching
const unsigned long TX_RETURN_MS = 500;   // transmitter must be absent for this period to return to manual
const unsigned long FOLLOW_BEACON_LOST_MS = 3000;  // if no beacon seen for this long -> beacon lost

// Ultrasonic avoidance
const long OBSTACLE_DISTANCE_CM = 18; // obstacle threshold in cm

// Motor speed constraints
const int MOTOR_MIN = 0;
const int MOTOR_MAX = 255;
const int MOTOR_SAFE_MIN = 60; // safety minimum speed for forward/backward mapping

// Smoothing for RSSI
const float RSSI_ALPHA = 0.3;

// =========================
//  GLOBAL OBJECTS & STATE
// =========================

// BLE objects
BLEServer* pServer = NULL;
BLECharacteristic* pTxCharacteristic = NULL;
BLEScan* pBLEScan = NULL;
bool deviceConnected = false;

// Mode flags
bool followMode = false;
bool transmitterMode = false;
bool manualMode = false;

// Motion variables
int motorSpeed = 150;   // default manual speed
float filteredRSSI = -100.0;
int prevRSSIraw = -1000;
unsigned long lastSeenBeacon = 0;
unsigned long lastPrint = 0;

// Telemetry counters
unsigned long loops = 0;
unsigned long lastTelemetryMillis = 0;

// Hysteresis detection for TX
unsigned long txDetectStart = 0;
bool txWasDetected = false;
unsigned long txLostStart = 0;
bool txWasLost = false;

// Monitoring last movement directive (for serial-friendly logs)
String lastMovement = "IDLE";

// =========================
//  FUNCTION DECLARATIONS
// =========================

void stopMotors();
void forward(int speed);
void backward(int speed);
void leftTurn(int speed);
void rightTurn(int speed);
long getDistance();
void sendResponse(String msg);
void setLED(int pin, bool on);
void beep(int ms);
int safeConstrain(int val, int lo, int hi);
void applyMotorOutputs(int leftVal, int rightVal);
void printTelemetryHeader();
void printTelemetry();

// =========================
//  HELPERS: BLE SEND RESPONSE
// =========================

void sendResponse(String msg) {
  if (deviceConnected && pTxCharacteristic != NULL) {
    pTxCharacteristic->setValue(msg.c_str());
    pTxCharacteristic->notify();
  }
}

// =========================
//  BLE SERVER CALLBACKS
// =========================

class ServerCallbacks: public BLEServerCallbacks {
  void onConnect(BLEServer* pServer) {
    deviceConnected = true;
    Serial.println("\n*** APP CONNECTED (BLE) ***\n");
    sendResponse("APP_CONNECTED");
    setLED(LED_MODE, true);
  }

  void onDisconnect(BLEServer* pServer) {
    deviceConnected = false;
    followMode = false;
    transmitterMode = false;
    manualMode = false;
    stopMotors();
    Serial.println("\n*** APP DISCONNECTED (BLE) ***\n");
    delay(200);
    BLEDevice::startAdvertising();
    setLED(LED_MODE, false);
  }
};

// =========================
//  RX CHARACTERISTIC CALLBACKS
// =========================

class RxCallbacks: public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *pCharacteristic) {
    String cmd = String(pCharacteristic->getValue().c_str());
    cmd.trim();
    if (cmd.length() == 0) return;

    Serial.print("CMD (BLE): ");
    Serial.println(cmd);

    // Commands follow your original scheme
    if (cmd == "MODE:FOLLOW") {
      followMode = true;
      transmitterMode = false;
      manualMode = false;
      sendResponse("Follow Mode Active");
      Serial.println("-> MODE: FOLLOW (ENGAGED)");
      setLED(LED_STATUS, true);
    }
    else if (cmd == "MODE:MANUAL") {
      followMode = false;
      transmitterMode = false;
      manualMode = true;
      stopMotors();
      sendResponse("Manual Mode Active");
      Serial.println("-> MODE: MANUAL (ENGAGED)");
      setLED(LED_STATUS, false);
    }
    else if (cmd == "MODE:TX") {
      followMode = false;
      transmitterMode = true;
      manualMode = false;
      stopMotors();
      sendResponse("Transmitter Mode Active");
      Serial.println("-> MODE: TX (ENGAGED)");
      setLED(LED_STATUS, true);
    }
    else if (cmd == "STOP") {
      stopMotors();
      sendResponse("Stopped");
      Serial.println("-> CMD: STOP");
    }
    else if (cmd == "DRV:FWD") {
      if (!followMode && !transmitterMode) {
        forward(motorSpeed);
        sendResponse("DRV: FWD");
      }
    }
    else if (cmd == "DRV:BACK") {
      if (!followMode && !transmitterMode) {
        backward(motorSpeed);
        sendResponse("DRV: BACK");
      }
    }
    else if (cmd == "DRV:LEFT") {
      if (!followMode && !transmitterMode) {
        leftTurn(motorSpeed);
        sendResponse("DRV: LEFT");
      }
    }
    else if (cmd == "DRV:RIGHT") {
      if (!followMode && !transmitterMode) {
        rightTurn(motorSpeed);
        sendResponse("DRV: RIGHT");
      }
    }
    else if (cmd.startsWith("SPD:")) {
      int spd = cmd.substring(4).toInt();
      motorSpeed = safeConstrain(spd, 60, 255);
      sendResponse("Speed " + String(motorSpeed));
      Serial.print("-> Speed set to: ");
      Serial.println(motorSpeed);
    }
    else {
      Serial.print("Unknown command via BLE: ");
      Serial.println(cmd);
      sendResponse("ERR: Unknown CMD");
    }
  }
};

// =========================
//  HELPERS: LEDs, BUZZER
// =========================

void setLED(int pin, bool on) {
  digitalWrite(pin, on ? HIGH : LOW);
}

void beep(int ms) {
  digitalWrite(BUZZER_PIN, HIGH);
  delay(ms);
  digitalWrite(BUZZER_PIN, LOW);
}

// =========================
//  MOTOR CONTROL UTILITIES
// =========================

int safeConstrain(int val, int lo, int hi) {
  if (val < lo) return lo;
  if (val > hi) return hi;
  return val;
}

void applyMotorOutputs(int leftVal, int rightVal) {
  // leftVal and rightVal range expected -255..255
  if (leftVal > 0) {
    digitalWrite(IN1, HIGH);
    digitalWrite(IN2, LOW);
  } else if (leftVal < 0) {
    digitalWrite(IN1, LOW);
    digitalWrite(IN2, HIGH);
  } else {
    digitalWrite(IN1, LOW);
    digitalWrite(IN2, LOW);
  }

  if (rightVal > 0) {
    digitalWrite(IN3, HIGH);
    digitalWrite(IN4, LOW);
  } else if (rightVal < 0) {
    digitalWrite(IN3, LOW);
    digitalWrite(IN4, HIGH);
  } else {
    digitalWrite(IN3, LOW);
    digitalWrite(IN4, LOW);
  }

  int leftPWM = safeConstrain(abs(leftVal), MOTOR_MIN, MOTOR_MAX);
  int rightPWM = safeConstrain(abs(rightVal), MOTOR_MIN, MOTOR_MAX);

  analogWrite(ENA, leftPWM);
  analogWrite(ENB, rightPWM);
}

void stopMotors() {
  applyMotorOutputs(0, 0);
  lastMovement = "STOP";
}

void forward(int s) {
  s = safeConstrain(s, MOTOR_SAFE_MIN, MOTOR_MAX);
  applyMotorOutputs(s, s);
  lastMovement = "FWD";
}

void backward(int s) {
  s = safeConstrain(s, MOTOR_SAFE_MIN, MOTOR_MAX);
  applyMotorOutputs(-s, -s);
  lastMovement = "BACK";
}

void leftTurn(int s) {
  s = safeConstrain(s, MOTOR_SAFE_MIN, MOTOR_MAX);
  applyMotorOutputs(-s, s);
  lastMovement = "LEFT_PIVOT";
}

void rightTurn(int s) {
  s = safeConstrain(s, MOTOR_SAFE_MIN, MOTOR_MAX);
  applyMotorOutputs(s, -s);
  lastMovement = "RIGHT_PIVOT";
}

// =========================
//  SENSING: ULTRASONIC
// =========================

long getDistance() {
  digitalWrite(TRIG, LOW);
  delayMicroseconds(2);
  digitalWrite(TRIG, HIGH);
  delayMicroseconds(10);
  digitalWrite(TRIG, LOW);
  long duration = pulseIn(ECHO, HIGH, 20000);
  if (duration == 0) return -1;
  long cm = duration * 0.034 / 2;
  return cm;
}

// =========================
//  TELEMETRY & PRINTING
// =========================

void printTelemetryHeader() {
  Serial.println(F("=== TELEMETRY HEADER ==="));
  Serial.println(F("Loops | Mode | Movement | FilteredRSSI | RawRSSI"));
  Serial.println(F("------------------------"));
}

void printTelemetry() {
  loops++;
  if (millis() - lastTelemetryMillis < 1000) return;
  lastTelemetryMillis = millis();

  String modeStr = "IDLE";
  if (transmitterMode) modeStr = "TX";
  else if (manualMode) modeStr = "MANUAL";
  else if (followMode) modeStr = "FOLLOW";

  Serial.print("TELEM: ");
  Serial.print(loops);
  Serial.print(" | Mode:");
  Serial.print(modeStr);
  Serial.print(" | Movement:");
  Serial.print(lastMovement);
  Serial.print(" | filteredRSSI:");
  Serial.print(filteredRSSI);
  Serial.print(" | prevRSSIraw:");
  Serial.println(prevRSSIraw);
}

// =========================
//  SETUP
// =========================

void setup() {
  Serial.begin(115200);
  delay(300);

  Serial.println("\n\n=== SMART ROVER - FULL FEATURE BUILD ===");

  pinMode(IN1, OUTPUT);
  pinMode(IN2, OUTPUT);
  pinMode(IN3, OUTPUT);
  pinMode(IN4, OUTPUT);
  pinMode(ENA, OUTPUT);
  pinMode(ENB, OUTPUT);
  pinMode(TRIG, OUTPUT);
  pinMode(ECHO, INPUT);
  pinMode(RC_CH1, INPUT);
  pinMode(RC_CH2, INPUT);
  pinMode(LED_STATUS, OUTPUT);
  pinMode(LED_MODE, OUTPUT);
  pinMode(BUZZER_PIN, OUTPUT);

  stopMotors();
  setLED(LED_STATUS, LOW);
  setLED(LED_MODE, LOW);
  digitalWrite(BUZZER_PIN, LOW);

  BLEDevice::init("SMARTROVER");
  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new ServerCallbacks());

  BLEService *pService = pServer->createService(SERVICE_UUID);

  pTxCharacteristic = pService->createCharacteristic(
                        CHARACTERISTIC_UUID_TX,
                        BLECharacteristic::PROPERTY_NOTIFY
                      );
  pTxCharacteristic->addDescriptor(new BLE2902());

  BLECharacteristic *pRxCharacteristic = pService->createCharacteristic(
                                           CHARACTERISTIC_UUID_RX,
                                           BLECharacteristic::PROPERTY_WRITE
                                         );
  pRxCharacteristic->setCallbacks(new RxCallbacks());

  pService->start();

  BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID);
  BLEDevice::startAdvertising();

  pBLEScan = BLEDevice::getScan();
  pBLEScan->setActiveScan(true);
  pBLEScan->setInterval(100);
  pBLEScan->setWindow(99);

  Serial.println(F("ROVER READY. Waiting for commands..."));
  beep(80);
  delay(80);
  beep(80);

  printTelemetryHeader();
}

// =========================
//  MAIN LOOP
// =========================

void loop() {
  printTelemetry();

  // OBSTACLE AVOIDANCE
  long distance = getDistance();
  if (distance > 0 && distance < OBSTACLE_DISTANCE_CM) {
    Serial.print("OBSTACLE DETECTED at ");
    Serial.print(distance);
    Serial.println(" cm -> performing avoidance maneuver");

    sendResponse("OBSTACLE");
    stopMotors();
    beep(40);
    backward(150);
    delay(300);
    stopMotors();
    rightTurn(150);
    delay(350);
    stopMotors();
    delay(50);
    return;
  }

  // TRANSMITTER MODE
  if (transmitterMode) {
    unsigned long ch1 = pulseIn(RC_CH1, HIGH, TX_PULSE_TIMEOUT);
    unsigned long ch2 = pulseIn(RC_CH2, HIGH, TX_PULSE_TIMEOUT);

    if (ch1 == 0 || ch2 == 0) {
      stopMotors();
      Serial.println("TX: NO SIGNAL -> STOP");
      sendResponse("TX:NO_SIGNAL");
      return;
    }

    int throttle = 0;
    if (ch2 > rcNeutral + rcDead) {
      throttle = map((int)ch2, rcNeutral + rcDead, 2000, 0, MOTOR_MAX);
    } else if (ch2 < rcNeutral - rcDead) {
      throttle = -map((int)ch2, rcNeutral - rcDead, 1000, 0, MOTOR_MAX);
    }

    int steering = 0;
    if (ch1 > rcNeutral + rcDead) {
      steering = map((int)ch1, rcNeutral + rcDead, 2000, 0, 200);
    } else if (ch1 < rcNeutral - rcDead) {
      steering = -map((int)ch1, rcNeutral - rcDead, 1000, 0, 200);
    }

    int leftMotor = constrain(throttle + steering, -MOTOR_MAX, MOTOR_MAX);
    int rightMotor = constrain(throttle - steering, -MOTOR_MAX, MOTOR_MAX);

    applyMotorOutputs(leftMotor, rightMotor);

    Serial.print("TX: CH1=");
    Serial.print(ch1);
    Serial.print(" CH2=");
    Serial.print(ch2);
    Serial.print(" -> L=");
    Serial.print(leftMotor);
    Serial.print(" R=");
    Serial.print(rightMotor);
    Serial.print(" Th=");
    Serial.print(throttle);
    Serial.print(" St=");
    Serial.println(steering);

    if (abs(leftMotor) > 0 || abs(rightMotor) > 0) {
      sendResponse("TX:DRIVING L:" + String(leftMotor) + " R:" + String(rightMotor));
    } else {
      sendResponse("TX:STOP");
    }
    return;
  }

  // FOLLOW MODE
  if (followMode && deviceConnected) {
    BLEScanResults* pResults = pBLEScan->start(1, false);
    int count = pResults->getCount();
    bool found = false;
    int currentRawRSSI = -1000;

    if (millis() - lastPrint > 2000) {
      Serial.print("FOLLOW: Scanning... devices found: ");
      Serial.println(count);
      lastPrint = millis();
    }

    for (int i = 0; i < count; i++) {
      BLEAdvertisedDevice device = pResults->getDevice(i);
      if (device.haveServiceUUID()) {
        String uuidStr = device.getServiceUUID().toString();
        if (uuidStr == targetUUID) {
          int rssi = device.getRSSI();
          filteredRSSI = RSSI_ALPHA * rssi + (1.0 - RSSI_ALPHA) * filteredRSSI;
          lastSeenBeacon = millis();
          found = true;
          currentRawRSSI = rssi;

          Serial.print("FOLLOW: Beacon RSSI raw=");
          Serial.print(rssi);
          Serial.print(" filtered=");
          Serial.println(filteredRSSI);
        }
      }
    }

    pBLEScan->clearResults();

    if (!found && millis() - lastSeenBeacon > FOLLOW_BEACON_LOST_MS) {
      stopMotors();
      sendResponse("BEACON_LOST");
      Serial.println("FOLLOW: BEACON LOST");
    }

    if (found) {
      int followSpeed = 0;
      if (filteredRSSI < -85.0) {
        followSpeed = constrain(map((int)filteredRSSI, -110, -85, 255, 180), 120, 255);
        forward(followSpeed);
        sendResponse("Following: FAR");
      } else if (filteredRSSI <= -60.0 && filteredRSSI >= -85.0) {
        followSpeed = constrain(map((int)filteredRSSI, -85, -60, 180, 120), 80, 180);
        forward(followSpeed);
        sendResponse("Following: MEDIUM");
      } else if (filteredRSSI > -60.0 && filteredRSSI <= -40.0) {
        stopMotors();
        sendResponse("Following: CLOSE");
      } else {
        stopMotors();
      }

      if (currentRawRSSI != -1000 && prevRSSIraw != -1000 && 
          (currentRawRSSI - prevRSSIraw) > 8 && currentRawRSSI > -50) {
        backward(160);
        sendResponse("Following: REV");
        Serial.println("FOLLOW: REV due to sudden proximity spike");
        delay(200);
      }
      prevRSSIraw = currentRawRSSI;
    }
    return;
  }

  // AUTO SWITCH: MANUAL -> TX
  if (manualMode && deviceConnected) {
    unsigned long ch1 = pulseIn(RC_CH1, HIGH, TX_PULSE_TIMEOUT);
    unsigned long ch2 = pulseIn(RC_CH2, HIGH, TX_PULSE_TIMEOUT);

    bool txSignalPresent = (ch1 > 900 && ch2 > 900);

    if (txSignalPresent) {
      if (!txWasDetected) {
        txWasDetected = true;
        txDetectStart = millis();
      } else {
        if (millis() - txDetectStart > TX_STABLE_MS) {
          manualMode = false;
          transmitterMode = true;
          stopMotors();
          sendResponse("AUTO_TX: Transmitter Detected -> Switched to TX");
          Serial.println("AUTO: TX detected - switching to transmitter mode");
          beep(50);
          delay(80);
          return;
        }
      }
    } else {
      txWasDetected = false;
    }
  }

  delay(15);
}
