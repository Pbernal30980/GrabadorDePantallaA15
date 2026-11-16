package com.pedro.grabador;

import android.annotation.SuppressLint;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.Bundle;
import android.util.Range;
import android.view.Surface;

import java.io.FileDescriptor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Motor de grabación: pantalla (H.264) + audio (AAC) desde audio interno y/o micrófono,
 * con pausa/reanudación. Todo en un MP4.
 */
public class ScreenEncoder {

    public static final int AUDIO_NONE = 0;
    public static final int AUDIO_INTERNAL = 1;
    public static final int AUDIO_MIC = 2;
    public static final int AUDIO_BOTH = 3;

    private static final String VIDEO_MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final String AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC;
    private static final int SAMPLE_RATE = 44100;
    private static final int CHUNK_FRAMES = 1024;
    private static final int FPS = 30;

    private final MediaProjection projection;
    private final int screenW, screenH, screenDpi;

    private MediaCodec video, audio;
    private Surface inputSurface;
    private VirtualDisplay display;
    private MediaMuxer muxer;
    private AudioRecord internalRec, micRec;
    private int audioMode = AUDIO_NONE;

    public int videoW, videoH;
    public String warning = null;

    private final Object lock = new Object();
    private int videoTrack = -1, audioTrack = -1;
    private boolean muxerStarted = false;
    private boolean wroteVideo = false;
    private long lastVideoPts = -1, lastAudioPts = -1;

    private volatile boolean running = false;
    private volatile boolean paused = false;
    private volatile boolean videoWaitKey = false;
    private volatile long pauseOffsetUs = 0, pauseStartUs = 0, resumeUs = 0;

    private Thread videoThread, audioThread;

    public ScreenEncoder(MediaProjection projection, int screenW, int screenH, int screenDpi) {
        this.projection = projection;
        this.screenW = screenW;
        this.screenH = screenH;
        this.screenDpi = screenDpi;
    }

    // ---------------------------------------------------------------- preparación

    /** quality: 0=1080p, 1=720p, 2=480p. Lanza excepción si no hay forma de codificar video. */
    @SuppressLint("MissingPermission")
    public void prepare(int quality, int requestedAudio) throws Exception {
        prepareVideo(quality);
        if (requestedAudio != AUDIO_NONE) {
            try {
                prepareAudio(requestedAudio);
            } catch (Exception e) {
                releaseAudio();
                audioMode = AUDIO_NONE;
                warning = "No se pudo capturar el audio (" + e.getMessage() + "). Se grabará sin sonido.";
            }
        }
    }

