package com.siply.pos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    private static final int REQUEST_CREATE_BACKUP = 1001;
    private static final int REQUEST_OPEN_BACKUP = 1002;

    /*
     * Android-side safety storage.
     *
     * This stores a copy of the WebView localStorage data
     * in SharedPreferences.
     */
    private static final String PREFS_NAME =
            "SipLyDataProtection";

    private static final String STORAGE_BACKUP_KEY =
            "localStorageBackup";

    private WebView webView;

    private String pendingBackupContent = null;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(
                android.view.Window.FEATURE_NO_TITLE
        );

        getWindow().setStatusBarColor(
                Color.rgb(113, 141, 103)
        );


        // --------------------------------------------------
        // WEBVIEW
        // --------------------------------------------------

        webView = new WebView(this);

        webView.setBackgroundColor(
                Color.rgb(247, 245, 238)
        );


        WebSettings settings =
                webView.getSettings();


        // Enable JavaScript
        settings.setJavaScriptEnabled(true);


        /*
         * IMPORTANT:
         *
         * Your POS stores its data using browser localStorage.
         * This must remain enabled.
         */
        settings.setDomStorageEnabled(true);


        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);


        // --------------------------------------------------
        // WEBVIEW CLIENT
        // --------------------------------------------------

        webView.setWebViewClient(
                new WebViewClient() {

                    @Override
                    public void onPageFinished(
                            WebView view,
                            String url
                    ) {

                        super.onPageFinished(
                                view,
                                url
                        );


                        /*
                         * First try to restore missing data
                         * from the Android safety copy.
                         */
                        restoreProtectedStorage();


                        /*
                         * After restoration has completed,
                         * save the current localStorage again.
                         */
                        view.postDelayed(
                                () -> saveProtectedStorage(),
                                500
                        );
                    }
                }
        );


        // --------------------------------------------------
        // JAVASCRIPT → ANDROID BRIDGE
        // --------------------------------------------------

        webView.addJavascriptInterface(
                new AndroidBridge(),
                "AndroidBridge"
        );


        // --------------------------------------------------
        // JAVASCRIPT ALERT / CONFIRM
        // --------------------------------------------------

        webView.setWebChromeClient(
                new WebChromeClient() {

                    @Override
                    public boolean onJsAlert(
                            WebView view,
                            String url,
                            String message,
                            JsResult result
                    ) {

                        new AlertDialog.Builder(
                                MainActivity.this
                        )
                                .setMessage(message)
                                .setPositiveButton(
                                        "OK",
                                        (dialog, which) ->
                                                result.confirm()
                                )
                                .setOnCancelListener(
                                        dialog ->
                                                result.cancel()
                                )
                                .show();

                        return true;
                    }


                    @Override
                    public boolean onJsConfirm(
                            WebView view,
                            String url,
                            String message,
                            JsResult result
                    ) {

                        new AlertDialog.Builder(
                                MainActivity.this
                        )
                                .setMessage(message)
                                .setNegativeButton(
                                        "CANCEL",
                                        (dialog, which) ->
                                                result.cancel()
                                )
                                .setPositiveButton(
                                        "CONFIRM",
                                        (dialog, which) ->
                                                result.confirm()
                                )
                                .setOnCancelListener(
                                        dialog ->
                                                result.cancel()
                                )
                                .show();

                        return true;
                    }
                }
        );


        // --------------------------------------------------
        // LOAD POS
        // --------------------------------------------------

        webView.loadUrl(
                "file:///android_asset/index.html"
        );


        setContentView(webView);
    }


    // ======================================================
    // RESTORE PROTECTED STORAGE
    // ======================================================

    private void restoreProtectedStorage() {

        String backup =
                getSharedPreferences(
                        PREFS_NAME,
                        Context.MODE_PRIVATE
                )
                        .getString(
                                STORAGE_BACKUP_KEY,
                                ""
                        );


        if (
                backup == null
                        || backup.trim().isEmpty()
        ) {
            return;
        }


        /*
         * JSONObject.quote() is the correct method
         * for safely inserting the backup JSON into
         * JavaScript.
         */
        String jsBackup =
                org.json.JSONObject.quote(
                        backup
                );


        String javascript =
                "(function() {" +
                        "try {" +

                        "var protectedBackup = " +
                        jsBackup +
                        ";" +

                        "if (!protectedBackup) return;" +

                        "var backupData = " +
                        "JSON.parse(protectedBackup);" +


                        /*
                         * Check existing transaction data.
                         */
                        "var currentOrders = " +
                        "localStorage.getItem(" +
                        "'siply_orders_v5'" +
                        ");" +


                        /*
                         * Only restore transactions if the
                         * current transaction database is missing
                         * or empty.
                         */
                        "var ordersMissing = " +
                        "(currentOrders === null || " +
                        "currentOrders === '' || " +
                        "currentOrders === '[]');" +


                        "if (" +
                        "ordersMissing && " +
                        "backupData['siply_orders_v5']" +
                        ") {" +

                        "localStorage.setItem(" +
                        "'siply_orders_v5'," +
                        "backupData['siply_orders_v5']" +
                        ");" +

                        "}" +


                        /*
                         * Restore other POS storage keys only if
                         * they are missing.
                         */
                        "Object.keys(backupData)" +
                        ".forEach(function(key) {" +

                        /*
                         * Transactions were handled separately.
                         */
                        "if (key === 'siply_orders_v5') " +
                        "return;" +

                        "var current = " +
                        "localStorage.getItem(key);" +

                        "if (" +
                        "current === null || " +
                        "current === ''" +
                        ") {" +

                        "localStorage.setItem(" +
                        "key," +
                        "backupData[key]" +
                        ");" +

                        "}" +

                        "});" +


                        "} catch(e) {" +

                        "console.log(" +
                        "'SipLy restore error: ' + e" +
                        ");" +

                        "}" +

                        "})();";


        webView.evaluateJavascript(
                javascript,
                null
        );
    }


    // ======================================================
    // SAVE PROTECTED STORAGE
    // ======================================================

    private void saveProtectedStorage() {

        if (webView == null) {
            return;
        }


        /*
         * JavaScript collects ALL localStorage entries.
         */
        String javascript =
                "(function() {" +
                        "try {" +

                        "var data = {};" +


                        "for (" +
                        "var i = 0;" +
                        "i < localStorage.length;" +
                        "i++" +
                        ") {" +

                        "var key = " +
                        "localStorage.key(i);" +


                        "if (key !== null) {" +

                        "data[key] = " +
                        "localStorage.getItem(key);" +

                        "}" +

                        "}" +


                        "return JSON.stringify(data);" +


                        "} catch(e) {" +

                        "return '';" +

                        "}" +

                        "})();";


        webView.evaluateJavascript(
                javascript,
                value -> {

                    if (value == null) {
                        return;
                    }


                    try {

                        /*
                         * evaluateJavascript() returns the
                         * JavaScript string as a JSON-encoded
                         * string.
                         *
                         * IMPORTANT:
                         * Do NOT use JSONTokener.quote().
                         *
                         * Instead, JSONTokener.nextValue()
                         * is used to decode the returned value.
                         */
                        String decoded;


                        if (
                                value.startsWith("\"")
                                        && value.endsWith("\"")
                        ) {

                            decoded =
                                    new org.json.JSONTokener(
                                            value
                                    )
                                            .nextValue()
                                            .toString();

                        } else {

                            decoded = value;
                        }


                        /*
                         * Don't overwrite the safety copy
                         * with an empty result.
                         */
                        if (
                                decoded == null
                                        || decoded
                                        .trim()
                                        .isEmpty()
                                        || decoded.equals("null")
                                        || decoded.equals("\"\"")
                                        || decoded.equals("{}")
                        ) {

                            return;
                        }


                        /*
                         * Save the complete localStorage
                         * snapshot in Android storage.
                         */
                        getSharedPreferences(
                                PREFS_NAME,
                                Context.MODE_PRIVATE
                        )
                                .edit()
                                .putString(
                                        STORAGE_BACKUP_KEY,
                                        decoded
                                )
                                .apply();


                    } catch (Exception e) {

                        /*
                         * Never interrupt the POS if the
                         * safety backup encounters an error.
                         */
                        e.printStackTrace();
                    }
                }
        );
    }


    // ======================================================
    // ACTIVITY PAUSE
    // ======================================================

    @Override
    protected void onPause() {

        /*
         * Save a copy whenever the app goes into the
         * background.
         */
        saveProtectedStorage();

        super.onPause();
    }


    // ======================================================
    // ACTIVITY DESTROY
    // ======================================================

    @Override
    protected void onDestroy() {

        /*
         * One final safety save.
         */
        saveProtectedStorage();


        if (webView != null) {

            webView.stopLoading();

            webView.destroy();

            webView = null;
        }


        super.onDestroy();
    }


    // ======================================================
    // CREATE BACKUP FILE
    // ======================================================

    private void chooseBackupLocation(
            String content,
            String fileName
    ) {

        pendingBackupContent = content;


        Intent intent =
                new Intent(
                        Intent.ACTION_CREATE_DOCUMENT
                );


        intent.addCategory(
                Intent.CATEGORY_OPENABLE
        );


        intent.setType(
                "application/vnd.ms-excel"
        );


        intent.putExtra(
                Intent.EXTRA_TITLE,
                fileName
        );


        startActivityForResult(
                intent,
                REQUEST_CREATE_BACKUP
        );
    }


    // ======================================================
    // OPEN BACKUP FILE
    // ======================================================

    private void chooseBackupFile() {

        Intent intent =
                new Intent(
                        Intent.ACTION_OPEN_DOCUMENT
                );


        intent.addCategory(
                Intent.CATEGORY_OPENABLE
        );


        intent.setType("*/*");


        intent.putExtra(
                Intent.EXTRA_MIME_TYPES,
                new String[]{
                        "application/vnd.ms-excel",
                        "application/xml",
                        "text/xml"
                }
        );


        startActivityForResult(
                intent,
                REQUEST_OPEN_BACKUP
        );
    }


    // ======================================================
    // WRITE BACKUP
    // ======================================================

    private void writeBackup(Uri uri) {

        if (pendingBackupContent == null) {
            return;
        }


        try (
                OutputStream outputStream =
                        getContentResolver()
                                .openOutputStream(uri)
        ) {


            if (outputStream == null) {

                throw new Exception(
                        "Unable to open selected file location."
                );
            }


            outputStream.write(
                    pendingBackupContent
                            .getBytes(
                                    StandardCharsets.UTF_8
                            )
            );


            outputStream.flush();


            pendingBackupContent = null;


            runOnUiThread(
                    () ->
                            webView.evaluateJavascript(
                                    "document.getElementById(" +
                                            "'backupStatus'" +
                                            ")" +
                                            ".textContent=" +
                                            "'Backup saved successfully.';",
                                    null
                            )
            );


        } catch (Exception e) {

            pendingBackupContent = null;


            showMessage(
                    "Backup failed: "
                            + e.getMessage()
            );
        }
    }


    // ======================================================
    // READ BACKUP
    // ======================================================

    private void readBackup(Uri uri) {

        try (
                InputStream inputStream =
                        getContentResolver()
                                .openInputStream(uri);

                BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        inputStream,
                                        StandardCharsets.UTF_8
                                )
                        )
        ) {


            if (inputStream == null) {

                throw new Exception(
                        "Unable to open selected backup file."
                );
            }


            StringBuilder content =
                    new StringBuilder();


            String line;


            while (
                    (line = reader.readLine())
                            != null
            ) {

                content
                        .append(line)
                        .append('\n');
            }


            /*
             * Safely encode the backup content before
             * sending it to JavaScript.
             */
            String jsArgument =
                    org.json.JSONObject.quote(
                            content.toString()
                    );


            runOnUiThread(
                    () ->
                            webView.evaluateJavascript(
                                    "window.androidImportBackup(" +
                                            jsArgument +
                                            ");",
                                    null
                            )
            );


        } catch (Exception e) {

            showMessage(
                    "Import failed: "
                            + e.getMessage()
            );
        }
    }


    // ======================================================
    // SHOW MESSAGE
    // ======================================================

    private void showMessage(
            String message
    ) {

        runOnUiThread(
                () ->
                        new AlertDialog.Builder(
                                MainActivity.this
                        )
                                .setMessage(message)
                                .setPositiveButton(
                                        "OK",
                                        null
                                )
                                .show()
        );
    }


    // ======================================================
    // JAVASCRIPT INTERFACE
    // ======================================================

    public class AndroidBridge {


        // --------------------------------------------------
        // EXPORT BACKUP
        // --------------------------------------------------

        @JavascriptInterface
        public void exportBackup(
                String content,
                String fileName
        ) {

            runOnUiThread(
                    () ->
                            chooseBackupLocation(
                                    content,
                                    fileName
                            )
            );
        }


        // --------------------------------------------------
        // IMPORT BACKUP
        // --------------------------------------------------

        @JavascriptInterface
        public void importBackup() {

            runOnUiThread(
                    MainActivity.this
                            ::chooseBackupFile
            );
        }


        // --------------------------------------------------
        // SAVE DATA
        // --------------------------------------------------

        @JavascriptInterface
        public void saveData(
                String data
        ) {

            if (
                    data == null
                            || data.trim().isEmpty()
            ) {
                return;
            }


            getSharedPreferences(
                    PREFS_NAME,
                    Context.MODE_PRIVATE
            )
                    .edit()
                    .putString(
                            STORAGE_BACKUP_KEY,
                            data
                    )
                    .apply();
        }


        // --------------------------------------------------
        // GET SAVED DATA
        // --------------------------------------------------

        @JavascriptInterface
        public String getSavedData() {

            return getSharedPreferences(
                    PREFS_NAME,
                    Context.MODE_PRIVATE
            )
                    .getString(
                            STORAGE_BACKUP_KEY,
                            ""
                    );
        }
    }


    // ======================================================
    // ACTIVITY RESULT
    // ======================================================

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data
    ) {

        super.onActivityResult(
                requestCode,
                resultCode,
                data
        );


        if (
                resultCode != RESULT_OK
                        || data == null
                        || data.getData() == null
        ) {

            return;
        }


        Uri uri =
                data.getData();


        if (
                requestCode
                        == REQUEST_CREATE_BACKUP
        ) {

            writeBackup(uri);


        } else if (
                requestCode
                        == REQUEST_OPEN_BACKUP
        ) {

            readBackup(uri);
        }
    }


    // ======================================================
    // BACK BUTTON
    // ======================================================

    @Override
    public void onBackPressed() {

        if (
                webView != null
                        && webView.canGoBack()
        ) {

            webView.goBack();

        } else {

            super.onBackPressed();
        }
    }
}