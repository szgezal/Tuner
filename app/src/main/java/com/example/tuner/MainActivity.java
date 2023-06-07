package com.example.tuner;

import static android.Manifest.permission.RECORD_AUDIO;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import be.tarsos.dsp.AudioDispatcher;
import be.tarsos.dsp.AudioProcessor;
import be.tarsos.dsp.io.android.AudioDispatcherFactory;
import be.tarsos.dsp.pitch.PitchDetectionHandler;
import be.tarsos.dsp.pitch.PitchProcessor;

public class MainActivity extends AppCompatActivity {
    //Képernyő elemek
    private Button button;
    private TextView pitchTextView;
    private TextView diff;
    private TextView note;
    private ProgressBar progressBarRight;
    private ProgressBar progressBarLeft;

    //Hangfeldolgozáshoz
    private AudioDispatcher dispatcher;

    //Hangok közötti lépésköz szorzója
    private static final double twelfthRootOf2 = Math.pow(2, (1.0/12));

    //Zenei hangok listája
    private static final ArrayList<String> notes = new ArrayList<>(List.of(
            "C", "C#/Db", "D", "D#/Eb", "E", "F", "F#/Gb", "G", "G#/Ab", "A", "A#/Bb", "B")
    );

    //Threadpool-t használok a szálak kezeléséhez, hogy ne fagyjon le az applikáció a sok indított szál miatt.
    private ExecutorService executorService;

    /**
     * App elindulásakor lefutó függvény.
     * @param savedInstanceState mentett állapot
     */
    @SuppressLint("SetTextI18n")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //Inicializálások
        button = findViewById(R.id.start_button);
        pitchTextView = findViewById(R.id.pitch_value);
        diff = findViewById(R.id.diff);
        progressBarRight = findViewById(R.id.progressBar);
        progressBarLeft = findViewById(R.id.progressBar2);
        note = findViewById(R.id.note);

        //Mikrofon hozzáféréséhez engedély kérése
        ActivityCompat.requestPermissions(MainActivity.this,
                new String[]{RECORD_AUDIO}, 200);

