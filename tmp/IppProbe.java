import com.hp.jipp.encoding.IppPacket;
import com.hp.jipp.model.Types;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;

public final class IppProbe {
    public static void main(String[] args) throws Exception {
        URI uri = URI.create("ipp://10.1.121.182:631/ipp/print");
        IppPacket packet = IppPacket.getPrinterAttributes(uri)
                .putOperationAttributes(Types.requestingUserName.of("MopriaScanPrint/0.1"))
                .build();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        packet.write(bytes);
        URL url = new URL("http://10.1.121.182:631/ipp/print");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/ipp");
        connection.setRequestProperty("Content-Length", Integer.toString(bytes.size()));
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(15000);
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(bytes.size());
        connection.getOutputStream().write(bytes.toByteArray());
        System.out.println("http=" + connection.getResponseCode());
        try (InputStream input = connection.getInputStream()) {
            IppPacket response = IppPacket.read(input);
            System.out.println("status=0x" + Integer.toHexString(response.getCode()));
            System.out.println(response.prettyPrint(0, ""));
        } finally {
            connection.disconnect();
        }
    }
}
