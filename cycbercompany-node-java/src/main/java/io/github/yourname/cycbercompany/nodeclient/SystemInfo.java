package io.github.yourname.cycbercompany.nodeclient;

import java.net.InetAddress;

public record SystemInfo(String hostname, String osName, String osArch, String clientVersion) {

    private static final String VERSION = "0.0.1-java";

    public static SystemInfo current() {
        return new SystemInfo(
                defaultNodeName(),
                System.getProperty("os.name", "unknown"),
                System.getProperty("os.arch", "unknown"),
                VERSION);
    }

    public static String defaultNodeName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception ex) {
            return "java-node";
        }
    }
}
