package com.dsharness.gifmaker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 图片转 GIF：把单文件网页装进 WebView 的极薄原生壳。
 *
 * 页面本身（选图预览、排序、量化、LZW 编码）100% 由 assets/index.html 在本地完成，
 * 不联网、不上传。原生侧只补三件浏览器里做不到或很别扭的事：
 *
 *   1. &lt;input type="file" multiple&gt; 的系统多选图片（WebChromeClient#onShowFileChooser）
 *   2. 点「下载 GIF」时真正的保存 —— blob: 链接在 WebView 里点了没反应，
 *      这里用原生桥把字节分块收下，写进系统相册 Pictures/GifMaker/
 *   3. 返回键优先回退网页历史
 *
 * 界面相关文案与逻辑全部留在网页里，壳永不联网（清单里没有 INTERNET 权限）。
 */
public class MainActivity extends Activity {

    private static final String TAG = "GifMaker";
    private static final int REQ_PICK_IMAGE = 4001;
    private static final String ASSET_URL = "file:///android_asset/index.html";
    private static final String BRIDGE = "AndroidBridge";
    private static final String SUB_DIR = "GifMaker";

    private WebView web;
    private ValueCallback<Uri[]> pendingFileCallback;

    /** 分块写盘状态（一次只允许一个保存任务）。 */
    private FileOutputStream pendingOut;
    private File pendingFile;
    private String pendingName;
    private int pendingExpected = -1;
    private int pendingWritten;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(0xF4, 0xF6, 0xFB)); // 同页面 --bg，避免打开时白闪
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);        // file:///android_asset 需要
        s.setAllowContentAccess(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(false);
        s.setMediaPlaybackRequiresUserGesture(false);

        // 页面自带浅色配色，禁止 WebView 的"强制深色"二次染色
        if (Build.VERSION.SDK_INT >= 33) {
            s.setAlgorithmicDarkeningAllowed(false);
        } else if (Build.VERSION.SDK_INT >= 29) {
            s.setForceDark(WebSettings.FORCE_DARK_OFF);
        }

        web.addJavascriptInterface(new NativeBridge(), BRIDGE);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // 页面加载完再注入桥接脚本：不改动 index.html 一个字节
                view.evaluateJavascript(readAsset("native-shim.js"), null);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                // 上一次没消费的回调必须先还掉，否则页面会"点了没反应"
                if (pendingFileCallback != null) {
                    pendingFileCallback.onReceiveValue(null);
                    pendingFileCallback = null;
                }
                pendingFileCallback = callback;

                Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("image/*");
                pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                String[] accept = (params != null) ? params.getAcceptTypes() : null;
                if (accept != null && accept.length == 1
                        && accept[0] != null && accept[0].contains("/")) {
                    pick.setType(accept[0]);
                }
                try {
                    startActivityForResult(Intent.createChooser(pick, "选择图片"),
                            REQ_PICK_IMAGE);
                    return true;
                } catch (Exception e) {
                    Log.w(TAG, "无法拉起选图器", e);
                    pendingFileCallback = null;
                    return false;
                }
            }

            /** 页面里的 alert（比如"请先选图"）用系统对话框呈现。 */
            @Override
            public boolean onJsAlert(WebView view, String url, String message,
                                     final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton("知道了", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                result.confirm();
                            }
                        })
                        .setOnCancelListener(new DialogInterface.OnCancelListener() {
                            @Override
                            public void onCancel(DialogInterface dialog) {
                                result.cancel();
                            }
                        })
                        .show();
                return true;
            }
        });

        applySystemBars();
        web.loadUrl(ASSET_URL);
    }

    /** 浅色状态栏/导航栏 + 深色系统图标，跟页面配色统一。 */
    private void applySystemBars() {
        try {
            getWindow().setStatusBarColor(Color.rgb(0xF4, 0xF6, 0xFB));
            getWindow().setNavigationBarColor(Color.WHITE);
        } catch (Throwable ignored) {
            // 个别 ROM 改色失败无所谓
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController c = getWindow().getInsetsController();
                if (c != null) {
                    int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    c.setSystemBarsAppearance(mask, mask);
                }
            } else {
                int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= 26) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
                getWindow().getDecorView().setSystemUiVisibility(flags);
            }
        } catch (Throwable ignored) {
            // 同上
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_PICK_IMAGE) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null) {
                // 多选走 clipData，单选走 data；两者都要认
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    result = new Uri[n];
                    for (int i = 0; i < n; i++) {
                        result[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
            }
            if (pendingFileCallback != null) {
                pendingFileCallback.onReceiveValue(result);
                pendingFileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        abortPendingSave();
        if (web != null) {
            web.removeJavascriptInterface(BRIDGE);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- JS 桥

    private class NativeBridge {

        @JavascriptInterface
        public boolean hasBridge() {
            return true;
        }

        /** 轻提示（保存结果、错误都走这里）。 */
        @JavascriptInterface
        public void toast(String text) {
            MainActivity.this.toast(text);
        }

        /** 按钮点击的触觉反馈，比纯网页更像原生。 */
        @JavascriptInterface
        public void tap() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (web != null) {
                        web.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    }
                }
            });
        }

        /** 开始接收一个 GIF：先落到缓存文件，避免把几 MB 的 base64 攒在 Java 堆里。 */
        @JavascriptInterface
        public void beginGif(String fileName, int totalBytes) {
            abortPendingSave();
            try {
                pendingName = sanitize(fileName);
                pendingFile = new File(getCacheDir(), "out.gif");
                pendingOut = new FileOutputStream(pendingFile, false);
                pendingExpected = totalBytes;
                pendingWritten = 0;
            } catch (Exception e) {
                Log.e(TAG, "创建缓存文件失败", e);
                abortPendingSave();
                toast("保存失败：" + e.getMessage());
            }
        }

        /** 追加一段（base64 编码的字节块）。 */
        @JavascriptInterface
        public void appendGif(String base64Chunk) {
            if (pendingOut == null || base64Chunk == null) {
                return;
            }
            try {
                byte[] bytes = Base64.decode(base64Chunk, Base64.DEFAULT);
                pendingOut.write(bytes);
                pendingWritten += bytes.length;
            } catch (Exception e) {
                Log.e(TAG, "写缓存失败", e);
                abortPendingSave();
                toast("保存失败：" + e.getMessage());
            }
        }

        /** 收完所有分块，落地到相册。 */
        @JavascriptInterface
        public void endGif() {
            if (pendingOut == null || pendingFile == null) {
                return;
            }
            String name = pendingName;
            File file = pendingFile;
            int written = pendingWritten;
            try {
                pendingOut.flush();
                pendingOut.close();
            } catch (IOException e) {
                Log.e(TAG, "关闭缓存失败", e);
            } finally {
                pendingOut = null;
                pendingFile = null;
                pendingName = null;
            }
            if (written <= 0) {
                file.delete();
                toast("GIF 数据为空，生成可能失败了");
                return;
            }
            if (pendingExpected > 0 && written != pendingExpected) {
                // 长度对不上就明说，别留半截坏文件在相册里
                file.delete();
                toast("GIF 传输不完整（" + written + "/" + pendingExpected + " 字节），请重试");
                return;
            }
            try {
                toast(saveToGallery(file, name));
                file.delete();
            } catch (Exception e) {
                Log.e(TAG, "保存到相册失败", e);
                toast("保存失败：" + e.getMessage());
            }
        }
    }

    private void abortPendingSave() {
        if (pendingOut != null) {
            try {
                pendingOut.close();
            } catch (IOException ignored) {
            }
            pendingOut = null;
        }
        if (pendingFile != null) {
            //noinspection ResultOfMethodCallIgnored
            pendingFile.delete();
            pendingFile = null;
        }
        pendingName = null;
        pendingExpected = -1;
        pendingWritten = 0;
    }

    /** 把缓存里的 GIF 写进系统相册（API 29+ 走 MediaStore 分区存储，零权限）。 */
    private String saveToGallery(File src, String fileName) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/gif");
            values.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/" + SUB_DIR);
            values.put(MediaStore.Images.Media.IS_PENDING, 1);

            Uri item = getContentResolver()
                    .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (item == null) {
                throw new Exception("相册拒绝创建条目");
            }
            OutputStream os = getContentResolver().openOutputStream(item);
            if (os == null) {
                throw new Exception("无法打开输出流");
            }
            try {
                copy(src, os);
            } finally {
                os.close();
            }
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            getContentResolver().update(item, values, null, null);
            return "已保存到相册 Pictures/" + SUB_DIR + "/" + fileName;
        }

        // API < 29：落到应用私有 Pictures 目录，免运行时权限
        File dir = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), SUB_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new Exception("无法创建目录 " + dir);
        }
        File out = new File(dir, fileName);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            copy(src, fos);
        }
        return "已保存：" + out.getAbsolutePath();
    }

    private static void copy(File src, OutputStream os) throws IOException {
        try (InputStream in = new java.io.FileInputStream(src)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
        }
        os.flush();
    }

    private String sanitize(String rawName) {
        String name = (rawName == null) ? "" : rawName.trim();
        if (name.length() == 0) {
            name = "动图.gif";
        }
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        if (!name.toLowerCase().endsWith(".gif")) {
            name = name + ".gif";
        }
        return name;
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            byte[] buf = new byte[16 * 1024];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = in.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, "UTF-8"));
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "读取注入脚本失败: " + name, e);
            return "";
        }
    }

    private void toast(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, text, Toast.LENGTH_LONG).show();
            }
        });
    }
}
