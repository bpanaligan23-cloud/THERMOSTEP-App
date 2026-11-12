# THERMOSTEP Android app

This project packages the supplied THERMOSTEP interface in an Android WebView and uses Android native Bluetooth LE APIs. The HTML is already adapted to call the native BLE bridge in the Android app.

## Build without a computer (GitHub Actions)

1. On your Android phone, open https://github.com in Chrome and sign in (create a free account if needed).
2. Create a new repository named `THERMOSTEP-Android` (public or private).
3. Upload the contents of this project folder to the repository, keeping the `.github/workflows/build-apk.yml` path. If GitHub hides dot-folders, enable Chrome's **Desktop site**.
4. Open the repository's **Actions** tab. Select **Build THERMOSTEP APK**, then tap **Run workflow**. If asked, enable GitHub Actions.
5. Wait for the workflow to finish with a green check.
6. Open the completed workflow run, scroll to **Artifacts**, and download `THERMOSTEP-debug-apk`.
7. Extract the downloaded ZIP, tap `app-debug.apk`, allow installation from that source if Android asks, then tap **Install**.

GitHub Actions compiles the APK in the cloud, so you do not need a computer or Android Studio. This produces a debug APK suitable for direct testing, not a Play Store release build.

## BLE interface expected by the ESP32-C3

- Service: `7b3e0001-8a6d-4f2b-9c1d-5e7a0b2c0001`
- RX (phone writes commands): `7b3e0002-8a6d-4f2b-9c1d-5e7a0b2c0001`
- TX (ESP32 notifies telemetry): `7b3e0003-8a6d-4f2b-9c1d-5e7a0b2c0001`

Commands are newline-terminated UTF-8 JSON, such as `{"cmd":"set_temp","value":25.0}` and `{"cmd":"power","value":true}`. Notifications are JSON, such as `{"temp":24.8,"battery":82,"power":true,"target":25.0}`.

The ESP32 must advertise the service UUID, RX must support writes, and TX must support notifications. Confirm the firmware matches these UUIDs and message formats.

## Safety and testing

Do not connect a Peltier element directly to an ESP32 GPIO. Use a correctly rated driver and power supply, temperature feedback, current protection, and independent thermal safeguards. Test BLE commands with the thermal load disconnected first.

The project and cloud workflow have not yet been built or tested against the physical ESP32-C3.
