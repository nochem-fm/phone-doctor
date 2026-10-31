package com.phone.doctor.fm;

import android.content.Intent;
import android.speech.RecognitionService;

/** Required by the assistant framework; Phone Doctor does not do speech recognition. */
public class StubRecognizer extends RecognitionService {
    @Override
    protected void onStartListening(Intent intent, Callback callback) { }

    @Override
    protected void onCancel(Callback callback) { }

    @Override
    protected void onStopListening(Callback callback) { }
}
