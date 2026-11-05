package app.sofabrowser.shortcut;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

/** Opens the saved page in Sofa, as the page alone, and gets out of the way. */
public class Launch extends Activity {
    private static final String PREFIX = "app.sofabrowser.shortcut.";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(getPackageName(), PackageManager.GET_META_DATA);
            String url = info.metaData.getString(PREFIX + "URL");
            String target = info.metaData.getString(PREFIX + "TARGET");
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .setClassName(target, "app.sofabrowser.PageActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NEW_DOCUMENT));
        } catch (Exception e) {
            // Sofa was uninstalled: nothing here can open the page.
            Toast.makeText(this, "Sofa TV Browser is not installed", Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
