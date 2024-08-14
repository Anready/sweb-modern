package landau.sweb;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DownloadManager;
import android.app.LoaderManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.Loader;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteStatement;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.net.http.SslCertificate;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.text.Editable;
import android.text.Html;
import android.text.TextWatcher;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Patterns;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.HttpAuthHandler;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebBackForwardList;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import landau.sweb.incognito.IncognitoAppCompatAutoCompleteTextView;
import landau.sweb.incognito.IncognitoWebView;
import landau.sweb.utils.AdblockRulesLoader;
import landau.sweb.utils.ExceptionLogger;
import landau.sweb.utils.PermissionHelper;
import landau.sweb.utils.PlacesDbHelper;

public class MainActivity extends Activity {

    private static class Tab {
        Tab(IncognitoWebView w) {
            this.webview = w;
        }

        IncognitoWebView webview;
        boolean isDesktopUA;
    }

    private static final String TAG = MainActivity.class.getSimpleName();

    static final String searchUrl = "https://www.google.com/search?q=%s";
    static final String searchCompleteUrl = "https://www.google.com/complete/search?client=firefox&q=%s";
    static final String desktopUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/80.0.3987.163 Safari/537.36";

    static final String[] adblockRulesList = {
            "https://easylist.to/easylist/easylist.txt",
            "https://easylist.to/easylist/easyprivacy.txt",
            "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=1&mimetype=plaintext",    // Peter's
            "https://easylist.to/easylist/fanboy-social.txt",
            "https://easylist-downloads.adblockplus.org/advblock.txt",  // RU AdList
    };

    static final int FORM_FILE_CHOOSER = 1;

    static final int PERMISSION_REQUEST_EXPORT_BOOKMARKS = 1;
    static final int PERMISSION_REQUEST_IMPORT_BOOKMARKS = 2;

    private final ArrayList<Tab> tabs = new ArrayList<>();
    private int currentTabIndex;
    private FrameLayout webviews;
    private IncognitoAppCompatAutoCompleteTextView et;
    private boolean isNightMode;
    private boolean isFullscreen;
    private SharedPreferences prefs;
    private boolean useAdBlocker;
    private AdBlocker adBlocker;
    private boolean isLogRequests;
    private ArrayList<String> requestsLog;
    private final View[] fullScreenView = new View[1];
    private final WebChromeClient.CustomViewCallback[] fullScreenCallback = new WebChromeClient.CustomViewCallback[1];
    private EditText searchEdit;
    private TextView searchCount;
    private TextView txtTabCount;

    private SQLiteDatabase placesDb;

    private ValueCallback<Uri[]> fileUploadCallback;
    private boolean fileUploadCallbackShouldReset;

    private static class MenuAction {

        static HashMap<String, MenuAction> actions = new HashMap<>();

        private MenuAction(String title, int icon, Runnable action) {
            this(title, icon, action, null);
        }

        private MenuAction(String title, int icon, Runnable action, MyBooleanSupplier getState) {
            this.title = title;
            this.icon = icon;
            this.action = action;
            this.getState = getState;
            actions.put(title, this);
        }

        @NonNull
        @Override
        public String toString() {
            return title;
        }

        private final String title;
        private final int icon;
        private final Runnable action;
        private final MyBooleanSupplier getState;
    }

    MenuAction[] menuActions;

    final String[][] toolbarActions = {
            {"Back", "Scroll to top", "Tab history"},
            {"Forward", "Scroll to bottom", "Ad Blocker"},
            {"Bookmarks", null, "Add bookmark"},
            {"Night mode", null, "Full screen"},
            {"Show tabs", "New tab", "Close tab"},
            {"Menu", "Reload", "Show address bar"},
    };

    final String[] shortMenu = {
            "Desktop UA", "Log requests", "Find on page", "Page info", "Share URL",
            "Open URL in app", "Full menu"
    };

    MenuAction getAction(String name) {
        MenuAction action = MenuAction.actions.get(name);
        if (action == null) throw new IllegalArgumentException("name");
        return action;
    }

    static class TitleAndUrl {
        String title;
        String url;
    }

    static class TitleAndBundle {
        String title;
        Bundle bundle;
    }

    private final ArrayList<TitleAndBundle> closedTabs = new ArrayList<>();

    private Tab getCurrentTab() {
        return tabs.get(currentTabIndex);
    }

    private IncognitoWebView getCurrentWebView() {
        return getCurrentTab().webview;
    }

    @SuppressLint({"SetJavaScriptEnabled", "DefaultLocale"})
    private IncognitoWebView createWebView(Bundle bundle) {
        final ProgressBar progressBar = findViewById(R.id.progressbar);

        IncognitoWebView webview = new IncognitoWebView(this);
        if (bundle != null) {
            webview.restoreState(bundle);
        }
        webview.setBackgroundColor(isNightMode ? Color.BLACK : Color.WHITE);
        WebSettings settings = webview.getSettings();
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setJavaScriptEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setDatabaseEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(true);
        webview.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                super.onProgressChanged(view, newProgress);
                injectCSS(view);
                if (newProgress == 100) {
                    progressBar.setVisibility(View.GONE);
                } else {
                    progressBar.setProgress(newProgress);
                }
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                fullScreenView[0] = view;
                fullScreenCallback[0] = callback;
                MainActivity.this.findViewById(R.id.main_layout).setVisibility(View.INVISIBLE);
                ViewGroup fullscreenLayout = MainActivity.this.findViewById(R.id.fullScreenVideo);
                fullscreenLayout.addView(view);
                fullscreenLayout.setVisibility(View.VISIBLE);
            }

