package org.wheelmap.android.tango;


import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.databinding.DataBindingUtil;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.support.annotation.MainThread;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.View;
import android.widget.Toast;

import com.google.atap.tango.ux.CustomTangoUxLayout;
import com.google.atap.tango.ux.TangoUx;
import com.google.atap.tango.ux.UxExceptionEvent;
import com.google.atap.tango.ux.UxExceptionEventListener;
import com.google.atap.tangoservice.Tango;
import com.google.atap.tangoservice.TangoCameraIntrinsics;
import com.google.atap.tangoservice.TangoConfig;
import com.google.atap.tangoservice.TangoCoordinateFramePair;
import com.google.atap.tangoservice.TangoErrorException;
import com.google.atap.tangoservice.TangoEvent;
import com.google.atap.tangoservice.TangoInvalidException;
import com.google.atap.tangoservice.TangoOutOfDateException;
import com.google.atap.tangoservice.TangoPointCloudData;
import com.google.atap.tangoservice.TangoPoseData;
import com.google.atap.tangoservice.TangoXyzIjData;
import com.google.auto.value.AutoValue;
import com.jakewharton.rxrelay.PublishRelay;
import com.projecttango.tangosupport.TangoPointCloudManager;
import com.projecttango.tangosupport.TangoSupport;

import org.rajawali3d.math.Matrix4;
import org.rajawali3d.math.vector.Vector3;
import org.wheelmap.android.activity.base.BaseActivity;
import org.wheelmap.android.model.Prefs;
import org.wheelmap.android.online.BuildConfig;
import org.wheelmap.android.online.R;
import org.wheelmap.android.online.databinding.TangoActivityBinding;
import org.wheelmap.android.tango.mode.Mode;
import org.wheelmap.android.tango.renderer.TangoRajawaliRenderer;
import org.wheelmap.android.tango.renderer.WheelmapModeRenderer;
import org.wheelmap.android.tango.renderer.WheelmapTangoRajawaliRenderer;
import org.wheelmap.android.utils.Arguments;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import rx.android.schedulers.AndroidSchedulers;
import rx.functions.Action1;
import rx.functions.Func1;

@TargetApi(19)
public class TangoMeasureActivity extends BaseActivity {

    private static final String TAG = TangoMeasureActivity.class.getSimpleName();
    private static final int INVALID_TEXTURE_ID = 0;

    private TangoMeasurePresenter presenter;
    private Tango tango;
    private TangoUx tangoUx;
    private TangoActivityBinding binding;
    private boolean isConnected = false;
    private AtomicBoolean isFrameAvailableTangoThread = new AtomicBoolean(false);
    private TangoPointCloudManager pointCloudManager = new TangoPointCloudManager();
    // Texture rendering related fields
    // NOTE: Naming indicates which thread is in charge of updating this variable
    private int connectedTextureIdGlThread = INVALID_TEXTURE_ID;

    private WheelmapTangoRajawaliRenderer renderer;
    private WheelmapModeRenderer modeRenderer;
    private TangoCameraIntrinsics intrinsics;
    private double rgbTimestampGlThread;
    private double cameraPoseTimestamp;

    private PublishRelay<Vector3> measurementStatusRelay = PublishRelay.create();
    private int displayRotation;

    public static Intent newIntent(Context context, long wmId) {
        Intent intent = new Intent(context, TangoMeasureActivity.class);
        intent.putExtras(new AutoValue_TangoMeasureActivity_Args(wmId).toBundle());
        return intent;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.tango_activity);

        renderer = new WheelmapTangoRajawaliRenderer(this);
        tangoUx = setupTangoUx(savedInstanceState);
        connectRenderer();
        setupUiOverlay();
        presenter = new TangoMeasurePresenter(this);

