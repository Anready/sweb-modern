package landau.sweb.incognito;

import android.content.Context;
import android.util.AttributeSet;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import androidx.appcompat.widget.AppCompatAutoCompleteTextView;

public class IncognitoAppCompatAutoCompleteTextView extends AppCompatAutoCompleteTextView {

    public IncognitoAppCompatAutoCompleteTextView(Context context) {
        super(context);
    }

    public IncognitoAppCompatAutoCompleteTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public IncognitoAppCompatAutoCompleteTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
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
