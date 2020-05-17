package com.cbruegg.emuserver.utils;

import java.io.*;
import java.math.BigInteger;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

public class IOUtils {
    private IOUtils() {
    }

    public static boolean moveOrCopy(File from, File to) {
        if (from.renameTo(to)) {
            return true;
        } else {
            try {
                new FileInputStream(from).transferTo(new FileOutputStream(to));
                return true;
            } catch (IOException e) {
                return false;
            }
        }
    }

    public static long transfer(InputStream from, OutputStream to, int bufferSize) throws IOException {
        Objects.requireNonNull(to, "to");
        long transferred = 0;
        byte[] buffer = new byte[bufferSize];
        int read;
        while ((read = from.read(buffer, 0, bufferSize)) >= 0) {
            to.write(buffer, 0, read);
            transferred += read;
        }
        return transferred;
    }

    public static String md5(File f) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }

        try (var in = new BufferedInputStream((new FileInputStream(f)));
             var out = new DigestOutputStream(OutputStream.nullOutputStream(), md)) {
            in.transferTo(out);
        }

        var fx = "%0" + (md.getDigestLength() * 2) + "x";
        return String.format(fx, new BigInteger(1, md.digest()));
    }

    public static int readInt(InputStream inputStream) throws IOException {
        byte[] buffer = new byte[4];
        inputStream.readNBytes(buffer, 0, 4);
        return (buffer[0] << 24) + (buffer[1] << 16) + (buffer[2] << 8) + buffer[3];
    }
}
