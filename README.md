## Android CameraX Video Recording
Based on this codelab by Google = <br>
https://developer.android.com/codelabs/camerax-getting-started#7 <br>
<br>
But i made adjustment so the Video Recording work backward compatible with Android 5 + (API 21+) since the codelab only <br>
provide how to save the result video for Android 10+ utilizing MediaStore. <br>
<br>
For backward compatible with API 21 and newer, i managed to achieve it by taking a look at the CameraX Video Code (Recorder.java) <br>
and found the additional parameter for .prepareRecording using FileOutputOptions Builder which support Android 5 and newer. <br>
