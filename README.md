# ZlwAudioRecorder   

###
forked from [zhaolewei/ZlwAudioRecorder](https://github.com/zhaolewei/ZlwAudioRecorder)

在源代码基础上加入 [AAC 格式](https://www.jianshu.com/p/648348d34570)支持

参考工程 [printlybyte/AndroidPCMtoAACpushing](https://github.com/printlybyte/AndroidPCMtoAACpushing)

### 功能
1. 使用AudioRecord进行录音
2. 实现pcm音频的录制
3. 实现wav音频的录制 
4. 实现mp3音频的录制
5. 实时获取录音的音量
6. 获取wav/mp3录音文件的时长

### 博客
https://www.jianshu.com/p/c0222de2faed

### Gradle
[![](https://jitpack.io/v/zhaolewei/ZlwAudioRecorder.svg)](https://jitpack.io/#zhaolewei/ZlwAudioRecorder)

    dependencies {
	    implementation 'com.github.zhaolewei:ZlwAudioRecorder:1.0.2'
	}
### 如何使用

1. 初始化
    ```java
    /**
    * 参数1： Application 实例
    * 参数2： 是否打印日志   
    */
    RecordManager.getInstance().init(MyApp.getInstance(), false);
   ```
2. 配置录音参数

* 修改录音格式(默认:WAV)
    ```java       
     RecordManager.getInstance().changeFormat(RecordConfig.RecordFormat.WAV);
    ```
* 修改录音文件存放位置（默认sdcard/Record）
    ```java       
       RecordManager.getInstance().changeRecordDir(recordDir);
    ```
* 录音状态监听
    ```java     
   RecordManager.getInstance().setRecordStateListener(new RecordStateListener() {
       @Override
       public void onStateChange(RecordHelper.RecordState state) {
           }
       }

       @Override
       public void onError(String error) {
       }
   });
    ```
* 录音结果监听 
    ```java     
   RecordManager.getInstance().setRecordResultListener(new RecordResultListener() {
       @Override
       public void onResult(File result) {
       }
    });
    ```
* 声音大小监听
    ```java
     RecordManager.getInstance().setRecordSoundSizeListener(new RecordSoundSizeListener() {
        @Override
        public void onSoundSize(int soundSize) {
        }
    });
    ```
3. 录音控制
* 开始录音
    ```java
    RecordManager.getInstance().start();
    ```  
* 暂停录音
    ```java
    RecordManager.getInstance().pasue();
    ```  
* 恢复录音
    ```java
    RecordManager.getInstance().resume();
    ```  
* 停止
    ```java
    RecordManager.getInstance().stop();
    ```    

