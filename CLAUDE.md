# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Twizy Tuner is an Android app (Kotlin + Jetpack Compose) that reads and changes parameters of the Renault Twizy's SEVCON Gen4 motor controller. It talks CANopen SDO through an ELM327 adapter over Bluetooth Classic SPP. It replaces the legacy Java app in `~/projectsa/twizy/Tune-the-T`. **Writes go to real vehicle hardware, so every change to the write path needs tests against the simulator.**

## Build and test

JDK 17, Gradle wrapper 9.8.0, AGP 9.4.1 (built-in Kotlin), Kotlin 2.4.10. The SDK is at `~/android-sdk` (`local.properties`, gitignored). Only the android-36 platform is installed, so Compose BOM and core-ktx are pinned to versions that still compile against SDK 36 (newer ones require compileSdk 37).

```
./gradlew :core:test                                   # all protocol/safety tests (pure JVM, no device)
./gradlew :core:test --tests '*SafeWriterTest*'        # one test class
./gradlew :core:test --tests '*SafeWriterTest.map changes are committed'
./gradlew assembleDebug                                # APK in app/build/outputs/apk/debug/
./gradlew :app:lintDebug
```

## Architecture

Two modules. `:core` is pure Kotlin/JVM and holds everything except Android glue, so it can be tested without a phone or a car. `:app` contains the Bluetooth transport and the Compose UI.

Layers in `core` (package `de.dressler.twizy.core`), from the bottom up:

1. **`transport.Transport`**: a byte pipe. On Android this is `app/BluetoothSppTransport` (insecure RFCOMM with the SPP UUID). In tests and in the app's "Use simulator" mode it is `sim.SimulatedElm327Transport`.
2. **`elm.Elm327`**: sends commands and reads the answer up to the `>` prompt, without any fixed sleeps. `initialize()` sends ATZ, E0, L0, S0, H0, SP6, CAF0, then SH601 and CRA581. `exchange()` sends one raw 8-byte frame. It also implements `canopen.CanChannel`. `NO DATA` is raised as a transient error.
3. **`canopen.SdoClient`**: CiA 301 SDO handling. Uploads can be expedited or segmented. Downloads are expedited with the size indicated (`0x2F/0x2B/0x23`). It checks that each response is for the requested object, decodes all 32 bits of abort codes (`SdoAbortCodes`, Table 22), and retries only after timeouts or NO DATA.
4. **`sevcon.Sevcon`**:
   - `identify()` reads `1018:02` and returns `TWIZY80` or `TWIZY45`.
   - `login()` writes `5000:03=0` and `5000:02=0x4BDF`, then verifies access level 4.
   - `enterPreOperational()` / `enterOperational()` use `2800:00` and `5110:00` (5 = op, 127 = pre-op).
   - On a CANopen general error it adds the SEVCON reason from `5310:00`.
   - Raw `write()` is `internal`.
5. **`params`**:
   - `SevconCatalog` contains all 145 known objects, with Twizy80/45 defaults, unit and scale (for example, 4610/4611 torques are in 1/16 Nm) and the pre-op flag. It is **generated**: `GeneratedCatalog.kt` comes from `tools/gen_catalog.py` (see below). Don't hand-edit it.
   - `SafetyLimits` is the **write whitelist**, with raw ranges per controller type taken from the OVMS `cfg_*` functions in "limited" mode. Currents never go above the factory default. Any object not listed is read-only.
6. **`write.SafeWriter`**: the only way to write.
   - `prepare()` identifies the controller, validates against `SafetyLimits`, and reads the current values (which serve as preview and backup).
   - `execute()` then runs these steps:
     1. Re-checks the controller type and that the values haven't changed since `prepare()`.
     2. Logs in.
     3. Asks the `PreOpGuard` (vehicle stationary, N, not GO) and enters pre-op, if the plan needs it.
     4. Writes each value and reads it back.
     5. Commits `4641:01` if 4610/4611 were changed.
     6. On failure, rolls back in reverse order.
     7. Always returns to op.
   - The result is `WriteResult.Success` or `WriteResult.Failed`, which carries rollback details and whether op mode was restored.
