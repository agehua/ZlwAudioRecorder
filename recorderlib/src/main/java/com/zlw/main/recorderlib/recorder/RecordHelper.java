package com.zlw.main.recorderlib.recorder;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.zlw.main.recorderlib.recorder.aac.AACEncoder;
import com.zlw.main.recorderlib.recorder.listener.RecordDataListener;
import com.zlw.main.recorderlib.recorder.listener.RecordResultListener;
import com.zlw.main.recorderlib.recorder.listener.RecordSoundSizeListener;
import com.zlw.main.recorderlib.recorder.listener.RecordStateListener;
import com.zlw.main.recorderlib.recorder.mp3.Mp3EncodeThread;
import com.zlw.main.recorderlib.recorder.wav.WavUtils;
import com.zlw.main.recorderlib.utils.ByteUtils;
import com.zlw.main.recorderlib.utils.FileUtils;
import com.zlw.main.recorderlib.utils.Logger;
import com.zlw.main.recorderlib.utils.RecordUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * @author zhaolewei on 2018/7/10.
 */
public class RecordHelper {
    private static final String TAG = RecordHelper.class.getSimpleName();
    private volatile static RecordHelper instance;
    private volatile RecordState state = RecordState.IDLE;
    private static final int RECORD_AUDIO_BUFFER_TIMES = 1;

    private RecordStateListener recordStateListener;
    private RecordDataListener recordDataListener;
    private RecordSoundSizeListener recordSoundSizeListener;
    private RecordResultListener recordResultListener;
    private RecordConfig currentConfig;
    private AudioRecordThread audioRecordThread;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    private File resultFile = null;
    private File tmpFile = null;
    private List<File> files = new ArrayList<>();
    private Mp3EncodeThread mp3EncodeThread;

    private RecordHelper() {
    }

    static RecordHelper getInstance() {
        if (instance == null) {
            synchronized (RecordHelper.class) {
                if (instance == null) {
                    instance = new RecordHelper();
                }
            }
        }
        return instance;
    }

    RecordState getState() {
        return state;
    }

    void setRecordStateListener(RecordStateListener recordStateListener) {
        this.recordStateListener = recordStateListener;
    }

    void setRecordDataListener(RecordDataListener recordDataListener) {
        this.recordDataListener = recordDataListener;
    }

    void setRecordSoundSizeListener(RecordSoundSizeListener recordSoundSizeListener) {
        this.recordSoundSizeListener = recordSoundSizeListener;
    }

    void setRecordResultListener(RecordResultListener recordResultListener) {
        this.recordResultListener = recordResultListener;
    }

    public void start(String _filePath, RecordConfig config) {
        this.currentConfig = config;
        if (state != RecordState.IDLE) {
            Logger.e(TAG, "状态异常当前状态： %s", state.name());
            return;
        }
        this.filePath = _filePath;
        resultFile = new File(filePath);
        String tempFilePath = getTempFilePath();

        Logger.d(TAG, "----------------开始录制 %s------------------------", currentConfig.getFormat().name());
        Logger.d(TAG, "参数： %s", currentConfig.toString());
        Logger.i(TAG, "pcm缓存 tmpFile: %s", tempFilePath);
        Logger.i(TAG, "录音文件 resultFile: %s", filePath);


        tmpFile = new File(tempFilePath);
        //1.开启录音线程并准备录音
        audioRecordThread = new AudioRecordThread();
        audioRecordThread.start();
    }

    public void stop() {
        if (null!= mAACEncoder){
            mAACEncoder.stop();
        }
        if (state == RecordState.IDLE) {
            Logger.e(TAG, "状态异常当前状态： %s", state.name());
            return;
        }

        if (state == RecordState.PAUSE) {
            makeFile();
            state = RecordState.IDLE;
        } else {
            state = RecordState.STOP;
        }
        notifyState();
    }

    void pause() {
        if (state != RecordState.RECORDING) {
            Logger.e(TAG, "状态异常当前状态： %s", state.name());
            return;
        }
        state = RecordState.PAUSE;
        notifyState();
    }

