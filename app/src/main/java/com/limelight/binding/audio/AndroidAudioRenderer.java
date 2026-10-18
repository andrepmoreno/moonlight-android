package com.limelight.binding.audio;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.audiofx.AudioEffect;
import android.os.Build;

import com.limelight.LimeLog;
import com.limelight.nvstream.av.audio.AudioRenderer;
import com.limelight.nvstream.jni.MoonBridge;

public class AndroidAudioRenderer implements AudioRenderer {

    private final Context context;
    private final boolean enableAudioFx;

    private AudioTrack track;

    // Decoded PCM ring: decode thread pushes, playback thread pulls + writes.
    private short[] ring;
    private short[] scratch;
    private int ringWritePos;
    private int ringReadPos;
    private int ringCount;
    private final Object ringLock = new Object();

    private Thread playbackThread;
    private volatile boolean playbackRunning;

    public AndroidAudioRenderer(Context context, boolean enableAudioFx) {
        this.context = context;
        this.enableAudioFx = enableAudioFx;
    }

    private AudioTrack createAudioTrack(int channelConfig, int sampleRate, int bufferSize, boolean lowLatency) {
        AudioAttributes.Builder attributesBuilder = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME);
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .build();

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // Use FLAG_LOW_LATENCY on L through N
            if (lowLatency) {
                attributesBuilder.setFlags(AudioAttributes.FLAG_LOW_LATENCY);
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioTrack.Builder trackBuilder = new AudioTrack.Builder()
                    .setAudioFormat(format)
                    .setAudioAttributes(attributesBuilder.build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferSize);

            // Use PERFORMANCE_MODE_LOW_LATENCY on O and later
            if (lowLatency) {
                trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
            }

            return trackBuilder.build();
        }
        else {
            return new AudioTrack(attributesBuilder.build(),
                    format,
                    bufferSize,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
        }
    }

    @Override
    public int setup(MoonBridge.AudioConfiguration audioConfiguration, int sampleRate, int samplesPerFrame) {
        int channelConfig;
        int bytesPerFrame;

        switch (audioConfiguration.channelCount)
        {
            case 2:
                channelConfig = AudioFormat.CHANNEL_OUT_STEREO;
                break;
            case 4:
                channelConfig = AudioFormat.CHANNEL_OUT_QUAD;
                break;
            case 6:
                channelConfig = AudioFormat.CHANNEL_OUT_5POINT1;
                break;
            case 8:
                // AudioFormat.CHANNEL_OUT_7POINT1_SURROUND isn't available until Android 6.0,
                // yet the CHANNEL_OUT_SIDE_LEFT and CHANNEL_OUT_SIDE_RIGHT constants were added
                // in 5.0, so just hardcode the constant so we can work on Lollipop.
                channelConfig = 0x000018fc; // AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
                break;
            default:
                LimeLog.severe("Decoder returned unhandled channel count");
                return -1;
        }

        LimeLog.info("Audio channel config: "+String.format("0x%X", channelConfig));

        bytesPerFrame = audioConfiguration.channelCount * samplesPerFrame * 2;

        // We're not supposed to request less than the minimum
        // buffer size for our buffer, but it appears that we can
        // do this on many devices and it lowers audio latency.
        // We'll try the small buffer size first and if it fails,
        // use the recommended larger buffer size.

        for (int i = 0; i < 4; i++) {
            boolean lowLatency;
            int bufferSize;

            // We will try:
            // 1) Small buffer, low latency mode
            // 2) Large buffer, low latency mode
            // 3) Small buffer, standard mode
            // 4) Large buffer, standard mode

            switch (i) {
                case 0:
                case 1:
                    lowLatency = true;
                    break;
                case 2:
                case 3:
                    lowLatency = false;
                    break;
                default:
                    // Unreachable
                    throw new IllegalStateException();
            }

            switch (i) {
                case 0:
                case 2:
                    bufferSize = bytesPerFrame * 2;
                    break;

                case 1:
                case 3:
                    // Try the larger buffer size
                    bufferSize = Math.max(AudioTrack.getMinBufferSize(sampleRate,
                            channelConfig,
                            AudioFormat.ENCODING_PCM_16BIT),
                            bytesPerFrame * 2);

                    // Round to next frame
                    bufferSize = (((bufferSize + (bytesPerFrame - 1)) / bytesPerFrame) * bytesPerFrame);
                    break;
                default:
                    // Unreachable
                    throw new IllegalStateException();
            }

            // Skip low latency options if hardware sample rate doesn't match the content
            if (AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC) != sampleRate && lowLatency) {
                continue;
            }

            // Skip low latency options when using audio effects, since low latency mode
            // precludes the use of the audio effect pipeline (as of Android 13).
            if (enableAudioFx && lowLatency) {
                continue;
            }

            try {
                track = createAudioTrack(channelConfig, sampleRate, bufferSize, lowLatency);
                track.play();

                // Successfully created working AudioTrack. We're done here.
                LimeLog.info("Audio track configuration: "+bufferSize+" "+lowLatency);
                break;
            } catch (Exception e) {
                // Try to release the AudioTrack if we got far enough
                e.printStackTrace();
                try {
                    if (track != null) {
                        track.release();
                        track = null;
                    }
                } catch (Exception ignored) {}
            }
        }

