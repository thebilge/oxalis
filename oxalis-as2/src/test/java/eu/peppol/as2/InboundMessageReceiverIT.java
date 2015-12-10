package eu.peppol.as2;

import com.google.inject.Inject;
import eu.peppol.document.SbdhFastParser;
import eu.peppol.identifier.AccessPointIdentifier;
import eu.peppol.persistence.MessageRepository;
import eu.peppol.persistence.SimpleMessageRepository;
import eu.peppol.security.CommonName;
import eu.peppol.security.KeystoreManager;
import eu.peppol.security.SecurityModule;
import eu.peppol.statistics.RawStatistics;
import eu.peppol.statistics.RawStatisticsRepository;
import eu.peppol.statistics.StatisticsGranularity;
import eu.peppol.statistics.StatisticsTransformer;
import eu.peppol.util.GlobalConfiguration;
import eu.peppol.util.RuntimeConfigurationModule;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Guice;
import org.testng.annotations.Test;

import javax.activation.MimeType;
import javax.activation.MimeTypeParseException;
import javax.mail.MessagingException;
import javax.mail.internet.InternetHeaders;
import javax.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Date;

import static org.testng.Assert.*;

/**
 * Simulates reception of a an AS2 Message, which is validated etc. and finally produces an MDN.
 *
 * @author steinar
 * @author thore
 */
@Test(groups = {"integration"})
@Guice(modules = {RuntimeConfigurationModule.class, SecurityModule.class})
public class InboundMessageReceiverIT {

    @Inject
    GlobalConfiguration globalConfiguration;
    @Inject
    KeystoreManager keystoreManager;

    private ByteArrayInputStream inputStream;
    private InternetHeaders headers;
    private MessageRepository messageRepository;
    private RawStatisticsRepository rawStatisticsRepository = createFailingStatisticsRepository();
    private AccessPointIdentifier ourAccessPointIdentifier = AccessPointIdentifier.valueOf(keystoreManager.getOurCommonName());

    @BeforeMethod
    public void createHeaders() {
        messageRepository = new SimpleMessageRepository(globalConfiguration);
        CommonName ourCommonName = keystoreManager.getOurCommonName();

        headers = new InternetHeaders();
        headers.addHeader(As2Header.DISPOSITION_NOTIFICATION_OPTIONS.getHttpHeaderName(), "Disposition-Notification-Options: signed-receipt-protocol=required, pkcs7-signature; signed-receipt-micalg=required,sha1");
        headers.addHeader(As2Header.AS2_TO.getHttpHeaderName(), PeppolAs2SystemIdentifier.AS2_SYSTEM_ID_PREFIX + ourCommonName.toString());
        headers.addHeader(As2Header.AS2_FROM.getHttpHeaderName(), PeppolAs2SystemIdentifier.AS2_SYSTEM_ID_PREFIX + ourCommonName.toString());
        headers.addHeader(As2Header.MESSAGE_ID.getHttpHeaderName(), "42");
        headers.addHeader(As2Header.AS2_VERSION.getHttpHeaderName(), As2Header.VERSION);
        headers.addHeader(As2Header.SUBJECT.getHttpHeaderName(), "An AS2 message");
        headers.addHeader(As2Header.DATE.getHttpHeaderName(), "Mon Oct 21 22:01:48 CEST 2013");
    }

    @BeforeMethod
    public void createInputStream() throws MimeTypeParseException, IOException, MessagingException {
        SMimeMessageFactory SMimeMessageFactory = new SMimeMessageFactory(keystoreManager.getOurPrivateKey(), keystoreManager.getOurCertificate());

        // Fetch input stream for data
        InputStream resourceAsStream = SMimeMessageFactory.class.getClassLoader().getResourceAsStream("as2-peppol-bis-invoice-sbdh.xml");
        assertNotNull(resourceAsStream);

        // Creates the signed message
        MimeMessage signedMimeMessage = SMimeMessageFactory.createSignedMimeMessage(resourceAsStream, new MimeType("application","xml"));
        assertNotNull(signedMimeMessage);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        signedMimeMessage.writeTo(baos);

        inputStream = new ByteArrayInputStream(baos.toByteArray());

        signedMimeMessage.writeTo(System.out);
    }

    private RawStatisticsRepository createFailingStatisticsRepository() {
        return new RawStatisticsRepository() {
            @Override
            public Integer persist(RawStatistics rawStatistics) {
                throw new IllegalStateException("Persistence of statistics failed, but this should not break the message reception");
            }
            @Override
            public void fetchAndTransformRawStatistics(StatisticsTransformer transformer, Date start, Date end, StatisticsGranularity granularity) {
            }
        };
    }

    public void loadAndReceiveTestMessageOK() throws Exception {

        InboundMessageReceiver inboundMessageReceiver = new InboundMessageReceiver(new SbdhFastParser(), new As2MessageInspector(keystoreManager));

        As2ReceiptData as2ReceiptData = inboundMessageReceiver.receive(headers, inputStream, messageRepository, rawStatisticsRepository, ourAccessPointIdentifier);

        assertEquals(as2ReceiptData.getMdnData().getAs2Disposition().getDispositionType(), As2Disposition.DispositionType.PROCESSED);
        assertNotNull(as2ReceiptData.getMdnData().getMic());
    }

    /**
     * Specifies an invalid MIC algorithm (MD5), which should cause reception to fail.
     *
     * @throws Exception
     */
    public void receiveMessageWithInvalidDispositionRequest() throws Exception {

        headers.setHeader(As2Header.DISPOSITION_NOTIFICATION_OPTIONS.getHttpHeaderName(), "Disposition-Notification-Options: signed-receipt-protocol=required, pkcs7-signature; signed-receipt-micalg=required,md5");

        InboundMessageReceiver inboundMessageReceiver = new InboundMessageReceiver(new SbdhFastParser(), new As2MessageInspector(keystoreManager));

        try {
            inboundMessageReceiver.receive(headers, inputStream, messageRepository, rawStatisticsRepository, ourAccessPointIdentifier);
            fail("Reception of AS2 messages request MD5 as the MIC algorithm, should have failed");
        } catch (ErrorWithMdnException e) {
            assertNotNull(e.getMdnData(), "MDN should have been returned upon reception of invalid AS2 Message");
            assertEquals(e.getMdnData().getAs2Disposition().getDispositionType(), As2Disposition.DispositionType.FAILED);
            assertEquals(e.getMdnData().getSubject(), MdnData.SUBJECT);
        }
    }

}