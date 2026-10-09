# Twizy Tuner

Android app for reading and adjusting the SEVCON Gen4 motor controller of the Renault Twizy 80 and Twizy 45. It needs an ELM327 Bluetooth (SPP) adapter on the OBD port.

**Status:** Phase 1. Connect, identify the controller, and read all known parameters. Writing is implemented in the core library and protected by safety limits, but the UI doesn't offer it yet.

**Warning:** changing controller parameters can damage the drivetrain, void your warranty, and make the vehicle illegal to drive on public roads. Use at your own risk.

## Build

```
./gradlew :core:test assembleDebug
```

The app has a simulator mode, so you can try it without a car.

## Credits

- Protocol knowledge and tuning limits come from the OVMS Twizy module by Michael Balzer.
- The object dictionary data comes from the Twizy community.
- The CANopen SDO protocol follows CiA 301.