        DisplayManager displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        if (displayManager != null) {
            displayManager.registerDisplayListener(new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                }

                @Override
                public void onDisplayChanged(int displayId) {
                    synchronized (this) {
                        setDisplayRotation();
                    }
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                }
            }, null);
        }
    }

    private void setupUiOverlay() {
        binding.fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                presenter.onFabClicked();
            }
        });
        binding.undo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                presenter.undo();
            }
        });
        binding.clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                presenter.clear();
            }
        });

        binding.homeAsUp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        List<ModeSelectionView.Item> itemList = new ArrayList<>();
        for (Mode mode : Mode.values()) {
            itemList.add(ModeSelectionView.Item.create(mode.title(), mode.icon(), mode));
        }
        binding.modeSelection.setItems(itemList);
        binding.modeSelection.setOnItemSelectionListener(new ModeSelectionView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(ModeSelectionView.Item item) {
                Mode mode = (Mode) item.tag();
                presenter.onModeSelected(mode);
                setMode(mode, true);
            }
        });
        setFabStatus(FabStatus.ADD_NEW);

        binding.helpFab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showHelp((Mode) binding.modeSelection.getSelectedItem().tag());
            }
        });
    }

    void setMode(Mode mode, boolean showHelp) {
        binding.modeSelection.setSelectedItemTag(mode);
        if (showHelp) {
            showHelp(mode);
        }
    }

    public Mode getMode() {
        return (Mode) binding.modeSelection.getSelectedItem().tag();
    }

    private void showHelp(Mode mode) {
        Intent intent = TangoTutorialActivity.newIntent(this, mode);
        startActivity(intent);
    }

    @MainThread
    void setFabStatus(FabStatus status) {
        Log.d(TAG, "setFabStatus(" + status + ")");
        switch (status) {
            case ADD_NEW:
                binding.fab.setImageResource(R.drawable.ic_fab_plus);
                binding.fab.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary_color)));
                break;
            case READY:
                binding.fab.setImageResource(R.drawable.ic_camera);
                binding.fab.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.green_700)));
                break;
        }
    }

    private TangoUx setupTangoUx(Bundle savedInstanceState) {
        TangoUx tangoUx = new TangoUx(this);
        tangoUx.setLayout(binding.tangoUxLayout);
        tangoUx.setUxExceptionEventListener(new UxExceptionEventListener() {
            @Override
            public void onUxExceptionEvent(UxExceptionEvent uxExceptionEvent) {
                if (uxExceptionEvent.getType() == UxExceptionEvent.TYPE_LYING_ON_SURFACE) {
                    Log.w(TAG, "Device lying on surface ");
                }
                if (uxExceptionEvent.getType() == UxExceptionEvent.TYPE_FEW_DEPTH_POINTS) {
                    Log.w(TAG, "Very few depth points in mPoint cloud ");
                }
                if (uxExceptionEvent.getType() == UxExceptionEvent.TYPE_FEW_FEATURES) {
                    Log.w(TAG, "Invalid poses in MotionTracking ");
                }
                if (uxExceptionEvent.getType() == UxExceptionEvent.TYPE_INCOMPATIBLE_VM) {
                    Log.w(TAG, "Device not running on ART");
                }
                if (uxExceptionEvent.getType() == UxExceptionEvent.TYPE_MOTION_TRACK_INVALID) {
                    Log.w(TAG, "Invalid poses in MotionTracking ");
                }
                if (uxExceptionEvent.getType() == UxExceptionEvent.TYPE_MOVING_TOO_FAST) {
                    Log.w(TAG, "Invalid poses in MotionTracking ");
                }
                if (uxExceptionEvent.getType() == UxExceptionEvent.TYPE_OVER_EXPOSED) {
                    Log.w(TAG, "Camera Over Exposed");
                }
                if (uxExceptionEvent.getType() == UxExceptionEvent.TYPE_TANGO_SERVICE_NOT_RESPONDING) {
                    Log.w(TAG, "TangoService is not responding ");
                }
                if (uxExceptionEvent.getType() == UxExceptionEvent.TYPE_UNDER_EXPOSED) {
                    Log.w(TAG, "Camera Under Exposed ");
                }
            }
        });

        if (savedInstanceState == null) {
            binding.tangoUxLayout.connectionStatusObservable()
                    .filter(new Func1<CustomTangoUxLayout.ConnectionStatus, Boolean>() {
                        @Override
                        public Boolean call(CustomTangoUxLayout.ConnectionStatus connectionStatus) {
                            return connectionStatus == CustomTangoUxLayout.ConnectionStatus.HIDE;
                        }
                    })
                    .take(1)
                    .delay(500, TimeUnit.MILLISECONDS)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new Action1<CustomTangoUxLayout.ConnectionStatus>() {
                        @Override
                        public void call(CustomTangoUxLayout.ConnectionStatus connectionStatus) {
                            showHelp(getMode());
                        }
                    });

        }

        return tangoUx;
    }

    @Override
    protected void onStart() {
        super.onStart();

        tangoUx.start(new TangoUx.StartParams());
        tango = new Tango(this, new Runnable() {
            @Override
            public void run() {
                // Synchronize against disconnecting while the service is being used in the
                // OpenGL thread or in the UI thread.
                synchronized (TangoMeasureActivity.this) {
                    try {
                        TangoSupport.initialize();
                        TangoConfig config = setupTangoConfig(tango);

                        tango.connect(config);
                        setTangoListeners();

                        isConnected = true;
                        setDisplayRotation();
                    } catch (TangoOutOfDateException e) {
                        if (tangoUx != null) {
                            tangoUx.showTangoOutOfDate();
                        }
                    } catch (TangoErrorException e) {
                        Log.e(TAG, getString(R.string.tango_error), e);
                        showsToastAndFinishOnUiThread(R.string.tango_error);
                    } catch (TangoInvalidException e) {
                        Log.e(TAG, getString(R.string.tango_invalid), e);
                        showsToastAndFinishOnUiThread(R.string.tango_invalid);
                    }
                }
            }
        });

    }

    /**
     * Display toast on UI thread.
     *
     * @param resId The resource id of the string resource to use. Can be formatted text.
     */
    private void showsToastAndFinishOnUiThread(final int resId) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(TangoMeasureActivity.this,
                        getString(resId), Toast.LENGTH_LONG).show();
                finish();
            }
        });
    }

    /**
     * Sets up the Tango configuration object. Make sure mTango object is initialized before
     * making this call.
     */
    private TangoConfig setupTangoConfig(Tango tango) {
        // Use default configuration for Tango Service (motion tracking), plus low latency
        // IMU integration, color camera, depth and drift correction.
        TangoConfig config = tango.getConfig(TangoConfig.CONFIG_TYPE_DEFAULT);
        // NOTE: Low latency integration is necessary to achieve a precise alignment of
        // virtual objects with the RGB image and produce a good AR effect.
        config.putBoolean(TangoConfig.KEY_BOOLEAN_LOWLATENCYIMUINTEGRATION, true);
        config.putBoolean(TangoConfig.KEY_BOOLEAN_COLORCAMERA, true);
        config.putBoolean(TangoConfig.KEY_BOOLEAN_DEPTH, true);
        config.putInt(TangoConfig.KEY_INT_DEPTH_MODE, TangoConfig.TANGO_DEPTH_MODE_POINT_CLOUD);
        // Drift correction allows motion tracking to recover after it loses tracking.
        // The drift-corrected pose is available through the frame pair with
        // base frame AREA_DESCRIPTION and target frame DEVICE.
        config.putBoolean(TangoConfig.KEY_BOOLEAN_DRIFT_CORRECTION, true);

        return config;
    }

    /**
     * Set the color camera background texture rotation and save the camera to display rotation.
     */
    private void setDisplayRotation() {
        Display display = getWindowManager().getDefaultDisplay();
        displayRotation = display.getRotation();

        // We also need to update the camera texture UV coordinates. This must be run in the OpenGL
        // thread.
        binding.surfaceView.queueEvent(new Runnable() {
            @Override
            public void run() {
                if (isConnected) {
                    renderer.updateColorCameraTextureUvGlThread(displayRotation);
                }
            }
        });
    }


    @Override
    protected void onStop() {
        super.onStop();
        // Synchronize against disconnecting while the service is being used in the OpenGL thread or
        // in the UI thread.
        synchronized (this) {
            if (isConnected) {
                // renderer.getCurrentScene().clearFrameCallbacks();
                tango.disconnectCamera(TangoCameraIntrinsics.TANGO_CAMERA_COLOR);

                // We need to invalidate the connected texture ID so that we cause a re-connection
                // in the OpenGL thread after resume
                connectedTextureIdGlThread = INVALID_TEXTURE_ID;
                tango.disconnect();
                isConnected = false;
                tangoUx.stop();
            }
        }
    }

    private void setTangoListeners() {
        ArrayList<TangoCoordinateFramePair> framePairs = new ArrayList<>();
        framePairs.add(new TangoCoordinateFramePair(TangoPoseData.COORDINATE_FRAME_START_OF_SERVICE,
                TangoPoseData.COORDINATE_FRAME_DEVICE));
        tango.connectListener(framePairs, new Tango.OnTangoUpdateListener() {
            @Override
            public void onPoseAvailable(TangoPoseData pose) {
                // Passing in the pose data to UX library produce exceptions.
                if (tangoUx != null) {
                    tangoUx.updatePoseStatus(pose.statusCode);
                }
            }

            @Override
            public void onXyzIjAvailable(TangoXyzIjData xyzIj) {
                if (tangoUx != null) {
                    tangoUx.updateXyzCount(xyzIj.xyzCount);
                }
            }

            @Override
            public void onTangoEvent(TangoEvent event) {
                if (tangoUx != null) {
                    tangoUx.updateTangoEvent(event);
                }
            }

            @Override
            public void onPointCloudAvailable(TangoPointCloudData tangoPointCloudData) {
                pointCloudManager.updatePointCloud(tangoPointCloudData);
            }

            @Override
            public void onFrameAvailable(int cameraId) {
                // Check if the frame available is for the camera we want and update its frame
                // on the view.
                if (cameraId == TangoCameraIntrinsics.TANGO_CAMERA_COLOR) {
                    // Mark a camera frame is available for rendering in the OpenGL thread
                    isFrameAvailableTangoThread.set(true);
                    binding.surfaceView.requestRender();
                }
            }
        });
        intrinsics = tango.getCameraIntrinsics(TangoCameraIntrinsics.TANGO_CAMERA_COLOR);
    }

    private void connectRenderer() {
        binding.surfaceView.setEGLContextClientVersion(2);
        renderer.getCurrentScene().registerFrameCallback(new SimpleASceneFrameCallback.PreFrameCallback() {
            @Override
            public void onPreFrame(long sceneTime, double deltaTime) {
                // NOTE: This is called from the OpenGL render thread, after all the renderer
                // onRender callbacks had a chance to run and before scene objects are rendered
                // into the scene.

                try {
                    synchronized (TangoMeasureActivity.this) {
                        // Don't execute any tango API actions if we're not connected to the service
                        if (!isConnected) {
                            return;
                        }

                        if (intrinsics == null) {
                            intrinsics = tango.getCameraIntrinsics(TangoCameraIntrinsics.TANGO_CAMERA_COLOR);
                        }

                        // Set-up scene camera projection to match RGB camera intrinsics
                        if (!renderer.isSceneCameraConfigured()) {
                            TangoCameraIntrinsics intrinsics =
                                    TangoSupport.getCameraIntrinsicsBasedOnDisplayRotation(
                                            TangoCameraIntrinsics.TANGO_CAMERA_COLOR,
                                            displayRotation);
                            renderer.setProjectionMatrix(
                                    TangoUtils.projectionMatrixFromCameraIntrinsics(intrinsics));
                        }

                        // Connect the camera texture to the OpenGL Texture if necessary
                        // NOTE: When the OpenGL context is recycled, Rajawali may re-generate the
                        // texture with a different ID.
                        if (connectedTextureIdGlThread != renderer.getTextureId()) {
                            tango.connectTextureId(TangoCameraIntrinsics.TANGO_CAMERA_COLOR,
                                    renderer.getTextureId());
                            connectedTextureIdGlThread = renderer.getTextureId();
                            Log.d(TAG, "connected to texture id: " + renderer.getTextureId());
                        }

                        // If there is a new RGB camera frame available, update the texture with it
                        if (isFrameAvailableTangoThread.compareAndSet(true, false)) {
                            rgbTimestampGlThread =
                                    tango.updateTexture(TangoCameraIntrinsics.TANGO_CAMERA_COLOR);
                        }

                        if (rgbTimestampGlThread > cameraPoseTimestamp) {
                            // Calculate the camera color pose at the camera frame update time in
                            // OpenGL engine.
                            //
                            // When drift correction mode is enabled in config file, we need
                            // to query the device with respect to Area Description pose in
                            // order to use the drift-corrected pose.
                            //
                            // Note that if you don't want to use the drift-corrected pose, the
                            // normal device with respect to start of service pose is still
                            // available.
                            TangoPoseData lastFramePose = TangoSupport.getPoseAtTime(
                                    rgbTimestampGlThread,
                                    TangoPoseData.COORDINATE_FRAME_AREA_DESCRIPTION,
                                    TangoPoseData.COORDINATE_FRAME_CAMERA_COLOR,
                                    TangoSupport.TANGO_SUPPORT_ENGINE_OPENGL,
                                    displayRotation);
                            if (lastFramePose.statusCode == TangoPoseData.POSE_VALID) {
                                // Update the camera pose from the renderer.
                                renderer.updateRenderCameraPose(lastFramePose);
                                cameraPoseTimestamp = lastFramePose.timestamp;
                            } else {
                                // When the pose status is not valid, it indicates the tracking has
                                // been lost. In this case, we simply stop rendering.
                                //
                                // This is also the place to display UI to suggest that the user
                                // walk to recover tracking.
                                Log.w(TAG, "Can't get device pose at time: " +
                                        rgbTimestampGlThread);
                            }
                        }
                    }
                    // Avoid crashing the application due to unhandled exceptions
                } catch (TangoErrorException e) {
                    Log.e(TAG, "Tango API call error within the OpenGL render thread", e);
                } catch (Throwable t) {
                    Log.e(TAG, "Exception on the OpenGL thread", t);
                }
            }
        });

        renderer.getCurrentScene().registerFrameCallback(new SimpleASceneFrameCallback.PreFrameCallback() {
            @Override
            public void onPreFrame(long sceneTime, double deltaTime) {

                // Don't execute any tango API actions if we're not connected to the service
                if (!isConnected) {
                    return;
                }

                synchronized (TangoMeasureActivity.this) {
                    try {
                        float[] planeFitTransform = doFitPlane(0.5f, 0.5f);
                        Matrix4 transform = new Matrix4(planeFitTransform);
                        final Vector3 position = transform.getTranslation();
                        measurementStatusRelay.call(position);
                    } catch (Exception e) {
                        measurementStatusRelay.call(null);
                    }
                }
            }
        });
        binding.surfaceView.setSurfaceRenderer(renderer);

        measurementStatusRelay
                .sample(1, TimeUnit.SECONDS)
                .distinctUntilChanged()
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext(new Action1<Vector3>() {
                    @Override
                    public void call(Vector3 position) {
                        if (position != null) {
                            binding.centerCross.setEnabled(true);

                            if (BuildConfig.BUILD_TYPE.equals("debug")) {
                                // for debug purposes
                                final String text = String.format(Locale.ENGLISH, "x: %.2f, y: %.2f, z: %.2f", position.x, position.y, position.z);
                                binding.currentPointerPosition.setText(text);
                                binding.currentPointerPosition.setTextColor(Color.BLACK);
                            } else {
                                binding.currentPointerPosition.setText("");
                            }
                        } else {
                            binding.centerCross.setEnabled(false);
                            binding.currentPointerPosition.setText(R.string.failed_measurement);
                            binding.currentPointerPosition.setTextColor(Color.RED);
                        }
                    }
                }).subscribe();
    }

    public float[] doFitPlane(float u, float v) {
        synchronized (this) {
            return TangoPointCloudUtils.doFitPlane(pointCloudManager, displayRotation, u, v, rgbTimestampGlThread);
        }
    }

    public void setWheelmapModeRenderer(WheelmapModeRenderer modeRenderer) {
        this.modeRenderer = modeRenderer;
        if (this.renderer != null) {
            this.renderer.setModeRenderer(modeRenderer);
        }
    }

    void captureScreenshot(final TangoRajawaliRenderer.ScreenshotCaptureListener listener) {
        renderer.captureScreenshot(new TangoRajawaliRenderer.ScreenshotCaptureListener() {
            @Override
            public void onScreenshotCaptured(final Bitmap bitmap) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        listener.onScreenshotCaptured(bitmap);
                    }
                }).start();
            }
        });
    }

    public Args getArgs() {
        return Args.fromBundle(getIntent().getExtras());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        presenter.onActivityResult(requestCode, resultCode, data);
    }

    enum FabStatus {
        READY,
        ADD_NEW
    }

    @AutoValue
    public abstract static class Args extends Arguments {
        public abstract long wmId();
    }

}
