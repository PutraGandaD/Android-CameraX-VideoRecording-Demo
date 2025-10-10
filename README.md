## Android CameraX Video Recording
Based on this codelab by Google = <br>
https://developer.android.com/codelabs/camerax-getting-started#7 <br>
<br>
But i made adjustment so the Video Recording work backward compatible with Android 5 + (API 21+) since the codelab only <br>
provide how to save the result video for Android 10+ utilizing MediaStore. <br>
<br>
For backward compatible with API 21 and newer, i managed to achieve it by taking a look at the CameraX Video Code (Recorder.java) <br>
and found the additional parameter for .prepareRecording using FileOutputOptions Builder which support Android 5 and newer. <br>

## Screenshots
![WhatsApp Image 2025-10-05 at 22 05 35](https://github.com/user-attachments/assets/1d9c0c80-0128-44dc-9fcc-fa5b12d2163b)

## Capability 
- [x] Record video with audio
- [x] Tap to Focus
- [x] Switch back/front camera
- [x] Turn on/off camera flash while recording
- [x] Video size compressing using Androidx Media3 Transformer (in this app it's set to 480p, but you can change it to 720p or 1080p or 4k) 
