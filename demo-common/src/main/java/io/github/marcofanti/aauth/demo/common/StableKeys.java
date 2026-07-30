package io.github.marcofanti.aauth.demo.common;

import io.github.marcofanti.aauth.signing.keys.KeyPairs;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Persists a service's stable Ed25519 key pair across restarts. The stable key anchors the
 * agent's identity at the Agent Provider; only its public half ever leaves the process.
 * Java cannot derive an Ed25519 public key from the private key, so both halves are stored:
 * {@code <name>-stable.key} (PKCS#8, base64) and {@code <name>-stable.pub} (X.509, base64).
 */
public final class StableKeys {

    private StableKeys() {}

    public static KeyPair loadOrCreate(Path directory, String name) {
        Path privateFile = directory.resolve(name + "-stable.key");
        Path publicFile = directory.resolve(name + "-stable.pub");
        try {
            if (Files.exists(privateFile) && Files.exists(publicFile)) {
                return load(privateFile, publicFile);
            }
            KeyPair keyPair = KeyPairs.generateEd25519();
            Files.createDirectories(directory);
            Files.writeString(
                    privateFile,
                    Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()));
            Files.writeString(
                    publicFile,
                    Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
            return keyPair;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load or create stable key for " + name + " in " + directory, e);
        }
    }

    private static KeyPair load(Path privateFile, Path publicFile) throws IOException {
        byte[] privateBytes =
                Base64.getDecoder().decode(Files.readString(privateFile).strip());
        byte[] publicBytes =
                Base64.getDecoder().decode(Files.readString(publicFile).strip());
        try {
            KeyFactory factory = KeyFactory.getInstance("Ed25519");
            PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(privateBytes));
            PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(publicBytes));
            return new KeyPair(publicKey, privateKey);
        } catch (java.security.NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException(
                    "Corrupt stable key files " + privateFile + " / " + publicFile + ": " + e.getMessage(), e);
        }
    }
}