            @Override
            public void onHideCustomView() {
                if (fullScreenView[0] == null) return;

                ViewGroup fullscreenLayout = MainActivity.this.findViewById(R.id.fullScreenVideo);
                fullscreenLayout.removeView(fullScreenView[0]);
                fullscreenLayout.setVisibility(View.GONE);
                fullScreenView[0] = null;
                fullScreenCallback[0] = null;
                MainActivity.this.findViewById(R.id.main_layout).setVisibility(View.VISIBLE);
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (fileUploadCallback != null) {
                    fileUploadCallback.onReceiveValue(null);
                }

                fileUploadCallback = filePathCallback;
                Intent intent = fileChooserParams.createIntent();
                try {
                    fileUploadCallbackShouldReset = true;
                    startActivityForResult(intent, FORM_FILE_CHOOSER);
                    return true;
                } catch (ActivityNotFoundException e) {
                    // Continue below
                }

                // FileChooserParams.createIntent() copies the <input type=file> "accept" attribute to the intent's getType(),
                // which can be e.g. ".png,.jpg" in addition to mime-type-style "image/*", however startActivityForResult()
                // only accepts mime-type-style. Try with just */* instead.
                intent.setType("*/*");
                try {
                    fileUploadCallbackShouldReset = false;
                    startActivityForResult(intent, FORM_FILE_CHOOSER);
                    return true;
                } catch (ActivityNotFoundException e) {
                    // Continue below
                }

                // Everything failed, let user know
                Toast.makeText(MainActivity.this, "Can't open file chooser", Toast.LENGTH_SHORT).show();
                fileUploadCallback = null;
                return false;
            }
        });
        webview.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progressBar.setProgress(0);
                progressBar.setVisibility(View.VISIBLE);
                if (view == getCurrentWebView()) {
                    et.setText(url);
                    et.setSelection(0);
                    view.requestFocus();
                }
                injectCSS(view);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (view == getCurrentWebView()) {
                    // Don't use the argument url here since navigation to that URL might have been
                    // cancelled due to SSL error
                    if (et.getSelectionStart() == 0 && et.getSelectionEnd() == 0 && et.getText().toString().equals(view.getUrl())) {
                        // If user haven't started typing anything, focus on webview
                        view.requestFocus();
                    }
                }
                injectCSS(view);
            }

            @Override
            public void onReceivedHttpAuthRequest(WebView view, final HttpAuthHandler handler, String host, String realm) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(host)
                        .setView(R.layout.login_password)
                        .setCancelable(false)
                        .setPositiveButton("OK", (dialog, which) -> {
                            String username = ((EditText) ((Dialog) dialog).findViewById(R.id.username)).getText().toString();
                            String password = ((EditText) ((Dialog) dialog).findViewById(R.id.password)).getText().toString();
                            handler.proceed(username, password);
                        })
                        .setNegativeButton("Cancel", (dialog, which) -> handler.cancel()).show();
            }

            final InputStream emptyInputStream = new ByteArrayInputStream(new byte[0]);

            String lastMainPage = "";

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (adBlocker != null) {
                    if (request.isForMainFrame()) {
                        lastMainPage = request.getUrl().toString();
                    }
                    if (adBlocker.shouldBlock(request.getUrl(), lastMainPage)) {
                        return new WebResourceResponse("text/plain", "UTF-8", emptyInputStream);
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // For intent:// URLs, redirect to browser_fallback_url if given
                if (url.startsWith("intent://")) {
                    int start = url.indexOf(";S.browser_fallback_url=");
                    if (start != -1) {
                        start += ";S.browser_fallback_url=".length();
                        int end = url.indexOf(';', start);
                        if (end != -1 && end != start) {
                            url = url.substring(start, end);
                            url = Uri.decode(url);
                            view.loadUrl(url);
                            return true;
                        }
                    }
                }
                return false;
            }

            @Override
            public void onLoadResource(WebView view, String url) {
                if (isLogRequests) {
                    requestsLog.add(url);
                }
            }

            final String[] sslErrors = {"Not yet valid", "Expired", "Hostname mismatch", "Untrusted CA", "Invalid date", "Unknown error"};

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                int primaryError = error.getPrimaryError();
                String errorStr = primaryError >= 0 && primaryError < sslErrors.length ? sslErrors[primaryError] : "Unknown error " + primaryError;
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Insecure connection")
                        .setMessage(String.format("Error: %s\nURL: %s\n\nCertificate:\n%s",
                                errorStr, error.getUrl(), certificateToStr(error.getCertificate())))
                        .setPositiveButton("Proceed", (dialog, which) -> handler.proceed())
                        .setNegativeButton("Cancel", (dialog, which) -> handler.cancel())
                        .show();
            }
        });
        webview.setOnLongClickListener(v -> {
            String url = null, imageUrl = null;
            IncognitoWebView.HitTestResult r = ((IncognitoWebView) v).getHitTestResult();
            switch (r.getType()) {
                case IncognitoWebView.HitTestResult.SRC_ANCHOR_TYPE:
                    url = r.getExtra();
                    break;
                case IncognitoWebView.HitTestResult.IMAGE_TYPE:
                    imageUrl = r.getExtra();
                    break;
                case IncognitoWebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE:
                case IncognitoWebView.HitTestResult.EMAIL_TYPE:
                case IncognitoWebView.HitTestResult.UNKNOWN_TYPE:
                    Handler handler = new Handler();
                    Message message = handler.obtainMessage();
                    ((IncognitoWebView) v).requestFocusNodeHref(message);
                    url = message.getData().getString("url");
                    if ("".equals(url)) {
                        url = null;
                    }
                    imageUrl = message.getData().getString("src");
                    if ("".equals(imageUrl)) {
                        imageUrl = null;
                    }
                    if (url == null && imageUrl == null) {
                        return false;
                    }
                    break;
                default:
                    return false;
            }
            showLongPressMenu(url, imageUrl);
            return true;
        });
        webview.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            String filename = URLUtil.guessFileName(url, contentDisposition, mimetype);
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Download")
                    .setMessage(String.format("Filename: %s\nSize: %.2f MB\nURL: %s",
                            filename,
                            contentLength / 1024.0 / 1024.0,
                            url))
                    .setPositiveButton("Download", (dialog, which) -> startDownload(url, userAgent, mimetype, contentDisposition))
                    .setNeutralButton("Open", (dialog, which) -> {
                        Intent i = new Intent(Intent.ACTION_VIEW);
                        i.setData(Uri.parse(url));
                        try {
                            startActivity(i);
                        } catch (ActivityNotFoundException e) {
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("Open")
                                    .setMessage("Can't open files of this type. Try downloading instead.")
                                    .setPositiveButton("OK", (dialog1, which1) -> {
                                    })
                                    .show();
                        }
                    })
                    .setNegativeButton("Cancel", (dialog, which) -> {
                    })
                    .show();
        });
        webview.setFindListener((activeMatchOrdinal, numberOfMatches, isDoneCounting) ->
                searchCount.setText(numberOfMatches == 0 ? "Not found" :
                        String.format("%d / %d", activeMatchOrdinal + 1, numberOfMatches)));
        return webview;
    }

    private void showLongPressMenu(String linkUrl, String imageUrl) {
        String url;
        String title;
        String[] options = new String[]{"Open in new tab", "Copy URL", "Show full URL", "Download"};

        if (imageUrl == null) {
            if (linkUrl == null) {
                throw new IllegalArgumentException("Bad null arguments in showLongPressMenu");
            } else {
                // Text link
                url = linkUrl;
                title = linkUrl;
            }
        } else {
            if (linkUrl == null) {
                // Image without link
                url = imageUrl;
                title = "Image: " + imageUrl;
            } else {
                // Image with link
                url = linkUrl;
                title = linkUrl;
                String[] newOptions = new String[options.length + 1];
                System.arraycopy(options, 0, newOptions, 0, options.length);
                newOptions[newOptions.length - 1] = "Image Options";
                options = newOptions;
            }
        }
        new AlertDialog.Builder(MainActivity.this).setTitle(title).setItems(options, (dialog, which) -> {
            switch (which) {
                case 0:
                    newTab(url);
                    break;
                case 1:
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    assert clipboard != null;
                    ClipData clipData = ClipData.newPlainText("URL", url);
                    clipboard.setPrimaryClip(clipData);
                    break;
                case 2:
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Full URL")
                            .setMessage(url)
                            .setPositiveButton("OK", (dialog1, which1) -> {
                            })
                            .show();
                    break;
                case 3:
                    break;
                case 4:
                    showLongPressMenu(null, imageUrl);
                    break;
            }
        }).show();
    }

    @SuppressLint("DefaultLocale")
    private static String certificateToStr(SslCertificate certificate) {
        if (certificate == null) {
            return null;
        }
        String s = "";
        SslCertificate.DName issuedTo = certificate.getIssuedTo();
        if (issuedTo != null) {
            s += "Issued to: " + issuedTo.getDName() + "\n";
        }
        SslCertificate.DName issuedBy = certificate.getIssuedBy();
        if (issuedBy != null) {
            s += "Issued by: " + issuedBy.getDName() + "\n";
        }
        Date issueDate = certificate.getValidNotBeforeDate();
        if (issueDate != null) {
            s += String.format("Issued on: %tF %tT %tz\n", issueDate, issueDate, issueDate);
        }
        Date expiryDate = certificate.getValidNotAfterDate();
        if (expiryDate != null) {
            s += String.format("Expires on: %tF %tT %tz\n", expiryDate, expiryDate, expiryDate);
        }
        return s;
    }

    private void startDownload(String url, String userAgent, String mimetype, String contentDisposition) {
        if (PermissionHelper.hasOrRequestPermission(this, 1)) {
            return;
        }

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
        request.setMimeType(mimetype);
        request.addRequestHeader("User-Agent", userAgent);
        request.setDescription("Downloading file...");
        request.setTitle(URLUtil.guessFileName(url, contentDisposition, mimetype));
        request.allowScanningByMediaScanner();
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, contentDisposition, mimetype));

        DownloadManager downloadManager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        downloadManager.enqueue(request);

        Toast.makeText(getApplicationContext(), "Downloading File", Toast.LENGTH_LONG).show();
    }

    private void newTabCommon(IncognitoWebView webview) {
        boolean isDesktopUA = !tabs.isEmpty() && getCurrentTab().isDesktopUA;
        webview.getSettings().setUserAgentString(isDesktopUA ? desktopUA : null);
        webview.getSettings().setUseWideViewPort(isDesktopUA);
        webview.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        webview.setVisibility(View.GONE);
        Tab tab = new Tab(webview);
        tab.isDesktopUA = isDesktopUA;
        tabs.add(tab);
        webviews.addView(webview);
        setTabCountText(tabs.size());
    }

    private void newTab(String url) {
        IncognitoWebView webview = createWebView(null);
        newTabCommon(webview);
        loadUrl(url, webview);
    }

    private void newTabFromBundle(Bundle bundle) {
        IncognitoWebView webview = createWebView(bundle);
        newTabCommon(webview);
    }

    private void switchToTab(int tab) {
        getCurrentWebView().setVisibility(View.GONE);
        currentTabIndex = tab;
        getCurrentWebView().setVisibility(View.VISIBLE);
        et.setText(getCurrentWebView().getUrl());
        getCurrentWebView().requestFocus();
    }

    private void updateFullScreen() {
        final int flags = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION // hide nav bar
                | View.SYSTEM_UI_FLAG_FULLSCREEN // hide status bar
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        boolean fullscreenNow = (getWindow().getDecorView().getSystemUiVisibility() & flags) == flags;
        if (fullscreenNow != isFullscreen) {
            getWindow().getDecorView().setSystemUiVisibility(isFullscreen ? flags : 0);
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            private final Thread.UncaughtExceptionHandler defaultUEH = Thread.getDefaultUncaughtExceptionHandler();

            @Override
            public void uncaughtException(@NonNull Thread t, @NonNull Throwable e) {
                ExceptionLogger.logException(e);
                assert defaultUEH != null;
                defaultUEH.uncaughtException(t, e);
            }
        });

        try {
            placesDb = new PlacesDbHelper(this).getWritableDatabase();
        } catch (SQLiteException e) {
            Log.e(TAG, "Can't open database", e);
        }

        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        setContentView(R.layout.activity_main);
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(visibility -> updateFullScreen());

        isFullscreen = false;
        isNightMode = prefs.getBoolean("night_mode", false);

        webviews = findViewById(R.id.webviews);
        currentTabIndex = 0;

        et = findViewById(R.id.et);

        // setup edit text
        et.setSelected(false);
        String initialUrl = getUrlFromIntent(getIntent());
        et.setText(initialUrl.isEmpty() ? "https://google.com/" : initialUrl);
        et.setAdapter(new SearchAutocompleteAdapter(this, text -> {
            et.setText(text);
            et.setSelection(text.length());
        }));
        et.setOnItemClickListener((parent, view, position, id) -> {
            getCurrentWebView().requestFocus();
            loadUrl(et.getText().toString(), getCurrentWebView());
        });

        et.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                loadUrl(et.getText().toString(), getCurrentWebView());
                getCurrentWebView().requestFocus();
                return true;
            } else {
                return false;
            }
        });

        searchEdit = findViewById(R.id.searchEdit);
        searchEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                getCurrentWebView().findAllAsync(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        searchCount = findViewById(R.id.searchCount);
        findViewById(R.id.searchFindNext).setOnClickListener(v -> {
            hideKeyboard();
            getCurrentWebView().findNext(true);
        });
        findViewById(R.id.searchFindPrev).setOnClickListener(v -> {
            hideKeyboard();
            getCurrentWebView().findNext(false);
        });
        findViewById(R.id.searchClose).setOnClickListener(v -> {
            getCurrentWebView().clearMatches();
            searchEdit.setText("");
            getCurrentWebView().requestFocus();
            findViewById(R.id.searchPane).setVisibility(View.GONE);
            hideKeyboard();
        });

        useAdBlocker = prefs.getBoolean("adblocker", true);
        initAdblocker();

        newTab(et.getText().toString());
        getCurrentWebView().setVisibility(View.VISIBLE);
        getCurrentWebView().requestFocus();
        onNightModeChange();
    }

    @Override
    protected void onDestroy() {
        if (placesDb != null) {
            placesDb.close();
        }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FORM_FILE_CHOOSER) {
            if (fileUploadCallback != null) {
                // When the first file chooser activity fails to start due to an intent type not being a mime-type,
                // we should not reset the callback since we'd be called back soon with the */* type.
                if (fileUploadCallbackShouldReset) {
                    fileUploadCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                    fileUploadCallback = null;
                } else {
                    fileUploadCallbackShouldReset = true;
                }
            }
        }
    }

    private void setTabCountText(int count) {
        if (txtTabCount != null) {
            txtTabCount.setText(String.valueOf(count));
        }
    }

    private void maybeSetupTabCountTextView(View view, String name) {
        if ("Show tabs".equals(name)) {
            txtTabCount = view.findViewById(R.id.txtText);
        }
    }

    private void setupToolbar(ViewGroup parent) {
        parent.removeAllViews();
        for (String[] actions : toolbarActions) {
            View v = getLayoutInflater().inflate(R.layout.toolbar_button, parent, false);
            parent.addView(v);
            Runnable a1 = null, a2 = null, a3 = null;
            if (actions[0] != null) {
                maybeSetupTabCountTextView(v, actions[0]);
                MenuAction action = getAction(actions[0]);
                ((ImageView) v.findViewById(R.id.btnShortClick)).setImageResource(action.icon);
                a1 = action.action;
            }
            if (actions[1] != null) {
                maybeSetupTabCountTextView(v, actions[1]);
                MenuAction action = getAction(actions[1]);
                ((ImageView) v.findViewById(R.id.btnLongClick)).setImageResource(action.icon);
                a2 = action.action;
            }
            if (actions[2] != null) {
                maybeSetupTabCountTextView(v, actions[2]);
                MenuAction action = getAction(actions[2]);
                ((ImageView) v.findViewById(R.id.btnSwipeUp)).setImageResource(action.icon);
                a3 = action.action;
            }
            setToolbarButtonActions(v, a1, a2, a3);
        }
    }

    private void pageInfo() {
        String s = "URL: " + getCurrentWebView().getUrl() + "\n";
        s += "Title: " + getCurrentWebView().getTitle() + "\n\n";
        SslCertificate certificate = getCurrentWebView().getCertificate();
        s += certificate == null ? "Not secure" : "Certificate:\n" + certificateToStr(certificate);

        new AlertDialog.Builder(this)
                .setTitle("Page info")
                .setMessage(s)
                .setPositiveButton("OK", (dialog, which) -> {
                })
                .show();
    }

    private void showOpenTabs() {
        String[] items = new String[tabs.size()];
        for (int i = 0; i < tabs.size(); i++) {
            items[i] = tabs.get(i).webview.getTitle();
        }
        ArrayAdapter<String> adapter = new ArrayAdapterWithCurrentItem<>(
                MainActivity.this,
                android.R.layout.simple_list_item_1,
                items,
                currentTabIndex);
        AlertDialog.Builder tabsDialog = new AlertDialog.Builder(MainActivity.this)
                .setTitle("Tabs")
                .setAdapter(adapter, (dialog, which) -> switchToTab(which));
        if (!closedTabs.isEmpty()) {
            tabsDialog.setNeutralButton("Undo closed tabs", (dialog, which) -> {
                String[] items1 = new String[closedTabs.size()];
                for (int i = 0; i < closedTabs.size(); i++) {
                    items1[i] = closedTabs.get(i).title;
                }
                AlertDialog undoClosedTabsDialog = new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Undo closed tabs")
                        .setItems(items1, (dialog1, which1) -> {
                            Bundle bundle = closedTabs.get(which1).bundle;
                            closedTabs.remove(which1);
                            newTabFromBundle(bundle);
                            switchToTab(tabs.size() - 1);
                        })
                        .create();
                undoClosedTabsDialog.getListView().setOnItemLongClickListener((parent, view, position, id) -> {
                    undoClosedTabsDialog.dismiss();
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Remove closed tab?")
                            .setMessage(closedTabs.get(position).title)
                            .setNegativeButton("Cancel", (dlg, which1) -> {
                            })
                            .setPositiveButton("Remove", (dlg, which1) -> closedTabs.remove(position))
                            .show();
                    return true;
                });
                undoClosedTabsDialog.show();
            });
        }
        tabsDialog.show();
    }

    private void showTabHistory() {
        WebBackForwardList list = getCurrentWebView().copyBackForwardList();
        final int size = list.getSize();
        final int idx = size - list.getCurrentIndex() - 1;
        String[] items = new String[size];
        for (int i = 0; i < size; i++) {
            items[size - i - 1] = list.getItemAtIndex(i).getTitle();
        }
        ArrayAdapter<String> adapter = new ArrayAdapterWithCurrentItem<>(
                this,
                android.R.layout.simple_list_item_1,
                items,
                idx);
        new AlertDialog.Builder(this)
                .setTitle("Navigation History")
                .setAdapter(adapter, (dialog, which) -> getCurrentWebView().goBackOrForward(idx - which))
                .show();
    }

    private void toggleFullscreen() {
        isFullscreen = !isFullscreen;
        updateFullScreen();
    }

    private void toggleShowAddressBar() {
        et.setVisibility(et.getVisibility() == View.GONE ? View.VISIBLE : View.GONE);
    }

    private void toggleNightMode() {
        isNightMode = !isNightMode;
        prefs.edit().putBoolean("night_mode", isNightMode).apply();
        onNightModeChange();
    }

    private void initAdblocker() {
        if (useAdBlocker) {
            adBlocker = new AdBlocker(getExternalFilesDir("adblock"));
        } else {
            adBlocker = null;
        }
    }

    private void toggleAdblocker() {
        useAdBlocker = !useAdBlocker;
        initAdblocker();
        prefs.edit().putBoolean("adblocker", useAdBlocker).apply();
        Toast.makeText(MainActivity.this, "Ad Blocker " + (useAdBlocker ? "enabled" : "disabled"), Toast.LENGTH_SHORT).show();
    }

    private void updateAdblockRules() {
        getLoaderManager().restartLoader(0, null, new LoaderManager.LoaderCallbacks<Integer>() {
            @Override
            public Loader<Integer> onCreateLoader(int id, Bundle args) {
                return new AdblockRulesLoader(MainActivity.this, adblockRulesList, getExternalFilesDir("adblock"));
            }

            @SuppressLint("DefaultLocale")
            @Override
            public void onLoadFinished(Loader<Integer> loader, Integer data) {
                Toast.makeText(MainActivity.this,
                        String.format("Updated %d / %d adblock subscriptions", data, adblockRulesList.length),
                        Toast.LENGTH_SHORT).show();
                initAdblocker();
            }

            @Override
            public void onLoaderReset(Loader<Integer> loader) {
            }
        });
    }

    private void showBookmarks() {
        if (placesDb == null) return;
        Cursor cursor = placesDb.rawQuery("SELECT title, url, id as _id FROM bookmarks", null);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Bookmarks")
                .setOnDismissListener(dlg -> cursor.close())
                .setCursor(cursor, (dlg, which) -> {
                    cursor.moveToPosition(which);
                    int i = cursor.getColumnIndex("url");
                    if (i != -1) {
                        String url = cursor.getString(i);
                        et.setText(url);
                        loadUrl(url, getCurrentWebView());
                    }
                }, "title")
                .create();
        dialog.getListView().setOnItemLongClickListener((parent, view, position, id) -> {
            cursor.moveToPosition(position);

            int rowidIndex = cursor.getColumnIndex("_id");
            int titleIndex = cursor.getColumnIndex("title");
            int urlIndex = cursor.getColumnIndex("url");

            int rowid;
            String title;
            String url;

            if (rowidIndex != -1) {
                rowid = cursor.getInt(rowidIndex);
            } else {
                rowid = -1;
            }

            if (titleIndex != -1) {
                title = cursor.getString(titleIndex);
            } else {
                title = null;
            }

            if (urlIndex != -1) {
                url = cursor.getString(urlIndex);
            } else {
                url = null;
            }

            dialog.dismiss();
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle(title)
                    .setItems(new String[]{"Rename", "Change URL", "Delete"}, (dlg, which) -> {
                        switch (which) {
                            case 0: {
                                EditText editView = new EditText(this);
                                editView.setText(title);
                                new AlertDialog.Builder(this)
                                        .setTitle("Rename bookmark")
                                        .setView(editView)
                                        .setPositiveButton("Rename", (renameDlg, which1) -> placesDb.execSQL("UPDATE bookmarks SET title=? WHERE id=?", new Object[]{editView.getText(), rowid}))
                                        .setNegativeButton("Cancel", (renameDlg, which1) -> {
                                        })
                                        .show();
                                break;
                            }
                            case 1: {
                                EditText editView = new EditText(this);
                                editView.setText(url);
                                new AlertDialog.Builder(this)
                                        .setTitle("Change bookmark URL")
                                        .setView(editView)
                                        .setPositiveButton("Change URL", (renameDlg, which1) -> placesDb.execSQL("UPDATE bookmarks SET url=? WHERE id=?", new Object[]{editView.getText(), rowid}))
                                        .setNegativeButton("Cancel", (renameDlg, which1) -> {
                                        })
                                        .show();
                                break;
                            }
                            case 2:
                                placesDb.execSQL("DELETE FROM bookmarks WHERE id = ?", new Object[]{rowid});
                                break;
                        }
                    })
                    .show();
            return true;
        });
        dialog.show();
    }

    private void addBookmark() {
        if (placesDb == null) return;
        ContentValues values = new ContentValues(2);
        values.put("title", getCurrentWebView().getTitle());
        values.put("url", getCurrentWebView().getUrl());
        placesDb.insert("bookmarks", null, values);
    }

    private void exportBookmarks() {
        if (placesDb == null) {
            new AlertDialog.Builder(this)
                    .setTitle("Export bookmarks error")
                    .setMessage("Can't open bookmarks database")
                    .setPositiveButton("OK", (dialog, which) -> {
                    })
                    .show();
            return;
        }
        if (PermissionHelper.hasOrRequestPermission(this, 1)) {
            return;
        }
        File file = new File(Environment.getExternalStorageDirectory(), "bookmarks.html");
        if (file.exists()) {
            new AlertDialog.Builder(this)
                    .setTitle("Export bookmarks")
                    .setMessage("The file bookmarks.html already exists on SD card. Overwrite?")
                    .setNegativeButton("Cancel", (dialog, which) -> {
                    })
                    .setPositiveButton("Overwrite", (dialog, which) -> {
                        //noinspection ResultOfMethodCallIgnored
                        file.delete();
                        exportBookmarks();
                    })
                    .show();
            return;
        }
        try {
            FileOutputStream fos = new FileOutputStream(file);
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(fos));
            bw.write("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n" +
                    "<!-- This is an automatically generated file.\n" +
                    "     It will be read and overwritten.\n" +
                    "     DO NOT EDIT! -->\n" +
                    "<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n" +
                    "<TITLE>Bookmarks</TITLE>\n" +
                    "<H1>Bookmarks Menu</H1>\n" +
                    "\n" +
                    "<DL><p>\n");
            try (Cursor cursor = placesDb.rawQuery("SELECT title, url FROM bookmarks", null)) {
                final int titleIdx = cursor.getColumnIndex("title");
                final int urlIdx = cursor.getColumnIndex("url");
                while (cursor.moveToNext()) {
                    bw.write("    <DT><A HREF=\"" + cursor.getString(urlIdx) + "\" ADD_DATE=\"0\" LAST_MODIFIED=\"0\">");
                    bw.write(Html.escapeHtml(cursor.getString(titleIdx)));
                    bw.write("</A>\n");
                }
            }
            bw.write("</DL>\n");
            bw.close();
            Toast.makeText(this, "Bookmarks exported to bookmarks.html on SD card", Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            new AlertDialog.Builder(this)
                    .setTitle("Export bookmarks error")
                    .setMessage(e.toString())
                    .setPositiveButton("OK", (dialog, which) -> {
                    })
                    .show();
        }
    }

    @SuppressLint("DefaultLocale")
    private void importBookmarks() {
        if (placesDb == null) {
            new AlertDialog.Builder(this)
                    .setTitle("Import bookmarks error")
                    .setMessage("Can't open bookmarks database")
                    .setPositiveButton("OK", (dialog, which) -> {
                    })
                    .show();
            return;
        }
        if (PermissionHelper.hasOrRequestPermission(this, 1)) {
            return;
        }
        File file = new File(Environment.getExternalStorageDirectory(), "bookmarks.html");
        StringBuilder sb = new StringBuilder();
        try {
            char[] buf = new char[16 * 1024];
            FileInputStream fis = new FileInputStream(file);
            BufferedReader br = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8));
            int count;
            while ((count = br.read(buf)) != -1) {
                sb.append(buf, 0, count);
            }
            br.close();
        } catch (FileNotFoundException e) {
            new AlertDialog.Builder(this)
                    .setTitle("Import bookmarks error")
                    .setMessage("Bookmarks should be placed in a bookmarks.html file on the SD Card")
                    .setPositiveButton("OK", (dialog, which) -> {
                    })
                    .show();
            return;
        } catch (IOException e) {
            new AlertDialog.Builder(this)
                    .setTitle("Import bookmarks error")
                    .setMessage(e.toString())
                    .setPositiveButton("OK", (dialog, which) -> {
                    })
                    .show();
            return;
        }

        ArrayList<TitleAndUrl> bookmarks = new ArrayList<>();
        Pattern pattern = Pattern.compile("<A HREF=\"([^\"]*)\"[^>]*>([^<]*)</A>");
        Matcher matcher = pattern.matcher(sb);
        while (matcher.find()) {
            TitleAndUrl pair = new TitleAndUrl();
            pair.url = matcher.group(1);
            pair.title = matcher.group(2);
            if (pair.url == null || pair.title == null) continue;
            pair.title = Html.fromHtml(pair.title).toString();
            bookmarks.add(pair);
        }

        if (bookmarks.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Import bookmarks")
                    .setMessage("No bookmarks found in bookmarks.html")
                    .setPositiveButton("OK", (dialog, which) -> {
                    })
                    .show();
            return;
        }

        try {
            placesDb.beginTransaction();
            SQLiteStatement stmt = placesDb.compileStatement("INSERT INTO bookmarks (title, url) VALUES (?,?)");
            for (TitleAndUrl pair : bookmarks) {
                stmt.bindString(1, pair.title);
                stmt.bindString(2, pair.url);
                stmt.execute();
            }
            placesDb.setTransactionSuccessful();
            Toast.makeText(this, String.format("Imported %d bookmarks", bookmarks.size()), Toast.LENGTH_SHORT).show();
        } finally {
            placesDb.endTransaction();
        }
    }

    private void deleteAllBookmarks() {
        if (placesDb == null) {
            new AlertDialog.Builder(this)
                    .setTitle("Bookmarks error")
                    .setMessage("Can't open bookmarks database")
                    .setPositiveButton("OK", (dialog, which) -> {
                    })
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Delete all bookmarks?")
                .setMessage("This action cannot be undone")
                .setNegativeButton("Cancel", (dialog, which) -> {
                })
                .setPositiveButton("Delete All", (dialog, which) -> placesDb.execSQL("DELETE FROM bookmarks"))
                .show();
    }

    private void clearHistoryCache() {
        IncognitoWebView v = getCurrentWebView();
        v.clearCache(true);
        v.clearFormData();
        v.clearHistory();
        CookieManager.getInstance().removeAllCookies(null);
        WebStorage.getInstance().deleteAllData();
    }

    private void closeCurrentTab() {
        if (getCurrentWebView().getUrl() != null && !getCurrentWebView().getUrl().equals("about:blank")) {
            TitleAndBundle titleAndBundle = new TitleAndBundle();
            titleAndBundle.title = getCurrentWebView().getTitle();
            titleAndBundle.bundle = new Bundle();
            getCurrentWebView().saveState(titleAndBundle.bundle);
            closedTabs.add(0, titleAndBundle);
            if (closedTabs.size() > 500) {
                closedTabs.remove(closedTabs.size() - 1);
            }
        }
        ((FrameLayout) findViewById(R.id.webviews)).removeView(getCurrentWebView());
        getCurrentWebView().destroy();
        tabs.remove(currentTabIndex);
        if (currentTabIndex >= tabs.size()) {
            currentTabIndex = tabs.size() - 1;
        }
        if (currentTabIndex == -1) {
            // We just closed the last tab
            newTab("");
            currentTabIndex = 0;
        }
        getCurrentWebView().setVisibility(View.VISIBLE);
        et.setText(getCurrentWebView().getUrl());
        setTabCountText(tabs.size());
        getCurrentWebView().requestFocus();
    }

    private String getUrlFromIntent(Intent intent) {
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            return intent.getDataString();
        } else if (Intent.ACTION_SEND.equals(intent.getAction()) && "text/plain".equals(intent.getType())) {
            return intent.getStringExtra(Intent.EXTRA_TEXT);
        } else if (Intent.ACTION_WEB_SEARCH.equals(intent.getAction()) && intent.getStringExtra("query") != null) {
            return intent.getStringExtra("query");
        } else {
            return "";
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        String url = getUrlFromIntent(intent);
        if (!url.isEmpty()) {
            newTab(url);
            switchToTab(tabs.size() - 1);
        }
    }

    private void onNightModeChange() {
        if (isNightMode) {
            int textColor = Color.WHITE;
            int backgroundColor = Color.BLACK;
            et.setTextColor(textColor);
            searchEdit.setTextColor(textColor);
            searchEdit.setBackgroundColor(backgroundColor);
            searchCount.setTextColor(textColor);
            findViewById(R.id.main_layout).setBackgroundColor(Color.BLACK);
            findViewById(R.id.toolbar).setBackgroundColor(Color.BLACK);
            ((ProgressBar) findViewById(R.id.progressbar)).setProgressTintList(ColorStateList.valueOf(Color.rgb(0, 0x66, 0)));
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            getWindow().setNavigationBarColor(Color.BLACK);
        } else {
            int textColor = Color.BLACK;
            int backgroundColor = Color.WHITE;
            et.setTextColor(backgroundColor);
            searchEdit.setTextColor(textColor);
            searchEdit.setBackgroundColor(backgroundColor);
            searchCount.setTextColor(textColor);
            findViewById(R.id.main_layout).setBackgroundColor(Color.WHITE);
            findViewById(R.id.toolbar).setBackgroundColor(Color.rgb(0xe0, 0xe0, 0xe0));
            ((ProgressBar) findViewById(R.id.progressbar)).setProgressTintList(ColorStateList.valueOf(Color.rgb(0, 0xcc, 0)));
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        }

        menuActions = new MenuAction[]{
                new MenuAction("Desktop UA", isNightMode ? R.drawable.ua_white : R.drawable.ua, this::toggleDesktopUA, () -> getCurrentTab().isDesktopUA),
                new MenuAction("3rd party cookies", isNightMode ? R.drawable.cookies_3rdparty_white : R.drawable.cookies_3rdparty, this::toggleThirdPartyCookies,
                        () -> CookieManager.getInstance().acceptThirdPartyCookies(getCurrentWebView())),
                new MenuAction("Ad Blocker", isNightMode ? R.drawable.adblocker_white : R.drawable.adblocker, this::toggleAdblocker, () -> useAdBlocker),
                new MenuAction("Update adblock rules", 0, this::updateAdblockRules),
                new MenuAction("Night mode", isNightMode ? R.drawable.night_white : R.drawable.night, this::toggleNightMode, () -> isNightMode),
                new MenuAction("Show address bar", isNightMode ? R.drawable.url_bar_white : R.drawable.url_bar, this::toggleShowAddressBar, () -> et.getVisibility() == View.VISIBLE),
                new MenuAction("Full screen", isNightMode ? R.drawable.fullscreen_white : R.drawable.fullscreen, this::toggleFullscreen, () -> isFullscreen),
                new MenuAction("Tab history", isNightMode ? R.drawable.left_right_white : R.drawable.left_right, this::showTabHistory),
                new MenuAction("Log requests", isNightMode ? R.drawable.log_requests_white : R.drawable.log_requests, this::toggleLogRequests, () -> isLogRequests),
                new MenuAction("Find on page", isNightMode ? R.drawable.find_on_page_white : R.drawable.find_on_page, this::findOnPage),
                new MenuAction("Page info", isNightMode ? R.drawable.page_info_white : R.drawable.page_info, this::pageInfo),
                new MenuAction("Share URL", isNightMode ? R.drawable.share_white : R.drawable.share, this::shareUrl),
                new MenuAction("Open URL in app", isNightMode ? R.drawable.open_in_app_white : R.drawable.open_in_app, this::openUrlInApp),

                new MenuAction("Back", isNightMode ? R.drawable.back_white : R.drawable.back,
                        () -> {
                            if (getCurrentWebView().canGoBack()) getCurrentWebView().goBack();
                        }),
                new MenuAction("Forward", isNightMode ? R.drawable.forward_white : R.drawable.forward,
                        () -> {
                            if (getCurrentWebView().canGoForward()) getCurrentWebView().goForward();
                        }),
                new MenuAction("Reload", isNightMode ? R.drawable.reload_white : R.drawable.reload, () -> getCurrentWebView().reload()),
                new MenuAction("Stop", isNightMode ? R.drawable.stop_white : R.drawable.stop, () -> getCurrentWebView().stopLoading()),
                new MenuAction("Scroll to top", isNightMode ? R.drawable.top_white : R.drawable.top,
                        () -> getCurrentWebView().pageUp(true)),
                new MenuAction("Scroll to bottom", isNightMode ? R.drawable.bottom_white : R.drawable.bottom,
                        () -> getCurrentWebView().pageDown(true)),

                new MenuAction("Menu", isNightMode ? R.drawable.menu_white : R.drawable.menu, this::showMenu),
                new MenuAction("Full menu", isNightMode ? R.drawable.menu_white : R.drawable.menu, this::showFullMenu),

                new MenuAction("Bookmarks", isNightMode ? R.drawable.bookmarks_white : R.drawable.bookmarks, this::showBookmarks),
                new MenuAction("Add bookmark", isNightMode ? R.drawable.add_white : R.drawable.add, this::addBookmark),
                new MenuAction("Export bookmarks", isNightMode ? R.drawable.bookmarks_export_white : R.drawable.bookmarks_export, this::exportBookmarks),
                new MenuAction("Import bookmarks", isNightMode ? R.drawable.bookmarks_import_white : R.drawable.bookmarks_import, this::importBookmarks),
                new MenuAction("Delete all bookmarks", 0, this::deleteAllBookmarks),

                new MenuAction("Clear history and cache", 0, this::clearHistoryCache),

                new MenuAction("Show tabs", isNightMode ? R.drawable.tabs_white : R.drawable.tabs, this::showOpenTabs),
                new MenuAction("New tab", isNightMode ? R.drawable.add_white : R.drawable.add, () -> {
                    newTab("");
                    switchToTab(tabs.size() - 1);
                }),
                new MenuAction("Close tab", isNightMode ? R.drawable.close_white : R.drawable.close, this::closeCurrentTab),};

        setupToolbar(findViewById(R.id.toolbar));
    }

    private void toggleDesktopUA() {
        Tab tab = getCurrentTab();
        tab.isDesktopUA = !tab.isDesktopUA;
        getCurrentWebView().getSettings().setUserAgentString(tab.isDesktopUA ? desktopUA : null);
        getCurrentWebView().getSettings().setUseWideViewPort(tab.isDesktopUA);
        getCurrentWebView().reload();
    }

    private void toggleThirdPartyCookies() {
        CookieManager cookieManager = CookieManager.getInstance();
        boolean newValue = !cookieManager.acceptThirdPartyCookies(getCurrentWebView());
        cookieManager.setAcceptThirdPartyCookies(getCurrentWebView(), newValue);
    }

    private void toggleLogRequests() {
        isLogRequests = !isLogRequests;
        if (isLogRequests) {
            // Start logging
            if (requestsLog == null) {
                requestsLog = new ArrayList<>();
            } else {
                requestsLog.clear();
            }
        } else {
            // End logging, show result
            StringBuilder sb = new StringBuilder("<title>Request Log</title><h1>Request Log</h1>");
            for (String url : requestsLog) {
                sb.append("<a href=\"");
                sb.append(url);
                sb.append("\">");
                sb.append(url);
                sb.append("</a><br><br>");
            }
            String base64 = Base64.encodeToString(sb.toString().getBytes(StandardCharsets.UTF_8), Base64.DEFAULT);
            newTab("data:text/html;base64," + base64);
            switchToTab(tabs.size() - 1);
        }
    }

    private void findOnPage() {
        searchEdit.setText("");
        findViewById(R.id.searchPane).setVisibility(View.VISIBLE);
        searchEdit.requestFocus();
        showKeyboard();
    }

    private void showMenu() {
        MenuAction[] shortMenuActions = new MenuAction[shortMenu.length];
        for (int i = 0; i < shortMenu.length; i++) {
            shortMenuActions[i] = getAction(shortMenu[i]);
        }
        MenuActionArrayAdapter adapter = new MenuActionArrayAdapter(
                MainActivity.this,
                android.R.layout.simple_list_item_1,
                shortMenuActions);
        new AlertDialog.Builder(MainActivity.this)
                .setTitle("Actions")
                .setAdapter(adapter, (dialog, which) -> shortMenuActions[which].action.run())
                .show();
    }

    private void showFullMenu() {
        MenuActionArrayAdapter adapter = new MenuActionArrayAdapter(
                MainActivity.this,
                android.R.layout.simple_list_item_1,
                menuActions);
        new AlertDialog.Builder(MainActivity.this)
                .setTitle("Full menu")
                .setAdapter(adapter, (dialog, which) -> menuActions[which].action.run())
                .show();
    }

    private void shareUrl() {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
        intent.putExtra(Intent.EXTRA_TEXT, getCurrentWebView().getUrl());
        intent.setType("text/plain");
        startActivity(Intent.createChooser(intent, "Share URL"));
    }

    private void openUrlInApp() {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setData(Uri.parse(getCurrentWebView().getUrl()));
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Open in app")
                    .setMessage("No app can open this URL.")
                    .setPositiveButton("OK", (dialog1, which1) -> {
                    })
                    .show();
        }
    }

    private void loadUrl(String url, IncognitoWebView webview) {
        url = url.trim();
        if (url.isEmpty()) {
            url = "https://google.com/";
        }
        if (url.startsWith("about:") || url.startsWith("javascript:") || url.startsWith("file:") || url.startsWith("data:") ||
                (url.indexOf(' ') == -1 && Patterns.WEB_URL.matcher(url).matches())) {
            int indexOfHash = url.indexOf('#');
            String guess = URLUtil.guessUrl(url);
            if (indexOfHash != -1 && guess.indexOf('#') == -1) {
                // Hash exists in original URL but no hash in guessed URL
                url = guess + url.substring(indexOfHash);
            } else {
                url = guess;
            }
        } else {
            url = URLUtil.composeSearchUrl(url, searchUrl, "%s");
        }

        webview.loadUrl(url);

        hideKeyboard();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        assert imm != null;
        imm.hideSoftInputFromWindow(et.getWindowToken(), 0);
    }

    private void showKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        assert imm != null;
        imm.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0);
    }

    @Override
    public void onBackPressed() {
        if (findViewById(R.id.fullScreenVideo).getVisibility() == View.VISIBLE && fullScreenCallback[0] != null) {
            fullScreenCallback[0].onCustomViewHidden();
        } else if (getCurrentWebView().canGoBack()) {
            getCurrentWebView().goBack();
        } else if (tabs.size() > 1) {
            closeCurrentTab();
        } else {
            super.onBackPressed();
        }
    }

    private void injectCSS(WebView webview) {
        try {
            if (!getCurrentTab().isDesktopUA) {
                webview.evaluateJavascript("javascript:document.querySelector('meta[name=viewport]').content='width=device-width, initial-scale=1.0, maximum-scale=3.0, user-scalable=1';", null);
            }
        } catch (Exception e) {
            Log.e("injectCSS", e.toString());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        switch (requestCode) {
            case PERMISSION_REQUEST_EXPORT_BOOKMARKS:
                exportBookmarks();
                break;
            case PERMISSION_REQUEST_IMPORT_BOOKMARKS:
                importBookmarks();
                break;
        }
    }

    // java.util.function.BooleanSupplier requires API 24
    interface MyBooleanSupplier {
        boolean getAsBoolean();
    }

    private void setToolbarButtonActions(View view, Runnable click, Runnable swipeDown, Runnable swipeUp) {
        if (click != null) {
            view.setOnClickListener(v -> click.run());
        }

        if (swipeUp != null || swipeDown != null) {
            final GestureDetector gestureDetector = new GestureDetector(this, new MyGestureDetector(this) {
                @Override
                boolean onFlingUp() {
                    if (swipeUp != null) {
                        swipeUp.run();
                    }
                    return true;
                }

                @Override
                boolean onFlingDown() {
                    if (swipeDown != null) {
                        swipeDown.run();
                    }

                    return true;
                }
            });
            //noinspection AndroidLintClickableViewAccessibility
            view.setOnTouchListener((v, event) -> gestureDetector.onTouchEvent(event));
        }
    }

    private static class MyGestureDetector extends GestureDetector.SimpleOnGestureListener {
        private static final double FORBIDDEN_ZONE_MIN = Math.PI / 4 - Math.PI / 12;
        private static final double FORBIDDEN_ZONE_MAX = Math.PI / 4 + Math.PI / 12;
        private static final int MIN_VELOCITY_DP = 80;  // 0.5 inch/sec
        private static final int MIN_DISTANCE_DP = 80;  // 0.5 inch
        private final float MIN_VELOCITY_PX;
        private final float MIN_DISTANCE_PX;
        private final float density;

        MyGestureDetector(Context context) {
            density = context.getResources().getDisplayMetrics().density;
            MIN_VELOCITY_PX = MIN_VELOCITY_DP * density;
            MIN_DISTANCE_PX = MIN_DISTANCE_DP * density;
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            float velocitySquared = velocityX * velocityX + velocityY * velocityY;
            if (velocitySquared < MIN_VELOCITY_PX * MIN_VELOCITY_PX) {
                // too slow
                return false;
            }

            float deltaX = e2.getX() - e1.getX();
            float deltaY = e2.getY() - e1.getY();

            if (Math.abs(deltaX) < MIN_DISTANCE_PX && Math.abs(deltaY) < MIN_DISTANCE_PX) {
                // small movement

                if (Math.abs(deltaX) < Math.abs(deltaY) && deltaY > 0) {
                    if (Math.abs(deltaY) < 35 * density) {
                        return false;
                    }
                } else {
                    return false;
                }
            }

            double angle = Math.atan2(Math.abs(deltaY), Math.abs(deltaX));
            if (angle > FORBIDDEN_ZONE_MIN && angle < FORBIDDEN_ZONE_MAX) {
                return false;
            }

            if (Math.abs(deltaX) > Math.abs(deltaY)) {
                if (deltaX > 0) {
                    return onFlingRight();
                } else {
                    return onFlingLeft();
                }
            } else {
                if (deltaY > 0) {
                    return onFlingDown();
                } else {
                    return onFlingUp();
                }
            }
        }

        boolean onFlingRight() {
            return true;
        }

        boolean onFlingLeft() {
            return true;
        }

        boolean onFlingUp() {
            return true;
        }

        boolean onFlingDown() {
            return true;
        }
    }

    static class ArrayAdapterWithCurrentItem<T> extends ArrayAdapter<T> {
        int currentIndex;

        ArrayAdapterWithCurrentItem(@NonNull Context context, int resource, @NonNull T[] objects, int currentIndex) {
            super(context, resource, objects);
            this.currentIndex = currentIndex;
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            View view = super.getView(position, convertView, parent);
            TextView textView = view.findViewById(android.R.id.text1);
            int icon = position == currentIndex ? android.R.drawable.ic_menu_mylocation : R.drawable.empty;
            Drawable d = ResourcesCompat.getDrawable(getContext().getResources(), icon, getContext().getTheme());
            int size = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24, getContext().getResources().getDisplayMetrics());
            assert d != null;
            d.setBounds(0, 0, size, size);
            textView.setCompoundDrawablesRelative(d, null, null, null);
            textView.setCompoundDrawablePadding(
                    (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12, getContext().getResources().getDisplayMetrics()));
            return view;
        }
    }

    static class MenuActionArrayAdapter extends ArrayAdapter<MenuAction> {

        MenuActionArrayAdapter(@NonNull Context context, int resource, @NonNull MenuAction[] objects) {
            super(context, resource, objects);
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            View view = super.getView(position, convertView, parent);
            TextView textView = view.findViewById(android.R.id.text1);
            DisplayMetrics m = getContext().getResources().getDisplayMetrics();

            MenuAction item = getItem(position);
            assert item != null;

            Drawable left = ResourcesCompat.getDrawable(getContext().getResources(), item.icon != 0 ? item.icon : R.drawable.empty, null);
            int size = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24, m);
            assert left != null;
            left.setBounds(0, 0, size, size);
            left.setTint(Color.WHITE);
            Drawable right = null;
            if (item.getState != null) {
                int icon = item.getState.getAsBoolean() ? android.R.drawable.checkbox_on_background :
                        android.R.drawable.checkbox_off_background;
                right = ResourcesCompat.getDrawable(getContext().getResources(), icon, null);
                assert right != null;
                right.setBounds(0, 0, size, size);
            }

            textView.setCompoundDrawables(left, null, right, null);
            textView.setCompoundDrawablePadding(
                    (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12, m));

            return view;
        }
    }

    static class SearchAutocompleteAdapter extends BaseAdapter implements Filterable {

        interface OnSearchCommitListener {
            void onSearchCommit(String text);
        }

        private final Context mContext;
        private final OnSearchCommitListener commitListener;
        private List<String> completions = new ArrayList<>();

        SearchAutocompleteAdapter(Context context, OnSearchCommitListener commitListener) {
            mContext = context;
            this.commitListener = commitListener;
        }

        @Override
        public int getCount() {
            return completions.size();
        }

        @Override
        public Object getItem(int position) {
            return completions.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        @SuppressWarnings("ConstantConditions")
        public View getView(final int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                LayoutInflater inflater = (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                convertView = inflater.inflate(R.layout.dropdown, parent, false);
            }

            TextView v = convertView.findViewById(R.id.text1);
            v.setText(completions.get(position));

            Drawable d = ResourcesCompat.getDrawable(mContext.getResources(), R.drawable.commit_search, null);
            Drawable s = ResourcesCompat.getDrawable(mContext.getResources(), R.drawable.url_bar_white, null);

            int size = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 32, mContext.getResources().getDisplayMetrics());
            d.setBounds(0, 0, size, size);
            s.setBounds(0, 0, size, size);

            v.setCompoundDrawables(s, null, d, null);
            //noinspection AndroidLintClickableViewAccessibility
            v.setOnTouchListener((v1, event) -> {
                if (event.getAction() != MotionEvent.ACTION_DOWN) {
                    return false;
                }

                TextView t = (TextView) v1;
                if (event.getX() > t.getWidth() - t.getCompoundPaddingRight()) {
                    commitListener.onSearchCommit(getItem(position).toString());
                    return true;
                }

                return false;
            });
            //noinspection AndroidLintClickableViewAccessibility
            parent.setOnTouchListener((dropdown, event) -> event.getX() > dropdown.getWidth() - size * 2);
            return convertView;
        }

        @Override
        public Filter getFilter() {
            return new Filter() {
                @Override
                protected FilterResults performFiltering(CharSequence constraint) {
                    // Invoked on a worker thread
                    FilterResults filterResults = new FilterResults();
                    if (constraint != null) {
                        List<String> results = getCompletions(constraint.toString());
                        filterResults.values = results;
                        filterResults.count = results.size();
                    }
                    return filterResults;
                }

                @Override
                @SuppressWarnings("unchecked")
                protected void publishResults(CharSequence constraint, FilterResults results) {
                    if (results != null && results.count > 0) {
                        completions = (List<String>) results.values;
                        notifyDataSetChanged();
                    } else {
                        notifyDataSetInvalidated();
                    }
                }
            };
        }

        // Runs on a worker thread
        private List<String> getCompletions(String text) {
            int total = 0;
            byte[] data = new byte[16384];
            try {
                URL url = new URL(URLUtil.composeSearchUrl(text, searchCompleteUrl, "%s"));
                HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
                try {
                    InputStream in = new BufferedInputStream(urlConnection.getInputStream());
                    while (total <= data.length) {
                        int count = in.read(data, total, data.length - total);
                        if (count == -1) {
                            break;
                        }
                        total += count;
                    }
                    if (total == data.length) {
                        // overflow
                        return new ArrayList<>();
                    }
                } finally {
                    urlConnection.disconnect();
                }
            } catch (IOException e) {
                // Swallow exception and return empty list
                return new ArrayList<>();
            }

            // Result looks like:
            // [ "original query", ["completion1", "completion2", ...], ...]

            JSONArray jsonArray;
            try {
                jsonArray = new JSONArray(new String(data, StandardCharsets.UTF_8));
            } catch (JSONException e) {
                return new ArrayList<>();
            }
            jsonArray = jsonArray.optJSONArray(1);
            if (jsonArray == null) {
                return new ArrayList<>();
            }
            final int MAX_RESULTS = 10;
            List<String> result = new ArrayList<>(Math.min(jsonArray.length(), MAX_RESULTS));
            for (int i = 0; i < jsonArray.length() && result.size() < MAX_RESULTS; i++) {
                String s = jsonArray.optString(i);
                if (s != null && !s.isEmpty()) {
                    result.add(s);
                }
            }
            return result;
        }
    }
}