    private void prepareVideo(int quality) throws Exception {
        int[] shorts = {1080, 720, 480, 360};
        int[] rates = {12_000_000, 6_000_000, 3_000_000, 1_500_000};
        boolean portrait = screenH >= screenW;
        int shortSide = Math.min(screenW, screenH), longSide = Math.max(screenW, screenH);

        MediaCodecInfo info = findEncoder();
        MediaCodecInfo.VideoCapabilities caps = info != null
                ? info.getCapabilitiesForType(VIDEO_MIME).getVideoCapabilities() : null;

        Exception last = null;
        for (int i = Math.max(0, Math.min(2, quality)); i < shorts.length; i++) {
            int s = Math.min(shorts[i], shortSide);
            int l = Math.round((float) s * longSide / shortSide);
            int w = portrait ? s : l, h = portrait ? l : s;
            int wa = 16, ha = 16;
            if (caps != null) { wa = Math.max(2, caps.getWidthAlignment()); ha = Math.max(2, caps.getHeightAlignment()); }
            w -= w % wa; h -= h % ha;
            if (caps != null && !caps.isSizeSupported(w, h)) continue;

            int bitrate = rates[i];
            if (caps != null) {
                Range<Integer> br = caps.getBitrateRange();
                bitrate = Math.max(br.getLower(), Math.min(br.getUpper(), bitrate));
            }
            MediaFormat f = MediaFormat.createVideoFormat(VIDEO_MIME, w, h);
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            f.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / FPS);
            MediaCodec c = null;
            try {
                c = info != null ? MediaCodec.createByCodecName(info.getName()) : MediaCodec.createEncoderByType(VIDEO_MIME);
                c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                inputSurface = c.createInputSurface();
                video = c;
                videoW = w;
                videoH = h;
                return;
            } catch (Exception e) {
                last = e;
                if (c != null) try { c.release(); } catch (Exception ignored) {}
            }
        }
        throw last != null ? last : new IllegalStateException("el codificador de video no aceptó ningún tamaño");
    }

    private static MediaCodecInfo findEncoder() {
        MediaCodecInfo fallback = null;
        for (MediaCodecInfo i : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!i.isEncoder()) continue;
            for (String t : i.getSupportedTypes()) {
                if (t.equalsIgnoreCase(VIDEO_MIME)) {
                    if (i.isHardwareAccelerated()) return i;
                    if (fallback == null) fallback = i;
                }
            }
        }
        return fallback;
    }

    @SuppressLint("MissingPermission")
    private void prepareAudio(int mode) throws Exception {
        int stereoMin = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        int monoMin = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);

        if (mode == AUDIO_INTERNAL || mode == AUDIO_BOTH) {
            AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();
            AudioFormat fmt = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build();
            internalRec = new AudioRecord.Builder()
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(Math.max(stereoMin * 4, 64 * 1024))
                    .setAudioPlaybackCaptureConfig(cfg)
                    .build();
            if (internalRec.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("audio interno no disponible");
        }
        if (mode == AUDIO_MIC || mode == AUDIO_BOTH) {
            micRec = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(monoMin * 4, 32 * 1024));
            if (micRec.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("micrófono no disponible");
        }

        MediaFormat af = MediaFormat.createAudioFormat(AUDIO_MIME, SAMPLE_RATE, 2);
        af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        af.setInteger(MediaFormat.KEY_BIT_RATE, 160_000);
        af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024);
        audio = MediaCodec.createEncoderByType(AUDIO_MIME);
        audio.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        audioMode = mode;
    }

    public int getAudioMode() { return audioMode; }

    // ---------------------------------------------------------------- control

    public void start(FileDescriptor fd) throws Exception {
        muxer = new MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        running = true;
        video.start();
        display = projection.createVirtualDisplay("Grabador", videoW, videoH,
                Math.max(120, Math.round(screenDpi * (float) Math.min(videoW, videoH) / Math.min(screenW, screenH))),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, inputSurface, null, null);
        if (audio != null) {
            audio.start();
            if (internalRec != null) internalRec.startRecording();
            if (micRec != null) micRec.startRecording();
            audioThread = new Thread(this::audioLoop, "grabador-audio");
            audioThread.start();
        }
        videoThread = new Thread(this::videoLoop, "grabador-video");
        videoThread.start();
    }

    public void pause() {
        if (paused) return;
        pauseStartUs = nowUs();
        paused = true;
    }

    public void resume() {
        if (!paused) return;
        long now = nowUs();
        pauseOffsetUs += now - pauseStartUs;
        resumeUs = now;
        videoWaitKey = true;
        paused = false;
        try {
            Bundle b = new Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            video.setParameters(b);
        } catch (Exception ignored) {}
    }

    /** Detiene todo. Devuelve true si quedó un video válido. Llamar fuera del hilo principal. */
    public boolean stop() {
        if (paused) resume();
        running = false;
        try { if (internalRec != null) internalRec.stop(); } catch (Exception ignored) {}
        try { if (micRec != null) micRec.stop(); } catch (Exception ignored) {}
        synchronized (lock) { lock.notifyAll(); }
        join(audioThread);
        join(videoThread);
        if (display != null) { try { display.release(); } catch (Exception ignored) {} display = null; }
        boolean ok = false;
        synchronized (lock) {
            if (muxer != null) {
                if (muxerStarted) {
                    try { muxer.stop(); ok = wroteVideo; } catch (Exception ignored) {}
                }
                try { muxer.release(); } catch (Exception ignored) {}
                muxer = null;
            }
        }
        releaseAll();
        return ok;
    }

    public void releaseAll() {
        if (display != null) { try { display.release(); } catch (Exception ignored) {} display = null; }
        if (video != null) { try { video.stop(); } catch (Exception ignored) {} try { video.release(); } catch (Exception ignored) {} video = null; }
        if (inputSurface != null) { try { inputSurface.release(); } catch (Exception ignored) {} inputSurface = null; }
        releaseAudio();
    }

    private void releaseAudio() {
        if (audio != null) { try { audio.stop(); } catch (Exception ignored) {} try { audio.release(); } catch (Exception ignored) {} audio = null; }
        if (internalRec != null) { try { internalRec.release(); } catch (Exception ignored) {} internalRec = null; }
        if (micRec != null) { try { micRec.release(); } catch (Exception ignored) {} micRec = null; }
    }

    private static void join(Thread t) {
        if (t == null) return;
        try { t.join(4000); } catch (InterruptedException ignored) {}
    }

    private static long nowUs() { return System.nanoTime() / 1000; }

    // ---------------------------------------------------------------- muxer

    private void onTrackAdded() {
        // llamado con lock tomado
        boolean ready = videoTrack >= 0 && (audio == null || audioTrack >= 0);
        if (ready && !muxerStarted) {
            muxer.start();
            muxerStarted = true;
            lock.notifyAll();
        }
    }

    // ---------------------------------------------------------------- hilo de video

    private void videoLoop() {
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        boolean eosSent = false;
        long deadline = Long.MAX_VALUE;
        try {
            while (true) {
                if (!running && !eosSent) {
                    try { video.signalEndOfInputStream(); } catch (Exception ignored) {}
                    eosSent = true;
                    deadline = System.currentTimeMillis() + 2500;
                }
                if (System.currentTimeMillis() > deadline) break;

                int idx = video.dequeueOutputBuffer(bi, 10_000);
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) continue;
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    synchronized (lock) {
                        if (videoTrack < 0) {
                            videoTrack = muxer.addTrack(video.getOutputFormat());
                            onTrackAdded();
                        }
                        long waitUntil = System.currentTimeMillis() + 3000;
                        while (!muxerStarted && running && System.currentTimeMillis() < waitUntil) {
                            try { lock.wait(50); } catch (InterruptedException ignored) {}
                        }
                    }
                    continue;
                }
                if (idx < 0) continue;

                ByteBuffer buf = video.getOutputBuffer(idx);
                boolean config = (bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                if (!config && bi.size > 0 && buf != null) writeVideo(buf, bi);
                video.releaseOutputBuffer(idx, false);
                if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
        } catch (Exception ignored) {
        }
    }

    private void writeVideo(ByteBuffer buf, MediaCodec.BufferInfo bi) {
        boolean key = (bi.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
        if (paused) return;
        if (videoWaitKey) {
            if (!key || bi.presentationTimeUs < resumeUs) return;
            videoWaitKey = false;
        }
        synchronized (lock) {
            if (!muxerStarted) return;
            if (!wroteVideo && !key) return;
            long pts = bi.presentationTimeUs - pauseOffsetUs;
            if (pts <= lastVideoPts) pts = lastVideoPts + 1;
            lastVideoPts = pts;
            MediaCodec.BufferInfo out = new MediaCodec.BufferInfo();
            out.set(bi.offset, bi.size, pts, bi.flags & ~MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            buf.position(bi.offset);
            buf.limit(bi.offset + bi.size);
            muxer.writeSampleData(videoTrack, buf, out);
            wroteVideo = true;
        }
    }

    // ---------------------------------------------------------------- hilo de audio

    private void audioLoop() {
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        short[] mix = new short[CHUNK_FRAMES * 2];
        short[] internal = new short[CHUNK_FRAMES * 2];
        short[] mic = new short[CHUNK_FRAMES];
        long chunkUs = CHUNK_FRAMES * 1_000_000L / SAMPLE_RATE;
        long baseUs = -1, frames = 0, lastInPts = 0;
        boolean inputEos = false;
        long deadline = Long.MAX_VALUE;

        try {
            while (true) {
                // ---- entrada
                if (!inputEos) {
                    if (running) {
                        if (!readChunk(mix, internal, mic)) continue;
                        long est = nowUs() - chunkUs;
                        long pts = baseUs + frames * 1_000_000L / SAMPLE_RATE;
                        if (baseUs < 0 || Math.abs(pts - est) > 200_000) {
                            baseUs = est;
                            frames = 0;
                            pts = est;
                        }
                        frames += CHUNK_FRAMES;
                        if (!paused && (resumeUs == 0 || est >= resumeUs - chunkUs)) {
                            int in = audio.dequeueInputBuffer(10_000);
                            if (in >= 0) {
                                ByteBuffer ib = audio.getInputBuffer(in);
                                ib.clear();
                                int shorts = Math.min(mix.length, ib.remaining() / 2);
                                ib.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(mix, 0, shorts);
                                lastInPts = Math.max(lastInPts + 1, pts - pauseOffsetUs);
                                audio.queueInputBuffer(in, 0, shorts * 2, lastInPts, 0);
                            }
                        }
                    } else {
                        int in = audio.dequeueInputBuffer(10_000);
                        if (in >= 0) {
                            audio.queueInputBuffer(in, 0, 0, lastInPts + 1, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEos = true;
                            deadline = System.currentTimeMillis() + 2000;
                        }
                    }
                }
                if (System.currentTimeMillis() > deadline) break;

                // ---- salida
                boolean eos = false;
                while (true) {
                    int out = audio.dequeueOutputBuffer(bi, inputEos ? 10_000 : 0);
                    if (out == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                    if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        synchronized (lock) {
                            if (audioTrack < 0) {
                                audioTrack = muxer.addTrack(audio.getOutputFormat());
                                onTrackAdded();
                            }
                        }
                        continue;
                    }
                    if (out < 0) continue;
                    ByteBuffer ob = audio.getOutputBuffer(out);
                    boolean config = (bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (!config && bi.size > 0 && ob != null) {
                        synchronized (lock) {
                            if (muxerStarted && wroteVideo) {
                                long pts = bi.presentationTimeUs;
                                if (pts <= lastAudioPts) pts = lastAudioPts + 1;
                                lastAudioPts = pts;
                                MediaCodec.BufferInfo o = new MediaCodec.BufferInfo();
                                o.set(bi.offset, bi.size, pts, bi.flags & ~MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                ob.position(bi.offset);
                                ob.limit(bi.offset + bi.size);
                                muxer.writeSampleData(audioTrack, ob, o);
                            }
                        }
                    }
                    audio.releaseOutputBuffer(out, false);
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) { eos = true; break; }
                }
                if (eos) break;
            }
        } catch (Exception ignored) {
        }
    }

    /** Llena mix (estéreo intercalado) con CHUNK_FRAMES cuadros. */
    private boolean readChunk(short[] mix, short[] internal, short[] mic) {
        int n = CHUNK_FRAMES * 2;
        switch (audioMode) {
            case AUDIO_INTERNAL: {
                int got = readFully(internalRec, internal, n, AudioRecord.READ_BLOCKING);
                if (got <= 0) return false;
                System.arraycopy(internal, 0, mix, 0, n);
                return true;
            }
            case AUDIO_MIC: {
                int got = readFully(micRec, mic, CHUNK_FRAMES, AudioRecord.READ_BLOCKING);
                if (got <= 0) return false;
                for (int i = 0; i < CHUNK_FRAMES; i++) { mix[2 * i] = mic[i]; mix[2 * i + 1] = mic[i]; }
                return true;
            }
            case AUDIO_BOTH: {
                int got = readFully(micRec, mic, CHUNK_FRAMES, AudioRecord.READ_BLOCKING);
                if (got <= 0) return false;
                int gi = internalRec.read(internal, 0, n, AudioRecord.READ_NON_BLOCKING);
                if (gi < 0) gi = 0;
                gi -= gi % 2;
                for (int i = gi; i < n; i++) internal[i] = 0;
                for (int i = 0; i < CHUNK_FRAMES; i++) {
                    mix[2 * i] = clamp(internal[2 * i] + mic[i]);
                    mix[2 * i + 1] = clamp(internal[2 * i + 1] + mic[i]);
                }
                return true;
            }
            default:
                return false;
        }
    }

    private int readFully(AudioRecord r, short[] buf, int n, int mode) {
        int total = 0;
        while (total < n && running) {
            int got = r.read(buf, total, n - total, mode);
            if (got <= 0) break;
            total += got;
        }
        if (total > 0 && total < n) for (int i = total; i < n; i++) buf[i] = 0;
        return total;
    }

    private static short clamp(int v) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
    }
}