7. **`backup`**:
   - `BackupService` reads every catalog object into a `Backup`, which is JSON via kotlinx.serialization (`format = 1`, values decoded and signed). Objects that can't be read are stored with their error, but a lost connection aborts the backup.
   - `BackupStore` keeps one JSON file per backup in a directory. It never overwrites and writes through a temp file.
   - `RestorePlanner` turns a backup, or the factory defaults from the catalog, into `WriteRequest`s:
     - Only `SafetyLimits` objects are included.
     - Values outside the safe range are reported as `skipped`, not written.
     - A backup from the other Twizy model is refused.
   - Restores always go through `SafeWriter`.
8. **`sim.SimulatedSevcon`**: an in-memory SDO server preloaded with catalog defaults. It models access level, NMT state, objects that need pre-op, read-only identity objects, segmented strings, and fault injection (`unansweredRequests`, `failingWrites`, `refusePreOp`). Its `5310` vendor codes are invented.

`TwizyConnection.open(transport)` ties the stack together and reads `ControllerInfo`.

In `:app`, `MainViewModel` (an AndroidViewModel holding a single `UiState`, with screens switched by the `Screen` enum, no navigation library) owns the connection:
- **Backups:** stored in `getExternalFilesDir()/backups`. The app makes an automatic backup on the first connection to a controller type and another before every restore, and supports import/export through SAF.
- **PreOpGuard:** implemented with a `CompletableDeferred` that the `PreOpDialog` completes.
- **Busy state:** while `busy` is set, navigation and Disconnect are disabled, so a running write can't be cancelled halfway.

The UI has not been tested on a device. The only system images installed are TV images, and those render black when headless.

## Regenerating the catalog

```
soffice --headless --convert-to 'csv:Text - txt - csv (StarCalc):44,34,76,1,,0,false,true,false,false,false,-1' \
    --outdir <scratch> ~/projectsa/twizy/material/Twizy-SDO-List.ods
python3 -I tools/gen_catalog.py <scratch>/Twizy-SDO-List-Tabelle1.csv \
    core/src/main/kotlin/de/dressler/twizy/core/params/GeneratedCatalog.kt
```

Data types, signedness, units and which objects can be written in op mode are decided in the generator script, not in the spreadsheet.

## Reference material (outside the repo, `~/projectsa/twizy/material/`)

- `301_v04020000.pdf`: CiA 301. SDO protocols are in §7.2.4.3 and abort codes in Table 22.
- `Twizy-SDO-List.ods`: SEVCON object dictionary with Twizy80/45 defaults.
- `Twizy CANBUS Objektverzeichnis.ods` (German): broadcast frames (BMS, charger, speed, gear). These are not used yet, because the `ATCRA581` filter hides them.
- `vehicle_twizy.c` (Latin-1): the OVMS module. It is the reference for login/configmode and for the tuning formulas (`cfg_speed`, `cfg_power`, `cfg_makepowermap`, `cfg_tsmap`, `cfg_applyprofile`), and for the default and extended maps in `twizy_cfg_params[]`.
- `canze/`: CanZE (GPL). Use it as a design reference for ELM327 handling only; don't copy its code.

## Roadmap

- Phase 1 (done): stack, simulator, safe writer, and a read-only UI (connect, identify, read all parameters).
- Phase 2 (done): full backup, auto-backup on first connect, restore from backup or factory defaults with old→new preview, import/export.
- Phase 3: parity with the old app. Speed in kph using the OVMS `cfg_speed` offsets, power up/restore using `cfg_makepowermap`, and a pre-op guard dialog in the UI.
- Phase 4: recup/drive, ramps, smoothing, TS maps, brake light; profiles; Twizy45 throughout.
- Phase 5 (optional): live dashboard from the broadcast frames.

Features should be pure functions that turn intent into a `List<WriteRequest>`, with cross-parameter invariants (e.g. `3813:33 < 35 <= 3B`) checked there. Execution always goes through `SafeWriter`.
