package com.vanco.abplayer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.TextUtils;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.vanco.util.DeviceUtils;
import com.vanco.util.ImageUtils;
import com.vanco.util.IntentHelper;
import com.vanco.util.MediaUtils;
import com.vanco.util.PreferenceUtils;
import com.vanco.util.StringUtils;
import com.vanco.view.ApplicationUtils;
import com.vanco.view.MediaController;
import com.vanco.view.PlayerService;
import com.vanco.view.VP;
import com.vanco.view.VideoView;

import java.io.File;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;

import io.vov.vitamio.utils.FileUtils;
import io.vov.vitamio.widget.OutlineTextView;

public class MainActivity extends Activity implements
        MediaController.MediaPlayerControl, VideoView.SurfaceCallback{

    public static final int RESULT_FAILED = -7;

    private static final IntentFilter USER_PRESENT_FILTER = new IntentFilter(
            Intent.ACTION_USER_PRESENT);
    private static final IntentFilter SCREEN_FILTER = new IntentFilter(
            Intent.ACTION_SCREEN_ON);
    private static final IntentFilter HEADSET_FILTER = new IntentFilter(
            Intent.ACTION_HEADSET_PLUG);
    private static final IntentFilter BATTERY_FILTER = new IntentFilter(
            Intent.ACTION_BATTERY_CHANGED);

    private boolean mCreated = false;
    private boolean mNeedLock;
    private String mDisplayName;
    private String mBatteryLevel;
    private boolean mFromStart;
    private int mLoopCount;
    private boolean mSaveUri;
    private int mParentId;
    private float mStartPos;
    private boolean mEnd = false;
    private String mSubPath;
    private boolean mSubShown;
    private View mViewRoot;
    private VideoView mVideoView;
    private View mVideoLoadingLayout;
    private TextView mVideoLoadingText;
    private View mSubtitleContainer;
    private OutlineTextView mSubtitleText;
    private ImageView mSubtitleImage;
    private Uri mUri;
    private ScreenReceiver mScreenReceiver;
    private HeadsetPlugReceiver mHeadsetPlugReceiver;
    private UserPresentReceiver mUserPresentReceiver;
    private BatteryReceiver mBatteryReceiver;
    private boolean mReceiverRegistered = false;
    private boolean mHeadsetPlaying = false;
    private boolean mCloseComplete = false;
    private boolean mIsHWCodec = false;

    private MediaController mMediaController;
    private PlayerService vPlayer;
    private ServiceConnection vPlayerServiceConnection;


    static {
        SCREEN_FILTER.addAction(Intent.ACTION_SCREEN_OFF);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        //设置常亮显示全屏
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (!io.vov.vitamio.LibsChecker.checkVitamioLibs(this))
            return;

        //初始化视频服务器
        vPlayerServiceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                //获取视频播放的服务
                vPlayer = ((PlayerService.LocalBinder) service).getService();
                mServiceConnected = true;
                if (mSurfaceCreated)
                    vPlayerHandler.sendEmptyMessage(OPEN_FILE);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                vPlayer = null;
                mServiceConnected = false;
            }
        };
        //设置声音
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        parseIntent(getIntent());
        //添加页面
        loadView(R.layout.activity_video);
        //注册各种广播
        manageReceivers();

        mCreated = true;

    }

    /**
     * 附加视频播放控制器
     */
    private void attachMediaController() {
        if (mMediaController != null) {
            mNeedLock = mMediaController.isLocked();
            mMediaController.release();
        }
        mMediaController = new MediaController(this, mNeedLock);
        mMediaController.setMediaPlayer(this);
        mMediaController.setAnchorView(mVideoView.getRootView());
        mMediaController.setDanmakuVisible(false);
        setFileName();
        setBatteryLevel();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (!mCreated)
            return;
        //绑定视频播放功能
        Intent serviceIntent = new Intent(this, PlayerService.class);
        serviceIntent.putExtra("isHWCodec", mIsHWCodec);
        bindService(serviceIntent, vPlayerServiceConnection,
                Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!mCreated)
            return;
        if (isInitialized()) {
            KeyguardManager keyguardManager = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            if (!keyguardManager.inKeyguardRestrictedInputMode()) {
                startPlayer();
            }
        } else {
            if (mCloseComplete) {
                reOpen();
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (!mCreated)
            return;
        if (isInitialized()) {
            savePosition();
            if (vPlayer != null && vPlayer.isPlaying()) {
                stopPlayer();
            }
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        if (!mCreated)
            return;
        if (isInitialized()) {
            vPlayer.releaseSurface();
        }
        if (mServiceConnected) {
            unbindService(vPlayerServiceConnection);
            mServiceConnected = false;
        }

    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (!mCreated)
            return;
        manageReceivers();
        if (isInitialized() && !vPlayer.isPlaying())
            release();
        if (mMediaController != null)
            mMediaController.release();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        if (isInitialized()) {
            setVideoLayout();
            attachMediaController();
        }

        super.onConfigurationChanged(newConfig);
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        // http://code.google.com/p/android/issues/detail?id=19917
        outState.putString("WORKAROUND_FOR_BUG_19917_KEY",
                "WORKAROUND_FOR_BUG_19917_VALUE");
        super.onSaveInstanceState(outState);
    }

    @Override
    public void showMenu() {

    }

    private void loadView(int id) {
        setContentView(id);
        getWindow().setBackgroundDrawable(null);
        mViewRoot = findViewById(R.id.video_root);
        mVideoView = (VideoView) findViewById(R.id.video);
        mVideoView.initialize(this, this, mIsHWCodec);
        mSubtitleContainer = findViewById(R.id.subtitle_container);
        mSubtitleText = (OutlineTextView) findViewById(R.id.subtitle_text);
        mSubtitleImage = (ImageView) findViewById(R.id.subtitle_image);
        mVideoLoadingText = (TextView) findViewById(R.id.video_loading_text);
        mVideoLoadingLayout = findViewById(R.id.video_loading);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void parseIntent(Intent i) {

        Uri dat = IntentHelper.getIntentUri(i);
        if (dat == null)
            resultFinish(RESULT_FAILED);

        String datString = dat.toString();
        if (!datString.equals(dat.toString()))
            dat = Uri.parse(datString);

        mUri = dat;

        mNeedLock = i.getBooleanExtra("lockScreen", false);
        mDisplayName = i.getStringExtra("displayName");
        mFromStart = i.getBooleanExtra("fromStart", false);
        mSaveUri = i.getBooleanExtra("saveUri", true);
        mStartPos = i.getFloatExtra("startPosition", -1.0f);
        mLoopCount = i.getIntExtra("loopCount", 1);
        mParentId = i.getIntExtra("parentId", 0);
        mSubPath = i.getStringExtra("subPath");
        mSubShown = i.getBooleanExtra("subShown", true);
        mIsHWCodec = i.getBooleanExtra("hwCodec", false);

    }

    /**
     * 各种广播接收器 屏幕的 电量等基本信息
     */
    private void manageReceivers() {
        if (!mReceiverRegistered) {
            mScreenReceiver = new ScreenReceiver();
            registerReceiver(mScreenReceiver, SCREEN_FILTER);
            mUserPresentReceiver = new UserPresentReceiver();
            registerReceiver(mUserPresentReceiver, USER_PRESENT_FILTER);
            mBatteryReceiver = new BatteryReceiver();
            registerReceiver(mBatteryReceiver, BATTERY_FILTER);
            mHeadsetPlugReceiver = new HeadsetPlugReceiver();
            registerReceiver(mHeadsetPlugReceiver, HEADSET_FILTER);
            mReceiverRegistered = true;
        } else {
            try {
                if (mScreenReceiver != null)
                    unregisterReceiver(mScreenReceiver);
                if (mUserPresentReceiver != null)
                    unregisterReceiver(mUserPresentReceiver);
                if (mHeadsetPlugReceiver != null)
                    unregisterReceiver(mHeadsetPlugReceiver);
                if (mBatteryReceiver != null)
                    unregisterReceiver(mBatteryReceiver);
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
            }
            mReceiverRegistered = false;
        }
    }

    /**
     * 设置播放文件的名字
     */
    private void setFileName() {
        if (mUri != null) {
            String name = null;
            if (mUri.getScheme() == null || mUri.getScheme().equals("file"))
                name = FileUtils.getName(mUri.toString());
            else
                name = mUri.getLastPathSegment();
            if (name == null)
                name = "null";
            if (mDisplayName == null)
                mDisplayName = name;
            mMediaController.setFileName(mDisplayName);
        }
    }

    private void applyResult(int resultCode) {
        vPlayerHandler.removeMessages(BUFFER_PROGRESS);
        Intent i = new Intent();
        i.putExtra("filePath", mUri.toString());
        if (isInitialized()) {
            i.putExtra("position", (double) vPlayer.getCurrentPosition()
                    / vPlayer.getDuration());
            i.putExtra("duration", vPlayer.getDuration());
            savePosition();
        }
        switch (resultCode) {
            case RESULT_FAILED:
                Toast.makeText(MainActivity.this,R.string.video_cannot_play,Toast.LENGTH_SHORT).show();
                break;
            case RESULT_CANCELED:
            case RESULT_OK:
                break;
        }
        setResult(resultCode, i);
    }

    /**
     * 播放完毕之后或者打开失败的话
     */
    private void resultFinish(int resultCode) {
        applyResult(resultCode);
        if (DeviceUtils.hasICS() && resultCode != RESULT_FAILED) {
            android.os.Process.killProcess(android.os.Process.myPid());
        } else {
            finish();
        }
    }

    /**
     * 释放播放器的资源
     */
    private void release() {
        if (vPlayer != null) {
            if (DeviceUtils.hasICS()) {
                android.os.Process.killProcess(android.os.Process.myPid());
            } else {
                vPlayer.release();
                vPlayer.releaseContext();
            }
        }
    }

    private void reOpen(Uri path, String name, boolean fromStart) {
        //要是初始化了成功就保存位置释放资源
        if (isInitialized()) {
            savePosition();
            vPlayer.release();
            vPlayer.releaseContext();
        }
        Intent i = getIntent();
        //保存是否锁屏
        if (mMediaController != null)
            i.putExtra("lockScreen", mMediaController.isLocked());
        //保存开始的位置
        i.putExtra("startPosition", PreferenceUtils.getFloat(mUri
                + VP.SESSION_LAST_POSITION_SUFIX, 7.7f));
        i.putExtra("fromStart", fromStart);
        //保存display的名字
        i.putExtra("displayName", name);
        i.setData(path);
        // TODO: 16/3/16 这个地方需要调试
        parseIntent(i);
        mUri = path;
        if (mViewRoot != null)
            mViewRoot.invalidate();
        if (mOpened != null)
            mOpened.set(false);
    }

    public void reOpen() {
        reOpen(mUri, mDisplayName, false);
    }

    /**
     * 开始播放视频
     */
    protected void startPlayer() {
        if (isInitialized() && mScreenReceiver.screenOn
                && !vPlayer.isBuffering()) {
            if (!vPlayer.isPlaying()) {
                vPlayer.start();
            }
        }
    }

    /**
     * 停止播放视频
     */
    protected void stopPlayer() {
        if (isInitialized()) {
            vPlayer.stop();
        }
    }

    /**
     * 设置电池信息
     */
    private void setBatteryLevel() {
        if (mMediaController != null)
            mMediaController.setBatteryLevel(mBatteryLevel);
    }

    /**
     * 电池信息的广播接收者
     */
    private class BatteryReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int percent = scale > 0 ? level * 100 / scale : 0;
            if (percent > 100)
                percent = 100;
            mBatteryLevel = String.valueOf(percent) + "%";
            setBatteryLevel();
        }
    }

    // TODO: 16/3/16  UserPresentReceiver
    private class UserPresentReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (isRootActivity()) {
                startPlayer();
            }
        }
    }

    /**
     * 是否在前台运行
     */
    private boolean isRootActivity() {
        return ApplicationUtils.isTopActivity(getApplicationContext(),
                getClass().getName());
    }

    public class HeadsetPlugReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && intent.hasExtra("state")) {
                int state = intent.getIntExtra("state", -1);
                if (state == 0) {
                    mHeadsetPlaying = isPlaying();
                    stopPlayer();
                } else if (state == 1) {
                    if (mHeadsetPlaying)
                        startPlayer();
                }
            }
        };
    }

    /**
     * 屏幕开关的广播
     */
    private class ScreenReceiver extends BroadcastReceiver {
        private boolean screenOn = true;

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                screenOn = false;
                stopPlayer();
            } else if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                screenOn = true;
            }
        }
    }

    /**
     * 视频播放器的初始化设置
     */
    private void loadVPlayerPrefs() {
        if (!isInitialized())
            return;
        vPlayer.setBuffer(VP.DEFAULT_BUF_SIZE);
        vPlayer.setVideoQuality(VP.DEFAULT_VIDEO_QUALITY);
        vPlayer.setDeinterlace(VP.DEFAULT_DEINTERLACE);
        vPlayer.setVolume(VP.DEFAULT_STEREO_VOLUME, VP.DEFAULT_STEREO_VOLUME);
        vPlayer.setSubEncoding(VP.DEFAULT_SUB_ENCODING);
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) mSubtitleContainer
                .getLayoutParams();
        lp.bottomMargin = (int) VP.DEFAULT_SUB_POS;
        mSubtitleContainer.setLayoutParams(lp);
        vPlayer.setSubShown(mSubShown);
        setTextViewStyle(mSubtitleText);
        if (!TextUtils.isEmpty(mSubPath))
            vPlayer.setSubPath(mSubPath);
        if (mVideoView != null && isInitialized())
            setVideoLayout();
    }

    /**
     * 设置OutlineTextView样式
     */
    private void setTextViewStyle(OutlineTextView v) {
        v.setTextColor(VP.DEFAULT_SUB_COLOR);
        v.setTypeface(VP.getTypeface(VP.DEFAULT_TYPEFACE_INT),
                VP.DEFAULT_SUB_STYLE);
        v.setShadowLayer(VP.DEFAULT_SUB_SHADOWRADIUS, 0, 0,
                VP.DEFAULT_SUB_SHADOWCOLOR);
    }

    /**
     * 判断是否初始化成功组件
     */
    private boolean isInitialized() {
        return (mCreated && vPlayer != null && vPlayer.isInitialized());
    }

    private Handler mSubHandler = new Handler() {
        Bundle data;
        String text;
        byte[] pixels;
        int width = 0, height = 0;
        Bitmap bm = null;
        int oldType = SUBTITLE_TEXT;

        @Override
        public void handleMessage(Message msg) {
            data = msg.getData();
            switch (msg.what) {
                case SUBTITLE_TEXT:
                    if (oldType != SUBTITLE_TEXT) {
                        mSubtitleImage.setVisibility(View.GONE);
                        mSubtitleText.setVisibility(View.VISIBLE);
                        oldType = SUBTITLE_TEXT;
                    }
                    text = data.getString(VP.SUB_TEXT_KEY);
                    mSubtitleText.setText(text == null ? "" : text.trim());
                    break;
                case SUBTITLE_BITMAP:
                    if (oldType != SUBTITLE_BITMAP) {
                        mSubtitleText.setVisibility(View.GONE);
                        mSubtitleImage.setVisibility(View.VISIBLE);
                        oldType = SUBTITLE_BITMAP;
                    }
                    pixels = data.getByteArray(VP.SUB_PIXELS_KEY);
                    if (bm == null || width != data.getInt(VP.SUB_WIDTH_KEY)
                            || height != data.getInt(VP.SUB_HEIGHT_KEY)) {
                        width = data.getInt(VP.SUB_WIDTH_KEY);
                        height = data.getInt(VP.SUB_HEIGHT_KEY);
                        bm = Bitmap.createBitmap(width, height,
                                Bitmap.Config.ARGB_8888);
                    }
                    if (pixels != null)
                        bm.copyPixelsFromBuffer(ByteBuffer.wrap(pixels));
                    mSubtitleImage.setImageBitmap(bm);
                    break;
            }
        }
    };

    private AtomicBoolean mOpened = new AtomicBoolean(Boolean.FALSE);
    private boolean mSurfaceCreated = false;
    private boolean mServiceConnected = false;
    private Object mOpenLock = new Object();
    private static final int OPEN_FILE = 0;
    private static final int OPEN_START = 1;
    private static final int OPEN_SUCCESS = 2;
    private static final int OPEN_FAILED = 3;
    private static final int HW_FAILED = 4;
    private static final int LOAD_PREFS = 5;
    private static final int BUFFER_START = 11;
    private static final int BUFFER_PROGRESS = 12;
    private static final int BUFFER_COMPLETE = 13;
    private static final int CLOSE_START = 21;
    private static final int CLOSE_COMPLETE = 22;
    private static final int SUBTITLE_TEXT = 0;
    private static final int SUBTITLE_BITMAP = 1;
    private Handler vPlayerHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case OPEN_FILE:
                    synchronized (mOpenLock) {
                        if (!mOpened.get() && vPlayer != null) {
                            mOpened.set(true);
                            //设置监听视频播放状态的监听器
                            vPlayer.setVPlayerListener(vPlayerListener);
                            //要是视频播放器初始化成功了的话
                            if (vPlayer.isInitialized())
                                //获取他的Uri
                                mUri = vPlayer.getUri();

                            if (mVideoView != null)
                                vPlayer.setDisplay(mVideoView.getHolder());
                            if (mUri != null)
                                //设置服务的初始化状态
                                vPlayer.initialize(mUri, mDisplayName, mSaveUri,
                                        getStartPosition(), vPlayerListener,
                                        mParentId, mIsHWCodec);
                        }
                    }
                    break;
                case OPEN_START:
                    //刚打开的时候
                    mVideoLoadingText.setText(R.string.video_layout_loading);
                    setVideoLoadingLayoutVisibility(View.VISIBLE);
                    break;
                case OPEN_SUCCESS:
                    //打开成功的时候
                    loadVPlayerPrefs();
                    //舍子等待消失
                    setVideoLoadingLayoutVisibility(View.GONE);
                    //设置基本的尺寸。
                    setVideoLayout();
                    //开始播放
                    vPlayer.start();
                    //附加控制器
                    attachMediaController();
                    break;
                case OPEN_FAILED:
                    //打开失败的话
                    resultFinish(RESULT_FAILED);
                    break;
                case BUFFER_START:
                    //开始缓存
                    setVideoLoadingLayoutVisibility(View.VISIBLE);
                    vPlayerHandler.sendEmptyMessageDelayed(BUFFER_PROGRESS, 1000);
                    break;
                case BUFFER_PROGRESS:
                    //缓存进度大于100
                    if (vPlayer.getBufferProgress() >= 100) {
                        setVideoLoadingLayoutVisibility(View.GONE);
                    } else {
                        mVideoLoadingText.setText(getString(
                                R.string.video_layout_buffering_progress,
                                vPlayer.getBufferProgress()));
                        vPlayerHandler.sendEmptyMessageDelayed(BUFFER_PROGRESS,
                                1000);
                        stopPlayer();
                    }
                    break;
                case BUFFER_COMPLETE:
                    //缓存完成
                    setVideoLoadingLayoutVisibility(View.GONE);
                    vPlayerHandler.removeMessages(BUFFER_PROGRESS);
                    break;
                case CLOSE_START:
                    mVideoLoadingText.setText(R.string.closing_file);
                    setVideoLoadingLayoutVisibility(View.VISIBLE);
                    break;
                case CLOSE_COMPLETE:
                    mCloseComplete = true;
                    break;
                case HW_FAILED:
                    if (mVideoView != null) {
                        mVideoView.setVisibility(View.GONE);
                        mVideoView.setVisibility(View.VISIBLE);
                        mVideoView.initialize(MainActivity.this,
                                MainActivity.this, false);
                    }
                    break;
                case LOAD_PREFS:
                    loadVPlayerPrefs();
                    break;
            }
        }
    };

    /**
     * 设置视频缓冲的等待可见不可见
     */
    private void setVideoLoadingLayoutVisibility(int visibility) {
        if (mVideoLoadingLayout != null) {
            mVideoLoadingLayout.setVisibility(visibility);
        }
    }

    /**
     * 视频播放器的基本状态：
     */
    private PlayerService.VPlayerListener vPlayerListener = new PlayerService.VPlayerListener() {
        @Override
        public void onHWRenderFailed() {
            if (Build.VERSION.SDK_INT < 11 && mIsHWCodec) {
                vPlayerHandler.sendEmptyMessage(HW_FAILED);
                vPlayerHandler.sendEmptyMessageDelayed(HW_FAILED, 200);
            }
        }

        @Override
        public void onSubChanged(String sub) {
            Message msg = new Message();
            Bundle b = new Bundle();
            b.putString(VP.SUB_TEXT_KEY, sub);
            msg.setData(b);
            msg.what = SUBTITLE_TEXT;
            mSubHandler.sendMessage(msg);
        }

        @Override
        public void onSubChanged(byte[] pixels, int width, int height) {
            Message msg = new Message();
            Bundle b = new Bundle();
            b.putByteArray(VP.SUB_PIXELS_KEY, pixels);
            b.putInt(VP.SUB_WIDTH_KEY, width);
            b.putInt(VP.SUB_HEIGHT_KEY, height);
            msg.setData(b);
            msg.what = SUBTITLE_BITMAP;
            mSubHandler.sendMessage(msg);
        }

        @Override
        public void onOpenStart() {
            vPlayerHandler.sendEmptyMessage(OPEN_START);
        }

        @Override
        public void onOpenSuccess() {
            vPlayerHandler.sendEmptyMessage(OPEN_SUCCESS);
        }

        @Override
        public void onOpenFailed() {
            vPlayerHandler.sendEmptyMessage(OPEN_FAILED);
        }

        @Override
        public void onBufferStart() {
            vPlayerHandler.sendEmptyMessage(BUFFER_START);
            stopPlayer();
        }

        @Override
        public void onBufferComplete() {
            vPlayerHandler.sendEmptyMessage(BUFFER_COMPLETE);
            if (vPlayer != null && !vPlayer.needResume())
                startPlayer();
        }

        @Override
        public void onPlaybackComplete() {
            mEnd = true;
            if (mLoopCount == 0 || mLoopCount-- > 1) {
                vPlayer.start();
                vPlayer.seekTo(0);
            } else {
                resultFinish(RESULT_OK);
            }
        }

        @Override
        public void onCloseStart() {
            vPlayerHandler.sendEmptyMessage(CLOSE_START);
        }

        @Override
        public void onCloseComplete() {
            vPlayerHandler.sendEmptyMessage(CLOSE_COMPLETE);
        }

        @Override
        public void onVideoSizeChanged(int width, int height) {
            if (mVideoView != null) {
                setVideoLayout();
            }
        }

        @Override
        public void onDownloadRateChanged(int kbPerSec) {
            if (!MediaUtils.isNative(mUri.toString())
                    && mMediaController != null) {
                mMediaController.setDownloadRate(String.format("%dKB/s",
                        kbPerSec));
            }
        }

    };

    private int mVideoMode = VideoView.VIDEO_LAYOUT_SCALE;

    private void setVideoLayout() {
        mVideoView.setVideoLayout(mVideoMode, VP.DEFAULT_ASPECT_RATIO,
                vPlayer.getVideoWidth(), vPlayer.getVideoHeight(),
                vPlayer.getVideoAspectRatio());
    }

    /**
     * 保存播放时候的位置
     */
    private void savePosition() {
        if (vPlayer != null && mUri != null) {
            PreferenceUtils.put(
                    mUri.toString(),
                    StringUtils.generateTime((int) (0.5 + vPlayer
                            .getCurrentPosition()))
                            + " / "
                            + StringUtils.generateTime(vPlayer.getDuration()));
            if (mEnd)
                PreferenceUtils
                        .put(mUri + VP.SESSION_LAST_POSITION_SUFIX, 1.0f);
            else
                PreferenceUtils
                        .put(mUri + VP.SESSION_LAST_POSITION_SUFIX,
                                (float) (vPlayer.getCurrentPosition() / (double) vPlayer
                                        .getDuration()));
        }
    }

    private float getStartPosition() {
        if (mFromStart)
            return 1.1f;
        if (mStartPos <= 0.0f || mStartPos >= 1.0f)
            return PreferenceUtils.getFloat(mUri
                    + VP.SESSION_LAST_POSITION_SUFIX, 7.7f);
        return mStartPos;
        //return 0.0f;
    }

    @Override
    public int getBufferPercentage() {
        //获取缓存的百分之
        if (isInitialized())
            return (int) (vPlayer.getBufferProgress() * 100);
        return 0;
    }

    @Override
    public long getCurrentPosition() {
        //获取当前的位置
        if (isInitialized())
            return vPlayer.getCurrentPosition();
        return (long) (getStartPosition() * vPlayer.getDuration());
    }

    @Override
    public long getDuration() {
        //获取持续时间
        if (isInitialized())
            return vPlayer.getDuration();
        return 0;
    }

    @Override
    public boolean isPlaying() {
        //获取当前是否在播放
        if (isInitialized())
            return vPlayer.isPlaying();
        return false;
    }

    @Override
    public void pause() {
        //暂停播放视频
        if (isInitialized())
            vPlayer.stop();
    }

    @Override
    public void seekTo(long arg0) {
        //跳转到位置播放
        if (isInitialized())
            vPlayer.seekTo((float) ((double) arg0 / vPlayer.getDuration()));
    }

    @Override
    public void start() {
        //开始播放
        if (isInitialized())
            vPlayer.start();
    }

    @Override
    public void previous() {
    }

    @Override
    public void next() {
    }

    private static final int VIDEO_MAXIMUM_HEIGHT = 2048;
    private static final int VIDEO_MAXIMUM_WIDTH = 2048;

    @Override
    public float scale(float scaleFactor) {
        float userRatio = VP.DEFAULT_ASPECT_RATIO;
        int videoWidth = vPlayer.getVideoWidth();
        int videoHeight = vPlayer.getVideoHeight();
        float videoRatio = vPlayer.getVideoAspectRatio();
        float currentRatio = mVideoView.mVideoHeight / (float) videoHeight;

        currentRatio += (scaleFactor - 1);
        if (videoWidth * currentRatio >= VIDEO_MAXIMUM_WIDTH)
            currentRatio = VIDEO_MAXIMUM_WIDTH / (float) videoWidth;

        if (videoHeight * currentRatio >= VIDEO_MAXIMUM_HEIGHT)
            currentRatio = VIDEO_MAXIMUM_HEIGHT / (float) videoHeight;

        if (currentRatio < 0.5f)
            currentRatio = 0.5f;

        mVideoView.mVideoHeight = (int) (videoHeight * currentRatio);
        mVideoView.setVideoLayout(mVideoMode, userRatio, videoWidth,
                videoHeight, videoRatio);
        return currentRatio;
    }

    @SuppressLint("SimpleDateFormat")
    @Override
    public void snapshot() {
        // TODO: 16/3/16 截图的相关操作
        if (!com.vanco.abplayer.FileUtils.sdAvailable()) {
          Toast.makeText(MainActivity.this,"文件存储路径不存在",Toast.LENGTH_SHORT).show();
        } else {
            Uri imgUri = null;
            Bitmap bitmap = vPlayer.getCurrentFrame();
            if (bitmap != null) {
                File screenshotsDirectory = new File(
                        Environment
                                .getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                                + VP.SNAP_SHOT_PATH);
                if (!screenshotsDirectory.exists()) {
                    screenshotsDirectory.mkdirs();
                }

                File savePath = new File(
                        screenshotsDirectory.getPath()
                                + "/"
                                + new SimpleDateFormat("yyyyMMddHHmmss")
                                .format(new Date()) + ".jpg");
                if (ImageUtils.saveBitmap(savePath.getPath(), bitmap)) {
                    imgUri = Uri.fromFile(savePath);
                }
            }
            if (imgUri != null) {
                sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                        imgUri));
                Toast.makeText(MainActivity.this,R.string.video_screenshot_save_in,Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(MainActivity.this,R.string.video_screenshot_failed,Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void toggleVideoMode(int mode) {
        mVideoMode = mode;
        setVideoLayout();
    }

    @Override
    public void stop() {
        onBackPressed();
    }

    @Override
    public long goForward() {
        return 0;
    }

    @Override
    public long goBack() {
        return 0;
    }

    @Override
    public void removeLoadingView() {
        mVideoLoadingLayout.setVisibility(View.GONE);
    }

    @Override
    public void onSurfaceCreated(SurfaceHolder holder) {

        mSurfaceCreated = true;
        if (mServiceConnected)
            vPlayerHandler.sendEmptyMessage(OPEN_FILE);
        if (vPlayer != null)
            vPlayer.setDisplay(holder);
    }

    @Override
    public void onSurfaceChanged(SurfaceHolder holder, int format, int width,
                                 int height) {
        if (vPlayer != null) {
            setVideoLayout();
        }
    }

    @Override
    public void onSurfaceDestroyed(SurfaceHolder holder) {

        if (vPlayer != null && vPlayer.isInitialized()) {
            if (vPlayer.isPlaying()) {
                vPlayer.stop();
                vPlayer.setState(PlayerService.STATE_NEED_RESUME);
            }
            vPlayer.releaseSurface();
            if (vPlayer.needResume())
                vPlayer.start();
        }
    }

    @Override
    public void setDanmakushow(boolean isShow) {
        //弹幕不做操作

    }
}
