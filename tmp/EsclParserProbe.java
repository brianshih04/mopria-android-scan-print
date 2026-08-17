import com.brianshih.mopria.android.scanprint.domain.EsclCapabilities;
import com.brianshih.mopria.android.scanprint.domain.EsclInputCapabilities;
import com.brianshih.mopria.android.scanprint.domain.EsclProtocol;
import com.brianshih.mopria.android.scanprint.domain.EsclSettingProfile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class EsclParserProbe {
    public static void main(String[] args) throws Exception {
        String xml = Files.readString(Path.of("tmp/hp-scanner-capabilities-20260814.xml"), StandardCharsets.UTF_8);
        EsclCapabilities caps = EsclProtocol.INSTANCE.parseCapabilities(xml);
        System.out.println("version=" + caps.getVersion());
        System.out.println("sources=" + caps.getInputSources());
        System.out.println("formats=" + caps.getDocumentFormats());
        System.out.println("modes=" + caps.getColorModes());
        System.out.println("resolutions=" + caps.getResolutions());
        for (EsclInputCapabilities input : caps.getInputs().values()) {
            System.out.println("input=" + input.getInputSource() + " selectSinglePage=" + input.getSelectSinglePage() + " profiles=" + input.getProfiles().size());
            for (EsclSettingProfile profile : input.getProfiles()) {
                System.out.println("  modes=" + profile.getColorModes() + " formats=" + profile.getDocumentFormats() + " resolutions=" + profile.getResolutions().size());
            }
        }
    }
}