    void resume() {
        if (state != RecordState.PAUSE) {
            Logger.e(TAG, "状态异常当前状态： %s", state.name());
            return;
        }
        String tempFilePath = getTempFilePath();
        Logger.i(TAG, "tmpPCM File: %s", tempFilePath);
        tmpFile = new File(tempFilePath);
        audioRecordThread = new AudioRecordThread();
        audioRecordThread.start();
    }

    private void notifyState() {
        if (recordStateListener == null) {
            return;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                recordStateListener.onStateChange(state);
            }
        });

        if (state == RecordState.STOP || state == RecordState.PAUSE) {
            if (recordSoundSizeListener != null) {
                recordSoundSizeListener.onSoundSize(0);
            }
        }
    }

    private void notifyFinish() {
        Logger.d(TAG, "录音结束 file: %s", resultFile.getAbsolutePath());

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (recordStateListener != null) {
                    recordStateListener.onStateChange(RecordState.FINISH);
                }
                if (recordResultListener != null) {
                    recordResultListener.onResult(resultFile);
                }
            }
        });
    }

    private void notifyError(final String error) {
        if (recordStateListener == null) {
            return;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                recordStateListener.onError(error);
            }
        });
    }

    private void notifyData(final byte[] data) {
        if (recordDataListener == null && recordSoundSizeListener == null) {
            return;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (recordDataListener != null) {
                    recordDataListener.onData(data);
                }
                if (recordSoundSizeListener != null) {
                    recordSoundSizeListener.onSoundSize((int) RecordUtils.getMaxDecibels(data));
                }
            }
        });
    }

    private void initMp3EncoderThread(int bufferSize) {
        try {
            mp3EncodeThread = new Mp3EncodeThread(resultFile, bufferSize);
            mp3EncodeThread.start();
        } catch (Exception e) {
            Logger.e(e, TAG, e.getMessage());
        }
    }

    private class AudioRecordThread extends Thread {
        private AudioRecord audioRecord;
        private int bufferSize;

        AudioRecordThread() {
            //2.根据录音参数构造AudioRecord实体对象
            bufferSize = AudioRecord.getMinBufferSize(currentConfig.getSampleRate(),
                    currentConfig.getChannelConfig(), currentConfig.getEncodingConfig()) * RECORD_AUDIO_BUFFER_TIMES;
            Logger.d(TAG, "record buffer size = %s", bufferSize);

            getValidBufferSize();
            if (currentConfig.getFormat() == RecordConfig.RecordFormat.MP3 && mp3EncodeThread == null) {
                initMp3EncoderThread(bufferSize);
            } else if (currentConfig.getFormat() == RecordConfig.RecordFormat.AAC) {
                initAACEncoder();
            }
        }

        public void getValidBufferSize() {
            for (int rate : new int[]{44100, 22050, 11025, 16000, 8000}) {  // add the rates you wish to check against
                int bufferSize = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_CONFIGURATION_DEFAULT, AudioFormat.ENCODING_PCM_16BIT);
                if (bufferSize > 0) {
                    // buffer size is valid, Sample rate supported
                    Logger.d(TAG, "buffer size is valid, = %s" + bufferSize);
                    /**
                     * @param audioSource ：录音源
                     * 这里选择使用麦克风：MediaRecorder.AudioSource.MIC
                     * @param sampleRateInHz： 采样率
                     * @param channelConfig：声道数
                     * @param audioFormat： 采样位数.
                     *   See {@link AudioFormat#ENCODING_PCM_8BIT}, {@link AudioFormat#ENCODING_PCM_16BIT},
                     *   and {@link AudioFormat#ENCODING_PCM_FLOAT}.
                     * @param bufferSizeInBytes： 音频录制的缓冲区大小
                     *   See {@link #getMinBufferSize(int, int, int)}
                     */
                    audioRecord = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, currentConfig.getSampleRate(),
                            currentConfig.getChannelConfig(), currentConfig.getEncodingConfig(), bufferSize);
                    return;
                }
            }
        }

        @Override
        public void run() {
            super.run();

            switch (currentConfig.getFormat()) {
                case AAC:
                    mAACEncoder.start();
                    startAacRecorder();
                    break;
                case MP3:
                    startMp3Recorder();
                    break;
                default:
                    startPcmRecorder();
                    break;
            }
        }

        private void startPcmRecorder() {
            state = RecordState.RECORDING;
            notifyState();
            Logger.d(TAG, "开始录制 Pcm");
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(tmpFile);
                audioRecord.startRecording();
                byte[] byteBuffer = new byte[bufferSize];

                while (state == RecordState.RECORDING) {
                    int end = audioRecord.read(byteBuffer, 0, byteBuffer.length);
                    notifyData(byteBuffer);
                    fos.write(byteBuffer, 0, end);
                    fos.flush();
                }
                audioRecord.stop();
                files.add(tmpFile);
                if (state == RecordState.STOP) {
                    makeFile();
                } else {
                    Logger.i(TAG, "暂停！");
                }
            } catch (Exception e) {
                Logger.e(e, TAG, e.getMessage());
                notifyError("录音失败");
            } finally {
                try {
                    if (fos != null) {
                        fos.close();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (state != RecordState.PAUSE) {
                state = RecordState.IDLE;
                notifyState();
                Logger.d(TAG, "录音结束");
            }
        }

        private void startMp3Recorder() {
            state = RecordState.RECORDING;
            notifyState();

            try {
                audioRecord.startRecording();
                short[] byteBuffer = new short[bufferSize];

                while (state == RecordState.RECORDING) {
                    int end = audioRecord.read(byteBuffer, 0, byteBuffer.length);
                    if (mp3EncodeThread != null) {
                        mp3EncodeThread.addChangeBuffer(new Mp3EncodeThread.ChangeBuffer(byteBuffer, end));
                    }
                    notifyData(ByteUtils.toBytes(byteBuffer));
                }
                audioRecord.stop();
            } catch (Exception e) {
                Logger.e(e, TAG, e.getMessage());
                notifyError("录音失败");
            }
            if (state != RecordState.PAUSE) {
                state = RecordState.IDLE;
                notifyState();
                if (mp3EncodeThread != null) {
                    mp3EncodeThread.stopSafe(new Mp3EncodeThread.EncordFinishListener() {
                        @Override
                        public void onFinish() {
                            notifyFinish();
                        }
                    });
                } else {
                    notifyFinish();
                }
            } else {
                Logger.d(TAG, "暂停");
            }
        }

        private void startAacRecorder() {
            state = RecordState.RECORDING;
            notifyState();

            try {
                if (null == audioRecord) {
                    return;
                }
                audioRecord.startRecording();
                byte[] byteBuffer = new byte[bufferSize];

                while (state == RecordState.RECORDING) {
                    int end = audioRecord.read(byteBuffer, 0, byteBuffer.length);
                    if (mAACEncoder != null) {
                        mAACEncoder.putAudioData(byteBuffer);
                    }
                    notifyData(byteBuffer);
                }
                audioRecord.stop();
                if (state == RecordState.STOP) {
                    makeFile();
                } else {
                    Logger.i(TAG, "暂停！");
                }
            } catch (Exception e) {
                Logger.e(TAG, e.getMessage());
                notifyError("录音失败");
            } finally {
                if (null != audioRecord) {
                    audioRecord.release();
                    audioRecord = null;
                }
                try {
                    if (fos != null) {
                        fos.close();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (state != RecordState.PAUSE) {
                state = RecordState.IDLE;
                notifyState();
            } else {
                Logger.d(TAG, "暂停");
            }

        }
    }

    private void makeFile() {
        switch (currentConfig.getFormat()) {
            case MP3:
                return;
            case WAV:
                mergePcmFile();
                makeWav();
                break;
            case PCM:
                mergePcmFile();
                break;
            case AAC:
                break;
            default:
                break;
        }
        notifyFinish();
        Logger.i(TAG, "录音完成！ path: %s ； 大小：%s", resultFile.getAbsoluteFile(), resultFile.length());
    }

    /**
     * 添加Wav头文件
     */
    private void makeWav() {
        if (!FileUtils.isFile(resultFile) || resultFile.length() == 0) {
            return;
        }
        byte[] header = WavUtils.generateWavFileHeader((int) resultFile.length(), currentConfig.getSampleRate(), currentConfig.getChannelCount(), currentConfig.getEncoding());
        WavUtils.writeHeader(resultFile, header);
    }

    /**
     * 合并文件
     */
    private void mergePcmFile() {
        boolean mergeSuccess = mergePcmFiles(resultFile, files);
        if (!mergeSuccess) {
            notifyError("合并失败");
        }
    }

    /**
     * 合并Pcm文件
     *
     * @param recordFile 输出文件
     * @param files      多个文件源
     * @return 是否成功
     */
    private boolean mergePcmFiles(File recordFile, List<File> files) {
        if (recordFile == null || files == null || files.size() <= 0) {
            return false;
        }

        FileOutputStream fos = null;
        BufferedOutputStream outputStream = null;
        byte[] buffer = new byte[1024];
        try {
            fos = new FileOutputStream(recordFile);
            outputStream = new BufferedOutputStream(fos);

            for (int i = 0; i < files.size(); i++) {
                BufferedInputStream inputStream = new BufferedInputStream(new FileInputStream(files.get(i)));
                int readCount;
                while ((readCount = inputStream.read(buffer)) > 0) {
                    outputStream.write(buffer, 0, readCount);
                }
                inputStream.close();
            }
        } catch (Exception e) {
            Logger.e(e, TAG, e.getMessage());
            return false;
        } finally {
            try {
                if (fos != null) {
                    fos.close();
                }
                if (outputStream != null) {
                    outputStream.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        for (int i = 0; i < files.size(); i++) {
            files.get(i).delete();
        }
        files.clear();
        return true;
    }

    /**
     * 根据当前的时间生成相应的文件名
     * 实例 record_20160101_13_15_12
     */
    private String getTempFilePath() {
        String fileDir = String.format(Locale.getDefault(), "%s/Record/", Environment.getExternalStorageDirectory().getAbsolutePath());
        if (!FileUtils.createOrExistsDir(fileDir)) {
            Logger.e(TAG, "文件夹创建失败：%s", fileDir);
        }
        String fileName = String.format(Locale.getDefault(), "record_tmp_%s", FileUtils.getNowString(new SimpleDateFormat("yyyyMMdd_HH_mm_ss", Locale.SIMPLIFIED_CHINESE)));
        return String.format(Locale.getDefault(), "%s%s.pcm", fileDir, fileName);
    }

    /**
     * 表示当前状态
     */
    public enum RecordState {
        /**
         * 空闲状态
         */
        IDLE,
        /**
         * 录音中
         */
        RECORDING,
        /**
         * 暂停中
         */
        PAUSE,
        /**
         * 正在停止
         */
        STOP,
        /**
         * 录音流程结束（转换结束）
         */
        FINISH
    }


    private LinkedBlockingQueue<Runnable> mRunnables = new LinkedBlockingQueue<>();


    private Thread workThread;
    private volatile boolean loop;

    FileOutputStream fos = null;
    private AACEncoder mAACEncoder;
    private String filePath;

    private void initAACEncoder(){
        mAACEncoder = AACEncoder.newInstance(currentConfig);
        if (null == resultFile){
            resultFile = new File(filePath);
        }
        try {
            fos = new FileOutputStream(resultFile);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            return;
        }

        workThread = new Thread("publish-thread") {
            @Override
            public void run() {
                while (loop && !Thread.interrupted()) {
                    try {
                        Runnable runnable = mRunnables.take();
                        runnable.run();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                mRunnables.clear();
                Log.d(TAG, "= =lgd= Rtmp发布线程退出...");
            }
        };

        loop = true;
        workThread.start();

        mAACEncoder.setCallback(new AACEncoder.Callback() {

            @Override
            public void outputAudioData(final byte[] aac, final int len,final int nTimeStamp) {
                Runnable runnable = new Runnable() {
                    @Override
                    public void run() {
                        Log.e(TAG, "outPutAACData len"+len);
                        if (null!=fos) {
                            try {
                                fos.write(aac, 0, len);
                                fos.flush();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                };
                try {
                    mRunnables.put(runnable);
                } catch (InterruptedException e) {
                    Log.e(TAG, " =lgd= outputAudioData=====error: "+e.toString());
                    e.printStackTrace();
                }
            }
        });


    }

}
