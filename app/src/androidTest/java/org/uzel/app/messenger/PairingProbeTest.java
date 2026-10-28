package org.uzel.app.messenger;

import android.content.Context;
import android.test.InstrumentationTestCase;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.uzel.app.CodePairing;
import org.uzel.messenger.chat.ChatService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.concurrent.TimeUnit;

/** The invitation must survive a QR round trip and the six digit LAN code exchange. */
public final class PairingProbeTest extends InstrumentationTestCase {
    public void testQrAndShortCode() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        Path ext = context.getExternalFilesDir(null).toPath();
        Path result = ext.resolve("pairing-result.txt");
        ChatService service = AndroidMessenger.chats(context).get(60, TimeUnit.SECONDS);
        String invitation = service.invitation("127.0.0.1", "");

        StringBuilder out = new StringBuilder();
        out.append("invitation=").append(invitation.length()).append('\n');
        String decoded = null;
        try {
            EnumMap<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            BitMatrix matrix = new QRCodeWriter().encode(invitation, BarcodeFormat.QR_CODE, 720, 720, hints);
            int[] pixels = new int[720 * 720];
            for (int y = 0; y < 720; y++)
                for (int x = 0; x < 720; x++) pixels[y * 720 + x] = matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(720, 720, pixels)));
            decoded = new MultiFormatReader().decode(bitmap).getText();
        } catch (Exception failed) {
            out.append("qrError=").append(failed).append('\n');
        }
        out.append("qrRoundTrip=").append(invitation.equals(decoded)).append('\n');

        CodePairing.serve("654321", invitation);
        String found = CodePairing.lookup("654321", 5000);
        CodePairing.stop();
        out.append("lanCodeFound=").append(found != null && found.startsWith("uzelio-chat:")).append('\n');
        out.append("lanCodeMatches=").append(invitation.equals(found)).append('\n');
        Files.write(result, out.toString().getBytes(StandardCharsets.UTF_8));
    }
}
