package org.leo.web.adapter.ssh;

import net.schmizz.sshj.DefaultConfig;

import java.security.Security;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;

/** Launched by the release gate through the packaged application's Boot loader, not Surefire. */
public final class PackagedSshCryptoProbe {
    public static void main(String[] args) throws Exception {
        new DefaultConfig();
        KeyAgreement.getInstance("DH", "BC");
        for (String algorithm : new String[]{"CHACHA", "CAST5/CBC/NoPadding", "CAST5/CTR/NoPadding"}) {
            Cipher.getInstance(algorithm, "BC");
        }
        System.out.println("Packaged SSH crypto verified: " + Security.getProvider("BC")
                .getClass().getProtectionDomain().getCodeSource().getLocation());
    }
}
