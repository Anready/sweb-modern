package landau.sweb.incognito;

import android.content.Context;
import android.util.AttributeSet;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.webkit.WebView;

public class IncognitoWebView extends WebView {

    public IncognitoWebView(Context context) {
        super(context);
    }

    public IncognitoWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        InputConnection inputConnection = super.onCreateInputConnection(outAttrs);
        if (inputConnection != null) {
            outAttrs.imeOptions = 0x1000000; // Add the flag for incognito mode
        }
        return inputConnection;
    }
}

