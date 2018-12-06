package com.zlw.main.recorderlib.recorder.aac;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.util.Log;


import com.zlw.main.recorderlib.recorder.RecordConfig;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * @author Conor
 *
 * 音视频编码，对视频进行AVC编码、对音频进行AAC编码
 */
public class AACEncoder {
    private static final String TAG = "AVEncoder";
    public static boolean DEBUG = true;

    ///////////////////AUDIO/////////////////////////////////
    // parameters for the encoder
    private static final String AUDIO_MIME_TYPE = "audio/mp4a-latm";
    private MediaCodec aEncoder;                // API >= 16(Android4.1.2)
    private MediaCodec.BufferInfo aBufferInfo;        // API >= 16(Android4.1.2)
    private MediaCodecInfo audioCodecInfo;
    private MediaFormat audioFormat;
    private Thread audioEncoderThread;
    private volatile boolean audioEncoderLoop = false;
    private volatile boolean aEncoderEnd = false;
    private LinkedBlockingQueue<byte[]> audioQueue;

    private long presentationTimeUs;
    private final int TIMEOUT_USEC = 10000;
    private Callback mCallback;

    public static AACEncoder newInstance(RecordConfig recordConfig) {
        return new AACEncoder(recordConfig);
    }

    private AACEncoder(RecordConfig recordConfig) {
        initAudioEncoder(recordConfig);
    }

    /**
     * 设置回调
     *
     * @param callback 回调
     */
    public void setCallback(Callback callback) {
        this.mCallback = callback;
    }

    public interface Callback {
        void outputAudioData(final byte[] aac, final int len, final int nTimeStamp);
    }


