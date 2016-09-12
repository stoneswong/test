package com.limelight;

import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import com.limelight.LimelightBuildProps;
import com.limelight.binding.PlatformBinding;
import com.limelight.binding.input.ControllerHandler;
import com.limelight.binding.input.KeyboardTranslator;
import com.limelight.binding.input.TouchContext;
import com.limelight.binding.input.evdev.EvdevListener;
import com.limelight.binding.input.evdev.EvdevWatcher;
import com.limelight.binding.video.ConfigurableDecoderRenderer;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.NvConnectionListener;
import com.limelight.nvstream.StreamConfiguration;
import com.limelight.nvstream.av.video.VideoDecoderRenderer;
import com.limelight.nvstream.input.KeyboardPacket;
import com.limelight.nvstream.input.MouseButtonPacket;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.Dialog;
import com.limelight.utils.SpinnerDialog;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Point;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnGenericMotionListener;
import android.view.View.OnSystemUiVisibilityChangeListener;
import android.view.View.OnTouchListener;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;


public class Game extends Activity implements SurfaceHolder.Callback,
	OnGenericMotionListener, OnTouchListener, NvConnectionListener, EvdevListener,
	OnSystemUiVisibilityChangeListener
{
	private int lastMouseX = Integer.MIN_VALUE;
	private int lastMouseY = Integer.MIN_VALUE;
	private int lastButtonState = 0;
	
	// Only 2 touches are supported
	private TouchContext[] touchContextMap = new TouchContext[2];
	
	private ControllerHandler controllerHandler;
	private KeyboardTranslator keybTranslator;
	
	private PreferenceConfiguration prefConfig;
	private Point screenSize = new Point(0, 0);
	
	private NvConnection conn;
	private HTConnection htConn;
	private SpinnerDialog spinner;
	private boolean displayedFailureDialog = false;
	private boolean connecting = false;
	private boolean connected = false;
	
	private EvdevWatcher evdevWatcher;
	private int modifierFlags = 0;
	private boolean grabbedInput = true;
	private boolean grabComboDown = false;
	
	private ConfigurableDecoderRenderer decoderRenderer;
	
	private WifiManager.WifiLock wifiLock;
	
	private int drFlags = 0;
	
	public static final String EXTRA_HOST = "Host";
	public static final String EXTRA_APP = "App";
	public static final String EXTRA_UNIQUEID = "UniqueId";
	public static final String EXTRA_STREAMING_REMOTE = "Remote";

	private SensorManager sm;
	private Timer htReportTimer; 
	
	private static final float NS2S = 1.0f / 1000000000.0f;
    private static final float EPSILON = 0.01f;

    private float[] deltaRotationVector = new float[4];
    private float[] rotationMatrixCurrent = new float[9];
    private float[] rotationMatrixOriginal = new float[9];
	private float[] gyroAngle = new float[3];
	private long gyroLastTime = 0;
	
	private float[] magnetLastValues = new float[3];
	private long magnetLastTime = 0;
	
	private final SensorEventListener gyroListener = new SensorEventListener() {
		
		@Override
		public void onSensorChanged(SensorEvent event) {
			if (gyroLastTime != 0) {

				final float dT = (event.timestamp - gyroLastTime) * NS2S;

                float axisX = event.values[2];
                float axisY = event.values[1];
                float axisZ = -event.values[0];

                float omegaMagnitude = (float) Math.sqrt(axisX*axisX + axisY*axisY + axisZ*axisZ);

                if (omegaMagnitude > EPSILON) {
                    axisX /= omegaMagnitude;
                    axisY /= omegaMagnitude;
                    axisZ /= omegaMagnitude;
                }

                float thetaOverTwo = omegaMagnitude * dT / 2.0f;
                float sinThetaOverTwo = (float) Math.sin(thetaOverTwo);
                float cosThetaOverTwo = (float) Math.cos(thetaOverTwo);

                deltaRotationVector[0] = sinThetaOverTwo * axisX;
                deltaRotationVector[1] = sinThetaOverTwo * axisY;
                deltaRotationVector[2] = sinThetaOverTwo * axisZ;
                deltaRotationVector[3] = cosThetaOverTwo;
			}
			gyroLastTime = event.timestamp;

            float[] deltaRotationMatrix = new float[9];
            SensorManager.getRotationMatrixFromVector(deltaRotationMatrix, deltaRotationVector);

            rotationMatrixCurrent = multiplication(rotationMatrixCurrent, deltaRotationMatrix);

            SensorManager.getAngleChange(gyroAngle, rotationMatrixCurrent, rotationMatrixOriginal);

        }
		
		@Override
		public void onAccuracyChanged(Sensor sensor, int accuracy) {
			
		}
	};
	
	private final SensorEventListener magnetListener = new SensorEventListener() {
		
		@Override
		public void onSensorChanged(SensorEvent event) {
			
			float dWb[] = {0,0,0};
			
			if (magnetLastTime != 0) {
				
				final float dT = (event.timestamp - magnetLastTime) * NS2S;
				
				if (dT > 0) {
					
					dWb[0] = (event.values[0] - magnetLastValues[0]) / dT;
					dWb[1] = (event.values[1] - magnetLastValues[1]) / dT;
					dWb[2] = (event.values[2] - magnetLastValues[2]) / dT;
					
				}
			}
			magnetLastTime = event.timestamp;
			magnetLastValues[0] = event.values[0];
			magnetLastValues[1] = event.values[1];
			magnetLastValues[2] = event.values[2];
			
			if ((dWb[0] > 500) || (dWb[1] > 500) || (dWb[2] > 500)
					|| (dWb[0] < -500) || (dWb[1] < -500) || (dWb[2] < -500)) {

				//reset angle and position
                rotationMatrixCurrent[0] = 1.0f; rotationMatrixCurrent[1] = 0.0f; rotationMatrixCurrent[2] = 0.0f;
                rotationMatrixCurrent[3] = 0.0f; rotationMatrixCurrent[4] = 1.0f; rotationMatrixCurrent[5] = 0.0f;
                rotationMatrixCurrent[6] = 0.0f; rotationMatrixCurrent[7] = 0.0f; rotationMatrixCurrent[8] = 1.0f;

                rotationMatrixOriginal[0] = 1.0f; rotationMatrixOriginal[1] = 0.0f; rotationMatrixOriginal[2] = 0.0f;
                rotationMatrixOriginal[3] = 0.0f; rotationMatrixOriginal[4] = 1.0f; rotationMatrixOriginal[5] = 0.0f;
                rotationMatrixOriginal[6] = 0.0f; rotationMatrixOriginal[7] = 0.0f; rotationMatrixOriginal[8] = 1.0f;

            }
		}
		
		@Override
		public void onAccuracyChanged(Sensor sensor, int accuracy) {
			
		}
	};

    private float calculateSingleResult(float[] matrixa, float[] matrixb, int row, int col) {

        float result = 0;

        for (int k = 0; k < 3; k++) {

            result += matrixa[row * 3 + k] * matrixb[k * 3 + col];

        }

        return result;
    }

    private float[] multiplication(float[] matrixa, float[] matrixb) {

        if ((matrixa.length == 9) && (matrixb.length == 9)) {

            float[] result = new float[9];

            for (int i = 0; i < 3; i++) {

                for (int j = 0; j < 3; j++) {

                    result[i * 3 + j] = calculateSingleResult(matrixa, matrixb, i, j);

                }

            }

            return result;

        }
        else{

            return null;

        }
    }
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		
		// We don't want a title bar
		requestWindowFeature(Window.FEATURE_NO_TITLE);
		
		// Full-screen and don't let the display go off
		getWindow().addFlags(
				WindowManager.LayoutParams.FLAG_FULLSCREEN |
				WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		
		// If we're going to use immersive mode, we want to have
		// the entire screen
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
			getWindow().getDecorView().setSystemUiVisibility(
					View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
					View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
					View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
			
			getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
		}
		
		// Listen for UI visibility events
		getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(this);
		
		// Change volume button behavior
		setVolumeControlStream(AudioManager.STREAM_MUSIC);
		
		// Inflate the content
		setContentView(R.layout.activity_game);
		
		// Start the spinner
		spinner = SpinnerDialog.displayDialog(this, getResources().getString(R.string.conn_establishing_title),
				getResources().getString(R.string.conn_establishing_msg), true);
		
		// Read the stream preferences
		prefConfig = PreferenceConfiguration.readPreferences(this);
		switch (prefConfig.decoder) {
		case PreferenceConfiguration.FORCE_SOFTWARE_DECODER:
			drFlags |= VideoDecoderRenderer.FLAG_FORCE_SOFTWARE_DECODING;
			break;
		case PreferenceConfiguration.AUTOSELECT_DECODER:
			break;
		case PreferenceConfiguration.FORCE_HARDWARE_DECODER:
			drFlags |= VideoDecoderRenderer.FLAG_FORCE_HARDWARE_DECODING;
			break;
		}
		
		if (prefConfig.stretchVideo) {
			drFlags |= VideoDecoderRenderer.FLAG_FILL_SCREEN;
		}
		
		Display display = getWindowManager().getDefaultDisplay();
		display.getSize(screenSize);
		
		// Listen for events on the game surface
		SurfaceView sv = (SurfaceView) findViewById(R.id.surfaceView);
		sv.setOnGenericMotionListener(this);
		sv.setOnTouchListener(this);
		        
		// Warn the user if they're on a metered connection
		checkDataConnection();
		
		// Make sure Wi-Fi is fully powered up
		WifiManager wifiMgr = (WifiManager) getSystemService(Context.WIFI_SERVICE);
		wifiLock = wifiMgr.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Limelight");
		wifiLock.setReferenceCounted(false);
		wifiLock.acquire();
		
		String host = Game.this.getIntent().getStringExtra(EXTRA_HOST);
		String app = Game.this.getIntent().getStringExtra(EXTRA_APP);
		String uniqueId = Game.this.getIntent().getStringExtra(EXTRA_UNIQUEID);
        boolean remote = Game.this.getIntent().getBooleanExtra(EXTRA_STREAMING_REMOTE, false);
		
		decoderRenderer = new ConfigurableDecoderRenderer();
		decoderRenderer.initializeWithFlags(drFlags);
        
		StreamConfiguration config = new StreamConfiguration.Builder()
                .setResolution(prefConfig.width, prefConfig.height)
                .setRefreshRate(prefConfig.fps)
                .setApp(app)
                .setBitrate(prefConfig.bitrate * 1000)
                .setEnableSops(prefConfig.enableSops)
                .enableAdaptiveResolution((decoderRenderer.getCapabilities() &
                        VideoDecoderRenderer.CAPABILITY_ADAPTIVE_RESOLUTION) != 0)
                .enableLocalAudioPlayback(prefConfig.playHostAudio)
                .setMaxPacketSize(remote ? 1024 : 1292)
                .setRemote(remote)
                .build();

		// Initialize the connection
		conn = new NvConnection(host, uniqueId, Game.this, config, PlatformBinding.getCryptoProvider(this));
		htConn = new HTConnection(host);
		keybTranslator = new KeyboardTranslator(conn);
		controllerHandler = new ControllerHandler(conn, prefConfig.deadzonePercentage);
		
		SurfaceHolder sh = sv.getHolder();
		if (prefConfig.stretchVideo || !decoderRenderer.isHardwareAccelerated()) {
			// Set the surface to the size of the video
			sh.setFixedSize(prefConfig.width, prefConfig.height);
		}
		
		// Initialize touch contexts
		for (int i = 0; i < touchContextMap.length; i++) {
			touchContextMap[i] = new TouchContext(conn, i,
                    ((double)prefConfig.width / (double)screenSize.x),
                    ((double)prefConfig.height / (double)screenSize.y));
		}
		
		if (LimelightBuildProps.ROOT_BUILD) {
			// Start watching for raw input
			evdevWatcher = new EvdevWatcher(this);
			evdevWatcher.start();
		}
		
		// The connection will be started when the surface gets created
		sh.addCallback(this);
		
		// get sensor manager
		sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
		  
	}
	
	private void resizeSurfaceWithAspectRatio(SurfaceView sv, double vidWidth, double vidHeight)
	{
		// Get the visible width of the activity
	    double visibleWidth = getWindow().getDecorView().getWidth();
	    
	    ViewGroup.LayoutParams lp = sv.getLayoutParams();
	    
	    // Calculate the new size of the SurfaceView
	    lp.width = (int) visibleWidth;
	    lp.height = (int) ((vidHeight / vidWidth) * visibleWidth);

	    // Apply the size change
	    sv.setLayoutParams(lp);
	}
	
	private void checkDataConnection()
	{
		ConnectivityManager mgr = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
		if (mgr.isActiveNetworkMetered()) {
			displayTransientMessage(getResources().getString(R.string.conn_metered));
		}
	}

	@SuppressLint("InlinedApi")
	private Runnable hideSystemUi = new Runnable() {
			@Override
			public void run() {
				// Use immersive mode on 4.4+ or standard low profile on previous builds
				if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
					Game.this.getWindow().getDecorView().setSystemUiVisibility(
							View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
							View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
							View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
							View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
							View.SYSTEM_UI_FLAG_FULLSCREEN |
							View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
				}
				else {
					Game.this.getWindow().getDecorView().setSystemUiVisibility(
							View.SYSTEM_UI_FLAG_FULLSCREEN |
							View.SYSTEM_UI_FLAG_LOW_PROFILE);
				}
			}
	};

	private void hideSystemUi(int delay) {
		Handler h = getWindow().getDecorView().getHandler();
		if (h != null) {
			h.removeCallbacks(hideSystemUi);
			h.postDelayed(hideSystemUi, delay);               
		}
	}
	
	@Override
	protected void onStop() {
		super.onStop();
		
		SpinnerDialog.closeDialogs(this);
		Dialog.closeDialogs();
		
		displayedFailureDialog = true;
		stopConnection();
		
		int averageEndToEndLat = decoderRenderer.getAverageEndToEndLatency();
		int averageDecoderLat = decoderRenderer.getAverageDecoderLatency();
		String message = null;
		if (averageEndToEndLat > 0) {
			message = getResources().getString(R.string.conn_client_latency)+" "+averageEndToEndLat+" ms";
			if (averageDecoderLat > 0) {
				message += " ("+getResources().getString(R.string.conn_client_latency_hw)+" "+averageDecoderLat+" ms)";
			}
		}
		else if (averageDecoderLat > 0) {
			message = getResources().getString(R.string.conn_hardware_latency)+" "+averageDecoderLat+" ms";
		}
		
		if (message != null) {
			Toast.makeText(this, message, Toast.LENGTH_LONG).show();
		}

		finish();
	}
	
	@Override
	protected void onDestroy() {
		super.onDestroy();
		
		wifiLock.release();
	}
	
	private Runnable toggleGrab = new Runnable() {
		@Override
		public void run() {
			
			if (evdevWatcher != null) {
				if (grabbedInput) {
					evdevWatcher.ungrabAll();
				}
				else {
					evdevWatcher.regrabAll();
				}
			}
			
			grabbedInput = !grabbedInput;
		}
	};
	
	// Returns true if the key stroke was consumed
	private boolean handleSpecialKeys(short translatedKey, boolean down) {
		int modifierMask = 0;
		
		// Mask off the high byte
		translatedKey &= 0xff;
		
		if (translatedKey == KeyboardTranslator.VK_CONTROL) {
			modifierMask = KeyboardPacket.MODIFIER_CTRL;
		}
		else if (translatedKey == KeyboardTranslator.VK_SHIFT) {
			modifierMask = KeyboardPacket.MODIFIER_SHIFT;
		}
		else if (translatedKey == KeyboardTranslator.VK_ALT) {
			modifierMask = KeyboardPacket.MODIFIER_ALT;
		}
		
		if (down) {
			this.modifierFlags |= modifierMask;
		}
		else {
			this.modifierFlags &= ~modifierMask;
		}
		
		// Check if Ctrl+Shift+Z is pressed
		if (translatedKey == KeyboardTranslator.VK_Z &&
			(modifierFlags & (KeyboardPacket.MODIFIER_CTRL | KeyboardPacket.MODIFIER_SHIFT)) ==
				(KeyboardPacket.MODIFIER_CTRL | KeyboardPacket.MODIFIER_SHIFT))
		{
			if (down) {
				// Now that we've pressed the magic combo
				// we'll wait for one of the keys to come up
				grabComboDown = true;
			}
			else {
				// Toggle the grab if Z comes up
				Handler h = getWindow().getDecorView().getHandler();
				if (h != null) {
					h.postDelayed(toggleGrab, 250);               
				}
				
				grabComboDown = false;
			}
			
			return true;
		}
		// Toggle the grab if control or shift comes up
		else if (grabComboDown) {
			Handler h = getWindow().getDecorView().getHandler();
			if (h != null) {
				h.postDelayed(toggleGrab, 250);               
			}
			
			grabComboDown = false;
			return true;
		}
		
		// Not a special combo
		return false;
	}
	
	private static byte getModifierState(KeyEvent event) {
		byte modifier = 0;
		if (event.isShiftPressed()) {
			modifier |= KeyboardPacket.MODIFIER_SHIFT;
		}
		if (event.isCtrlPressed()) {
			modifier |= KeyboardPacket.MODIFIER_CTRL;
		}
		if (event.isAltPressed()) {
			modifier |= KeyboardPacket.MODIFIER_ALT;
		}
		return modifier;
	}
	
	private byte getModifierState() {
		return (byte) modifierFlags;
	}
	
	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		// Pass-through virtual navigation keys
		if ((event.getFlags() & KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0) {
			return super.onKeyDown(keyCode, event);
		}
		
		// Try the controller handler first
		boolean handled = controllerHandler.handleButtonDown(event);
		if (!handled) {
			// Try the keyboard handler
			short translated = keybTranslator.translate(event.getKeyCode());
			if (translated == 0) {
				return super.onKeyDown(keyCode, event);
			}
			
			// Let this method take duplicate key down events
			if (handleSpecialKeys(translated, true)) {
				return true;
			}
			
			// Eat repeat down events
			if (event.getRepeatCount() > 0) {
				return true;
			}
			
			// Pass through keyboard input if we're not grabbing
			if (!grabbedInput) {
				return super.onKeyDown(keyCode, event);
			}
			
			keybTranslator.sendKeyDown(translated,
					getModifierState(event));
		}
		
		return true;
	}
	
	@Override
	public boolean onKeyUp(int keyCode, KeyEvent event) {
		// Pass-through virtual navigation keys
		if ((event.getFlags() & KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0) {
			return super.onKeyUp(keyCode, event);
		}
		
		// Try the controller handler first
		boolean handled = controllerHandler.handleButtonUp(event);
		if (!handled) {
			// Try the keyboard handler
			short translated = keybTranslator.translate(event.getKeyCode());
			if (translated == 0) {
				return super.onKeyUp(keyCode, event);
			}
			
			if (handleSpecialKeys(translated, false)) {
				return true;
			}
			
			// Pass through keyboard input if we're not grabbing
			if (!grabbedInput) {
				return super.onKeyUp(keyCode, event);
			}
			
			keybTranslator.sendKeyUp(translated,
					getModifierState(event));
		}
		
		return true;
	}
	
	private TouchContext getTouchContext(int actionIndex)
	{	
		if (actionIndex < touchContextMap.length) {
			return touchContextMap[actionIndex];
		}
		else {
			return null;
		}
	}

	// Returns true if the event was consumed
	private boolean handleMotionEvent(MotionEvent event) {
		// Pass through keyboard input if we're not grabbing
		if (!grabbedInput) {
			return false;
		}

		if ((event.getSource() & InputDevice.SOURCE_CLASS_JOYSTICK) != 0) {
			if (controllerHandler.handleMotionEvent(event)) {
				return true;
			}		
		}
		else if ((event.getSource() & InputDevice.SOURCE_CLASS_POINTER) != 0)
		{
			// This case is for touch-based input devices
			if (event.getSource() == InputDevice.SOURCE_TOUCHSCREEN ||
					event.getSource() == InputDevice.SOURCE_STYLUS)
			{
				int actionIndex = event.getActionIndex();

				int eventX = (int)event.getX(actionIndex);
				int eventY = (int)event.getY(actionIndex);

				TouchContext context = getTouchContext(actionIndex);
				if (context == null) {
					return false;
				}

				switch (event.getActionMasked())
				{
				case MotionEvent.ACTION_POINTER_DOWN:
				case MotionEvent.ACTION_DOWN:
					context.touchDownEvent(eventX, eventY);
					break;
				case MotionEvent.ACTION_POINTER_UP:
				case MotionEvent.ACTION_UP:
					context.touchUpEvent(eventX, eventY);
					if (actionIndex == 0 && event.getPointerCount() > 1) {
						// The original secondary touch now becomes primary
						context.touchDownEvent((int)event.getX(1), (int)event.getY(1));
					}
					break;
				case MotionEvent.ACTION_MOVE:
					// ACTION_MOVE is special because it always has actionIndex == 0
					// We'll call the move handlers for all indexes manually

                    // First process the historical events
                    for (int i = 0; i < event.getHistorySize(); i++) {
                        for (TouchContext aTouchContextMap : touchContextMap) {
                            if (aTouchContextMap.getActionIndex() < event.getPointerCount())
                            {
                                aTouchContextMap.touchMoveEvent(
                                        (int)event.getHistoricalX(aTouchContextMap.getActionIndex(), i),
                                        (int)event.getHistoricalY(aTouchContextMap.getActionIndex(), i));
                            }
                        }
                    }

                    // Now process the current values
                    for (TouchContext aTouchContextMap : touchContextMap) {
                        if (aTouchContextMap.getActionIndex() < event.getPointerCount())
                        {
                            aTouchContextMap.touchMoveEvent(
                                    (int)event.getX(aTouchContextMap.getActionIndex()),
                                    (int)event.getY(aTouchContextMap.getActionIndex()));
                        }
                    }
					break;
				default:
					return false;
				}
			}
			// This case is for mice
			else if (event.getSource() == InputDevice.SOURCE_MOUSE)
			{
				int changedButtons = event.getButtonState() ^ lastButtonState;
				
				if (event.getActionMasked() == MotionEvent.ACTION_SCROLL) {
					// Send the vertical scroll packet
					byte vScrollClicks = (byte) event.getAxisValue(MotionEvent.AXIS_VSCROLL);
					conn.sendMouseScroll(vScrollClicks);
				}

				if ((changedButtons & MotionEvent.BUTTON_PRIMARY) != 0) {
					if ((event.getButtonState() & MotionEvent.BUTTON_PRIMARY) != 0) {
						conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
					}
					else {
						conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
					}
				}

				if ((changedButtons & MotionEvent.BUTTON_SECONDARY) != 0) {
					if ((event.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0) {
						conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
					}
					else {
						conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
					}
				}

				if ((changedButtons & MotionEvent.BUTTON_TERTIARY) != 0) {
					if ((event.getButtonState() & MotionEvent.BUTTON_TERTIARY) != 0) {
						conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_MIDDLE);
					}
					else {
						conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_MIDDLE);
					}
				}

                // First process the history
                for (int i = 0; i < event.getHistorySize(); i++) {
                    updateMousePosition((int)event.getHistoricalX(i), (int)event.getHistoricalY(i));
                }

                // Now process the current values
                updateMousePosition((int)event.getX(), (int)event.getY());

                lastButtonState = event.getButtonState();
			}
			else
			{
				// Unknown source
				return false;
			}

			// Handled a known source
			return true;
		}

		// Unknown class
		return false;
	}
	
	@Override
	public boolean onTouchEvent(MotionEvent event) {
        return handleMotionEvent(event) || super.onTouchEvent(event);

    }

	@Override
	public boolean onGenericMotionEvent(MotionEvent event) {
        return handleMotionEvent(event) || super.onGenericMotionEvent(event);

    }
	
	private void updateMousePosition(int eventX, int eventY) {
		// Send a mouse move if we already have a mouse location
		// and the mouse coordinates change
		if (lastMouseX != Integer.MIN_VALUE &&
			lastMouseY != Integer.MIN_VALUE &&
			!(lastMouseX == eventX && lastMouseY == eventY))
		{
			int deltaX = eventX - lastMouseX;
			int deltaY = eventY - lastMouseY;
			
			// Scale the deltas if the device resolution is different
			// than the stream resolution
			deltaX = (int)Math.round((double)deltaX * ((double)prefConfig.width / (double)screenSize.x));
			deltaY = (int)Math.round((double)deltaY * ((double)prefConfig.height / (double)screenSize.y));
			
			conn.sendMouseMove((short)deltaX, (short)deltaY);
		}
		
		// Update pointer location for delta calculation next time
		lastMouseX = eventX;
		lastMouseY = eventY;
	}

	@Override
	public boolean onGenericMotion(View v, MotionEvent event) {
		return handleMotionEvent(event);
	}

	@SuppressLint("ClickableViewAccessibility")
	@Override
	public boolean onTouch(View v, MotionEvent event) {
		return handleMotionEvent(event);
	}

	@Override
	public void stageStarting(Stage stage) {
		if (spinner != null) {
			spinner.setMessage(getResources().getString(R.string.conn_starting)+" "+stage.getName());
		}
	}

	@Override
	public void stageComplete(Stage stage) {
	}
	
	private void stopConnection() {
		if (connecting || connected) {
			connecting = connected = false;
			conn.stop();
            htConn.stop();
            sm.unregisterListener(gyroListener);
            sm.unregisterListener(magnetListener);
            htReportTimer.cancel();
		}
		
		// Close the Evdev watcher to allow use of captured input devices
		if (evdevWatcher != null) {
			evdevWatcher.shutdown();
			evdevWatcher = null;
		}
	}

	@Override
	public void stageFailed(Stage stage) {
		if (spinner != null) {
			spinner.dismiss();
			spinner = null;
		}

		if (!displayedFailureDialog) {
			displayedFailureDialog = true;
			stopConnection();
			Dialog.displayDialog(this, getResources().getString(R.string.conn_error_title),
					getResources().getString(R.string.conn_error_msg)+" "+stage.getName(), true);
		}
	}

	@Override
	public void connectionTerminated(Exception e) {
		if (!displayedFailureDialog) {
			displayedFailureDialog = true;
			e.printStackTrace();
			
			stopConnection();
			Dialog.displayDialog(this, getResources().getString(R.string.conn_terminated_title),
					getResources().getString(R.string.conn_terminated_msg), true);
		}
	}

	@Override
	public void connectionStarted() {
		if (spinner != null) {
			spinner.dismiss();
			spinner = null;
		}
		
		connecting = false;
		connected = true;
		
		hideSystemUi(1000);
	}

	@Override
	public void displayMessage(final String message) {
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				Toast.makeText(Game.this, message, Toast.LENGTH_LONG).show();
			}
		});
	}

	@Override
	public void displayTransientMessage(final String message) {
		if (!prefConfig.disableWarnings) {
			runOnUiThread(new Runnable() {
				@Override
				public void run() {
					Toast.makeText(Game.this, message, Toast.LENGTH_LONG).show();
				}
			});	
		}
	}

	@Override
	public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
	}

	@Override
	public void surfaceCreated(SurfaceHolder holder) {
		if (!connected && !connecting) {
			connecting = true;
			
			// Resize the surface to match the aspect ratio of the video
			// This must be done after the surface is created.
			if (!prefConfig.stretchVideo && decoderRenderer.isHardwareAccelerated()) {
				resizeSurfaceWithAspectRatio((SurfaceView) findViewById(R.id.surfaceView),
                        prefConfig.width, prefConfig.height);
			}
			
			conn.start(PlatformBinding.getDeviceName(), holder, drFlags,
					PlatformBinding.getAudioRenderer(), decoderRenderer);
			htConn.start();
			
			//register sensor listener
	    	List<Sensor> gyroscopes = sm.getSensorList(Sensor.TYPE_GYROSCOPE);
	    	Sensor GyroSensor = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);		
	    	if (gyroscopes != null && gyroscopes.size() > 1) {
	    	//	GyroSensor = gyroscopes.get(gyroscopes.size() - 1);	
	    	}
	    	
	    	Sensor MagnetSensor = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);	
	    	
	    	sm.registerListener(gyroListener, GyroSensor, SensorManager.SENSOR_DELAY_GAME);	
	    	sm.registerListener(magnetListener, MagnetSensor, SensorManager.SENSOR_DELAY_GAME);

            rotationMatrixCurrent[0] = 1.0f; rotationMatrixCurrent[1] = 0.0f; rotationMatrixCurrent[2] = 0.0f;
            rotationMatrixCurrent[3] = 0.0f; rotationMatrixCurrent[4] = 1.0f; rotationMatrixCurrent[5] = 0.0f;
            rotationMatrixCurrent[6] = 0.0f; rotationMatrixCurrent[7] = 0.0f; rotationMatrixCurrent[8] = 1.0f;

            rotationMatrixOriginal[0] = 1.0f; rotationMatrixOriginal[1] = 0.0f; rotationMatrixOriginal[2] = 0.0f;
            rotationMatrixOriginal[3] = 0.0f; rotationMatrixOriginal[4] = 1.0f; rotationMatrixOriginal[5] = 0.0f;
            rotationMatrixOriginal[6] = 0.0f; rotationMatrixOriginal[7] = 0.0f; rotationMatrixOriginal[8] = 1.0f;

            gyroAngle[0] = 0;
            gyroAngle[1] = 0;
            gyroAngle[2] = 0;

            htReportTimer = new Timer();
	    	htReportTimer.scheduleAtFixedRate(new TimerTask(){  
			   public void run()  
			   {  
				   htConn.sendTrackerInput(
						   (short) (0),
						   (short) (0),
						   (short) (0),
						   (short) (Math.round(gyroAngle[0] * 57.29578 * 100.0)),
						   (short) (Math.round(gyroAngle[2] * 57.29578 * 100.0)),
						   (short) (Math.round(gyroAngle[1] * -57.29578 * 100.0)));
			   }  
			}, 10000, 16);
		}
	}

	@Override
	public void surfaceDestroyed(SurfaceHolder holder) {
		if (connected) {
			stopConnection();
		}
	}

	@Override
	public void mouseMove(int deltaX, int deltaY) {
		conn.sendMouseMove((short) deltaX, (short) deltaY);
	}

	@Override
	public void mouseButtonEvent(int buttonId, boolean down) {
		byte buttonIndex;
		
		switch (buttonId)
		{
		case EvdevListener.BUTTON_LEFT:
			buttonIndex = MouseButtonPacket.BUTTON_LEFT;
			break;
		case EvdevListener.BUTTON_MIDDLE:
			buttonIndex = MouseButtonPacket.BUTTON_MIDDLE;
			break;
		case EvdevListener.BUTTON_RIGHT:
			buttonIndex = MouseButtonPacket.BUTTON_RIGHT;
			break;
		default:
			LimeLog.warning("Unhandled button: "+buttonId);
			return;
		}
		
		if (down) {
			conn.sendMouseButtonDown(buttonIndex);
		}
		else {
			conn.sendMouseButtonUp(buttonIndex);
		}
	}

	@Override
	public void mouseScroll(byte amount) {
		conn.sendMouseScroll(amount);
	}

	@Override
	public void keyboardEvent(boolean buttonDown, short keyCode) {
		short keyMap = keybTranslator.translate(keyCode);
		if (keyMap != 0) {
			if (handleSpecialKeys(keyMap, buttonDown)) {
				return;
			}
			
			if (buttonDown) {
				keybTranslator.sendKeyDown(keyMap, getModifierState());
			}
			else {
				keybTranslator.sendKeyUp(keyMap, getModifierState());
			}
		}
	}

	@Override
	public void onSystemUiVisibilityChange(int visibility) {
		// Don't do anything if we're not connected
		if (!connected) {
			return;
		}
		
		// This flag is set for all devices
		if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
			hideSystemUi(2000);
		}
		// This flag is only set on 4.4+
		else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT &&
				 (visibility & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0) {
			hideSystemUi(2000);
		}
		// This flag is only set before 4.4+
		else if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.KITKAT &&
				 (visibility & View.SYSTEM_UI_FLAG_LOW_PROFILE) == 0) {
			hideSystemUi(2000);	 
		}
	}
}