        //Start/stop gomb funkciói
        button.setOnClickListener(e -> {
            if (button.getText().equals("START")) {
                startTuning();
                button.setText("STOP");
            } else if (button.getText().equals("STOP")) {
                stopTuning();
                button.setText("START");
            }
        });
    }

    /**
     * Eltérés vizualizálása: diff értékét ábrázolom két progressbar-on
     * @param finalDiff Egész hangtól való eltérés (diff kiírt értéke)
     * @param finalClosestPitch Legközelebbi egész hang Hz-ben
     */
    private void setProgress(double finalDiff, double finalClosestPitch) {
        if (finalDiff >= 0.0) {
            double nextPitch = finalClosestPitch * twelfthRootOf2;
            int interval = (int)(nextPitch - finalClosestPitch);
            progressBarRight.setProgress((int)(((finalDiff / (double)interval) * 2) * 100));
            progressBarLeft.setProgress(0);
        } else {
            double previousPitch = finalClosestPitch / twelfthRootOf2;
            int interval = (int)(finalClosestPitch - previousPitch);
            progressBarLeft.setProgress((int)(((-finalDiff / (double)interval) * 2) * 100));
            progressBarRight.setProgress(0);
        }
    }

    /**
     * Hangolás megkezdése.
     * Ha van értelmezhető hang, egy új szálon azt feldolgozza szálbiztosan.
     */
    private void startTuning() {
        //Jelfeldolgozó példánya (paraméterek: mintavételezési ferekvencia, puffer mérete, minták átlapolódása (általában puffer fele))
        dispatcher = AudioDispatcherFactory.fromDefaultMicrophone(44100, 2048, 1024);
        executorService = Executors.newFixedThreadPool(4);
        @SuppressLint("SetTextI18n") PitchDetectionHandler pdh = (result, e) -> {
            //FFT után lekérem a konkrét hangot (Hz)
            final double pitchInHz = Math.round(result.getPitch() * 100.0) / 100.0;
            if (pitchInHz > 0.0) {
                this.executorService.execute(() -> processPitch(pitchInHz));
            } else {
                runOnUiThread(() -> {
                    pitchTextView.setText("Pitch: N/A");
                    diff.setText("Diff: N/A");
                    note.setText("No pitch detected");
                    progressBarLeft.setProgress(0);
                    progressBarRight.setProgress(0);
                });
            }
        };
        //Yin féle FFT-t használok. (FFT egy implementációja)
        AudioProcessor pitchProcessor = new PitchProcessor(PitchProcessor.PitchEstimationAlgorithm.FFT_YIN, 44100, 2048, pdh);
        dispatcher.addAudioProcessor(pitchProcessor);
        new Thread(dispatcher, "Pitch Detector").start();
    }

    /**
     * Itt történik a legközelebbi zenei hang megkeresése és a gui frissítése.
     * @param pitchInHz észlelt hang Hz-ben
     */
    @SuppressLint("SetTextI18n")
    private void processPitch(double pitchInHz) {
        runOnUiThread(() -> pitchTextView.setText("Pitch: " + pitchInHz + " Hz"));
        double closestPitch = 261.63; //C4-ről indul a hang keresése
        int counter = 0;
        //Kettébontottam a hang keresést és felfele vagy lefele keres iteratívan.
        if (pitchInHz >= 261.63) {
            //Megkeresem a hallotthoz legközelebbi egész hangot.
            while (pitchInHz - closestPitch * twelfthRootOf2 > 0) {
                closestPitch = closestPitch * twelfthRootOf2;
                counter++;
            }
            //Ha esetleg a következő lenne a közelebbi, akkor frissítem a legközelebbi hangot.
            double secondClosestPitch = closestPitch * twelfthRootOf2;
            if (secondClosestPitch - pitchInHz < pitchInHz - closestPitch) {
                closestPitch = secondClosestPitch;
                counter++;
            }
            final double finalClosestPitch = Math.round(closestPitch * 100.0) / 100.0;
            final double finalDiff = Math.round((pitchInHz - finalClosestPitch) * 100.0) / 100.0;
            final int finalcounter = counter;
            runOnUiThread(() -> {
                pitchTextView.setText("Pitch: " + pitchInHz + " Hz");
                diff.setText("Diff: " + finalDiff);
                note.setText(notes.get((finalcounter) % 12) + " (" + (((finalcounter) / 12) + 4) + ")");
                setProgress(finalDiff, finalClosestPitch);
            });
        } else {
            while (pitchInHz - closestPitch / twelfthRootOf2 < 0) {
                closestPitch = closestPitch / twelfthRootOf2;
                counter--;
            }
            double secondClosestPitch = closestPitch / twelfthRootOf2;
            if (pitchInHz - secondClosestPitch < closestPitch - pitchInHz) {
                closestPitch = secondClosestPitch;
                counter--;
            }
            final double finalClosestPitch = Math.round(closestPitch * 100.0) / 100.0;
            final double finalDiff = Math.round((pitchInHz - finalClosestPitch) * 100.0) / 100.0;
            final int noteIndex;
            final int finalCounter = counter;
            if (counter % 12 == 0)
                noteIndex = 0;
            else
                noteIndex = 12 + (counter % 12);
            runOnUiThread(() -> {
                pitchTextView.setText("Pitch: " + pitchInHz + " Hz");
                diff.setText("Diff: " + finalDiff);
                if (finalCounter % 12 == 0)
                    note.setText(notes.get(noteIndex) + " (" + ((finalCounter / 12) + 4) + ")");
                else
                    note.setText(notes.get(noteIndex) + " (" + ((finalCounter / 12) + 3) + ")");
                setProgress(finalDiff, finalClosestPitch);
            });
        }
    }

    /**
     * Hangolás leállítása.
     */
    @SuppressLint("SetTextI18n")
    private void stopTuning() {
        if (dispatcher != null) {
            dispatcher.stop();
            dispatcher = null;
        }
        if (executorService != null) {
            executorService.shutdown();
            executorService = null;
        }
        pitchTextView.setText("Pitch: N/A");
        diff.setText("Diff: N/A");
        note.setText("Musical note");
    }
}
