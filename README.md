# Elite-Motion (Android / Kotlin)

An original, native Android video editor starter application. It is **not** Alight Motion source code, a branded clone, or a production-complete replacement.

## Implemented

- Import multiple videos through Android's Storage Access Framework (persistent URI access where provided)
- Play the selected clip with native playback controls
- Timeline with selection, clip reorder, duplication and deletion
- Set precise start/end trim points per clip
- Export selected clips **in sequence** to an MP4 file via AndroidX Media3 Transformer
- Export-time grayscale, rotation, 480/720/1080 height scaling, and static text overlay
- Optionally mute original clip audio during export
- Automatically save a single project to local JSON; restore it when reopening the app
- Dark Jetpack Compose editing interface

## Not yet implemented

Independent composited video layers, animated/keyframed overlays, audio mixing/import, advanced effects/transitions, multitrack timeline, and export progress/cancellation. The timeline is a sequential clip list, not a multitrack compositor. Text is static and global across the export. This app is a functional MVP, **not** full feature parity with Alight Motion.

## Build and run

1. Install Android Studio (Ladybug or newer) with Android SDK Platform 35 and JDK 17.
2. Extract this ZIP and choose **Open** in Android Studio, selecting the `Elite-Motion` folder.
3. Let Android Studio download/sync the Android Gradle Plugin 8.7.3, Kotlin 2.0.21 and Media3 1.5.1 dependencies.
4. **Gradle wrapper note:** This archive cannot include the binary `gradle-wrapper.jar` because dependency downloads are unavailable in the creation environment. If Android Studio asks for a wrapper, use a local Gradle 8.9 installation in Settings > Build, Execution, Deployment > Build Tools > Gradle; or run `gradle wrapper --gradle-version 8.9` with a locally installed Gradle. Then sync again.
5. Connect an Android 8.0+ device or launch an emulator, then Run > Run 'app'.
6. Select **Import videos**, choose clips, edit, then **Export MP4**. Keep the app in foreground while exporting.

## Export files

Exports go to the app-specific external movies directory:
`Android/data/com.elitemotion.editor/files/Movies/EliteMotion_YYYYMMDD_HHMMSS.mp4`

On Android 11+, access may be restricted in ordinary file managers. Use Android Studio Device Explorer or `adb pull /sdcard/Android/data/com.elitemotion.editor/files/Movies/` for development. A future release should add a MediaStore save/share button.

## Engineering notes

- Uses SAF content URIs rather than broad storage permissions.
- Video encoding availability, supported effects and export performance depend on the device's codecs and GPU.
- Project saves are a single local file (`project.json`); there is no multi-project library or cloud sync.
- Text overlay, effect and sequence API compatibility should be verified by syncing/building in Android Studio. This project was generated without access to an Android SDK or Gradle runner and **has not been compiled or device-tested**.
- Only use media you have rights to edit.

## Source layout

- `app/src/main/java/com/elitemotion/editor/MainActivity.kt`: UI, project state, player, Media3 export
- `app/src/main/AndroidManifest.xml`: app entry point
- `app/build.gradle.kts`: Android and Media3 dependencies

## Version 1.1 experimental tracks/keyframes

- Add motion keyframes at timestamps (position, scale, rotation); interpolation is linear and applied as a Media3 MatrixTransformation to the main sequence during export. Preview still shows the unprocessed source clip.
- Import extra video and audio tracks, adjust their start offsets, and remove them. Tracks are saved in project.json and are represented by separate Media3 composition sequences.
- Audio sequences are mixed using Media3's default composition mixing. The gain slider is currently stored in project metadata but **does not affect export**. Video-layer compositing may be unsupported by some Media3 Transformer versions and can fail at export; this is not a production-grade multilayer compositor.
- Cut transitions work. Fade selection is a placeholder and currently exports as a cut; no crossfade rendering is implemented.
- This upgraded source has not been compiled or tested on an Android device. The project still needs a Gradle wrapper jar or a locally installed Gradle distribution. In particular, Media3 API differences may require adjustments during sync.

## Version 1.2 — export screen and portable projects

- New **Export & Share** screen styled after the provided reference, with available storage and approximate MP4 file size.
- Output-height selection: 480p, 720p, 1080p, 1440p, 2160p. Device capabilities may limit successful exports.
- Video bitrate slider: 1–50 Mbps. Requests the selected video encoder bitrate via Media3 DefaultEncoderFactory; actual encoded bitrate may differ.
- FPS choices (24, 30, 60) are shown and saved, but **not yet enforced** in the rendering pipeline. Output may use source timing.
- Export editable `.json` project through Android's document picker; import `.json` project and limited Elite-Motion XML.
- The supported XML structure is `<elite-motion-project><clip uri="content://..." duration="10000" start="0" end="10000"/></elite-motion-project>`. XML import only covers sequential video clips; other editing metadata is not converted.
- Portable project JSON refers to original media URIs. Media is **not embedded**, and projects may not load on another phone until media is relinked.
- The other screenshot export modes (cloud upload, PNG frame, image sequence) are **UI placeholders** and do not export.
- This project does **not** parse or execute `.fxx`, `.aep`, `.aepx`, or Alight Motion's proprietary XML effect definitions. Those are distinct formats, not interchangeable with this project's XML.
- The supplied `ALIGHT MOTION EFFECTS.zip` contains third-party effect XML, shaders, and presets. It is **not bundled or claimed as implemented**. A compatible effect engine and asset permission review would be needed first.
- **Build status:** source changes have not been compiled or device-tested in this environment. Media3 API signatures and encoder compatibility should be checked in Android Studio before release.
