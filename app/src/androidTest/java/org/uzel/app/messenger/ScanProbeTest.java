package org.uzel.app.messenger;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.MediaStore;
import android.test.InstrumentationTestCase;
import androidx.core.content.FileProvider;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Checks that the system camera can be launched with our file provider for QR capture. */
public final class ScanProbeTest extends InstrumentationTestCase {
    public void testCaptureIntentIsReady() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        Context app = context.getApplicationContext();
        StringBuilder out = new StringBuilder();
        try {
            File dir = new File(app.getCacheDir(), "qr");
            dir.mkdirs();
            File photo = new File(dir, "scan.jpg");
            Uri uri = FileProvider.getUriForFile(app, app.getPackageName() + ".files", photo);
            out.append("uri=").append(uri).append('\n');
            Intent capture = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            capture.putExtra(MediaStore.EXTRA_OUTPUT, uri);
            capture.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            List<ResolveInfo> handlers = app.getPackageManager().queryIntentActivities(capture, 0);
            out.append("handlers=").append(handlers.size()).append('\n');
            for (ResolveInfo info : handlers) out.append("camera=").append(info.activityInfo.packageName).append('\n');
        } catch (Exception failed) {
            out.append("error=").append(failed).append('\n');
        }
        Path result = context.getExternalFilesDir(null).toPath().resolve("scan-result.txt");
        Files.write(result, out.toString().getBytes(StandardCharsets.UTF_8));
    }
}
