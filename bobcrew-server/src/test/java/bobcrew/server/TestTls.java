package bobcrew.server;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Base64;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** A throwaway self-signed certificate for localhost, made with the JDK's keytool. */
final class TestTls {

    private static final String PASSWORD = "changeit";

    final Path certificatePem;

    final Path privateKeyPem;

    /** Trusts exactly the generated certificate. */
    final SSLContext clientContext;

    private TestTls(Path certificatePem, Path privateKeyPem, SSLContext clientContext) {
        this.certificatePem = certificatePem;
        this.privateKeyPem = privateKeyPem;
        this.clientContext = clientContext;
    }

    static TestTls generate(Path directory) throws Exception {
        Path keyStoreFile = directory.resolve("test.p12");
        Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=localhost", "-ext", "san=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12",
                "-keystore", keyStoreFile.toString(), "-storepass", PASSWORD, "-keypass", PASSWORD)
            .redirectErrorStream(true)
            .start();
        String output = new String(keytool.getInputStream().readAllBytes());
        if (keytool.waitFor() != 0) {
            throw new IllegalStateException("keytool failed: " + output);
        }
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keyStoreFile)) {
            keyStore.load(in, PASSWORD.toCharArray());
        }
        Certificate certificate = keyStore.getCertificate("server");
        Path certificatePem = directory.resolve("server.crt");
        Path privateKeyPem = directory.resolve("server.key");
        Files.writeString(certificatePem, pem("CERTIFICATE", certificate.getEncoded()));
        Files.writeString(privateKeyPem, pem("PRIVATE KEY", keyStore.getKey("server", PASSWORD.toCharArray()).getEncoded()));

        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        trustStore.setCertificateEntry("server", certificate);
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(trustStore);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        return new TestTls(certificatePem, privateKeyPem, context);
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }
}
