package mactak.headless;

/**
 * G1.5 — the {@code --datapackage} precedence decision, pulled out of
 * {@link HeadlessCLI} so the canary can assert it (#29). Pure data: no
 * natives, no files, no commo.
 *
 * <p>The merge, in order:</p>
 *
 * <ul>
 *   <li>{@code host}/{@code port} — explicit {@code --connect} first, else
 *       the package's {@code cot_streams} address (the two move as the one
 *       flag);</li>
 *   <li>{@code callsign} — explicit {@code --callsign} first, else the
 *       package's {@code location_units} entry, else {@code HEADLESS};</li>
 *   <li>{@code certPath}/{@code caPath} — explicit {@code --cert}/{@code
 *       --ca} first (the pair, as usage requires both), else the package's
 *       extracted PKCS#12 pair (plaintext package: neither);</li>
 *   <li>{@code certPass} — {@code MACTAK_CERT_PASS} first, else the
 *       package's firecrypt {@code Password}, else the built-in
 *       {@code atakatak};</li>
 *   <li>{@code caPass} — {@code MACTAK_CA_PASS} first, else certPass.</li>
 * </ul>
 *
 * <p>When the address comes from the package, its proto must agree with the
 * cert pair: an {@code :ssl} address with no certs (from the package or the
 * flags) would connect in plaintext to a TLS port, and a plaintext address
 * whose package ships certs would open TLS to a plaintext port — both fail
 * at connect with nothing pointing at the package. Refused up front instead
 * ({@link IllegalArgumentException}, a usage error).</p>
 *
 * <p>Asserted by DataPackageSelfTest (test/datapackage.sh) against both
 * package shapes — the SSL fixture and a plaintext one.</p>
 */
public final class ConnectOptions {

    private ConnectOptions() {
    }

    /** The merged connect options; see the class comment. */
    public static final class Resolved {
        public final String host;
        public final int port;
        public final String callsign;
        /** Cert paths; a null pair connects in plaintext. */
        public final String certPath;
        public final String caPath;
        public final String certPass;
        public final String caPass;

        Resolved(String host, int port, String callsign,
                 String certPath, String caPath,
                 String certPass, String caPass) {
            this.host = host;
            this.port = port;
            this.callsign = callsign;
            this.certPath = certPath;
            this.caPath = caPath;
            this.certPass = certPass;
            this.caPass = caPass;
        }
    }

    /**
     * @param flagHost    --connect's host, or null when the flag is unset
     * @param flagPort    --connect's port, or -1 when the flag is unset
     * @param flagCallsign --callsign, or null when unset
     * @param flagCertPath --cert, or null when unset (usage(2) already
     *                    demanded --ca with it)
     * @param flagCaPath  --ca, or null when unset
     * @param envCertPass MACTAK_CERT_PASS, or null when unset/empty
     * @param envCaPass   MACTAK_CA_PASS, or null when unset/empty
     * @param dp          what DataPackage.open resolved, or null for a
     *                    plain --connect run
     * @throws IllegalArgumentException when the package's address proto and
     *         the cert pair disagree (see the class comment)
     */
    public static Resolved resolve(String flagHost, int flagPort,
                                   String flagCallsign, String flagCertPath,
                                   String flagCaPath, String envCertPass,
                                   String envCaPass, DataPackage.Resolved dp) {
        String host = flagHost;
        int port = flagPort;
        String callsign = flagCallsign;
        String certPath = flagCertPath;
        String caPath = flagCaPath;
        String dpCertPass = null;
        if (dp != null) {
            final boolean addressFromPackage = host == null;
            if (addressFromPackage) {
                host = dp.host;
                port = dp.port;
            }
            if (callsign == null && dp.callsign != null)
                callsign = dp.callsign;
            final boolean certsFromPackage = certPath == null;
            if (certsFromPackage) {
                certPath = dp.certPath;
                caPath = dp.caPath;
            }
            dpCertPass = dp.certPass;
            if (addressFromPackage && dp.ssl && certPath == null)
                throw new IllegalArgumentException("its address is ssl but it"
                        + " ships no PKCS#12 pair — pass --cert and --ca");
            if (addressFromPackage && !dp.ssl && certsFromPackage
                    && certPath != null)
                throw new IllegalArgumentException("its address is plaintext"
                        + " but it ships a PKCS#12 pair — fix the package's"
                        + " cot_streams proto or pass --connect");
        }
        if (callsign == null)
            callsign = "HEADLESS";
        String certPass = envCertPass != null ? envCertPass
                : dpCertPass != null ? dpCertPass : "atakatak";
        String caPass = envCaPass != null ? envCaPass : certPass;
        return new Resolved(host, port, callsign,
                certPath, caPath, certPass, caPass);
    }
}
