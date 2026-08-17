import com.brianshih.mopria.android.scanprint.domain.EsclHttpClient;
import com.brianshih.mopria.android.scanprint.domain.EsclScannerStatus;

public final class EsclTransportProbe {
    public static void main(String[] args) {
        EsclHttpClient client = new EsclHttpClient();
        String base = "http://10.1.121.182:80/eSCL";
        try {
            String caps = client.fetchCapabilities(base);
            System.out.println("capabilities-bytes=" + caps.length());
            EsclScannerStatus status = client.fetchScannerStatus(base);
            System.out.println("status=" + status.getState() + " adf=" + status.getAdfState());
        } catch (Throwable error) {
            error.printStackTrace(System.out);
        }
    }
}