        if (track == null) {
            // Couldn't create any audio track for playback
            return -2;
        }

        // 1 second of PCM shorts; scratch holds one decoded frame for blocking writes.
        ring = new short[sampleRate * audioConfiguration.channelCount];
        scratch = new short[samplesPerFrame * audioConfiguration.channelCount];
        synchronized (ringLock) {
            ringWritePos = 0;
            ringReadPos = 0;
            ringCount = 0;
        }

        return 0;
    }

    @Override
    public void playDecodedAudio(short[] audioData) {
        // Never block the decode thread: copy into the ring or discard.
        synchronized (ringLock) {
            if (ring == null || audioData.length > ring.length - ringCount) {
                return;
            }

            int length = audioData.length;
            int first = Math.min(length, ring.length - ringWritePos);
            System.arraycopy(audioData, 0, ring, ringWritePos, first);
            if (first < length) {
                System.arraycopy(audioData, first, ring, 0, length - first);
            }
            ringWritePos = (ringWritePos + length) % ring.length;
            ringCount += length;
            ringLock.notifyAll();
        }
    }

    private void playbackLoop() {
        while (playbackRunning) {
            int n;

            synchronized (ringLock) {
                while (playbackRunning && ringCount == 0) {
                    try {
                        ringLock.wait();
                    } catch (InterruptedException e) {
                        // Re-check playbackRunning
                        break;
                    }
                }

                if (!playbackRunning || ring == null || scratch == null) {
                    break;
                }

                n = Math.min(scratch.length, ringCount);
                if (n <= 0) {
                    continue;
                }

                int first = Math.min(n, ring.length - ringReadPos);
                System.arraycopy(ring, ringReadPos, scratch, 0, first);
                if (first < n) {
                    System.arraycopy(ring, 0, scratch, first, n - first);
                }
                ringReadPos = (ringReadPos + n) % ring.length;
                ringCount -= n;
            }

            // Blocking write stays off the decode thread so the native queue can drain.
            track.write(scratch, 0, n);
        }
    }

    @Override
    public void start() {
        if (enableAudioFx) {
            // Open an audio effect control session to allow equalizers to apply audio effects
            Intent i = new Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
            i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, track.getAudioSessionId());
            i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.getPackageName());
            i.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_GAME);
            context.sendBroadcast(i);
        }

        playbackRunning = true;
        playbackThread = new Thread(new Runnable() {
            @Override
            public void run() {
                playbackLoop();
            }
        }, "MoonlightAudioPlayback");
        playbackThread.start();
    }

    @Override
    public void stop() {
        if (enableAudioFx) {
            // Close our audio effect control session when we're stopping
            Intent i = new Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
            i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, track.getAudioSessionId());
            i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.getPackageName());
            context.sendBroadcast(i);
        }
    }

    @Override
    public void cleanup() {
        // Stop the playback thread before touching the track.
        playbackRunning = false;
        synchronized (ringLock) {
            ringLock.notifyAll();
        }

        boolean joined = true;
        if (playbackThread != null) {
            playbackThread.interrupt();
            try {
                playbackThread.join(2000);
            } catch (InterruptedException e) {
                joined = false;
            }
            if (playbackThread.isAlive()) {
                LimeLog.severe("Audio playback thread did not stop; skipping track release");
                joined = false;
            }
            playbackThread = null;
        }

        if (!joined) {
            return;
        }

        // Immediately drop all pending data
        track.pause();
        track.flush();

        track.release();
    }
}
