package io.github.marcofanti.aauth.demo.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.security.KeyPair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StableKeysTest {

    @TempDir
    Path directory;

    @Test
    void createsThenReloadsSameKeyPair() {
        KeyPair first = StableKeys.loadOrCreate(directory, "backend");
        KeyPair second = StableKeys.loadOrCreate(directory, "backend");

        assertThat(second.getPublic().getEncoded()).isEqualTo(first.getPublic().getEncoded());
        assertThat(second.getPrivate().getEncoded())
                .isEqualTo(first.getPrivate().getEncoded());
    }

    @Test
    void corruptKeyFilesFailWithClearError() throws Exception {
        java.nio.file.Files.writeString(
                directory.resolve("broken-stable.key"),
                java.util.Base64.getEncoder().encodeToString("not a key".getBytes()));
        java.nio.file.Files.writeString(
                directory.resolve("broken-stable.pub"),
                java.util.Base64.getEncoder().encodeToString("not a key".getBytes()));

        org.assertj.core.api.Assertions.assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> StableKeys.loadOrCreate(directory, "broken"))
                .withMessageContaining("Corrupt stable key files");
    }

    @Test
    void distinctNamesGetDistinctKeys() {
        KeyPair backend = StableKeys.loadOrCreate(directory, "backend");
        KeyPair agent = StableKeys.loadOrCreate(directory, "supply-chain-agent");

        assertThat(agent.getPublic().getEncoded())
                .isNotEqualTo(backend.getPublic().getEncoded());
    }
}