    public void initAudioEncoder(RecordConfig recordConfig){
        aBufferInfo = new MediaCodec.BufferInfo();
        audioQueue = new LinkedBlockingQueue<>();
        audioCodecInfo = selectCodec(AUDIO_MIME_TYPE);
        if (audioCodecInfo == null) {
            if (DEBUG) Log.e(TAG, "= =lgd= Unable to find an appropriate codec for " + AUDIO_MIME_TYPE);
            return;
        }
        int sampleRate = recordConfig.getSampleRate();
        int pcmFormat = recordConfig.getEncoding();
        int chanelCount = recordConfig.getChannelCount();

        Log.d(TAG, "===liuguodong===selected codec: " + audioCodecInfo.getName());
        audioFormat = MediaFormat.createAudioFormat(AUDIO_MIME_TYPE, sampleRate, chanelCount);
        audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        audioFormat.setInteger(MediaFormat.KEY_CHANNEL_MASK, AudioFormat.CHANNEL_IN_STEREO);//CHANNEL_IN_STEREO 立体声
        int bitRate = sampleRate * pcmFormat * chanelCount;
        audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        audioFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, chanelCount);
        audioFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate);
        Log.d(TAG, " =lgd= =====format: " + audioFormat.toString());

        if (aEncoder != null) {
            return;
        }
        try {
            aEncoder = MediaCodec.createEncoderByType(AUDIO_MIME_TYPE);
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException("===liuguodong===初始化音频编码器失败", e);
        }
    }


    private MediaCodecInfo selectCodec(String mimeType) {
        int numCodecs = MediaCodecList.getCodecCount();
        for (int i = 0; i < numCodecs; i++) {
            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);
            if (!codecInfo.isEncoder()) {
                continue;
            }
            String[] types = codecInfo.getSupportedTypes();
            for (int j = 0; j < types.length; j++) {
                if (types[j].equalsIgnoreCase(mimeType)) {
                    return codecInfo;
                }
            }
        }
        return null;
    }

    /**
     * 开始
     */
    public void start() {
        startAudioEncode();
    }

    /**
     * 停止
     */
    public void stop() {
        stopAudioEncode();
    }


    private void startAudioEncode() {
        if (aEncoder == null) {
            throw new RuntimeException(" =lgd= =请初始化音频编码器=====");
        }

        if (audioEncoderLoop) {
            throw new RuntimeException(" =lgd= 音频编码线程必须先停止===");
        }
        audioEncoderThread = new Thread() {
            @Override
            public void run() {
                Log.d(TAG, "===liuguodong=====Audio 编码线程 启动...");
                presentationTimeUs = System.currentTimeMillis() * 1000;
                aEncoderEnd = false;
                aEncoder.configure(audioFormat, null, null,
                        MediaCodec.CONFIGURE_FLAG_ENCODE);
                aEncoder.start();
                while (audioEncoderLoop && !Thread.interrupted()) {
                    try {
                        byte[] data = audioQueue.take();
                        encodeAudioData(data);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                        break;
                    }
                }
                if (aEncoder != null) {
                    //停止音频编码器
                    aEncoder.stop();
                    //释放音频编码器
                    aEncoder.release();
                    aEncoder = null;
                }

                audioQueue.clear();
                Log.d(TAG, "= =lgd= ==Audio 编码线程 退出...");
            }
        };
        audioEncoderLoop = true;
        audioEncoderThread.start();
    }

    private void stopAudioEncode() {
        Log.d(TAG, "== =lgd= ==stop Audio 编码...");
        aEncoderEnd = true;
    }


    /**
     * 添加音频数据
     *
     * @param data
     */
    public void putAudioData(byte[] data) {
        try {
            audioQueue.put(data);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private int getYuvBuffer(int width, int height) {
        int yStride = (int) Math.ceil(width / 16.0) * 16;
        int uvStride = (int) Math.ceil( (yStride / 2) / 16.0) * 16;
        int ySize = yStride * height;
        int uvSize = uvStride * height / 2;
        return ySize + uvSize * 2;
    }


    private void encodeAudioData(byte[] input){
        try {
            //拿到输入缓冲区,用于传送数据进行编码
            ByteBuffer[] inputBuffers = aEncoder.getInputBuffers();
            //首先通过dequeueInputBuffer(long timeoutUs)请求一个输入缓存，timeoutUs代表等待时间，设置为-1代表无限等待
            int inputBufferIndex = aEncoder.dequeueInputBuffer(-1);

            if (inputBufferIndex >= 0) {
                ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
                //使用之前要clear一下，避免之前的缓存数据影响当前数据
                inputBuffer.clear();
                //把数据添加到输入缓存中，
                inputBuffer.put(input);
                //并调用queueInputBuffer()把缓存数据入队
                aEncoder.queueInputBuffer(inputBufferIndex, 0, input.length, 0, 0);
            }

            //拿到输出缓冲区,用于取到编码后的数据
            ByteBuffer[] outputBuffers = aEncoder.getOutputBuffers();
            //拿到输出缓冲区的索引
            int outputBufferIndex = aEncoder.dequeueOutputBuffer(aBufferInfo, TIMEOUT_USEC);

            ByteBuffer outputBuffer;
            int outBitSize;
            int outPacketSize;
            byte[] chunkAudio;

            while (outputBufferIndex >= 0) {
                outBitSize = aBufferInfo.size;

                //添加ADTS头,ADTS头包含了AAC文件的采样率、通道数、帧数据长度等信息。
                outPacketSize = outBitSize + 7;//7为ADTS头部的大小
                outputBuffer = outputBuffers[outputBufferIndex];//拿到输出Buffer
                outputBuffer.position(aBufferInfo.offset);
                outputBuffer.limit(aBufferInfo.offset + outBitSize);
                chunkAudio = new byte[outPacketSize];
                addADTStoPacket(chunkAudio, outPacketSize);
                outputBuffer.get(chunkAudio, 7, outBitSize);//将编码得到的AAC数据 取出到byte[]中偏移量offset=7
                outputBuffer.position(aBufferInfo.offset);

                if (null != mCallback) {
                    mCallback.outputAudioData(chunkAudio, chunkAudio.length, (int) aBufferInfo.presentationTimeUs / 1000);
                }
                //releaseOutputBuffer方法必须调用
                aEncoder.releaseOutputBuffer(outputBufferIndex, false);
                outputBufferIndex = aEncoder.dequeueOutputBuffer(aBufferInfo, 10000);
            }

        } catch (Exception t) {
            Log.e(TAG, "= =lgd= =encodeAudioData=====error: " + t.toString());
        }
    }

    /**
     * 添加ADTS头
     *
     * @param packet
     * @param packetLen
     */
    private void addADTStoPacket(byte[] packet, int packetLen) {
        int profile = 2; // AAC LC
        int freqIdx = 8; // 44.1KHz
        int chanCfg = 1; // CPE

        // fill in ADTS data
        packet[0] = (byte) 0xFF;
        packet[1] = (byte) 0xF9;
        packet[2] = (byte) (((profile - 1) << 6) + (freqIdx << 2) + (chanCfg >> 2));
        packet[3] = (byte) (((chanCfg & 3) << 6) + (packetLen >> 11));
        packet[4] = (byte) ((packetLen & 0x7FF) >> 3);
        packet[5] = (byte) (((packetLen & 7) << 5) + 0x1F);
        packet[6] = (byte) 0xFC;
    }
}
