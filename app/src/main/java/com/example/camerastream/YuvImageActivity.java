package com.example.camerastream;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.os.Build;
import android.os.Bundle;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

public class YuvImageActivity extends Activity {
    private Camera camera;
    private SurfaceHolder surfaceHolder;
    private ImageView imageView;
    private TextView textView;
    private long elapsedTime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Переводим приложение в полноэкранный режим.
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        setContentView(R.layout.activity_yuv_image);

        lockScreenOrientation();

        SurfaceView surfaceView = (SurfaceView) findViewById(R.id.surfaceView);
        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(surfaceHolderCallback);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB)
            surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        imageView = (ImageView) findViewById(R.id.imageView);
        textView = (TextView) findViewById(R.id.textView);
        textView.setText("");
    }

    @Override
    protected void onResume() {
        super.onResume();

        startCamera();
    }

    // Обработка выключения камеры
    @Override
    protected void onPause() {
        super.onPause();

        stopCamera();
    }

    private boolean startCamera() {
        if (hasCamera(this)) {
            if (camera != null)
                camera.release();

            try {
                camera = Camera.open(); // Может выдать ошибку "Fail to connect to camera service".
            } catch (RuntimeException e) {
                e.printStackTrace();
                Toast.makeText(this, "Невозможно получить доступ к видеокамере."
                        + "\nПопробуйте перезагрузить устройство", Toast.LENGTH_LONG).show();
                finish();
                return false;
            }
        } else {
            Toast.makeText(this, "Извините, на устройстве не обнаружено видеокамеры.",
                    Toast.LENGTH_LONG).show();
            finish();
            return false;
        }
        return true;
    }

    private void stopCamera() {
        if (camera != null) {
            camera.setPreviewCallback(null);
            // Останавливаем предпросмотр.
            camera.stopPreview();
            // Освобождаем камеру для использования другими приложениями.
            camera.release();
            camera = null;
        }
    }

    private void refreshCamera(int width, int height) {
        if (camera != null) {
            try {
                camera.stopPreview();
            } catch (Exception e) {
                e.printStackTrace();
            }

            setOptimalPreview(width, height);
        }
    }

    // Имеет ли устройство камеру.
    private boolean hasCamera(Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA);
    }

    private void lockScreenOrientation() {
        int currentOrientation = getResources().getConfiguration().orientation;
        if (currentOrientation == Configuration.ORIENTATION_PORTRAIT) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        }
    }

    // Установить оптимальное соотношение сторон изображения.
    private void setOptimalPreview(int width, int height) {
        if (surfaceHolder.getSurface() != null && camera != null) {
            Camera.Parameters param = camera.getParameters();
            param.setPreviewSize(width, height);
            // Определим список доступных разрешений экрана.
            List<Camera.Size> sizes = param.getSupportedPreviewSizes();
            if (sizes != null) {
                int displayOrientation = getScreenOrientation(this, 0);
                Camera.Size optimalSize = getOptimalPreviewSize(sizes, width, height, displayOrientation);
                if (optimalSize != null) {
                    param.setPreviewSize(optimalSize.width, optimalSize.height);
                    setCameraDisplayOrientation(this, 0, camera);
                    try {
                        camera.setParameters(param); // Может выдать: "RuntimeException: setParameters failed".
                    } catch (RuntimeException e) {
                        e.printStackTrace();
                    }
                }

                camera.setPreviewCallback(previewCallback);
                //camera.setPreviewCallbackWithBuffer(previewCallback);
                try {
                    camera.setPreviewDisplay(surfaceHolder);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                camera.startPreview();
            }
        }
    }

    // Обработка отрисовки предпросмотра.
    SurfaceHolder.Callback surfaceHolderCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            if (startCamera())
                setOptimalPreview(200, 200);
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            refreshCamera(width, height);
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            stopCamera();
        }
    };

    private Camera.PreviewCallback previewCallback = new Camera.PreviewCallback() {
        @Override
        public synchronized void onPreviewFrame(byte[] data, Camera camera) {
            // Добавим расчёт времени на обработку одного кадра.
            long currentTime = System.currentTimeMillis();
            if (elapsedTime == 0)
                elapsedTime = currentTime;
            final long time = currentTime - elapsedTime;
            elapsedTime = currentTime;

            Camera.Parameters parameters = camera.getParameters();
            int format = parameters.getPreviewFormat();
            //YUV formats require more conversion
            if (format == ImageFormat.NV21 || format == ImageFormat.YUY2 || format == ImageFormat.NV16) {
                int width = parameters.getPreviewSize().width;
                int height = parameters.getPreviewSize().height;

                Matrix matrix = new Matrix();
                matrix.postRotate(getScreenOrientation(YuvImageActivity.this, 0));
                YuvImage yuvImage = new YuvImage(data, parameters.getPreviewFormat(), width, height, null);

                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                yuvImage.compressToJpeg(new Rect(0, 0, width, height), 100, outputStream);

                byte[] bytes = outputStream.toByteArray();

                /*FileOutputStream outStream = null;
                try {
                    // Write to SD Card
                    File file = createFileInSDCard(FOLDER_PATH, "Image_"+System.currentTimeMillis()+".jpg");
                    //Uri uriSavedImage = Uri.fromFile(file);
                    outStream = new FileOutputStream(file);
                    outStream.write(bytes);
                    outStream.close();
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                }*/

                //final Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                final Bitmap bitmap = Bitmap.createBitmap(bmp, 0, 0, width, height, matrix, true);

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        imageView.setImageBitmap(bitmap);
                        textView.setText(elapsedTime == 0 ? "" : String.valueOf(time) + " мс");
                    }
                });
            }
        }
    };

    // Определить лучшее соотношение сторон изображения, чтобы объекты на экране имели пропорции,
    // близкие к реальным.
    private Camera.Size getOptimalPreviewSize(List<Camera.Size> sizes, int width, int height, int displayOrientation) {
        final double ASPECT_TOLERANCE = 0.1;

        if (sizes == null || height == 0)
            return null;
        double targetRatio = (double) width / height;
        Camera.Size optimalSize = null;
        double minDiff = Double.MAX_VALUE;
        int targetHeight = height;
        if (displayOrientation == 90 || displayOrientation == 270)
            targetRatio = (double) height / width;

        // Попытаемся найти оптимальное соотношение сторон.
        for (Camera.Size size : sizes) {
            double ratio = (double) size.width / size.height;
            if (Math.abs(ratio - targetRatio) <= ASPECT_TOLERANCE)
                if (Math.abs(size.height - targetHeight) < minDiff) {
                    optimalSize = size;
                    minDiff = Math.abs(size.height - targetHeight);
                }
        }
        // Если невозможно найти близкое соотношение сторон, найдём близкие длины сторон.
        if (optimalSize == null) {
            minDiff = Double.MAX_VALUE;
            for (Camera.Size size : sizes) {
                if (Math.abs(size.height - targetHeight) < minDiff) {
                    optimalSize = size;
                    minDiff = Math.abs(size.height - targetHeight);
                }
            }
        }
        return optimalSize;
    }

    // Обработка поворота изображения при повороте камеры.
    private void setCameraDisplayOrientation(Activity activity, int cameraId, android.hardware.Camera camera) {
        camera.setDisplayOrientation(getScreenOrientation(activity, cameraId));
    }

    // Определим, насколько повёрнута камера от нормального положения.
    private int getScreenOrientation(Activity activity, int cameraId) {
        if (Build.VERSION.SDK_INT < 9)
            return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        else {
            // Определим, насколько повёрнута камера от нормального положения.
            int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
            int degrees = 0;
            switch (rotation) {
                case Surface.ROTATION_0:
                    degrees = 0;
                    break;
                case Surface.ROTATION_90:
                    degrees = 90;
                    break;
                case Surface.ROTATION_180:
                    degrees = 180;
                    break;
                case Surface.ROTATION_270:
                    degrees = 270;
                    break;
            }

            android.hardware.Camera.CameraInfo info = new android.hardware.Camera.CameraInfo();
            android.hardware.Camera.getCameraInfo(cameraId, info);
            int result = 0;
            // Для передней и задней камеры по-разному считаются повороты.
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT)
                result = (360 - degrees) - info.orientation + 360;
            else if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK)
                result = (360 - degrees) + info.orientation;

            result %= 360;
            return result;
        }
    }
}