package com.cbruegg.emuserver.utils;

public enum OperatingSystem {
    Windows, Unix;

    public static OperatingSystem getCurrent() {
        var osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("win")) {
            return Windows;
        } else if (osName.contains("nix") || osName.contains("nux")) {
            return Unix;
        } else {
            throw new UnsupportedOperationException("Unsupported OS!");
        }
    }
}
